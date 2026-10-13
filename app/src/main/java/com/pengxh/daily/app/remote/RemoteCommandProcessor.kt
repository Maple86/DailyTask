package com.pengxh.daily.app.remote

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.pengxh.daily.app.extensions.isApplicationExist
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.TaskScheduler
import com.pengxh.kt.lite.utils.SaveKeyValues

data class CommandResult(
    val status: String,
    val message: String,
    val retry: Boolean = false
)

object RemoteCommandProcessor {
    suspend fun execute(context: Context, command: JsonObject): CommandResult {
        val type = command.get("type")?.asString.orEmpty()
        return when (type) {
            "START_TASK" -> executeWithScheduler {
                if (TaskScheduler.isRunning()) {
                    CommandResult("SUCCEEDED", "任务已在运行中")
                } else {
                    TaskScheduler.startTask()
                    MessageDispatcher.sendMessage("启动任务通知", "任务已启动，将按计划时间执行")
                    CommandResult("SUCCEEDED", "任务已启动")
                }
            }

            "STOP_TASK" -> executeWithScheduler {
                if (!TaskScheduler.isRunning()) {
                    CommandResult("SUCCEEDED", "任务当前未在运行")
                } else {
                    TaskScheduler.stopTask()
                    MessageDispatcher.sendMessage("停止任务通知", "任务停止成功，请及时打开下次任务")
                    CommandResult("SUCCEEDED", "任务已停止")
                }
            }

            "TRIGGER_CLOCK_IN" -> executeClockIn(context)

            "QUERY_STATUS" -> CommandResult(
                "SUCCEEDED",
                if (TaskScheduler.isRunning()) "任务运行中" else "任务未运行"
            )

            else -> CommandResult("FAILED", "当前版本暂不支持指令：$type")
        }
    }

    private suspend fun executeWithScheduler(
        action: suspend () -> CommandResult
    ): CommandResult {
        if (TaskScheduler.isAttached()) {
            return action()
        }

        val app = com.pengxh.daily.app.DailyTaskApplication.get()
        ContextCompat.startForegroundService(
            app,
            Intent(app, ForegroundRunningService::class.java)
        )
        return CommandResult("RETRY", "正在启动任务服务，稍后重试", retry = true)
    }

    /**
     * 远程「立即打卡」：打开目标应用并等待打卡成功通知或超时。
     * 与定时任务共用 TaskScheduler.waitForClockIn()，行为保持一致。
     */
    private suspend fun executeClockIn(context: Context): CommandResult {
        if (!context.isApplicationExist(Constant.getTargetApp())) {
            return CommandResult("FAILED", "目标应用未安装，无法执行打卡")
        }

        MessageDispatcher.sendMessage("立即打卡通知", "已打开目标应用，正在等待打卡结果")

        val timeoutSeconds = SaveKeyValues.loadInt(
            Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
        )

        val ok = runCatching {
            TaskScheduler.waitForClockIn(timeoutSeconds, captureOnTimeout = false)
        }.getOrElse { error ->
            return CommandResult("FAILED", "打卡执行异常：${error.message ?: "未知错误"}")
        }

        return if (ok) {
            CommandResult("SUCCEEDED", "打卡成功")
        } else {
            CommandResult("SUCCEEDED", "已打开目标应用，但 ${timeoutSeconds}s 内未收到打卡成功通知")
        }
    }
}