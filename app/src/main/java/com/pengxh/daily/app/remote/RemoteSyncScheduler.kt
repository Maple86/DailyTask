package com.pengxh.daily.app.remote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pengxh.daily.app.receiver.RelaySyncAlarmReceiver
import java.util.concurrent.TimeUnit

/**
 * 远程指令同步调度。
 *
 * 三重保障：
 *   1. ForegroundRunningService 内 60 秒轮询（最快，但会被国产 ROM 杀）
 *   2. AlarmManager 每 15 分钟兜底唤醒（不依赖前台服务存活）
 *   3. WorkManager 15 分钟周期任务（系统级兜底，可能被延迟）
 */
object RemoteSyncScheduler {
    private const val WORK_NAME = "remote_relay_periodic_sync"
    private const val ONE_SHOT_WORK_NAME = "remote_relay_alarm_sync"
    private const val REQUEST_CODE = 0x6474
    private const val ALARM_INTERVAL_MS = 15 * 60 * 1000L

    /**
     * 注册全部兜底调度。需要在 Application 启动与开机广播时调用。
     */
    fun schedule(context: Context) {
        scheduleWorkManager(context)
        scheduleAlarm(context)
    }

    /**
     * 取消全部兜底调度。解绑设备或关闭远程中继时调用。
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(ONE_SHOT_WORK_NAME)

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager?.cancel(alarmPendingIntent(context))
    }

    private fun scheduleWorkManager(context: Context) {
        val request = PeriodicWorkRequestBuilder<RemoteSyncWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * 用 AlarmManager 触发一次性 Worker。
     * AlarmManager 在国产 ROM 上比 WorkManager 周期任务更容易准时执行。
     */
    private fun enqueueOneShotSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<RemoteSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun scheduleAlarm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MS
        val pendingIntent = alarmPendingIntent(context)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // 没有精确闹钟权限时退化为不精确闹钟，仍然比 WorkManager 准时
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    /**
     * 由 RelaySyncAlarmReceiver 在每次唤醒后调用，串起下一次闹钟。
     * 同时入队一次性 Worker，保证有网络时能真正完成同步。
     */
    fun rescheduleAfterAlarm(context: Context) {
        scheduleAlarm(context)
        enqueueOneShotSync(context)
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RelaySyncAlarmReceiver::class.java).apply {
            action = "com.pengxh.daily.app.action.RELAY_SYNC_ALARM"
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}