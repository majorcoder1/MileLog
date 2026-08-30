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
