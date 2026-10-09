package com.pengxh.daily.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class WorkdayScheduleResolverTest {

    @Test
    fun isWorkday_fixedSchedule_expectedWorkdays() {
        val schedule = WorkdaySchedule(mode = WorkdayMode.FIXED)

        assertTrue(
            WorkdayScheduleResolver.isWorkday(
                schedule, LocalDate.of(2026, 10, 9)
            )
        )
        assertFalse(
            WorkdayScheduleResolver.isWorkday(
                schedule, LocalDate.of(2026, 10, 10)
            )
        )
    }

    @Test
    fun isWorkday_alternatingBigWeek_saturdayIsWorkday() {
        val schedule = alternatingSchedule(LocalDate.of(2025, 1, 6))

        assertTrue(
            WorkdayScheduleResolver.isWorkday(
                schedule, LocalDate.of(2025, 1, 11)
            )
        )
        assertFalse(
            WorkdayScheduleResolver.isWorkday(
                schedule, LocalDate.of(2025, 1, 12)
            )
        )
    }

    @Test
    fun isWorkday_alternatingSmallWeek_saturdayIsRestDay() {
        val schedule = alternatingSchedule(LocalDate.of(2025, 1, 6))

        assertFalse(
            WorkdayScheduleResolver.isWorkday(
                schedule, LocalDate.of(2025, 1, 18)
            )
        )
        assertTrue(
            WorkdayScheduleResolver.isWorkday(
                schedule, LocalDate.of(2025, 1, 20)
            )
        )
    }

    @Test
    fun resolveWeekType_crossYear_alternatesCorrectly() {
        val schedule = alternatingSchedule(LocalDate.of(2024, 12, 30))

        assertEquals(
            WorkWeekType.BIG,
            WorkdayScheduleResolver.resolveWeekType(
                schedule, LocalDate.of(2025, 1, 4)
            )
        )
        assertEquals(
            WorkWeekType.SMALL,
            WorkdayScheduleResolver.resolveWeekType(
                schedule, LocalDate.of(2025, 1, 11)
            )
        )
        assertEquals(
            WorkWeekType.BIG,
            WorkdayScheduleResolver.resolveWeekType(
                schedule, LocalDate.of(2025, 1, 18)
            )
        )
    }

    @Test
    fun isWorkday_alternatingWithoutAnchor_fallsBackToFixedWorkdays() {
        val schedule = WorkdaySchedule(
            mode = WorkdayMode.ALTERNATING,
            fixedWorkdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            smallWeekWorkdays = setOf(DayOfWeek.MONDAY),
            bigWeekWorkdays = setOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY)
        )

        assertTrue(
            WorkdayScheduleResolver.isWorkday(
                schedule, LocalDate.of(2026, 10, 9)
            )
        )
        assertFalse(
            WorkdayScheduleResolver.isWorkday(
                schedule, LocalDate.of(2026, 10, 10)
            )
        )
    }

    @Test
    fun resolveWeekType_fixedSchedule_returnsNull() {
        assertNull(
            WorkdayScheduleResolver.resolveWeekType(
                WorkdaySchedule(), LocalDate.of(2026, 10, 9)
            )
        )
    }

    private fun alternatingSchedule(anchorMonday: LocalDate): WorkdaySchedule {
        return WorkdaySchedule(
            mode = WorkdayMode.ALTERNATING,
            smallWeekWorkdays = WorkdaySchedule.DEFAULT_SMALL_WEEK_WORKDAYS,
            bigWeekWorkdays = WorkdaySchedule.DEFAULT_BIG_WEEK_WORKDAYS,
            bigWeekAnchorMonday = anchorMonday
        )
    }
}
