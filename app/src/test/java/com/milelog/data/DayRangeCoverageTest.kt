package com.milelog.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/**
 * An exhaustive sweep of the DayRange window, rather than the spot checks in
 * PeriodBoundaryTest.
 *
 * DayRange.fromMillis / toMillis (Period.kt:20-25) and Fmt.epochDayOf (Money.kt:28-30)
 * are two independent conversions between a calendar day and an instant. Every trip
 * total in the app depends on them agreeing: the trip is selected by the millis window
 * (Daos.kt:63) and then priced by the day epochDayOf gives back (Repo.kt:124). If they
 * ever disagree, a trip is counted in one day and priced at another day's rate.
 *
 * The zones below include every awkward shape java.time knows about: midnight DST gaps
 * (Santiago, Beirut), midnight DST overlaps (Havana), half-hour and 45-minute offsets
 * and a 30-minute DST step (Kathmandu, Chatham, Lord Howe), and a +14 offset
 * (Kiritimati). Crossville's own zone leads the list.
 */
class DayRangeCoverageTest {

    private val zones = listOf(
        "America/New_York",
        "UTC",
        "America/Santiago",     // clocks jump 00:00 -> 01:00; local midnight does not exist
        "America/Havana",       // clocks fall back through midnight; local midnight happens twice
        "Asia/Beirut",          // another midnight gap
        "Asia/Kathmandu",       // +05:45
        "Pacific/Chatham",      // +12:45, and DST on top
        "Australia/Lord_Howe",  // 30-minute DST step
        "Pacific/Kiritimati"    // +14:00
    )

    private var saved: TimeZone? = null

    @Before fun remember() { saved = TimeZone.getDefault() }
    @After fun restore() { saved?.let { TimeZone.setDefault(it) } }

    /**
     * For every single day of 2024 through 2028, in every zone: the window opens on the
     * first instant of that day and closes on the last one, with nothing either side.
     *
     * fromMillis - 1 must belong to the previous day and toMillis + 1 to the next, which
     * is what proves the window neither clips the final second nor spills over midnight.
     */
    @Test fun everyDayWindowIsExactlyThatDayInEveryZone() {
        var checked = 0
        for (id in zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            var d = LocalDate.of(2024, 1, 1)
            val end = LocalDate.of(2028, 12, 31)
            while (!d.isAfter(end)) {
                val day = d.toEpochDay()
                val r = DayRange.of(d, d)

                assertEquals("$id $d: window opens on the wrong day", day, Fmt.epochDayOf(r.fromMillis))
                assertEquals("$id $d: window closes on the wrong day", day, Fmt.epochDayOf(r.toMillis))
                assertEquals(
                    "$id $d: the millisecond before the window is not the previous day",
                    day - 1, Fmt.epochDayOf(r.fromMillis - 1)
                )
                assertEquals(
                    "$id $d: the millisecond after the window is not the next day",
                    day + 1, Fmt.epochDayOf(r.toMillis + 1)
                )
                checked++
                d = d.plusDays(1)
            }
        }
        assertEquals(1_827 * zones.size, checked)
    }

    /** Consecutive days tile with no gap and no overlap, everywhere. */
    @Test fun consecutiveDaysTileWithoutAGapOrAnOverlap() {
        for (id in zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            var d = LocalDate.of(2024, 1, 1)
            val end = LocalDate.of(2028, 12, 31)
            while (d.isBefore(end)) {
                val today = DayRange.of(d, d)
                val tomorrow = DayRange.of(d.plusDays(1), d.plusDays(1))
                assertEquals(
                    "$id $d: days do not butt up against each other",
                    today.toMillis + 1, tomorrow.fromMillis
                )
                d = d.plusDays(1)
            }
        }
    }

    /**
     * A multi-day range is exactly the union of its days, so a year window is the sum of
     * its 365 or 366 day windows with nothing left over. This is the property the whole
     * Taxes tab rests on.
     */
    @Test fun aYearWindowIsExactlyItsDaysAddedUp() {
        for (id in zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            for (year in 2024..2028) {
                val whole = DayRange.forYear(year)
                var summed = 0L
                var d = LocalDate.of(year, 1, 1)
                while (d.year == year) {
                    val r = DayRange.of(d, d)
                    summed += r.toMillis - r.fromMillis + 1
                    d = d.plusDays(1)
                }
                assertEquals(
                    "$id $year: the year window is not its days added up",
                    summed, whole.toMillis - whole.fromMillis + 1
                )
                assertEquals(
                    "$id $year: the year must open on 1 January",
                    LocalDate.of(year, 1, 1).toEpochDay(), Fmt.epochDayOf(whole.fromMillis)
                )
                assertEquals(
                    "$id $year: the year must close on 31 December",
                    LocalDate.of(year, 12, 31).toEpochDay(), Fmt.epochDayOf(whole.toMillis)
                )
            }
        }
    }

    /**
     * The 2026 rate split, checked as instants rather than day numbers: the last
     * millisecond that gets 72.5 cents and the first that gets 76 are adjacent, in every
     * zone, with no instant falling into both halves or neither.
     */
    @Test fun theJulyFirstSplitHasNoSeamInAnyZone() {
        for (id in zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            val h1 = DayRange.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))
            val h2 = DayRange.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31))
            val year = DayRange.forYear(2026)

            assertEquals("$id: gap or overlap at the July split", h1.toMillis + 1, h2.fromMillis)
            assertEquals("$id: the year must start where the first half does", year.fromMillis, h1.fromMillis)
            assertEquals("$id: the year must end where the second half does", year.toMillis, h2.toMillis)
            assertEquals(
                "$id: 30 June is the last day of the first half",
                LocalDate.of(2026, 6, 30).toEpochDay(), Fmt.epochDayOf(h1.toMillis)
            )
            assertEquals(
                "$id: 1 July is the first day of the second half",
                LocalDate.of(2026, 7, 1).toEpochDay(), Fmt.epochDayOf(h2.fromMillis)
            )
        }
    }
}
