package com.milelog.export

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A very small .xlsx writer. Excel, LibreOffice and Google Sheets all open what it
 * produces, and it keeps a multi-megabyte spreadsheet library out of the app.
 */
object Xlsx {

    const val PLAIN = 0
    const val HEADER = 1
    const val MONEY = 2
    const val MILES = 3
    const val DATE = 4
    const val BOLD = 5

    sealed interface Cell {
        data object Blank : Cell
        data class Text(val value: String, val style: Int = PLAIN) : Cell
        data class Num(val value: Double, val style: Int = PLAIN) : Cell
        /** Days since 1970-01-01, written as a real Excel date. */
        data class Day(val epochDay: Long, val style: Int = DATE) : Cell
    }

    class Sheet(val name: String) {
        val rows = mutableListOf<List<Cell>>()
        var columnWidths: List<Double> = emptyList()

        fun row(vararg cells: Cell) { rows += cells.toList() }
        fun header(vararg titles: String) { rows += titles.map { Cell.Text(it, HEADER) } }
        fun blank() { rows.add(emptyList()) }
    }

    fun write(file: File, sheets: List<Sheet>) {
        file.parentFile?.mkdirs()
        file.outputStream().use { write(it, sheets) }
    }

    fun write(out: OutputStream, requested: List<Sheet>) {
        // Excel will not open a workbook with no worksheet in it, so an empty request
        // gets one blank sheet rather than a file that cannot be opened.
        val sheets = requested.ifEmpty { listOf(Sheet("Sheet1")) }
        val names = safeSheetNames(sheets)
        ZipOutputStream(out).use { zip ->
            zip.entry("[Content_Types].xml", contentTypes(sheets.size))
            zip.entry("_rels/.rels", ROOT_RELS)
            zip.entry("xl/workbook.xml", workbook(names))
            zip.entry("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            zip.entry("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, sheet ->
                zip.entry("xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet))
            }
        }
    }

    private fun ZipOutputStream.entry(name: String, body: String) {
        putNextEntry(ZipEntry(name))
        write(body.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                // Control characters are illegal in XML; drop them rather than write a broken file.
                c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> Unit
                c.code == 0x7F -> Unit
                // Illegal in XML at any position; one of these makes the whole
                // workbook unreadable, not just the cell it sits in.
                c.code == 0xFFFE || c.code == 0xFFFF -> Unit
                c.isSurrogate() -> Unit
                else -> append(c)
            }
        }
    }

    /** Excel refuses a workbook holding a cell longer than this. */
    private const val MAX_CELL_CHARS = 32767

    private val FORBIDDEN_IN_SHEET_NAME = charArrayOf(':', '\\', '/', '?', '*', '[', ']')

    /**
     * Sheet names Excel will accept: the characters it forbids removed, trimmed to 31,
     * never blank, and never repeated within one workbook.
     */
    private fun safeSheetNames(sheets: List<Sheet>): List<String> {
        val used = mutableSetOf<String>()
        return sheets.mapIndexed { index, sheet ->
            val cleaned = sheet.name
                .filterNot { it in FORBIDDEN_IN_SHEET_NAME }
                .trim()
                .take(31)
                .ifBlank { "Sheet${index + 1}" }
            var candidate = cleaned
            var n = 2
            while (!used.add(candidate.lowercase())) {
                val suffix = " ($n)"
                candidate = cleaned.take(31 - suffix.length) + suffix
                n++
            }
            candidate
        }
    }

    /** 0 -> A, 25 -> Z, 26 -> AA */
    private fun colName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, 'A' + n % 26)
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    /** Excel counts days from 1899-12-30. */
    private fun excelSerial(epochDay: Long): Double = (epochDay + 25569).toDouble()

    private fun sheetXml(sheet: Sheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        if (sheet.columnWidths.isNotEmpty()) {
            append("<cols>")
            sheet.columnWidths.forEachIndexed { i, w ->
                append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
            }
            append("</cols>")
        }
        append("<sheetData>")
        sheet.rows.forEachIndexed { r, cells ->
            val rowNum = r + 1
            append("""<row r="$rowNum">""")
            cells.forEachIndexed { c, cell ->
                val ref = "${colName(c)}$rowNum"
                when (cell) {
                    is Cell.Blank -> Unit
                    is Cell.Text ->
                        if (cell.value.isNotEmpty()) {
                            append("""<c r="$ref" s="${cell.style}" t="inlineStr"><is><t xml:space="preserve">""")
                            append(esc(cell.value.take(MAX_CELL_CHARS)))
                            append("""</t></is></c>""")
                        }
                    is Cell.Num ->
                        // NaN and Infinity are not numbers Excel can hold; skip the cell
                        // rather than write something that makes it repair the file.
                        if (cell.value.isFinite()) {
                            append("""<c r="$ref" s="${cell.style}"><v>${cell.value}</v></c>""")
                        }
                    is Cell.Day -> append("""<c r="$ref" s="${cell.style}"><v>${excelSerial(cell.epochDay)}</v></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun workbook(names: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        names.forEachIndexed { i, name ->
            append("""<sheet name="${esc(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..count) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${count + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun contentTypes(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (i in 1..count) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        append("</Types>")
    }

    private const val ROOT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<numFmts count="3">""" +
            """<numFmt numFmtId="164" formatCode="&quot;${'$'}&quot;#,##0.00"/>""" +
            """<numFmt numFmtId="165" formatCode="#,##0.0"/>""" +
            """<numFmt numFmtId="166" formatCode="mm/dd/yyyy"/>""" +
            """</numFmts>""" +
            """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>""" +
            """<fills count="3"><fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF1D4ED8"/><bgColor indexed="64"/></patternFill></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="6">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>""" +
            """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            """</cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
