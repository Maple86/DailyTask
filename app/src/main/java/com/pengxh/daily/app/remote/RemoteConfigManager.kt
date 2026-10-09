package com.pengxh.daily.app.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import com.pengxh.daily.app.utils.WorkdayMode
import com.pengxh.daily.app.utils.WorkdaySchedule
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

object RemoteConfigManager {
    private val taskTimePattern = Regex("""^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$""")

    suspend fun snapshot(): JsonObject {
        val schedule = CustomWorkdayManager.loadSchedule()
        val taskArray = JsonArray()
        val tasks = withContext(Dispatchers.IO) {
            DatabaseWrapper.loadAllTask()
        }
        tasks.forEach { task ->
            taskArray.add(JsonObject().apply {
                addProperty("time", task.time)
            })
        }

        return JsonObject().apply {
            addProperty("resetTime", SaveKeyValues.loadInt(Constant.RESET_TIME_KEY, 0))
            addProperty(
                "overtime",
                SaveKeyValues.loadInt(Constant.STAY_OVERTIME_KEY, Constant.DEFAULT_OVER_TIME)
            )
            addProperty("timeRange", SaveKeyValues.loadInt(Constant.TIME_RANGE_KEY, 5))
            addProperty("targetApp", SaveKeyValues.loadInt(Constant.TARGET_APP_KEY, 0))
            addProperty("autoRecycle", SaveKeyValues.loadBoolean(Constant.TASK_AUTO_RECYCLE_KEY, true))
            addProperty("randomTime", SaveKeyValues.loadBoolean(Constant.RANDOM_TIME_KEY, true))
            addProperty("skipHoliday", SaveKeyValues.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true))
            addProperty("powerSave", SaveKeyValues.loadBoolean(Constant.POWER_SAVE_MODE_KEY, false))
            addProperty("workdayMode", schedule.mode.name)
            addProperty(
                "fixedWorkdays",
                CustomWorkdayManager.serializeWorkdays(schedule.fixedWorkdays)
            )
            addProperty(
                "smallWeekWorkdays",
                CustomWorkdayManager.serializeWorkdays(schedule.smallWeekWorkdays)
            )
            addProperty(
                "bigWeekWorkdays",
                CustomWorkdayManager.serializeWorkdays(schedule.bigWeekWorkdays)
            )
            addProperty("bigWeekAnchorMonday", schedule.bigWeekAnchorMonday?.toString().orEmpty())
            add("tasks", taskArray)
        }
    }

    suspend fun apply(config: JsonObject) {
        if (config.has("resetTime")) {
            SaveKeyValues.saveInt(Constant.RESET_TIME_KEY, config.get("resetTime").asInt.coerceIn(0, 23))
        }
        if (config.has("overtime")) {
            SaveKeyValues.saveInt(
                Constant.STAY_OVERTIME_KEY,
                config.get("overtime").asInt.coerceAtLeast(1)
            )
        }
        if (config.has("timeRange")) {
            SaveKeyValues.saveInt(
                Constant.TIME_RANGE_KEY,
                config.get("timeRange").asInt.coerceAtLeast(1)
            )
        }
        if (config.has("targetApp")) {
            SaveKeyValues.saveInt(Constant.TARGET_APP_KEY, config.get("targetApp").asInt.coerceIn(0, 3))
        }
        if (config.has("autoRecycle")) {
            SaveKeyValues.saveBoolean(Constant.TASK_AUTO_RECYCLE_KEY, config.get("autoRecycle").asBoolean)
        }
        if (config.has("randomTime")) {
            SaveKeyValues.saveBoolean(Constant.RANDOM_TIME_KEY, config.get("randomTime").asBoolean)
        }
        if (config.has("skipHoliday")) {
            SaveKeyValues.saveBoolean(Constant.SKIP_HOLIDAY_KEY, config.get("skipHoliday").asBoolean)
        }
        if (config.has("powerSave")) {
            SaveKeyValues.saveBoolean(Constant.POWER_SAVE_MODE_KEY, config.get("powerSave").asBoolean)
        }

        applyWorkdayConfig(config)
        applyTasks(config)
    }

    private fun applyWorkdayConfig(config: JsonObject) {
        val current = CustomWorkdayManager.loadSchedule()
        val mode = config.get("workdayMode")?.asString
            ?.let { CustomWorkdayManager.parseMode(it) }
            ?: current.mode
        val fixed = config.get("fixedWorkdays")?.asString
            ?.let { CustomWorkdayManager.loadWorkdaysFromRaw(it, current.fixedWorkdays) }
            ?: current.fixedWorkdays
        val small = config.get("smallWeekWorkdays")?.asString
            ?.let { CustomWorkdayManager.loadWorkdaysFromRaw(it, fixed) }
            ?: current.smallWeekWorkdays
        val big = config.get("bigWeekWorkdays")?.asString
            ?.let { CustomWorkdayManager.loadWorkdaysFromRaw(it, current.bigWeekWorkdays) }
            ?: current.bigWeekWorkdays
        val anchor = config.get("bigWeekAnchorMonday")?.asString
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: current.bigWeekAnchorMonday

        CustomWorkdayManager.saveSchedule(
            WorkdaySchedule(
                mode = if (mode == WorkdayMode.ALTERNATING && anchor == null) {
                    current.mode
                } else {
                    mode
                },
                fixedWorkdays = fixed,
                smallWeekWorkdays = small,
                bigWeekWorkdays = big,
                bigWeekAnchorMonday = anchor
            )
        )
    }

    private suspend fun applyTasks(config: JsonObject) {
        if (!config.has("tasks") || !config.get("tasks").isJsonArray) return

        val tasks = config.getAsJsonArray("tasks").mapNotNull { element ->
            val time = runCatching {
                element.asJsonObject.get("time")?.asString.orEmpty()
            }.getOrDefault("")
            if (!taskTimePattern.matches(time)) {
                null
            } else {
                DailyTaskBean().apply {
                    id = 0
                    this.time = time
                }
            }
        }.distinctBy { it.time }

        if (tasks.isNotEmpty() || config.getAsJsonArray("tasks").size() == 0) {
            withContext(Dispatchers.IO) {
                DatabaseWrapper.replaceAllTasks(tasks)
            }
        }
    }
}
