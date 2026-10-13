package com.pengxh.daily.app.remote

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/**
 * 远程中继协调器。
 *
 * 只负责远程快捷操作指令（启动/停止任务、立即打卡、循环开关、状态查询）的拉取与回执，
 * 以及设备状态上报和考勤补交。
 *
 * 任务时间、工作日等配置由 Android 端本地管理，不再通过云端下发。
 */
object RemoteRelayCoordinator {
    private const val TAG = "RemoteRelay"
    private const val POLL_INTERVAL_SECONDS = 60L
    private const val ACK_TIMEOUT_BUFFER_SECONDS = 15L

    private val client = RemoteRelayClient()
    private val mutex = Mutex()
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var applicationContext: Context? = null

    /**
     * 正在执行中的指令 ID。
     *
     * 指令执行（尤其是远程打卡要等待通知或超时，最长可达数十秒）与网络同步解耦，
     * 否则会长时间占用 mutex，导致后续轮询被阻塞。
     */
    private val inFlightCommands: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        RemoteRelayStore.initialize(context.applicationContext)
    }

    /**
     * 是否已启用且完成配对。WorkManager / AlarmManager 兜底任务用它做前置判断。
     */
    fun shouldSync(): Boolean {
        return RemoteRelayStore.enabled && RemoteRelayStore.isConfigured()
    }

    fun start(scope: CoroutineScope) {
        stop()
        if (!shouldSync()) return

        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive && RemoteRelayStore.enabled) {
                // 单次同步失败不能中断整个轮询循环，更不能让异常冒泡杀死进程
                runCatching { syncOnce() }.onFailure {
                    Log.e(TAG, "轮询同步异常，已忽略并等待下一轮", it)
                }
                delay(POLL_INTERVAL_SECONDS * 1000L)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun syncNow() {
        if (!shouldSync()) return
        internalScope.launch {
            runCatching { syncOnce() }.onFailure {
                Log.e(TAG, "手动同步异常", it)
            }
        }
    }

    suspend fun pair(endpoint: String, pairingCode: String, deviceName: String): RelayResponse {
        initializeIfNeeded()
        val request = JsonObject().apply {
            addProperty("action", "pairDevice")
            addProperty("pairingCode", pairingCode)
            addProperty("deviceName", deviceName.ifBlank { Build.MODEL })
            addProperty("deviceModel", Build.MODEL)
            addProperty("androidVersion", Build.VERSION.SDK_INT)
            addProperty("appVersion", com.pengxh.daily.app.BuildConfig.VERSION_NAME)
        }
        val response = client.call(endpoint, request)
        if (response.ok) {
            val data = response.data ?: JsonObject()
            RemoteRelayStore.endpoint = endpoint
            RemoteRelayStore.deviceId = data.stringOrEmpty("deviceId")
            RemoteRelayStore.deviceToken = data.stringOrEmpty("deviceToken")
            RemoteRelayStore.deviceName = deviceName.ifBlank { Build.MODEL }
            RemoteRelayStore.enabled = true
            RemoteRelayStore.lastError = ""
        }
        return response
    }

    /**
     * 一次同步：上报设备状态、拉取指令、补交考勤。
     *
     * 指令执行放到独立协程，避免阻塞轮询与 mutex。
     */
    suspend fun syncOnce(): Boolean {
        initializeIfNeeded()
        if (!shouldSync()) return false

        val commands = mutex.withLock {
            val context = applicationContext ?: return@withLock null
            val response = client.call(
                endpoint = RemoteRelayStore.endpoint,
                payload = DeviceStatusCollector.buildSyncPayload(context),
                token = RemoteRelayStore.deviceToken
            )
            if (!response.ok) {
                RemoteRelayStore.lastError = response.errorMessage
                Log.w(TAG, "sync failed: ${response.errorCode} ${response.errorMessage}")
                return@withLock null
            }

            val data = response.data ?: JsonObject()
            flushAttendance()

            RemoteRelayStore.lastSyncAt = System.currentTimeMillis()
            RemoteRelayStore.lastError = ""
            data.arrayOrNull("commands") ?: JsonArray()
        } ?: return false

        dispatchCommands(commands)
        return true
    }

    private fun dispatchCommands(commands: JsonArray) {
        val context = applicationContext ?: return
        commands.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val command = element.asJsonObject
            val commandId = command.get("id")?.asString
                ?: command.get("commandId")?.asString
                ?: return@forEach

            if (!inFlightCommands.add(commandId)) return@forEach
            internalScope.launch {
                try {
                    handleCommand(context, command, commandId)
                } catch (e: Throwable) {
                    Log.e(TAG, "执行指令 $commandId 异常", e)
                } finally {
                    inFlightCommands.remove(commandId)
                }
            }
        }
    }

    fun reportAttendance(record: JsonObject) {
        initializeIfNeeded()
        if (!shouldSync()) return
        RemoteRelayStore.queueAttendance(record)
        syncNow()
    }

    fun clearBinding() {
        stop()
        applicationContext?.let { RemoteSyncScheduler.cancel(it) }
        inFlightCommands.clear()
        RemoteRelayStore.reset()
    }

    private suspend fun handleCommand(
        context: Context,
        command: JsonObject,
        commandId: String
    ) {
        // 上一次已执行但 ACK 失败时，直接补发结果，避免重复执行
        val cached = RemoteRelayStore.commandResult(commandId)
        if (cached != null && cached.get("status")?.asString != "RETRY") {
            val status = cached.get("status")?.asString ?: "FAILED"
            val message = cached.get("message")?.asString ?: ""
            if (ackCommand(commandId, status, message)) {
                RemoteRelayStore.removeCommandResult(commandId)
            }
            return
        }

        // 远程「立即打卡」需要等待打卡通知，最长可达配置的超时时间
        val ackTimeoutSeconds = SaveKeyValues.loadInt(
            Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME
        ) + ACK_TIMEOUT_BUFFER_SECONDS

        val commandResult = withTimeoutOrNull(ackTimeoutSeconds * 1000L) {
            RemoteCommandProcessor.execute(context, command)
        } ?: CommandResult("FAILED", "执行超时，已放弃等待打卡结果")

        if (commandResult.retry) return

        if (ackCommand(commandId, commandResult.status, commandResult.message)) {
            RemoteRelayStore.removeCommandResult(commandId)
        } else {
            // ACK 失败时暂存结果，下次同步优先补发，不重复执行指令
            RemoteRelayStore.saveCommandResult(
                commandId, commandResult.status, commandResult.message
            )
        }
    }

    private suspend fun ackCommand(
        commandId: String,
        status: String,
        message: String
    ): Boolean {
        val request = JsonObject().apply {
            addProperty("action", "deviceAck")
            addProperty("deviceId", RemoteRelayStore.deviceId)
            addProperty("commandId", commandId)
            addProperty("status", status)
            addProperty("message", message)
        }
        val response = client.call(
            RemoteRelayStore.endpoint,
            request,
            RemoteRelayStore.deviceToken
        )
        return response.ok
    }

    private suspend fun flushAttendance() {
        val pending = RemoteRelayStore.pendingAttendance()
        if (pending.isEmpty()) return

        val records = JsonArray()
        pending.forEach { records.add(it) }
        val request = JsonObject().apply {
            addProperty("action", "deviceAttendance")
            addProperty("deviceId", RemoteRelayStore.deviceId)
            add("records", records)
        }
        val response = client.call(
            RemoteRelayStore.endpoint,
            request,
            RemoteRelayStore.deviceToken
        )
        if (response.ok) {
            RemoteRelayStore.removeAttendance(
                pending.mapNotNull { it.get("dedupeKey")?.asString }
            )
        }
    }

    private fun initializeIfNeeded() {
        if (applicationContext == null) {
            error("RemoteRelayCoordinator 尚未初始化")
        }
    }
}