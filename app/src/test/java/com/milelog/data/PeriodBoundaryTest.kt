package com.milelog.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Boundary audit for DayRange / Period.forPeriod.
 *
 * Every one of these runs in America/New_York, the zone the app is actually used in,
 * because DayRange.fromMillis / toMillis are built from ZoneId.systemDefault().
 */
class PeriodBoundaryTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private var saved: TimeZone? = null

    @Before fun pinZone() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After fun restoreZone() {
        saved?.let { TimeZone.setDefault(it) }
    }

    private fun millisOf(d: LocalDate, h: Int, m: Int, s: Int, ms: Int = 0): Long =
        LocalDateTime.of(d.year, d.month, d.dayOfMonth, h, m, s, ms * 1_000_000)
            .atZone(zone).toInstant().toEpochMilli()

    // ---- toMillis / fromMillis window ------------------------------------------------

    @Test fun windowCoversTheWholeFirstAndLastDay() {
        val r = DayRange.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

        assertEquals("start of Jan 1 must be inside", millisOf(LocalDate.of(2026, 1, 1), 0, 0, 0), r.fromMillis)
        assertTrue("midnight Jan 1 must be inside", millisOf(LocalDate.of(2026, 1, 1), 0, 0, 0) >= r.fromMillis)
        assertTrue(
            "23:59:00 on Dec 31 must be inside",
            millisOf(LocalDate.of(2026, 12, 31), 23, 59, 0) <= r.toMillis
        )
        assertTrue(
            "23:59:59 on Dec 31 must be inside",
            millisOf(LocalDate.of(2026, 12, 31), 23, 59, 59) <= r.toMillis
        )
        assertTrue(
            "23:59:59.999 on Dec 31 must be inside",
            millisOf(LocalDate.of(2026, 12, 31), 23, 59, 59, 999) <= r.toMillis
        )
        assertFalse(
            "midnight Jan 1 of the next year must be outside",
            millisOf(LocalDate.of(2027, 1, 1), 0, 0, 0) <= r.toMillis
        )
        assertFalse(
            "23:59:59.999 of Dec 30 must not be the end",
            millisOf(LocalDate.of(2026, 12, 30), 23, 59, 59, 999) == r.toMillis
        )
    }

    @Test fun oneDayWindowIsExactly24HoursOnAnOrdinaryDay() {
        val r = DayRange.forPeriod(Period.TODAY, today = LocalDate.of(2026, 3, 2))
        assertEquals(86_400_000L - 1, r.toMillis - r.fromMillis)
    }

    /** Spring forward: the local day is 23 hours long, so the window must be too. */
    @Test fun springForwardDayIs23Hours() {
        val dst = LocalDate.of(2026, 3, 8) // 2:00 AM EST -> 3:00 AM EDT
        val r = DayRange.forPeriod(Period.TODAY, today = dst)
        assertEquals(
            "spring-forward day must be 23h, not 24h",
            23 * 3_600_000L - 1, r.toMillis - r.fromMillis
        )
    }

    /** Fall back: the local day is 25 hours long. */
    @Test fun fallBackDayIs25Hours() {
        val dst = LocalDate.of(2026, 11, 1)
        val r = DayRange.forPeriod(Period.TODAY, today = dst)
        assertEquals(
            "fall-back day must be 25h, not 24h",
            25 * 3_600_000L - 1, r.toMillis - r.fromMillis
        )
    }

    /** A month containing a DST switch must not lose or gain a whole day. */
    @Test fun marchWindowSpansThirtyOneLocalDays() {
        val r = DayRange.forPeriod(Period.THIS_MONTH, today = LocalDate.of(2026, 3, 15))
        assertEquals(LocalDate.of(2026, 3, 1).toEpochDay(), r.fromDay)
        assertEquals(LocalDate.of(2026, 3, 31).toEpochDay(), r.toDay)
        assertEquals(31 * 86_400_000L - 3_600_000L - 1, r.toMillis - r.fromMillis)
    }

    // ---- the eight periods -----------------------------------------------------------

    @Test fun today() {
        val d = LocalDate.of(2026, 6, 30)
        val r = DayRange.forPeriod(Period.TODAY, today = d)
        assertEquals(d.toEpochDay(), r.fromDay)
        assertEquals(d.toEpochDay(), r.toDay)
    }

    @Test fun thisWeekRunsSundayThroughSaturday() {
        // 2026-03-04 is a Wednesday.
        val r = DayRange.forPeriod(Period.THIS_WEEK, today = LocalDate.of(2026, 3, 4))
        assertEquals(LocalDate.of(2026, 3, 1).toEpochDay(), r.fromDay)  // Sunday
        assertEquals(LocalDate.of(2026, 3, 7).toEpochDay(), r.toDay)    // Saturday
        assertEquals(6, r.toDay - r.fromDay)
    }

    @Test fun thisWeekOnASundayStartsThatSameSunday() {
        val sunday = LocalDate.of(2026, 3, 1)
        val r = DayRange.forPeriod(Period.THIS_WEEK, today = sunday)
        assertEquals(sunday.toEpochDay(), r.fromDay)
        assertEquals(LocalDate.of(2026, 3, 7).toEpochDay(), r.toDay)
    }

    @Test fun thisWeekOnASaturdayEndsThatSameSaturday() {
        val saturday = LocalDate.of(2026, 3, 7)
        val r = DayRange.forPeriod(Period.THIS_WEEK, today = saturday)
        assertEquals(LocalDate.of(2026, 3, 1).toEpochDay(), r.fromDay)
        assertEquals(saturday.toEpochDay(), r.toDay)
    }

    @Test fun lastWeekIsTheSevenDaysBeforeThisWeek() {
        val r = DayRange.forPeriod(Period.LAST_WEEK, today = LocalDate.of(2026, 3, 4))
        assertEquals(LocalDate.of(2026, 2, 22).toEpochDay(), r.fromDay)
        assertEquals(LocalDate.of(2026, 2, 28).toEpochDay(), r.toDay)
        val thisWeek = DayRange.forPeriod(Period.THIS_WEEK, today = LocalDate.of(2026, 3, 4))
        assertEquals("last week must butt up against this week", thisWeek.fromDay, r.toDay + 1)
    }

    @Test fun monthLengths() {
        fun span(y: Int, m: Int, d: Int): Long {
            val r = DayRange.forPeriod(Period.THIS_MONTH, today = LocalDate.of(y, m, d))
            return r.toDay - r.fromDay + 1
        }
        assertEquals(31, span(2026, 1, 15))
        assertEquals(28, span(2026, 2, 15))
        assertEquals(29, span(2024, 2, 15))   // leap
        assertEquals(29, span(2028, 2, 29))   // leap, on the leap day itself
        assertEquals(30, span(2026, 4, 15))
        assertEquals(31, span(2026, 12, 31))
    }

    @Test fun lastMonthDoesNotSlideWhenTodayIsThe31st() {
        // The classic minusMonths trap: Mar 31 -> Feb 28/29.
        val r2026 = DayRange.forPeriod(Period.LAST_MONTH, today = LocalDate.of(2026, 3, 31))
        assertEquals(LocalDate.of(2026, 2, 1).toEpochDay(), r2026.fromDay)
        assertEquals(LocalDate.of(2026, 2, 28).toEpochDay(), r2026.toDay)

        val r2024 = DayRange.forPeriod(Period.LAST_MONTH, today = LocalDate.of(2024, 3, 31))
        assertEquals(LocalDate.of(2024, 2, 1).toEpochDay(), r2024.fromDay)
        assertEquals(LocalDate.of(2024, 2, 29).toEpochDay(), r2024.toDay)

        val may = DayRange.forPeriod(Period.LAST_MONTH, today = LocalDate.of(2026, 5, 31))
        assertEquals(LocalDate.of(2026, 4, 1).toEpochDay(), may.fromDay)
        assertEquals(LocalDate.of(2026, 4, 30).toEpochDay(), may.toDay)
    }

    @Test fun lastMonthInJanuaryIsDecemberOfThePreviousYear() {
        val r = DayRange.forPeriod(Period.LAST_MONTH, today = LocalDate.of(2026, 1, 5))
        assertEquals(LocalDate.of(2025, 12, 1).toEpochDay(), r.fromDay)
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), r.toDay)
    }

    @Test fun yearsCoverEveryDayIncludingLeapDay() {
        val y2026 = DayRange.forPeriod(Period.THIS_YEAR, today = LocalDate.of(2026, 7, 4))
        assertEquals(365, y2026.toDay - y2026.fromDay + 1)
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), y2026.fromDay)
        assertEquals(LocalDate.of(2026, 12, 31).toEpochDay(), y2026.toDay)

        val y2024 = DayRange.forYear(2024)
        assertEquals(366, y2024.toDay - y2024.fromDay + 1)
        assertTrue(
            "Feb 29 2024 must be inside the 2024 year range",
            LocalDate.of(2024, 2, 29).toEpochDay() in y2024.fromDay..y2024.toDay
        )

        val last = DayRange.forPeriod(Period.LAST_YEAR, today = LocalDate.of(2026, 7, 4))
        assertEquals(LocalDate.of(2025, 1, 1).toEpochDay(), last.fromDay)
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), last.toDay)
        assertEquals("last year must butt up against this year", y2026.fromDay, last.toDay + 1)
    }

    @Test fun customUsesTheDatesGivenAndFallsBackToToday() {
        val r = DayRange.forPeriod(
            Period.CUSTOM,
            today = LocalDate.of(2026, 7, 4),
            customFrom = LocalDate.of(2026, 1, 1),
            customTo = LocalDate.of(2026, 6, 30)
        )
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), r.fromDay)
        assertEquals(LocalDate.of(2026, 6, 30).toEpochDay(), r.toDay)

        val fallback = DayRange.forPeriod(Period.CUSTOM, today = LocalDate.of(2026, 7, 4))
        assertEquals(LocalDate.of(2026, 7, 4).toEpochDay(), fallback.fromDay)
        assertEquals(LocalDate.of(2026, 7, 4).toEpochDay(), fallback.toDay)
    }

    /**
     * FINDING: a backwards custom range is accepted and silently reports zero of
     * everything, because every query is "BETWEEN :from AND :to".
     */
    @Test fun backwardsCustomRangeIsSilentlyEmpty() {
        val r = DayRange.forPeriod(
            Period.CUSTOM,
            today = LocalDate.of(2026, 7, 4),
            customFrom = LocalDate.of(2026, 12, 31),
            customTo = LocalDate.of(2026, 1, 1)
        )
        assertTrue("from is after to, so BETWEEN matches nothing", r.fromDay > r.toDay)
        assertTrue(r.fromMillis > r.toMillis)
    }

    /**
     * The two halves of 2026 must tile the year with no gap and no overlap, so a
     * Jan-Jun range plus a Jul-Dec range equals the whole year.
     */
    @Test fun theTwoHalvesOf2026TileTheYear() {
        val h1 = DayRange.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))
        val h2 = DayRange.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31))
        val full = DayRange.forYear(2026)

        assertEquals("no gap between the halves", h1.toDay + 1, h2.fromDay)
        assertEquals(full.fromDay, h1.fromDay)
        assertEquals(full.toDay, h2.toDay)
        assertEquals(
            "the halves must add up to the year",
            full.toDay - full.fromDay + 1,
            (h1.toDay - h1.fromDay + 1) + (h2.toDay - h2.fromDay + 1)
        )
        assertEquals("no millisecond gap either", h1.toMillis + 1, h2.fromMillis)
    }
}
