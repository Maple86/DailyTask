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
                syncOnce()
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
        internalScope.launch { syncOnce() }
    }

    suspend fun pair(endpoint: String, pairingCode: String, deviceName: String): RelayResponse {
        initializeIfNeeded()
        // 记录初始配置内容指纹：配对时服务端已保存该快照，
        // 这样首次同步不会因为「内容不一致」而重复应用一次。
        val initialConfig = RemoteConfigManager.snapshot()
        val request = JsonObject().apply {
            addProperty("action", "pairDevice")
            addProperty("pairingCode", pairingCode)
            addProperty("deviceName", deviceName.ifBlank { Build.MODEL })
            addProperty("deviceModel", Build.MODEL)
            addProperty("androidVersion", Build.VERSION.SDK_INT)
            addProperty("appVersion", com.pengxh.daily.app.BuildConfig.VERSION_NAME)
            add("initialConfig", initialConfig)
        }
        val response = client.call(endpoint, request)
        if (response.ok) {
            val data = response.data ?: JsonObject()
            RemoteRelayStore.endpoint = endpoint
            RemoteRelayStore.deviceId = data.get("deviceId")?.asString.orEmpty()
            RemoteRelayStore.deviceToken = data.get("deviceToken")?.asString.orEmpty()
            RemoteRelayStore.deviceName = deviceName.ifBlank { Build.MODEL }
            RemoteRelayStore.enabled = true
            RemoteRelayStore.configRevision = 1
            RemoteRelayStore.configHash = stableConfigHash(initialConfig)
            RemoteRelayStore.lastError = ""
        }
        return response
    }

    /**
     * 一次同步：上报设备状态、拉取配置与指令、补交考勤。
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
            applyConfig(data)
            flushAttendance()

            RemoteRelayStore.lastSyncAt = System.currentTimeMillis()
            RemoteRelayStore.lastError = ""
            data.getAsJsonArray("commands") ?: JsonArray()
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

    /**
     * 应用云端下发的配置。
     *
     * 判断条件从「revision 变大」改为「revision 变大 或 配置内容发生变化」：
     * 仅比较 revision 时，一旦本地版本号追平或超过后端（重装、重新配对、并发写入等），
     * 配置将永远不会再被应用，表现为小程序改了配置但手机端长期不更新。
     */
    private suspend fun applyConfig(data: JsonObject) {
        val revision = data.get("configRevision")?.asInt ?: return
        val config = data.getAsJsonObject("config") ?: return

        val contentHash = stableConfigHash(config)
        val revisionAdvanced = revision > RemoteRelayStore.configRevision
        val contentChanged = contentHash != RemoteRelayStore.configHash
        if (!revisionAdvanced && !contentChanged) return

        RemoteConfigManager.apply(config)
        RemoteRelayStore.configRevision = revision
        RemoteRelayStore.configHash = contentHash
        Log.d(
            TAG,
            "配置已应用 revision=$revision (revisionAdvanced=$revisionAdvanced, contentChanged=$contentChanged)"
        )
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

    /**
     * 计算配置内容的稳定哈希。
     *
     * 不能直接用 `JsonObject.hashCode()` / `toString()`：两者都与键顺序相关，
     * 服务端 JSON 往返后键顺序可能变化，会把相同配置判定为「已变化」而反复应用。
     * 这里对各字段名排序后拼接，保证同样的配置得到同样的结果。
     */
    private fun stableConfigHash(config: JsonObject): String = normalizeJson(config).toString()

    /**
     * 递归规范化 Json：对象按键名排序，数组保持原顺序。
     * 这样服务端往返导致的键顺序变化不会影响指纹。
     */
    private fun normalizeJson(element: com.google.gson.JsonElement): com.google.gson.JsonElement {
        return when {
            element.isJsonObject -> JsonObject().apply {
                element.asJsonObject.entrySet()
                    .sortedBy { it.key }
                    .forEach { add(it.key, normalizeJson(it.value)) }
            }

            element.isJsonArray -> JsonArray().apply {
                element.asJsonArray.forEach { add(normalizeJson(it)) }
            }

            else -> element
        }
    }

    private fun initializeIfNeeded() {
        if (applicationContext == null) {
            error("RemoteRelayCoordinator 尚未初始化")
        }
    }
}