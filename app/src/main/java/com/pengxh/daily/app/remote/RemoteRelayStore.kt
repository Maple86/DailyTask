package com.pengxh.daily.app.remote

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object RemoteRelayStore {
    private const val PREFS_NAME = "remote_relay"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_PENDING_ATTENDANCE = "pending_attendance"
    private const val KEY_COMMAND_RESULTS = "command_results"

    private val gson = Gson()
    private lateinit var preferences: SharedPreferences

    fun initialize(context: Context) {
        if (!::preferences.isInitialized) {
            preferences = context.applicationContext.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            )
        }
    }

    var endpoint: String
        get() = loadString(KEY_ENDPOINT)
        set(value) = saveString(KEY_ENDPOINT, value.trim().trimEnd('/'))

    var deviceId: String
        get() = loadString(KEY_DEVICE_ID)
        set(value) = saveString(KEY_DEVICE_ID, value.trim())

    var deviceToken: String
        get() = loadString(KEY_DEVICE_TOKEN)
        set(value) = saveString(KEY_DEVICE_TOKEN, value.trim())

    var deviceName: String
        get() = loadString(KEY_DEVICE_NAME)
        set(value) = saveString(KEY_DEVICE_NAME, value.trim())

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    var lastSyncAt: Long
        get() = preferences.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_SYNC_AT, value).apply()

    var lastError: String
        get() = loadString(KEY_LAST_ERROR)
        set(value) = saveString(KEY_LAST_ERROR, value)

    fun isConfigured(): Boolean {
        return endpoint.isNotBlank() && deviceId.isNotBlank() && deviceToken.isNotBlank()
    }

    @Synchronized
    fun queueAttendance(record: JsonObject) {
        val dedupeKey = record.get("dedupeKey")?.asString.orEmpty()
        if (dedupeKey.isBlank()) return

        val queue = loadJsonArray(KEY_PENDING_ATTENDANCE)
        if (queue.any { it.asJsonObject.get("dedupeKey")?.asString == dedupeKey }) return
        queue.add(record)
        saveJsonArray(KEY_PENDING_ATTENDANCE, queue)
    }

    @Synchronized
    fun pendingAttendance(): List<JsonObject> {
        return loadJsonArray(KEY_PENDING_ATTENDANCE).mapNotNull { element ->
            runCatching { element.asJsonObject }.getOrNull()
        }
    }

    @Synchronized
    fun removeAttendance(dedupeKeys: Collection<String>) {
        if (dedupeKeys.isEmpty()) return
        val keys = dedupeKeys.toSet()
        val queue = JsonArray()
        loadJsonArray(KEY_PENDING_ATTENDANCE).forEach { element ->
            if (!keys.contains(element.asJsonObject.get("dedupeKey")?.asString)) {
                queue.add(element)
            }
        }
        saveJsonArray(KEY_PENDING_ATTENDANCE, queue)
    }

    @Synchronized
    fun saveCommandResult(commandId: String, status: String, message: String) {
        val results = loadJsonObject(KEY_COMMAND_RESULTS)
        results.add(commandId, JsonObject().apply {
            addProperty("status", status)
            addProperty("message", message)
            addProperty("updatedAt", System.currentTimeMillis())
        })
        saveJsonObject(KEY_COMMAND_RESULTS, results)
    }

    @Synchronized
    fun commandResult(commandId: String): JsonObject? {
        val results = loadJsonObject(KEY_COMMAND_RESULTS)
        return results.get(commandId)?.takeIf { it.isJsonObject }?.asJsonObject
    }

    @Synchronized
    fun removeCommandResult(commandId: String) {
        val results = loadJsonObject(KEY_COMMAND_RESULTS)
        results.remove(commandId)
        saveJsonObject(KEY_COMMAND_RESULTS, results)
    }

    @Synchronized
    fun reset() {
        preferences.edit().clear().apply()
    }

    private fun loadString(key: String): String = preferences.getString(key, "").orEmpty()

    private fun saveString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    private fun loadJsonArray(key: String): JsonArray {
        val raw = loadString(key)
        if (raw.isBlank()) return JsonArray()
        return runCatching { JsonParser.parseString(raw).asJsonArray }.getOrElse { JsonArray() }
    }

    private fun saveJsonArray(key: String, value: JsonArray) {
        saveString(key, gson.toJson(value))
    }

    private fun loadJsonObject(key: String): JsonObject {
        val raw = loadString(key)
        if (raw.isBlank()) return JsonObject()
        return runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse { JsonObject() }
    }

    private fun saveJsonObject(key: String, value: JsonObject) {
        saveString(key, gson.toJson(value))
    }
}

