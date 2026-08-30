package com.milelog.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import path reads a file the app did not write. Anything it silently drops or
 * silently mis-reads becomes a hole in a year of records that nobody notices until
 * tax time, so these tests aim at the rows that go missing rather than at the happy path.
 */
class CsvImportRobustnessTest {

    // ---- record splitting ---------------------------------------------------------

    @Test
    fun anUnmatchedQuoteDoesNotSwallowTheRestOfTheFile() {
        // A note or a merchant name holding a single " is ordinary: 5" hose, he said "no.
        val text = "Date,Merchant,Amount\n" +
            "03/02/2026,Lowes 5\" pipe,12.34\n" +
            "03/03/2026,Murphy USA,45.67\n" +
            "03/04/2026,Pilot,22.10\n"
        val records = CsvImport.splitRecords(text)
        assertEquals(
            "one stray quote merged the remaining rows into a single record: $records",
            4, records.size
        )
        assertEquals("03/04/2026", records[3][0])
    }

    @Test
    fun aQuoteAtTheEndOfAFieldDoesNotEatTheNextRow() {
        val text = "Date,Notes\n" +
            "03/02/2026,ends with a quote\"\n" +
            "03/03/2026,next row\n"
        val records = CsvImport.splitRecords(text)
        assertEquals("the row after the stray quote was lost: $records", 3, records.size)
    }

    @Test
    fun quotedFieldsStillWorkTheWayTheyShould() {
        val text = "Date,Merchant,Notes\n" +
            "03/02/2026,\"Murphy USA, Crossville\",\"line one\nline two\"\n"
        val records = CsvImport.splitRecords(text)
        assertEquals(2, records.size)
        assertEquals("Murphy USA, Crossville", records[1][1])
        assertEquals("line one\nline two", records[1][2])
    }

    // ---- money ---------------------------------------------------------------------

    @Test
    fun amountsWithSurroundingTextAreNotInvented() {
        // "Order 12 - $3.50" must not become $12,350.00. Better to skip the row than to
        // file a made-up number with the IRS.
        val cents = CsvImport.parseCents("Order 12 - $3.50")
        assertTrue(
            "read \"Order 12 - \$3.50\" as ${cents?.div(100.0)} dollars",
            cents == null || cents == 350L
        )
    }

    @Test
    fun aCommaDecimalIsNotReadAsAThousandsSeparator() {
        // Some exports are written on a comma-decimal locale: 1.234,56 means $1234.56.
        val cents = CsvImport.parseCents("1.234,56")
        assertTrue(
            "read \"1.234,56\" as ${cents?.div(100.0)} dollars",
            cents == null || cents == 123456L
        )
    }

    @Test
    fun twoDecimalPointsAreRejectedRatherThanGuessed() {
        assertNull("\"1.2.3\" is not an amount", CsvImport.parseCents("1.2.3"))
    }

    @Test
    fun aTrailingMinusIsStillNegative() {
        assertEquals(-1234L, CsvImport.parseCents("12.34-"))
    }

    @Test
    fun theAmountsWeAlreadyHandleKeepWorking() {
        assertEquals(123456L, CsvImport.parseCents("$1,234.56"))
        assertEquals(-1234L, CsvImport.parseCents("(12.34)"))
        assertEquals(-1234L, CsvImport.parseCents("-12.34"))
        assertEquals(2956L, CsvImport.parseCents("29.56"))
    }

    // ---- miles ----------------------------------------------------------------------

    @Test
    fun milesWithSurroundingTextAreNotInvented() {
        val miles = CsvImport.parseMiles("23.7 mi")
        assertEquals(23.7, miles!!, 0.001)
    }

    @Test
    fun scientificNotationIsNotSilentlyRenumbered() {
        // "1e5" loses its exponent and comes back as 15 miles.
        val miles = CsvImport.parseMiles("1e5")
        assertTrue("read \"1e5\" as $miles miles", miles == null || miles == 100_000.0)
    }

    // ---- what kind of file is this --------------------------------------------------

    @Test
    fun aTransactionsFileThatAlsoCarriesMileageIsNotTreatedAsTrips() {
        // Everlance transaction reports can carry a mileage column. Reading this as a
        // trips file throws away every amount in it.
        val headers = listOf("Date", "Merchant", "Amount", "Category", "Type", "Miles")
        assertEquals(
            "a file with merchants and amounts was read as trips, dropping the money",
            CsvImport.Kind.TRANSACTIONS,
            CsvImport.detectKind(headers)
        )
    }

    @Test
    fun theKindsWeAlreadyDetectKeepWorking() {
        assertEquals(
            CsvImport.Kind.TRIPS,
            CsvImport.detectKind(listOf("Date", "Start Location", "End Location", "Distance", "Purpose"))
        )
        assertEquals(
            CsvImport.Kind.TRANSACTIONS,
            CsvImport.detectKind(listOf("Date", "Merchant", "Amount", "Category", "Type"))
        )
        assertEquals(CsvImport.Kind.UNKNOWN, CsvImport.detectKind(listOf("Something", "Else")))
    }

    // ---- times, and why duplicate detection collapses --------------------------------

    @Test
    fun aTripsFileWithNoTimeColumnGivesEveryRowTheSameStamp() {
        // startMillis falls back to noon when neither a start-time column nor a time on
        // the date parses. Every trip on a day then shares one timestamp, and the
        // duplicate check spans start +/- 12 hours, i.e. the whole day.
        assertNull(CsvImport.parseTime("03/02/2026"))
        assertNull(CsvImport.parseTime("2026-03-02"))
    }

    @Test
    fun timesWeAlreadyReadKeepWorking() {
        assertEquals(java.time.LocalTime.of(16, 31), CsvImport.parseTime("4:31 PM"))
        assertEquals(java.time.LocalTime.of(16, 31), CsvImport.parseTime("03/02/2026 4:31 PM"))
    }

    /**
     * A real day of delivery driving, in the shape Everlance exports it: no time column,
     * several short runs, some of them the same length. commit() drops a row when the
     * database already holds a trip within +/-12 hours whose mileage is within 0.05, and
     * every row here lands on noon of its own day, so the window is the whole day. This
     * counts how many of the file's own rows collide with an earlier row in the same file.
     */
    @Test
    fun everyRowOfADeliveryDaySurvivesTheDuplicateCheck() {
        val text = "Date,Distance,Purpose\n" +
            "03/02/2026,3.2,DoorDash\n" +
            "03/02/2026,7.8,DoorDash\n" +
            "03/02/2026,3.2,DoorDash\n" +
            "03/02/2026,12.4,DoorDash\n" +
            "03/02/2026,3.20,DoorDash\n"
        val records = CsvImport.splitRecords(text)
        val headers = records.first().map { it.lowercase() }
        val rows = records.drop(1).map { cells ->
            CsvImport.Row(headers.indices.associate { i -> headers[i] to cells[i] })
        }
        assertEquals(5, rows.size)

        // The importer gives same-day rows distinct times in file order, so two genuine
        // 3.2 mile runs on one day no longer look like the same trip recorded twice.
        val stamps = rows.mapIndexed { i, row ->
            CsvImport.startMillis(java.time.LocalDate.of(2026, 3, 2), row, i)
        }
        assertEquals("same-day rows collapsed onto one timestamp", 5, stamps.toSet().size)

        // And each is far enough apart that a one-minute duplicate window cannot merge them.
        stamps.sorted().zipWithNext().forEach { (a, b) ->
            assertTrue("rows only ${b - a}ms apart", b - a > 60_000L)
        }
    }
}
