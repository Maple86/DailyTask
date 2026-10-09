package com.pengxh.daily.app.utils

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class WorkdayMode {
    FIXED,
    ALTERNATING
}

enum class WorkWeekType {
    BIG,
    SMALL
}

data class WorkdaySchedule(
    val mode: WorkdayMode = WorkdayMode.FIXED,
    val fixedWorkdays: Set<DayOfWeek> = DEFAULT_FIXED_WORKDAYS,
    val smallWeekWorkdays: Set<DayOfWeek> = DEFAULT_SMALL_WEEK_WORKDAYS,
    val bigWeekWorkdays: Set<DayOfWeek> = DEFAULT_BIG_WEEK_WORKDAYS,
    val bigWeekAnchorMonday: LocalDate? = null
) {
    companion object {
        val DEFAULT_FIXED_WORKDAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
        )

        val DEFAULT_SMALL_WEEK_WORKDAYS: Set<DayOfWeek> = DEFAULT_FIXED_WORKDAYS

        val DEFAULT_BIG_WEEK_WORKDAYS: Set<DayOfWeek> = DEFAULT_FIXED_WORKDAYS + DayOfWeek.SATURDAY
    }
}

object WorkdayScheduleResolver {

    fun isWorkday(schedule: WorkdaySchedule, date: LocalDate): Boolean {
        return when (schedule.mode) {
            WorkdayMode.FIXED -> date.dayOfWeek in schedule.fixedWorkdays
            WorkdayMode.ALTERNATING -> {
                val weekType = resolveWeekType(schedule, date)
                    ?: return date.dayOfWeek in schedule.fixedWorkdays
                val workdays = when (weekType) {
                    WorkWeekType.BIG -> schedule.bigWeekWorkdays
                    WorkWeekType.SMALL -> schedule.smallWeekWorkdays
                }
                date.dayOfWeek in workdays
            }
        }
    }

    /**
     * 返回日期所在周的类型。大周锚点所在周为大周，其后按周交替。
     */
    fun resolveWeekType(schedule: WorkdaySchedule, date: LocalDate): WorkWeekType? {
        if (schedule.mode != WorkdayMode.ALTERNATING) return null
        val anchor = schedule.bigWeekAnchorMonday ?: return null

        val currentMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val anchorMonday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeksFromAnchor = ChronoUnit.WEEKS.between(anchorMonday, currentMonday)
        return if (Math.floorMod(weeksFromAnchor, 2L) == 0L) {
            WorkWeekType.BIG
        } else {
            WorkWeekType.SMALL
        }
    }
}
