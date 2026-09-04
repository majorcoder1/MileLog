package com.milelog.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of a real Everlance export, reproduced from one.
 *
 * The file does not begin with its column names. It opens with a banner, a tips
 * section, an "Export For" block and a summary table, and only then the header — line 35
 * in a trips export, 36 in a transactions one. Reading line one as the header gave ten
 * columns all called "==========", and every import was rejected as unreadable.
 */
class EverlanceExportTest {

    private val banner = buildString {
        appendLine("==========,==========,==========,==========,==========")
        appendLine("Made with Everlance.")
        appendLine("Free Automatic GPS mileage log and expense tracker.")
        appendLine("Visit us at:,http://everlance.com")
        appendLine("\"\"")
        appendLine("==========,==========,==========,==========,==========")
        appendLine("\"\"")
        appendLine("Tips:")
        appendLine("1),\"To filter through the table, click on any cell.\"")
        appendLine("\"\"")
        appendLine("Export For")
        appendLine("Email,driver@example.com")
        appendLine("Name,Driver")
        appendLine("\"\"")
        appendLine("Date Range,\"January  1, 2026 - September  2, 2026\"")
        appendLine("Export Type, Trips")
        appendLine("\"\"")
        appendLine("Summary")
        appendLine("\"\",Miles,Value")
        appendLine("Work,2991.6,\$2273.61")
        appendLine("TOTAL,3083.0,\$2273.61")
        appendLine("\"\"")
    }

    private val trips = banner +
        "\"Value\",\"Miles\",\"From\",\"To\",\"Date\",\"Purpose\",\"Tags\",\"Business Line\"," +
        "\"Map Image URL\",\"Track Method\",\"Notes\",\"Vehicle\",\"Time Started\",\"Time Ended\"\n" +
        "\$19.76,26.0,\"6445 SR-68, Crossville, TN\",\"1029 Old Elmore Rd, Crossville, TN\"," +
        "2026-09-02,\"Work\",\"\",Doordash,\"http://x\",\"auto\",\"\",\"scion xb\",11:31 AM,12:07 PM\n" +
        "\$8.46,11.1,\"855 Old Elmore Rd, Crossville, TN\",\"2542 N Main St, Crossville, TN\"," +
        "2026-09-02,\"Personal\",\"\",\"\",\"http://x\",\"auto\",\"\",\"scion xb\",1:02 PM,1:20 PM\n"

    private val transactions = banner +
        "Amount,Date,Merchant,Category,Purpose,Tags,Business Line,Notes,Bank Description\n" +
        "-\$7.68,\"2026-08-28\",\"Speedway\",Snacks & Drinks for Clients (50%),Work,\"\",Doordash,\"\",\"\"\n" +
        "-\$10.01,\"2026-08-27\",\"Murphy USA\",Gasoline,Work,\"scion xb\",Walmart Sparks,\"Miles 329498\",\"\"\n"

    private fun rowsOf(text: String): Pair<List<String>, List<CsvImport.Row>> {
        val records = CsvImport.splitRecords(text, CsvImport.sniffDelimiter(text))
        val at = CsvImport.headerRowIndex(records)
        val header = records[at]
        val keys = header.map { h -> h.lowercase().filter { it.isLetterOrDigit() } }
        val rows = records.drop(at + 1).map { cells ->
            CsvImport.Row(buildMap {
                keys.forEachIndexed { i, k ->
                    if (k.isNotEmpty() && !containsKey(k)) put(k, cells.getOrNull(i)?.trim().orEmpty())
                }
            })
        }
        return header to rows
    }

    @Test
    fun theHeaderIsFoundUnderTheBannerAndTheSummary() {
        val (tripHeader, tripRows) = rowsOf(trips)
        assertEquals(CsvImport.Kind.TRIPS, CsvImport.detectKind(tripHeader))
        assertEquals(2, tripRows.size)

        val (txnHeader, txnRows) = rowsOf(transactions)
        assertEquals(CsvImport.Kind.TRANSACTIONS, CsvImport.detectKind(txnHeader))
        assertEquals(2, txnRows.size)
    }

    @Test
    fun aTripReadsItsMilesDateTimesAndAddresses() {
        val row = rowsOf(trips).second.first()
        assertEquals(26.0, CsvImport.parseMiles(row.first("miles", "distance"))!!, 0.001)
        assertEquals(java.time.LocalDate.of(2026, 9, 2), CsvImport.parseDate(row.first("date")))
        assertEquals(java.time.LocalTime.of(11, 31), CsvImport.parseTime(row.first("timestarted")))
        assertEquals(java.time.LocalTime.of(12, 7), CsvImport.parseTime(row.first("timeended")))
        assertEquals("6445 SR-68, Crossville, TN", row.first("from"))
    }

    @Test
    fun businessLineBecomesThePurpose() {
        // Everlance splits it: "Purpose" is Work or Personal, "Business Line" is who you
        // drove for. The second is what MileLog means by a purpose.
        val rows = rowsOf(trips).second
        assertEquals("Doordash", rows[0].first("businessline", "purpose"))
        // With no business line, it falls back to the purpose column.
        assertEquals("Personal", rows[1].first("businessline", "purpose"))
    }

    @Test
    fun spendingIsReadAsNegative() {
        val rows = rowsOf(transactions).second
        assertEquals(-768L, CsvImport.parseCents(rows[0].first("amount")))
        assertEquals(-1001L, CsvImport.parseCents(rows[1].first("amount")))
        assertEquals("Speedway", rows[0].first("merchant"))
        assertEquals("Gasoline", rows[1].first("category"))
    }

    @Test
    fun theBannerIsNeverMistakenForColumnNames() {
        val records = CsvImport.splitRecords(trips)
        val at = CsvImport.headerRowIndex(records)
        assertTrue("line one was chosen as the header", at > 0)
        assertTrue(
            "the row chosen as the header was: ${records[at]}",
            records[at].any { it.trim('"').equals("Miles", true) }
        )
        // The banner and the summary must never win.
        assertEquals(CsvImport.Kind.UNKNOWN, CsvImport.detectKind(records[0]))
    }
}
