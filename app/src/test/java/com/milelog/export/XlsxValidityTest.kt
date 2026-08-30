package com.milelog.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the .xlsx the app hands the accountant is really a valid OOXML package.
 * Every part is run through a real XML parser and the package-level rules Excel
 * enforces are asserted, because Excel's answer to a broken part is to refuse the
 * whole workbook rather than open the good sheets.
 */
class XlsxValidityTest {

    // ---- helpers -----------------------------------------------------------------

    private fun partsOf(vararg sheets: Xlsx.Sheet): Map<String, String> {
        val bytes = ByteArrayOutputStream().also { Xlsx.write(it, sheets.toList()) }.toByteArray()
        return partsOfBytes(bytes)
    }

    private fun partsOfBytes(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** Parses one part, failing the test with the parser's own complaint. */
    private fun parse(name: String, xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(e: SAXParseException) = Unit
            override fun error(e: SAXParseException): Unit = throw e
            override fun fatalError(e: SAXParseException): Unit = throw e
        })
        return try {
            builder.parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            fail("$name is not well-formed XML: ${e.message}")
            throw e
        }
    }

    private fun parseAll(parts: Map<String, String>) {
        parts.forEach { (name, body) ->
            if (name.endsWith(".xml") || name.endsWith(".rels")) parse(name, body)
        }
    }

    private fun sheetNames(parts: Map<String, String>): List<String> =
        Regex("""<sheet name="([^"]*)"""").findAll(parts.getValue("xl/workbook.xml"))
            .map { it.groupValues[1] }
            .toList()

    // ---- the shapes that already work --------------------------------------------

    @Test
    fun ordinaryWorkbookIsWellFormedAndCompletelyWired() {
        val parts = partsOf(
            Xlsx.Sheet("Summary").apply {
                columnWidths = listOf(30.0, 18.0)
                row(Xlsx.Cell.Text("MileLog summary", Xlsx.BOLD))
                row(Xlsx.Cell.Text("Total miles"), Xlsx.Cell.Num(1983.94, Xlsx.MILES))
                blank()
                row(Xlsx.Cell.Text("Odd text & <tags> \"quoted\" 'single'"), Xlsx.Cell.Blank)
            },
            Xlsx.Sheet("Trips").apply {
                header("Date", "Purpose", "Miles")
                row(Xlsx.Cell.Day(20_000), Xlsx.Cell.Text("DoorDash"), Xlsx.Cell.Num(23.7, Xlsx.MILES))
            }
        )
        parseAll(parts)
        assertTrue("[Content_Types].xml missing", parts.containsKey("[Content_Types].xml"))
        assertEquals(
            "[Content_Types].xml must be the first entry in the package",
            "[Content_Types].xml",
            parts.keys.first()
        )
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(parts.containsKey("xl/worksheets/sheet2.xml"))
    }

    @Test
    fun sheetWithNoRowsIsStillValid() {
        val parts = partsOf(Xlsx.Sheet("Receipts"))
        parseAll(parts)
        assertTrue(
            "an empty sheet still needs a sheetData element",
            parts.getValue("xl/worksheets/sheet1.xml").contains("<sheetData>")
        )
    }

    @Test
    fun blankRowsAndBlankCellsStayValid() {
        val parts = partsOf(
            Xlsx.Sheet("Gaps").apply {
                blank()
                row(Xlsx.Cell.Blank, Xlsx.Cell.Blank)
                row(Xlsx.Cell.Text(""), Xlsx.Cell.Num(1.0))
                blank()
            }
        )
        parseAll(parts)
    }

    @Test
    fun columnLettersRunPastZCorrectly() {
        val wide = Xlsx.Sheet("Wide").apply {
            row(*(0 until 60).map { Xlsx.Cell.Num(it.toDouble()) }.toTypedArray())
        }
        val xml = partsOf(wide).getValue("xl/worksheets/sheet1.xml")
        val refs = Regex("""<c r="([A-Z]+)1"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(60, refs.size)
        assertEquals("A", refs[0])
        assertEquals("Z", refs[25])
        assertEquals("AA", refs[26])
        assertEquals("AZ", refs[51])
        assertEquals("BA", refs[52])
        assertEquals("BH", refs[59])
    }

    @Test
    fun controlCharactersAreStrippedRatherThanWritten() {
        val parts = partsOf(
            Xlsx.Sheet("Ctrl").apply {
                row(Xlsx.Cell.Text("bell\u0007 null\u0000 unit\u001F delete\u007F"))
            }
        )
        parseAll(parts)
    }

    // ---- the edge cases that break the file ---------------------------------------

    @Test
    fun sheetNameDropsTheCharactersExcelForbids() {
        // Excel rejects : \ / ? * [ ] in a sheet name and calls the whole workbook
        // unreadable. A sheet named after a purpose or a vehicle can hold any of them.
        val parts = partsOf(Xlsx.Sheet("Trips: 2026/27 [draft]"))
        parseAll(parts)
        val name = sheetNames(parts).single()
        val forbidden = name.filter { it in ":\\/?*[]" }
        assertEquals(
            "sheet name \"$name\" carries characters Excel forbids: $forbidden",
            "",
            forbidden
        )
    }

    @Test
    fun sheetNameIsNeverEmpty() {
        val parts = partsOf(Xlsx.Sheet(""))
        parseAll(parts)
        assertTrue("a sheet with an empty name makes the workbook unreadable", sheetNames(parts).single().isNotEmpty())
    }

    @Test
    fun sheetNamesAreMadeUnique() {
        // Two long names that differ only past character 31 collide once take(31) runs.
        val parts = partsOf(
            Xlsx.Sheet("Expenses for the 2026 tax year A"),
            Xlsx.Sheet("Expenses for the 2026 tax year B"),
            Xlsx.Sheet("Trips"),
            Xlsx.Sheet("Trips")
        )
        parseAll(parts)
        val names = sheetNames(parts)
        assertEquals("duplicate sheet names make Excel refuse the workbook: $names", names.size, names.toSet().size)
    }

    @Test
    fun workbookWithNoSheetsIsNotWritten() {
        // Excel requires at least one visible worksheet.
        val bytes = ByteArrayOutputStream().also { Xlsx.write(it, emptyList()) }.toByteArray()
        val parts = partsOfBytes(bytes)
        parseAll(parts)
        assertTrue(
            "a workbook with zero sheets cannot be opened; writing one should not be possible",
            sheetNames(parts).isNotEmpty()
        )
    }

    @Test
    fun veryLongTextIsCutToTheCellLimit() {
        val long = "x".repeat(40_000)
        val parts = partsOf(Xlsx.Sheet("Notes").apply { row(Xlsx.Cell.Text(long)) })
        parseAll(parts)
        val written = Regex("""<t xml:space="preserve">(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
            .find(parts.getValue("xl/worksheets/sheet1.xml"))!!.groupValues[1]
        assertTrue(
            "cell text is ${written.length} characters; Excel's limit is 32767 and it repairs the file above it",
            written.length <= 32_767
        )
    }

    @Test
    fun nonFiniteNumbersNeverReachTheFile() {
        val parts = partsOf(
            Xlsx.Sheet("Numbers").apply {
                row(Xlsx.Cell.Num(Double.NaN), Xlsx.Cell.Num(Double.POSITIVE_INFINITY))
            }
        )
        parseAll(parts)
        val values = Regex("""<v>([^<]*)</v>""").findAll(parts.getValue("xl/worksheets/sheet1.xml"))
            .map { it.groupValues[1] }
            .toList()
        values.forEach { raw ->
            val n = raw.toDoubleOrNull()
            assertTrue("<v>$raw</v> is not a number Excel can read", n != null && n.isFinite())
        }
    }

    @Test
    fun charactersXmlForbidsAreStripped() {
        // U+FFFE and U+FFFF are not legal XML characters at all. One of them anywhere in
        // a note or an address makes every part of the workbook unparseable.
        val parts = partsOf(
            Xlsx.Sheet("Odd").apply { row(Xlsx.Cell.Text("note \uFFFF end")) }
        )
        parseAll(parts)
    }

    @Test
    fun columnWidthsAreWrittenWithADecimalPointRegardlessOfLocale() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val parts = partsOf(
                Xlsx.Sheet("Locale").apply {
                    columnWidths = listOf(12.5)
                    row(Xlsx.Cell.Num(1234.56, Xlsx.MONEY))
                }
            )
            parseAll(parts)
            val xml = parts.getValue("xl/worksheets/sheet1.xml")
            assertTrue("column width used a comma: $xml", xml.contains("""width="12.5""""))
            assertTrue("cell value used a comma: $xml", xml.contains("<v>1234.56</v>"))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
