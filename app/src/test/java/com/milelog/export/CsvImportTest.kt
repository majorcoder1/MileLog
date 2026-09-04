package com.milelog.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The parts that read someone else's file, where the format is not ours to control. */
class CsvImportTest {

    @Test
    fun readsQuotedFieldsCommasAndEmbeddedNewlines() {
        val text = "Date,Merchant,Notes\n" +
            "03/02/2026,\"Murphy USA, Crossville\",\"line one\nline two\"\n" +
            "03/03/2026,Pilot,\"he said \"\"fill it\"\"\"\n"
        val records = CsvImport.splitRecords(text)
        assertEquals(3, records.size)
        assertEquals(listOf("Date", "Merchant", "Notes"), records[0])
        assertEquals("Murphy USA, Crossville", records[1][1])
        assertEquals("line one\nline two", records[1][2])
        assertEquals("he said \"fill it\"", records[2][2])
    }

    @Test
    fun readsTheDateFormatsTheseExportsUse() {
        val expected = LocalDate.of(2026, 3, 2)
        listOf(
            "03/02/2026", "3/2/2026", "2026-03-02", "2026/03/02",
            "Mar 2, 2026", "March 2, 2026", "2 Mar 2026",
            "2026-03-02T16:31:00", "03/02/2026 4:31 PM", "3/2/2026 16:31"
        ).forEach { raw ->
            assertEquals("failed on $raw", expected, CsvImport.parseDate(raw))
        }
        assertNull(CsvImport.parseDate(""))
        assertNull(CsvImport.parseDate("not a date"))
    }

    @Test
    fun readsTimesIncludingOnesStuckToADate() {
        assertEquals(LocalTime.of(16, 31), CsvImport.parseTime("4:31 PM"))
        assertEquals(LocalTime.of(16, 31), CsvImport.parseTime("16:31"))
        assertEquals(LocalTime.of(16, 31), CsvImport.parseTime("03/02/2026 4:31 PM"))
        assertNull(CsvImport.parseTime(""))
    }

    @Test
    fun readsMoneyHoweverItIsWritten() {
        assertEquals(1234_56L, CsvImport.parseCents("$1,234.56"))
        assertEquals(-12_34L, CsvImport.parseCents("(12.34)"))
        assertEquals(-12_34L, CsvImport.parseCents("-12.34"))
        assertEquals(29_56L, CsvImport.parseCents("29.56"))
        assertEquals(1000L, CsvImport.parseCents("10"))
        assertNull(CsvImport.parseCents(""))
    }

    @Test
    fun readsMiles() {
        assertEquals(23.7, CsvImport.parseMiles("23.7 mi")!!, 0.001)
        assertEquals(1983.94, CsvImport.parseMiles("1983.94")!!, 0.001)
        assertNull(CsvImport.parseMiles(""))
    }

    @Test
    fun tellsATripsFileFromATransactionsFile() {
        assertEquals(
            CsvImport.Kind.TRIPS,
            CsvImport.detectKind(listOf("Date", "Start Location", "End Location", "Distance", "Purpose"))
        )
        assertEquals(
            CsvImport.Kind.TRIPS,
            CsvImport.detectKind(listOf("Trip Date", "Miles", "Category", "Notes"))
        )
        assertEquals(
            CsvImport.Kind.TRANSACTIONS,
            CsvImport.detectKind(listOf("Date", "Merchant", "Amount", "Category", "Type"))
        )
        assertEquals(
            CsvImport.Kind.UNKNOWN,
            CsvImport.detectKind(listOf("Something", "Else"))
        )
    }
}

/** The parsing failures the review found, kept honest. */
class CsvImportEdgeTest {

    @org.junit.Test
    fun anUnmatchedQuoteDoesNotSwallowTheRestOfTheFile() {
        val text = "Date,Merchant,Amount\n" +
            "03/02/2026,Lowes 5\" pipe,12.34\n" +
            "03/03/2026,Murphy USA,45.67\n" +
            "03/04/2026,Pilot,22.10\n"
        val records = CsvImport.splitRecords(text)
        org.junit.Assert.assertEquals(4, records.size)
        org.junit.Assert.assertEquals("Lowes 5\" pipe", records[1][1])
        org.junit.Assert.assertEquals("Pilot", records[3][1])
    }

    @org.junit.Test
    fun properlyQuotedFieldsStillWork() {
        val text = "A,B\n\"one, two\",\"he said \"\"hi\"\"\"\n"
        val records = CsvImport.splitRecords(text)
        org.junit.Assert.assertEquals("one, two", records[1][0])
        org.junit.Assert.assertEquals("he said \"hi\"", records[1][1])
    }
}

/**
 * Everlance does not put the column names on line one. The real export opens with a
 * title block and a row of separators, which the importer used to read as the header —
 * hence "Columns it found: ==========, ==========".
 */
class CsvImportPreambleTest {

    private val everlanceShaped =
        "Everlance Trip Report\n" +
            "major4jesus@gmail.com\n" +
            "2026-01-01 to 2026-09-02\n" +
            "==========,==========,==========,==========,==========\n" +
            "Date,Start Location,End Location,Distance,Purpose\n" +
            "03/02/2026,\"105 Elmore St, Monterey, TN\",\"1029 Old Elmore Rd, Crossville, TN\",13.7,Doordash\n" +
            "03/03/2026,\"1029 Old Elmore Rd, Crossville, TN\",\"105 Elmore St, Monterey, TN\",13.7,Doordash\n"

    @org.junit.Test
    fun theHeaderIsFoundBelowTheTitleBlock() {
        val records = CsvImport.splitRecords(everlanceShaped)
        val headerIndex = records.indexOfFirst { it.firstOrNull() == "Date" }
        org.junit.Assert.assertEquals(4, headerIndex)
        org.junit.Assert.assertEquals(
            CsvImport.Kind.TRIPS,
            CsvImport.detectKind(records[headerIndex])
        )
    }

    @org.junit.Test
    fun aRowOfSeparatorsIsNotMistakenForColumnNames() {
        val records = CsvImport.splitRecords(everlanceShaped)
        org.junit.Assert.assertEquals(
            "a row of ===== was read as the header",
            CsvImport.Kind.UNKNOWN,
            CsvImport.detectKind(records[3])
        )
    }

    @org.junit.Test
    fun semicolonAndTabFilesAreRecognised() {
        org.junit.Assert.assertEquals(',', CsvImport.sniffDelimiter("Date,Miles\n03/02/2026,13.7\n"))
        org.junit.Assert.assertEquals(';', CsvImport.sniffDelimiter("Date;Miles\n03/02/2026;13.7\n"))
        org.junit.Assert.assertEquals('\t', CsvImport.sniffDelimiter("Date\tMiles\n03/02/2026\t13.7\n"))
    }

    @org.junit.Test
    fun aSemicolonFileSplitsIntoTheRightColumns() {
        val text = "Date;Distance;Purpose\n03/02/2026;13.7;Doordash\n"
        val records = CsvImport.splitRecords(text, CsvImport.sniffDelimiter(text))
        org.junit.Assert.assertEquals(listOf("Date", "Distance", "Purpose"), records[0])
        org.junit.Assert.assertEquals("13.7", records[1][1])
    }
}
