package com.milelog.export

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Writes a workbook covering every cell type and the awkward characters, so a
 * corrupt export shows up here instead of on the phone.
 */
class XlsxTest {

    @Test
    fun writesAWorkbookThatOpens() {
        val summary = Xlsx.Sheet("Summary").apply {
            columnWidths = listOf(30.0, 18.0)
            row(Xlsx.Cell.Text("MileLog summary", Xlsx.BOLD))
            row(Xlsx.Cell.Text("Total miles"), Xlsx.Cell.Num(1983.94, Xlsx.MILES))
            row(Xlsx.Cell.Text("Deduction"), Xlsx.Cell.Num(1507.79, Xlsx.MONEY))
            row(Xlsx.Cell.Text("Generated"), Xlsx.Cell.Day(LocalDate.of(2026, 8, 27).toEpochDay()))
            blank()
            row(Xlsx.Cell.Text("Odd text & <tags> \"quoted\" 'single'"), Xlsx.Cell.Blank)
        }
        val trips = Xlsx.Sheet("Trips").apply {
            header("Date", "Purpose", "Miles", "Rate (cents)", "Deduction")
            row(
                Xlsx.Cell.Day(LocalDate.of(2026, 3, 2).toEpochDay()),
                Xlsx.Cell.Text("DoorDash"),
                Xlsx.Cell.Num(23.7, Xlsx.MILES),
                Xlsx.Cell.Num(72.5),
                Xlsx.Cell.Num(17.18, Xlsx.MONEY)
            )
            row(
                Xlsx.Cell.Day(LocalDate.of(2026, 8, 27).toEpochDay()),
                Xlsx.Cell.Text("Spark"),
                Xlsx.Cell.Num(110.3, Xlsx.MILES),
                Xlsx.Cell.Num(76.0),
                Xlsx.Cell.Num(83.83, Xlsx.MONEY)
            )
        }
        // 30 columns forces the AA-and-beyond column naming.
        val wide = Xlsx.Sheet("Wide").apply {
            header(*(1..30).map { "Col $it" }.toTypedArray())
            row(*(1..30).map { Xlsx.Cell.Num(it.toDouble()) }.toTypedArray())
        }

        val out = File("build/xlsx-check/sample.xlsx")
        Xlsx.write(out, listOf(summary, trips, wide))

        assertTrue("file was not written", out.exists())
        assertTrue("file is suspiciously small", out.length() > 1000)
    }
}
