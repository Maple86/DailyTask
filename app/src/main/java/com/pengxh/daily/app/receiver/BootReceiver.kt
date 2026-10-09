package com.pengxh.daily.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.pengxh.daily.app.remote.RemoteRelayCoordinator
import com.pengxh.daily.app.remote.RemoteRelayStore
import com.pengxh.daily.app.remote.RemoteSyncScheduler
import com.pengxh.daily.app.service.ForegroundRunningService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        RemoteRelayCoordinator.initialize(context)
        RemoteSyncScheduler.schedule(context)
        if (!RemoteRelayStore.enabled || !RemoteRelayStore.isConfigured()) return

        ContextCompat.startForegroundService(
            context,
            Intent(context, ForegroundRunningService::class.java)
        )
    }
}
