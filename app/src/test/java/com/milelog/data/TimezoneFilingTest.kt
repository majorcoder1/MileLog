package com.milelog.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Trips are stored as epoch millis (Entities.kt:57) and turned back into a calendar day
 * with Fmt.epochDayOf (Money.kt:28-30), which reads ZoneId.systemDefault() at the moment
 * you look. Transactions are stored as an epochDay (Entities.kt:88) that is frozen the
 * day they are entered. The two do not behave the same way, and this is where it shows.
 */
class TimezoneFilingTest {

    private var saved: TimeZone? = null

    @Before fun remember() { saved = TimeZone.getDefault() }
    @After fun restore() { saved?.let { TimeZone.setDefault(it) } }

    private fun inZone(id: String, block: () -> Unit) {
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        block()
    }

    private fun millisAt(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    // ---- a trip's tax year is not stored, it is recomputed ----------------------------

    /**
     * FINDING: a late-evening trip on 31 December changes tax year when the phone's
     * zone changes. 11:30 PM on 2026-12-31 in Crossville is 04:30 UTC on 2027-01-01,
     * so a phone set to UTC — a factory reset before the zone is picked, a trip abroad,
     * a SIM-less tablet — files the same trip in the following year.
     */
    @Test fun aNewYearsEveTripMovesYearWhenTheZoneMoves() {
        val trip = millisAt("America/New_York", 2026, 12, 31, 23, 30)

        inZone("America/New_York") {
            assertEquals(LocalDate.of(2026, 12, 31).toEpochDay(), Fmt.epochDayOf(trip))
            val y2026 = DayRange.forYear(2026)
            assertTrue("filed in 2026 at home", trip in y2026.fromMillis..y2026.toMillis)
        }

        inZone("UTC") {
            assertEquals(
                "the very same row now reads as 1 January 2027",
                LocalDate.of(2027, 1, 1).toEpochDay(), Fmt.epochDayOf(trip)
            )
            val y2026 = DayRange.forYear(2026)
            assertFalse("no longer in the 2026 range", trip in y2026.fromMillis..y2026.toMillis)
            val y2027 = DayRange.forYear(2027)
            assertTrue("it has jumped into 2027", trip in y2027.fromMillis..y2027.toMillis)
        }
    }

    /** Westward movement pushes an early-morning trip back into the previous year. */
    @Test fun aNewYearsDayTripMovesBackWhenTheZoneMovesWest() {
        val trip = millisAt("America/New_York", 2027, 1, 1, 0, 45)
        inZone("America/New_York") {
            assertEquals(LocalDate.of(2027, 1, 1).toEpochDay(), Fmt.epochDayOf(trip))
        }
        inZone("America/Los_Angeles") {
            assertEquals(
                "now filed on 31 December 2026",
                LocalDate.of(2026, 12, 31).toEpochDay(), Fmt.epochDayOf(trip)
            )
        }
    }

    /**
     * A transaction does not move, because its day was decided once at entry
     * (Vm.kt:486, LocalDate.now().toEpochDay()). So a fuel receipt and the drive it paid
     * for, entered minutes apart, can end up in different tax years.
     */
    @Test fun theReceiptStaysPutWhileTheTripMoves() {
        val trip = millisAt("America/New_York", 2026, 12, 31, 23, 30)
        val receiptDay = LocalDate.of(2026, 12, 31).toEpochDay()   // stored, not recomputed

        inZone("UTC") {
            assertEquals("the receipt is still in 2026", 2026, LocalDate.ofEpochDay(receiptDay).year)
            assertEquals(
                "the drive it paid for is now in 2027",
                2027, LocalDate.ofEpochDay(Fmt.epochDayOf(trip)).year
            )
        }
    }

    // ---- the rate boundary, at midnight ------------------------------------------------

    /**
     * FINDING: only startEpoch decides which rate a trip gets (Repo.kt:104). A drive
     * that starts before midnight on 30 June 2026 and ends after it is priced entirely
     * at the January-June rate.
     */
    @Test fun aTripAcrossTheJulyFirstBoundaryIsPricedEntirelyAtTheOldRate() {
        inZone("America/New_York") {
            val start = millisAt("America/New_York", 2026, 6, 30, 23, 40)
            val end = millisAt("America/New_York", 2026, 7, 1, 1, 10)
            assertEquals(LocalDate.of(2026, 6, 30).toEpochDay(), Fmt.epochDayOf(start))
            assertEquals(LocalDate.of(2026, 7, 1).toEpochDay(), Fmt.epochDayOf(end))

            // 60 miles, almost all of them after the rate went up.
            val asFiled = 60.0 * 72.5 / 100.0
            val ifSplitByClock = 10.0 * 72.5 / 100.0 + 50.0 * 76.0 / 100.0
            assertEquals(43.50, asFiled, 0.005)
            assertEquals(45.25, ifSplitByClock, 0.005)
            assertNotEquals(asFiled, ifSplitByClock, 0.005)
        }
    }

    /** The same trip on 31 December files all of its miles in the earlier year. */
    @Test fun anOvernightShiftFilesAllOfItsMilesOnTheStartDay() {
        inZone("America/New_York") {
            val start = millisAt("America/New_York", 2026, 12, 31, 21, 0)
            val end = millisAt("America/New_York", 2027, 1, 1, 3, 0)
            val y2026 = DayRange.forYear(2026)
            assertTrue("the trip is filed in 2026", start in y2026.fromMillis..y2026.toMillis)
            assertFalse("even though it ended in 2027", end in y2026.fromMillis..y2026.toMillis)
        }
    }

    // ---- daylight saving -----------------------------------------------------------------

    /** A trip through the spring-forward hour still lands on the right day. */
    @Test fun theSpringForwardHourDoesNotMisfileATrip() {
        inZone("America/New_York") {
            val before = millisAt("America/New_York", 2026, 3, 8, 1, 30)
            val after = millisAt("America/New_York", 2026, 3, 8, 3, 30)
            val day = LocalDate.of(2026, 3, 8).toEpochDay()
            assertEquals(day, Fmt.epochDayOf(before))
            assertEquals(day, Fmt.epochDayOf(after))

            val r = DayRange.forPeriod(Period.TODAY, today = LocalDate.of(2026, 3, 8))
            assertTrue(before in r.fromMillis..r.toMillis)
            assertTrue(after in r.fromMillis..r.toMillis)

            // Fmt.duration must report the hour that was skipped, not two hours.
            assertEquals("1h 0m", Fmt.duration(after - before))
        }
    }

    /** Both passes of the repeated 1:30 AM on the fall-back day stay on that day. */
    @Test fun bothPassesOfTheRepeatedHourStayOnTheSameDay() {
        inZone("America/New_York") {
            val day = LocalDate.of(2026, 11, 1)
            val firstPass = LocalDateTime.of(2026, 11, 1, 1, 30)
                .atZone(ZoneId.of("America/New_York")).withEarlierOffsetAtOverlap()
                .toInstant().toEpochMilli()
            val secondPass = LocalDateTime.of(2026, 11, 1, 1, 30)
                .atZone(ZoneId.of("America/New_York")).withLaterOffsetAtOverlap()
                .toInstant().toEpochMilli()

            assertEquals(3_600_000L, secondPass - firstPass)
            assertEquals(day.toEpochDay(), Fmt.epochDayOf(firstPass))
            assertEquals(day.toEpochDay(), Fmt.epochDayOf(secondPass))
            val r = DayRange.forPeriod(Period.TODAY, today = day)
            assertTrue(firstPass in r.fromMillis..r.toMillis)
            assertTrue(secondPass in r.fromMillis..r.toMillis)
        }
    }

    /**
     * A whole year's window measured in real elapsed time is one hour short of
     * 365 days, because the year holds one 23 hour day and one 25 hour day. Pinned so
     * a future "just add 365 * 86400000" shortcut fails here first.
     */
    @Test fun aYearWindowIsNot365TimesTwentyFourHours() {
        inZone("America/New_York") {
            val r = DayRange.forYear(2026)
            val span = r.toMillis - r.fromMillis + 1
            assertEquals(365 * 86_400_000L, span)
            // Eastern gains an hour in November and loses one in March, so they cancel.
            assertTrue(span > 0)
        }
        inZone("UTC") {
            val r = DayRange.forYear(2026)
            assertEquals(365 * 86_400_000L, r.toMillis - r.fromMillis + 1)
        }
    }
}
