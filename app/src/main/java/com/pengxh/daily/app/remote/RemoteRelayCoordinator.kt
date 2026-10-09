package com.pengxh.daily.app.remote

import android.content.Context
import android.os.Build
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RemoteRelayCoordinator {
    private val client = RemoteRelayClient()
    private val mutex = Mutex()
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        RemoteRelayStore.initialize(context.applicationContext)
    }

    fun start(scope: CoroutineScope) {
        stop()
        if (!RemoteRelayStore.enabled || !RemoteRelayStore.isConfigured()) return

        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive && RemoteRelayStore.enabled) {
                syncOnce()
                val waitSeconds = 60L
                delay(waitSeconds * 1000L)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun syncNow() {
        if (!RemoteRelayStore.enabled || !RemoteRelayStore.isConfigured()) return
        internalScope.launch { syncOnce() }
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
            add("initialConfig", RemoteConfigManager.snapshot())
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
            RemoteRelayStore.lastError = ""
        }
        return response
    }

    suspend fun syncOnce(): Boolean {
        initializeIfNeeded()
        if (!RemoteRelayStore.enabled || !RemoteRelayStore.isConfigured()) return false

        return mutex.withLock {
            val context = applicationContext ?: return@withLock false
            val response = client.call(
                endpoint = RemoteRelayStore.endpoint,
                payload = DeviceStatusCollector.buildSyncPayload(context),
                token = RemoteRelayStore.deviceToken
            )
            if (!response.ok) {
                RemoteRelayStore.lastError = response.errorMessage
                return@withLock false
            }

            val data = response.data ?: JsonObject()
            applyConfig(data)
            val commands = data.getAsJsonArray("commands") ?: JsonArray()
            commands.forEach { element ->
                if (!element.isJsonObject) return@forEach
                handleCommand(context, element.asJsonObject)
            }
            flushAttendance()
            RemoteRelayStore.lastSyncAt = System.currentTimeMillis()
            RemoteRelayStore.lastError = ""
            true
        }
    }

    fun reportAttendance(record: JsonObject) {
        initializeIfNeeded()
        if (!RemoteRelayStore.enabled || !RemoteRelayStore.isConfigured()) return
        RemoteRelayStore.queueAttendance(record)
        syncNow()
    }

    fun clearBinding() {
        stop()
        RemoteRelayStore.reset()
    }

    private suspend fun applyConfig(data: JsonObject) {
        val revision = data.get("configRevision")?.asInt ?: return
        if (revision <= RemoteRelayStore.configRevision) return
        val config = data.getAsJsonObject("config") ?: return

        RemoteConfigManager.apply(config)
        RemoteRelayStore.configRevision = revision
    }

    private suspend fun handleCommand(context: Context, command: JsonObject) {
        val commandId = command.get("id")?.asString.orEmpty()
        if (commandId.isBlank()) return

        val cached = RemoteRelayStore.commandResult(commandId)
        val result = if (cached != null && cached.get("status")?.asString != "RETRY") {
            CommandResult(
                cached.get("status")?.asString ?: "FAILED",
                cached.get("message")?.asString ?: ""
            )
        } else {
            RemoteCommandProcessor.execute(context, command).also { commandResult ->
                RemoteRelayStore.saveCommandResult(
                    commandId, commandResult.status, commandResult.message
                )
            }
        }

        if (result.retry) return

        val ackRequest = JsonObject().apply {
            addProperty("action", "deviceAck")
            addProperty("deviceId", RemoteRelayStore.deviceId)
            addProperty("commandId", commandId)
            addProperty("status", result.status)
            addProperty("message", result.message)
        }
        val ackResponse = client.call(
            RemoteRelayStore.endpoint,
            ackRequest,
            RemoteRelayStore.deviceToken
        )
        if (ackResponse.ok) {
            RemoteRelayStore.removeCommandResult(commandId)
        }
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
