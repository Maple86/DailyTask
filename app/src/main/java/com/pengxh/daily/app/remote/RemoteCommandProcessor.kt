package com.pengxh.daily.app.remote

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.pengxh.daily.app.extensions.isApplicationExist
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.service.ForegroundRunningService
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.TaskScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CommandResult(
    val status: String,
    val message: String,
    val retry: Boolean = false
)

object RemoteCommandProcessor {
    suspend fun execute(context: Context, command: JsonObject): CommandResult {
        val type = command.get("type")?.asString.orEmpty()
        return when (type) {
            "START_TASK" -> executeWithScheduler(context) {
                if (!TaskScheduler.isRunning()) {
                    TaskScheduler.startTask()
                }
                CommandResult("SUCCEEDED", "任务已启动")
            }

            "STOP_TASK" -> executeWithScheduler(context) {
                if (TaskScheduler.isRunning()) {
                    TaskScheduler.stopTask()
                }
                CommandResult("SUCCEEDED", "任务已停止")
            }

            "TRIGGER_CLOCK_IN" -> executeClockIn(context)

            "QUERY_STATUS" -> CommandResult("SUCCEEDED", "状态已同步")

            else -> CommandResult("FAILED", "当前版本暂不支持指令：$type")
        }
    }

    private fun executeWithScheduler(
        context: Context,
        action: () -> CommandResult
    ): CommandResult {
        if (TaskScheduler.isAttached()) {
            return action()
        }

        ContextCompat.startForegroundService(
            context,
            Intent(context, ForegroundRunningService::class.java)
        )
        return CommandResult("RETRY", "正在启动任务服务，稍后重试", retry = true)
    }

    private suspend fun executeClockIn(context: Context): CommandResult {
        if (!context.isApplicationExist(Constant.getTargetApp())) {
            return CommandResult("FAILED", "目标应用未安装，无法执行打卡")
        }
        return withContext(Dispatchers.Main) {
            runCatching {
                context.openApplication()
            }.fold(
                onSuccess = {
                    CommandResult("SUCCEEDED", "已打开目标应用，打卡结果将自动上报")
                },
                onFailure = {
                    CommandResult("FAILED", it.message ?: "打开目标应用失败")
                }
            )
        }
    }
}

