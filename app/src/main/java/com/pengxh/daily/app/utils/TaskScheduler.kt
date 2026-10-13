package com.pengxh.daily.app.utils

import android.content.Intent
import android.os.SystemClock
import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.extensions.formatTime
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.extensions.resolveExecutionTime
import com.pengxh.daily.app.service.CaptureImageService
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.ui.MainActivity
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * 任务调度器
 */
object TaskScheduler {
    /**
     * 调度器是否在运行中
     * */
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    /**
     * UI 文本事件（tipsView / adapter 高亮），不参与按钮逻辑
     * */
    private val _tipsEvent = MutableSharedFlow<TipsEvent>(extraBufferCapacity = 1)
    val tipsEvent = _tipsEvent.asSharedFlow()

    /**
     * 超时后回到主页信号（TaskScheduler → MainActivity）
     * */
    private val _returnToApp = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val returnToApp = _returnToApp.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /**
     * 打卡成功信号。
     *
     * 使用 CONFLATED Channel 而非共享的 CompletableDeferred：
     *   - 多个等待者不会互相覆盖，避免并发调用时信号丢失
     *   - 无人等待时只有最新一次信号被暂存，不会无限堆积
     *   - 每次等待前会清空陈旧信号，避免上一次通知被误判为本次成功
     */
    private val clockInSignal = Channel<Unit>(Channel.CONFLATED)

    private var lastProcessedDate: LocalDate? = null

    /**
     * 由 ForegroundRunningService 调用，注入协程作用域
     */
    fun attach(serviceScope: CoroutineScope) {
        scope?.cancel()
        scope = serviceScope
    }

    fun isAttached(): Boolean {
        return scope != null
    }

    fun isRunning(): Boolean {
        return _isRunning.value
    }

    /**
     * 启动每日任务调度
     * 时序：防重复 → 检查协程作用域 → 判断周末/节假日 → 构建排程 → 启动核心循环
     *
     * @param force 手动启动时为 true：清除「今日已处理」标记并忽略休息日判定，允许重新执行当天排程
     */
    fun startTask(force: Boolean = false) {
        if (_isRunning.value) {
            LogFileManager.writeLog("任务已在执行中，忽略重复启动")
            return
        }

        val currentScope = scope
        if (currentScope == null) {
            LogFileManager.writeLog("TaskScheduler scope 未初始化")
            return
        }

        if (force) {
            // 强制重启前先取消旧调度协程，避免出现两个调度器同时运行
            job?.cancel()
            job = null
            lastProcessedDate = null
            LogFileManager.writeLog("手动启动：清除今日已处理标记，忽略休息日判定，强制重新执行排程")
        }

        _isRunning.value = true

        val tempJob = currentScope.launch {
            while (isActive) {
                val today = LocalDate.now()

                // 今天已经处理过了，不再重复
                if (lastProcessedDate == today) {
                    LogFileManager.writeLog("今日已处理，等待下一次重置")
                    if (isActive) waitUntilNextReset()
                    continue
                }

                if (!force && shouldSkipToday()) {
                    LogFileManager.writeLog(
                        "今日判定为休息日，跳过任务（dayFlag=${getDayFlag()}）"
                    )
                    _tipsEvent.emit(TipsEvent.Skip)
                    ForegroundRunningService.emitNotificationText("今日休息，任务已跳过")
                } else {
                    val schedule = buildTodaySchedule()
                    if (schedule.isEmpty()) {
                        LogFileManager.writeLog("任务列表为空，停止调度")
                        return@launch
                    }

                    LogFileManager.writeLog(
                        "开始执行每日任务，共 ${schedule.size} 个：" +
                                schedule.joinToString("，") {
                                    "计划=${it.plannedTime}/实际=${it.actualTime}"
                                }
                    )
                    executeSchedule(schedule)
                }

                lastProcessedDate = today

                // 今天结束，睡到明天
                if (isActive) waitUntilNextReset()
            }
        }
        tempJob.invokeOnCompletion {
            _isRunning.value = false
        }
        job = tempJob
    }

    /**
     * 获取当日 flag
     * */
    fun getDayFlag(): String {
        val today = LocalDate.now()
        return when {
            ChinaHolidayManager.isWorkday(today) -> "补班日"
            ChinaHolidayManager.isHoliday(today) -> "节假日"
            CustomWorkdayManager.isRestDay(today) -> "休息日"
            else -> "工作日"
        }
    }

    /**
     * 链式任务主循环
     * for 循环保证顺序执行，每个任务经历三个阶段：
     *   阶段1 - delay(到任务时间) + 通知栏秒级倒计时
     *   阶段2 - openApplication() + select{超时|打卡} 竞态等待
     *   阶段3 - 推进到下一个任务（或全部完成 emit Completed）
     */
    private suspend fun CoroutineScope.executeSchedule(schedule: List<ScheduledTask>) {
        var executedCount = 0
        var skippedCount = 0

        for (task in schedule) {
            val now = System.currentTimeMillis()

            // 任务时间已过，跳过
            if (task.actualTimeMillis <= now) {
                skippedCount++
                LogFileManager.writeLog(
                    "第 ${task.displayIndex} 个任务已过期（计划=${task.plannedTime}，" +
                            "实际=${task.actualTime}），跳过"
                )
                continue
            }

            // ====== 阶段 1：倒计时等待 ======
            val delayMs = task.actualTimeMillis - now
            _tipsEvent.emit(
                TipsEvent.Executing(
                    task.displayIndex,
                    schedule.size,
                    task.actualTime,
                    task.plannedTime
                )
            )

            LogFileManager.writeLog(
                "调度第 ${task.displayIndex} 个任务，" +
                        "计划时间=${task.plannedTime}，" +
                        "实际时间=${task.actualTime}，" +
                        "延迟=${delayMs / 1000}s"
            )

            updateCountdownWithNotification(delayMs) { remaining ->
                val seconds = (remaining / 1000).toInt()
                // 更新通知栏
                ForegroundRunningService.emitNotificationText("${seconds.formatTime()}后执行第${task.displayIndex}个任务")
            }

            // ====== 阶段 2：打开目标 App，等待打卡或超时 ======
            val timeoutSeconds = SaveKeyValues.loadInt(
                Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
            )
            val clockInSuccess = waitForClockIn(timeoutSeconds, captureOnTimeout = true)

            // 超时路径——打卡失败，回到主页 + 兜底通知 + 继续下一个任务
            if (!clockInSuccess) {
                _returnToApp.emit(Unit)
                MessageDispatcher.sendMessage(
                    "任务执行结果通知", "任务超时，请手动检查是否打卡成功"
                )
            }

            // ====== 阶段 3：回到主界面，处理结果 ======
            executedCount++
        }

        // ====== 全部完成 ======
        val message = when {
            executedCount + skippedCount == 0 -> "无任务可供执行"
            executedCount == 0 -> "今日所有任务均已过期，跳过（$skippedCount 个），无需执行"
            skippedCount > 0 -> "今日任务已全部执行完毕（执行 $executedCount 个，跳过 $skippedCount 个）"
            else -> "今日任务已全部执行完毕"
        }
        LogFileManager.writeLog(message)
        ForegroundRunningService.emitNotificationText(message)
    }

    /**
     * 调试用：非 null 时跳过真实计算，直接使用指定秒数
     * 生产环境保持 null
     */
    @Volatile
    var debugWaitSeconds: Long? = null

    /**
     * 等待到下一个每日重置时间
     */
    private suspend fun waitUntilNextReset() {
        val resetHour = SaveKeyValues.loadInt(
            Constant.RESET_TIME_KEY, Constant.DEFAULT_RESET_HOUR
        )

        val waitSeconds = debugWaitSeconds ?: calculateSecondsUntilReset(resetHour)
        if (waitSeconds <= 0L) return  // 防御性代码：防止自旋

        LogFileManager.writeLog("等待 ${waitSeconds}s 后进入下一个任务周期")

        // 只发一次静态通知，不每秒刷新
        _tipsEvent.emit(TipsEvent.Completed)
        ForegroundRunningService.emitNotificationText("今日任务已执行完毕，等待下次任务")

        delay(waitSeconds * 1000)
    }

    /**
     * 打开目标应用并等待打卡结果。
     *
     * 定时任务与小程序的远程「立即打卡」共用此流程：
     *   1. 打开目标 App
     *   2. 等待打卡成功通知，或等待超时
     *   3. 返回是否在超时前收到成功信号
     *
     * @param timeoutSeconds 等待超时时间（秒）
     * @param captureOnTimeout 超时前是否触发兜底截屏（定时任务需要；远程指令不需要）
     */
    suspend fun waitForClockIn(
        timeoutSeconds: Int,
        captureOnTimeout: Boolean = false
    ): Boolean = coroutineScope {
        // 打开目标应用必须在主线程执行
        withContext(Dispatchers.Main) {
            DailyTaskApplication.get().openApplication()
        }

        // 清空上一次残留的成功信号，避免误判为本次打卡成功
        clockInSignal.tryReceive()

        var hasCaptured = false
        var captureDeferred: CompletableDeferred<String?>? = null

        // 使用子协程而非独立 CoroutineScope：
        // 调用方被取消（例如远程指令超时）时，倒计时任务会一并取消
        val timeoutJob = launch {
            updateCountdownWithNotification(timeoutSeconds * 1000L) { remaining ->
                val tick = (remaining / 1000).toInt()
                FloatingWindowController.updateTime(tick)

                if (captureOnTimeout && tick <= 5 && !hasCaptured) {
                    val resultSource = SaveKeyValues.loadInt(
                        Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX
                    )
                    if (resultSource == 1) {
                        hasCaptured = true
                        captureDeferred = CaptureImageService.requestCaptureScreen()
                    }
                }
            }
        }

        try {
            val success = select {
                timeoutJob.onJoin { false }
                clockInSignal.onReceive { true }
            }

            if (!success && hasCaptured) {
                val imagePath = withContext(Dispatchers.IO) {
                    runCatching { captureDeferred?.await() }.getOrNull() ?: ""
                }
                if (imagePath.isNotEmpty()) {
                    MessageDispatcher.sendAttachmentMessage(
                        "任务执行结果通知", "任务执行结果见附件", imagePath
                    )
                } else {
                    MessageDispatcher.sendMessage(
                        "任务执行结果通知", "截屏失败，请手动检查打卡结果"
                    )
                }
            }

            success
        } finally {
            // 无论正常结束还是被取消，都清理倒计时任务与残留信号
            timeoutJob.cancel()
            clockInSignal.tryReceive()
            // 打卡流程结束后回到本应用，远程触发与定时任务保持一致
            returnToDailyTask()
        }
    }

    /**
     * 打卡流程结束后把本应用切到前台。
     *
     * 原先该动作依赖 `_returnToApp` 事件，而它只有 MainActivity 在前台时才会被收集；
     * 远程指令触发打卡时 MainActivity 可能不在前台，事件会被丢弃，导致停留在目标应用。
     * 这里改为直接从应用上下文拉起 MainActivity，使远程触发与定时任务行为一致。
     */
    private fun returnToDailyTask() {
        runCatching {
            val app = DailyTaskApplication.get()
            val intent = Intent(app, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            app.startActivity(intent)
            LogFileManager.writeLog("打卡流程结束，已切回本应用")
        }.onFailure {
            LogFileManager.writeLog("回到本应用失败：${it.message ?: "未知错误"}")
        }
    }

    /**
     * 打卡成功通知
     *
     * 由 NotificationMonitorService 直接调用（不依赖 MainActivity 存活）。
     * 通过 CONFLATED Channel 广播，多个等待者不会互相覆盖。
     */
    fun notifyClockIn() {
        clockInSignal.trySend(Unit)
    }
    fun stopTask() {
        if (!_isRunning.value) {
            LogFileManager.writeLog("任务未运行，无需停止")
            return
        }

        LogFileManager.writeLog("停止执行每日任务")
        job?.cancel()
        job = null
        _isRunning.value = false
        ForegroundRunningService.emitNotificationText("为保证程序正常运行，请勿移除此通知")
    }

    /**
     * 因外部错误请求停止（目标 App 未安装、启动失败等）
     * 由 Context.openApplication() 在无法打开目标 App 时调用
     *
     * 与 stopTask() 的区别：
     *   stopTask()     — 用户主动点击"停止"，发消息通知
     *   requestStopDueToError() — 系统错误停止，不发消息通知，只重置调度器
     */
    fun requestStopDueToError(reason: String) {
        LogFileManager.writeLog("因错误请求停止：$reason")
        job?.cancel()
        job = null
        _isRunning.value = false
    }

    /**
     * 自校准倒计时 tick，支持 UI 回调。
     * 使用 elapsedRealtime 确保休眠唤醒后剩余时间准确。
     */
    private suspend fun CoroutineScope.updateCountdownWithNotification(
        totalMs: Long, onTick: (remainingMs: Long) -> Unit
    ) {
        val target = SystemClock.elapsedRealtime() + totalMs
        while (isActive) {
            val remaining = target - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            onTick(remaining)
            val step = minOf(1000L, remaining).coerceAtLeast(1)
            delay(step)
        }
    }

    private fun shouldSkipToday(): Boolean {
        val skipEnabled = SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
        if (!skipEnabled) return false

        val today = LocalDate.now()

        // 调休补班日（覆盖一切，必须执行）
        if (ChinaHolidayManager.isWorkday(today)) {
            LogFileManager.writeLog("今日为调休补班日，正常执行任务")
            return false
        }

        // 法定节假日 → 跳过
        if (ChinaHolidayManager.isHoliday(today)) {
            LogFileManager.writeLog("今日为法定节假日，跳过任务")
            return true
        }

        // 固定工作日或大小周休息日 → 跳过
        if (CustomWorkdayManager.isRestDay(today)) {
            LogFileManager.writeLog("今日为休息日，跳过任务")
            return true
        }

        // 其余情况 → 正常执行
        return false
    }

    /**
     * 从数据库加载所有任务，计算出当日实际执行时间，按时间排序
     * */
    private suspend fun buildTodaySchedule(): List<ScheduledTask> {
        val allTasks = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadAllTask()
        }
        if (allTasks.isEmpty()) return emptyList()

        val baseMillis = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        return allTasks.map { task ->
            val actualTime = task.resolveExecutionTime()
            val timeParts = actualTime.split(":").map { it.toInt() }
            val actualMillis = baseMillis +
                    timeParts[0] * 3_600_000L +
                    timeParts[1] * 60_000L +
                    timeParts[2] * 1_000L
            Triple(task, actualTime, actualMillis)
        }.sortedBy { it.third }.mapIndexed { index, (task, actualTime, actualMillis) ->
            ScheduledTask(task, index + 1, task.time, actualTime, actualMillis)
        }
    }

    /**
     * 计算距离下一次重置还有多少秒
     */
    private fun calculateSecondsUntilReset(resetHour: Int): Long {
        val now = Calendar.getInstance()
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, resetHour)
        target.set(Calendar.MINUTE, 0)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)

        if (now.timeInMillis >= target.timeInMillis) {
            target.add(Calendar.DATE, 1)
        }

        return ((target.timeInMillis - now.timeInMillis) / 1000).coerceAtLeast(1)
    }

    private data class ScheduledTask(
        val task: DailyTaskBean,
        val displayIndex: Int,
        val plannedTime: String,
        val actualTime: String,
        val actualTimeMillis: Long
    )
}
