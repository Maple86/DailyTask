package com.pengxh.daily.app.remote

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RemoteSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        RemoteRelayCoordinator.initialize(applicationContext)
        if (!RemoteRelayCoordinator.shouldSync()) {
            return Result.success()
        }
        return if (RemoteRelayCoordinator.syncOnce()) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}