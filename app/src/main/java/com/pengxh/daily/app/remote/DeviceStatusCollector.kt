package com.pengxh.daily.app.remote

import android.content.Context
import android.os.BatteryManager
import com.google.gson.JsonObject
import com.pengxh.daily.app.BuildConfig
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.TaskScheduler

object DeviceStatusCollector {
    fun buildSyncPayload(context: Context): JsonObject {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val activeNetwork = connectivity?.activeNetwork
        val capabilities = connectivity?.getNetworkCapabilities(activeNetwork)
        val networkType = when {
            capabilities == null -> "NONE"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }

        return JsonObject().apply {
            addProperty("action", "deviceSync")
            addProperty("deviceId", RemoteRelayStore.deviceId)
            addProperty("deviceName", RemoteRelayStore.deviceName)
            addProperty("appVersion", BuildConfig.VERSION_NAME)
            addProperty("androidVersion", android.os.Build.VERSION.SDK_INT)
            addProperty("battery", battery)
            addProperty("networkType", networkType)
            addProperty("taskRunning", TaskScheduler.isRunning())
            addProperty("notificationListenerAlive", context.notificationEnable())
            addProperty("projectionAlive", ProjectionSession.isStateActive())
        }
    }
}
