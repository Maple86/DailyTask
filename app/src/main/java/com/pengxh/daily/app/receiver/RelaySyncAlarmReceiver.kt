package com.pengxh.daily.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengxh.daily.app.remote.RemoteRelayCoordinator
import com.pengxh.daily.app.remote.RemoteSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AlarmManager 兜底同步。
 *
 * 使用场景：国产 ROM（OPPO/ColorOS 等）会杀死前台服务，
 * 导致 RemoteRelayCoordinator 的 60 秒轮询循环停止。
 * 该广播由 RemoteSyncScheduler 定时唤醒，主动拉取一次指令。
 */
class RelaySyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        RemoteRelayCoordinator.initialize(context)
        if (!RemoteRelayCoordinator.shouldSync()) return

        // 先串起下一次闹钟，避免本次执行异常后兜底链断裂
        RemoteSyncScheduler.rescheduleAfterAlarm(context)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                RemoteRelayCoordinator.syncOnce()
            } catch (e: Throwable) {
                // 忽略：下次唤醒会重试
            } finally {
                pending.finish()
            }
        }
    }
}
