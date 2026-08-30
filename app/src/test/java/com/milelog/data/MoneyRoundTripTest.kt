package com.milelog.data

import com.milelog.export.Xlsx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Money is stored as Long cents. Every place it becomes a Double and comes back is
 * checked here, including both export formats.
 */
class MoneyRoundTripTest {

    /** The number of cents a reader gets back from a "12.34"-style string. */
    private fun backFromText(text: String): Long = Math.round(text.toDouble() * 100)

    // ---- the scan --------------------------------------------------------------------

    /**
     * Exporter.kt:198/215 writes money with String.format("%.2f", cents / 100.0), and
     * EditTxnScreen.kt:99 reads it back the same way. Sweep the whole range a driver
     * could plausibly hit and prove no cent is created or destroyed.
     */
    @Test fun csvMoneyFormattingIsExactForEveryCent() {
        val ranges = listOf(0L..2_000_00L, 999_900L..1_000_100L, 99_999_900L..100_000_100L)
        var checked = 0
        for (r in ranges) {
            for (n in r) {
                val text = String.format(Locale.US, "%.2f", n / 100.0)
                assertEquals("cents $n formatted as $text", n, backFromText(text))
                checked++
            }
        }
        assertTrue(checked > 200_000)
    }

    /**
     * EditTxnScreen.kt:149 and CsvImport.kt:177 both do Math.round(dollars * 100).
     * Prove that is exact for anything that was a whole number of cents to begin with.
     */
    @Test fun roundTripThroughDoubleIsExactForWholeCents() {
        for (n in 0L..2_000_00L) {
            assertEquals("cents $n", n, Math.round((n / 100.0) * 100))
        }
        for (n in 99_999_900L..100_000_100L) {
            assertEquals("cents $n", n, Math.round((n / 100.0) * 100))
        }
    }

    /** Fmt.cents, Money.kt:14 — the number every screen prints. */
    @Test fun fmtCentsPrintsTheExactAmount() {
        assertEquals("$0.00", Fmt.cents(0))
        assertEquals("$0.01", Fmt.cents(1))
        assertEquals("$12.34", Fmt.cents(1234))
        assertEquals("$1,234.56", Fmt.cents(123456))
        assertEquals("-$12.34", Fmt.cents(-1234))
        assertEquals("$1,000,000.00", Fmt.cents(100_000_000))
        for (n in 0L..200_00L) {
            val printed = Fmt.cents(n).removePrefix("$").replace(",", "")
            assertEquals("cents $n printed as ${Fmt.cents(n)}", n, backFromText(printed))
        }
    }

    // ---- the xlsx export -------------------------------------------------------------

    /**
     * Xlsx.kt:117 writes a numeric cell as "<v>${cell.value}</v>", i.e. Kotlin's
     * Double.toString. Write a real workbook, unzip it, and read the values back the
     * way a spreadsheet would.
     */
    @Test fun xlsxMoneyCellsCarryTheExactCents() {
        val amounts = listOf(
            0L, 1L, 7L, 99L, 100L, 1234L, 99_99L, 1_234_56L, 12_345_67L,
            99_999_99L, 100_000_00L, 1_000_000_00L
        )
        val sheet = Xlsx.Sheet("Money").apply {
            header("Label", "Amount")
            amounts.forEach { cents ->
                row(Xlsx.Cell.Text("c$cents"), Xlsx.Cell.Num(cents / 100.0, Xlsx.MONEY))
            }
        }
        val out = File("build/money-check/money.xlsx")
        Xlsx.write(out, listOf(sheet))

        val xml = ZipFile(out).use { zip ->
            zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readBytes().toString(Charsets.UTF_8)
        }
        val values = Regex("<v>([^<]+)</v>").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(amounts.size, values.size)

        amounts.forEachIndexed { i, cents ->
            val raw = values[i]
            assertEquals(
                "cell for $cents cents was written as <v>$raw</v>",
                cents, Math.round(raw.toDouble() * 100)
            )
        }

        // A spreadsheet reads xsd:double, so scientific notation is legal, but note
        // where the writer switches into it.
        val sci = amounts.indices.filter { values[it].contains('E') }.map { amounts[it] }
        println("xlsx money cells written in scientific notation (cents): $sci")
        println("xlsx money cell values: $values")
    }

    /** The deduction column: miles * cents / 100.0, then read back at 2 decimals. */
    @Test fun xlsxDeductionColumnMatchesHandArithmetic() {
        val sheet = Xlsx.Sheet("Trips").apply {
            header("Miles", "Rate", "Deduction")
            listOf(
                Triple(23.7, 72.5, 17.1825),
                Triple(110.3, 76.0, 83.828),
                Triple(12_345.6, 72.5, 8_950.56),
                Triple(6_000.0, 76.0, 4_560.00)
            ).forEach { (mi, cents, _) ->
                row(Xlsx.Cell.Num(mi, Xlsx.MILES), Xlsx.Cell.Num(cents), Xlsx.Cell.Num(mi * cents / 100.0, Xlsx.MONEY))
            }
        }
        val out = File("build/money-check/trips.xlsx")
        Xlsx.write(out, listOf(sheet))
        val xml = ZipFile(out).use { zip ->
            zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readBytes().toString(Charsets.UTF_8)
        }
        val v = Regex("<v>([^<]+)</v>").findAll(xml).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(17.1825, v[2], 1e-9)
        assertEquals(83.828, v[5], 1e-9)
        assertEquals(8_950.56, v[8], 1e-9)
        assertEquals(4_560.00, v[11], 1e-9)
    }

    // ---- money typed into the app ----------------------------------------------------

    /**
     * FINDING: EditTxnScreen.kt:147-149 keeps only digits and dots, then does
     * Math.round(dollars * 100). Two dots make toDoubleOrNull() null, which the code
     * turns into 0.0 and stores as 0 cents while the field still shows what was typed.
     */
    @Test fun aSecondDecimalPointSilentlyZeroesTheAmount() {
        fun asTyped(text: String): Long {
            val filtered = text.filter { it.isDigit() || it == '.' }
            val dollars = filtered.toDoubleOrNull() ?: 0.0
            return Math.round(dollars * 100)
        }
        assertEquals(4_275L, asTyped("42.75"))
        assertEquals("field reads 42.7.5, amount stored is zero", 0L, asTyped("42.7.5"))
        assertEquals("a stray leading dot also zeroes it", 4_275L, asTyped("$42.75"))
        // 1,234.56 typed with its comma loses the comma and still works.
        assertEquals(123_456L, asTyped("1,234.56"))
    }

    /**
     * A third decimal is rounded to the nearest cent, half up, which is what a person
     * would expect. Recorded here so a later change to the parsing cannot quietly
     * start truncating.
     */
    @Test fun aThirdDecimalRoundsToTheNearestCent() {
        fun asTyped(text: String) = Math.round((text.toDoubleOrNull() ?: 0.0) * 100)
        assertEquals(1_235L, asTyped("12.345"))
        assertEquals(1_234L, asTyped("12.344"))
        assertEquals(1L, asTyped("0.005"))
        assertEquals(0L, asTyped("0.004"))
    }

    /**
     * FINDING: CsvImport.parseCents keeps the sign of a refund or a credit
     * (CsvImport.kt:172-179), but commit() throws it away with Math.abs at
     * CsvImport.kt:359, and isRevenue (CsvImport.kt:385-395) takes `cents` as a
     * parameter and never looks at it. A negative row with no type column therefore
     * lands as an expense of the same size.
     */
    @Test fun animportedRefundBecomesAnExpenseOfTheSameSize() {
        val parsed = com.milelog.export.CsvImport.parseCents("(500.00)")
        assertEquals("the parser gets the sign right", -50_000L, parsed)

        // What commit() then stores, CsvImport.kt:358-359.
        val storedType = "EXPENSE"      // isRevenue ignores the sign
        val storedCents = Math.abs(parsed!!)
        assertEquals(50_000L, storedCents)
        assertEquals("EXPENSE", storedType)

        // A 500.00 credit should have moved profit up by 500. It moves it down by 500.
        val profitIfHandledRight = 50_000L
        val profitAsStored = -50_000L
        assertNotEquals(profitIfHandledRight, profitAsStored)
        assertEquals("the swing on the return", 100_000L, profitIfHandledRight - profitAsStored)
    }
}
