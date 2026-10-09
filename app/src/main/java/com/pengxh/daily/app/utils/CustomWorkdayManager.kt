package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object CustomWorkdayManager {
    private val orderedDays = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY
    )

    private val defaultWorkdays = WorkdaySchedule.DEFAULT_FIXED_WORKDAYS
    private val defaultSmallWeekWorkdays = WorkdaySchedule.DEFAULT_SMALL_WEEK_WORKDAYS
    private val defaultBigWeekWorkdays = WorkdaySchedule.DEFAULT_BIG_WEEK_WORKDAYS

    private val dayNameMap = mapOf(
        DayOfWeek.MONDAY to "周一",
        DayOfWeek.TUESDAY to "周二",
        DayOfWeek.WEDNESDAY to "周三",
        DayOfWeek.THURSDAY to "周四",
        DayOfWeek.FRIDAY to "周五",
        DayOfWeek.SATURDAY to "周六",
        DayOfWeek.SUNDAY to "周日"
    )

    fun loadSchedule(): WorkdaySchedule {
        val fixedWorkdays = loadWorkdaysFromRaw(
            SaveKeyValues.loadString(
                Constant.CUSTOM_WORKDAYS_KEY, serializeWorkdays(defaultWorkdays)
            )
        )
        val mode = parseMode(
            SaveKeyValues.loadString(Constant.WORKDAY_MODE_KEY, WorkdayMode.FIXED.name)
        )
        val smallWeekWorkdays = loadWorkdaysFromRaw(
            SaveKeyValues.loadString(
                Constant.WORKDAY_SMALL_WEEK_KEY, serializeWorkdays(fixedWorkdays)
            ),
            fixedWorkdays
        )
        val bigWeekWorkdays = loadWorkdaysFromRaw(
            SaveKeyValues.loadString(
                Constant.WORKDAY_BIG_WEEK_KEY, serializeWorkdays(defaultBigWeekWorkdays)
            ),
            defaultBigWeekWorkdays
        )
        val anchor = SaveKeyValues.loadString(Constant.WORKDAY_BIG_WEEK_ANCHOR_KEY, "")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        return WorkdaySchedule(
            mode = mode,
            fixedWorkdays = fixedWorkdays,
            smallWeekWorkdays = smallWeekWorkdays,
            bigWeekWorkdays = bigWeekWorkdays,
            bigWeekAnchorMonday = anchor
        )
    }

    fun saveSchedule(schedule: WorkdaySchedule) {
        val fixedWorkdays = schedule.fixedWorkdays.ifEmpty { defaultWorkdays }
        val smallWeekWorkdays = schedule.smallWeekWorkdays.ifEmpty { fixedWorkdays }
        val bigWeekWorkdays = schedule.bigWeekWorkdays.ifEmpty { defaultBigWeekWorkdays }
        val anchorMonday = schedule.bigWeekAnchorMonday?.with(
            TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        )

        SaveKeyValues.saveString(
            Constant.CUSTOM_WORKDAYS_KEY, serializeWorkdays(fixedWorkdays)
        )
        SaveKeyValues.saveString(Constant.WORKDAY_MODE_KEY, schedule.mode.name)
        SaveKeyValues.saveString(
            Constant.WORKDAY_SMALL_WEEK_KEY, serializeWorkdays(smallWeekWorkdays)
        )
        SaveKeyValues.saveString(
            Constant.WORKDAY_BIG_WEEK_KEY, serializeWorkdays(bigWeekWorkdays)
        )
        SaveKeyValues.saveString(
            Constant.WORKDAY_BIG_WEEK_ANCHOR_KEY, anchorMonday?.toString().orEmpty()
        )
    }

    fun loadWorkdays(): Set<DayOfWeek> {
        return loadSchedule().fixedWorkdays
    }

    fun loadWorkdaysFromRaw(
        raw: String,
        fallback: Set<DayOfWeek> = defaultWorkdays
    ): Set<DayOfWeek> {
        val parsed = raw.split(",").mapNotNull { token ->
            token.trim().toIntOrNull()?.let { value ->
                orderedDays.firstOrNull { it.value == value }
            }
        }.toSet()
        return parsed.ifEmpty { fallback }
    }

    fun saveWorkdays(workdays: Set<DayOfWeek>) {
        val current = loadSchedule()
        saveSchedule(current.copy(mode = WorkdayMode.FIXED, fixedWorkdays = workdays))
    }

    fun serializeWorkdays(
        workdays: Set<DayOfWeek>,
        fallback: Set<DayOfWeek> = defaultWorkdays
    ): String {
        val normalized = orderedDays.filter {
            it in workdays
        }.map {
            it.value.toString()
        }
        return if (normalized.isEmpty()) {
            orderedDays.filter { it in fallback }.joinToString(",") {
                it.value.toString()
            }
        } else {
            normalized.joinToString(",")
        }
    }

    fun formatWorkdays(workdays: Set<DayOfWeek>): String {
        val normalized = workdays.ifEmpty { defaultWorkdays }
        val workdayNames = orderedDays.filter {
            it in normalized
        }.joinToString("、") {
            dayNameMap[it].orEmpty()
        }

        return when (normalized.size) {
            7 -> "每天"
            else -> workdayNames
        }
    }

    fun formatSchedule(schedule: WorkdaySchedule): String {
        return when (schedule.mode) {
            WorkdayMode.FIXED -> formatWorkdays(schedule.fixedWorkdays)
            WorkdayMode.ALTERNATING -> {
                val weekType = WorkdayScheduleResolver.resolveWeekType(schedule, LocalDate.now())
                when (weekType) {
                    WorkWeekType.BIG -> "大小周｜本周大周"
                    WorkWeekType.SMALL -> "大小周｜本周小周"
                    null -> "大小周｜需设置本周"
                }
            }
        }
    }

    fun getOrderedDays(): List<DayOfWeek> {
        return orderedDays
    }

    fun getDayLabel(dayOfWeek: DayOfWeek): String {
        return dayNameMap[dayOfWeek].orEmpty()
    }

    fun parseMode(raw: String?): WorkdayMode {
        return runCatching {
            WorkdayMode.valueOf(raw.orEmpty().trim().uppercase())
        }.getOrDefault(WorkdayMode.FIXED)
    }

    fun createAlternatingDefaults(current: WorkdaySchedule): WorkdaySchedule {
        if (current.mode == WorkdayMode.ALTERNATING) return current

        val smallWeekWorkdays = current.fixedWorkdays.ifEmpty { defaultSmallWeekWorkdays }
        val preferredBigWeekWorkdays = smallWeekWorkdays + DayOfWeek.SATURDAY
        val bigWeekWorkdays = if (smallWeekWorkdays.size == orderedDays.size) {
            smallWeekWorkdays
        } else if (preferredBigWeekWorkdays != smallWeekWorkdays) {
            preferredBigWeekWorkdays
        } else {
            smallWeekWorkdays + orderedDays.first { it !in smallWeekWorkdays }
        }
        return current.copy(
            mode = WorkdayMode.ALTERNATING,
            smallWeekWorkdays = smallWeekWorkdays,
            bigWeekWorkdays = bigWeekWorkdays
        )
    }

    fun createBigWeekAnchor(
        currentWeekType: WorkWeekType,
        today: LocalDate = LocalDate.now()
    ): LocalDate {
        val currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return if (currentWeekType == WorkWeekType.BIG) {
            currentMonday
        } else {
            currentMonday.minusWeeks(1)
        }
    }

    fun getLegacyExportWorkdays(schedule: WorkdaySchedule): Set<DayOfWeek> {
        return if (schedule.mode == WorkdayMode.ALTERNATING) {
            schedule.smallWeekWorkdays + schedule.bigWeekWorkdays
        } else {
            schedule.fixedWorkdays
        }
    }

    /**
     * 判断今天是否是用户设置的工作日计划中的休息日。
     */
    fun isRestDay(date: LocalDate): Boolean {
        return !WorkdayScheduleResolver.isWorkday(loadSchedule(), date)
    }

    /**
     * 保留旧方法名，兼容现有调用。
     */
    fun isWeekdayRestDay(date: LocalDate): Boolean {
        return isRestDay(date)
    }
}
