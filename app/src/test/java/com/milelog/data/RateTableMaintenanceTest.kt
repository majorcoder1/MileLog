package com.milelog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * How the rate table is kept up to date after the first run — and what happens when it
 * is not.
 *
 * Seed.seedRates is the only code in the app that ever inserts a MileageRate
 * (MileLogDb.kt:104-119, the single call site is Seed.runIfNeeded at MileLogDb.kt:54).
 * It is gated on "if (db.rates().count() == 0)". SettingsScreen's RateDialog
 * (SettingsScreen.kt:873-907) edits three cent figures on an existing row; it has no
 * date fields and there is no "add a rate" control anywhere in the screen.
 */
class RateTableMaintenanceTest {

    private fun rate(
        label: String, from: LocalDate, to: LocalDate,
        biz: Double, med: Double, chr: Double, mov: Double
    ) = MileageRate(
        label = label, fromEpochDay = from.toEpochDay(), toEpochDay = to.toEpochDay(),
        businessCents = biz, medicalCents = med, charityCents = chr, movingCents = mov
    )

    // ---- Seed.seedRates, MileLogDb.kt:115-118 ----------------------------------------
    private fun shippedRates(): List<MileageRate> = listOf(
        rate("2024", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 67.0, 21.0, 14.0, 21.0),
        rate("2025", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 70.0, 21.0, 14.0, 21.0),
        rate("2026 Jan–Jun", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), 72.5, 20.5, 14.0, 20.5),
        rate("2026 Jul–Dec", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31), 76.0, 23.5, 14.0, 23.5)
    )

    /** Seed.runIfNeeded, MileLogDb.kt:54. */
    private fun runIfNeeded(existing: List<MileageRate>): List<MileageRate> =
        if (existing.isEmpty()) shippedRates() else existing

    /** Repo.summarize's rateOn, Repo.kt:93-102. */
    private fun rateOn(table: List<MileageRate>, day: Long): Pair<MileageRate, Boolean> {
        table.firstOrNull { day in it.fromEpochDay..it.toEpochDay }?.let { return it to false }
        val nearest = table.filter { it.toEpochDay <= day }.maxByOrNull { it.toEpochDay }
            ?: table.minByOrNull { it.fromEpochDay }
            ?: return MileageRate(
                label = "unset", fromEpochDay = day, toEpochDay = day,
                businessCents = 0.0, medicalCents = 0.0, charityCents = 0.0, movingCents = 0.0
            ) to true
        return nearest to true
    }

    // ---- 1. the table stops at 31 December 2026 ---------------------------------------

    /**
     * FINDING: the shipped table ends on 2026-12-31 and nothing tops it up. On
     * 1 January 2027 every business mile is priced at the second-half-2026 rate, and the
     * line the Taxes tab prints is labelled "2026 Jul-Dec".
     */
    @Test fun everyDayOf2027IsPricedAtTheLast2026Rate() {
        val table = shippedRates()
        var day = LocalDate.of(2027, 1, 1)
        while (day.year == 2027) {
            val (r, estimated) = rateOn(table, day.toEpochDay())
            assertEquals("$day", "2026 Jul–Dec", r.label)
            assertEquals("$day", 76.0, r.businessCents, 0.0)
            assertTrue("$day must at least be flagged", estimated)
            day = day.plusDays(1)
        }
    }

    /**
     * The worked number. A full-time gig driver files 14,000 business miles for 2027.
     * If the 2027 business rate lands anywhere other than 76 cents, the figure the app
     * hands him is wrong by 140 dollars for every cent of difference.
     */
    @Test fun aFullYearOf2027MilesIsPricedOffTheEndOfTheTable() {
        val miles = 14_000.0
        val asShipped = miles * rateOn(shippedRates(), LocalDate.of(2027, 6, 1).toEpochDay())
            .first.businessCents / 100.0
        assertEquals("what MileLog reports for 2027", 10_640.00, asShipped, 0.005)

        // Two plausible 2027 rates, and the error each would leave on the return.
        assertEquals("if 2027 comes in at 78 cents", 10_920.00, miles * 78.0 / 100.0, 0.005)
        assertEquals("understated by", 280.00, miles * 78.0 / 100.0 - asShipped, 0.005)
        assertEquals("if 2027 comes in at 74 cents", 10_360.00, miles * 74.0 / 100.0, 0.005)
        assertEquals("overstated by", 280.00, asShipped - miles * 74.0 / 100.0, 0.005)
    }

    /**
     * FINDING: an update that adds a 2027 row to Seed.seedRates never reaches a phone
     * that already has rates in it, because Seed.runIfNeeded only fires on an empty
     * table. The user is told (TaxesScreen.kt:162, Exporter.kt:83) to "Add the new
     * year's rate in Settings"; RateDialog has no date fields and SettingsScreen has no
     * add button, so there is nothing there to add.
     */
    @Test fun theSeedGateSkipsAnyPhoneThatAlreadyHasARateRow() {
        // Fresh install: the table is written.
        assertEquals(4, runIfNeeded(emptyList()).size)

        // Existing install, already carrying the four shipped rows. Even if a later
        // release adds a 2027 row to seedRates, runIfNeeded does nothing at all.
        val onThePhone = shippedRates()
        assertEquals(onThePhone, runIfNeeded(onThePhone))
        assertTrue(
            "no 2027 row can arrive by update",
            runIfNeeded(onThePhone).none { it.label.startsWith("2027") }
        )
    }

    // ---- 2. RateDialog writes the medical rate into the moving rate --------------------

    /** SettingsScreen.kt:894-902, verbatim, over the three fields the dialog offers. */
    private fun rateDialogSave(
        original: MileageRate, business: String, medical: String, charity: String
    ) = original.copy(
        businessCents = business.toDoubleOrNull() ?: original.businessCents,
        medicalCents = medical.toDoubleOrNull() ?: original.medicalCents,
        charityCents = charity.toDoubleOrNull() ?: original.charityCents,
        movingCents = medical.toDoubleOrNull() ?: original.movingCents
    )

    /**
     * FINDING: SettingsScreen.kt:900 reads `medical` where it means `moving`. The dialog
     * has no Moving field at all (SettingsScreen.kt:888-890), so the moving rate is not
     * merely un-editable — it is silently overwritten with whatever the medical rate is
     * every time the row is saved.
     */
    @Test fun savingARateOverwritesTheMovingRateWithTheMedicalOne() {
        // Start from a row where the two genuinely differ, which is what a correction
        // to only one of them produces.
        val row = rate(
            "2026 Jul–Dec", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31),
            76.0, 23.5, 14.0, 21.0
        )
        val saved = rateDialogSave(row, business = "76.0", medical = "23.5", charity = "14.0")

        assertEquals("business survives", 76.0, saved.businessCents, 0.0)
        assertEquals("medical survives", 23.5, saved.medicalCents, 0.0)
        assertEquals("charity survives", 14.0, saved.charityCents, 0.0)
        assertNotEquals(
            "moving was 21.0 before the dialog was opened",
            21.0, saved.movingCents, 0.0
        )
        assertEquals("it is now a copy of medical", 23.5, saved.movingCents, 0.0)

        // 3,000 armed-forces moving miles: 630.00 becomes 705.00, without the user
        // touching a Moving field, because there is not one.
        assertEquals(630.00, 3_000.0 * 21.0 / 100.0, 0.005)
        assertEquals(705.00, 3_000.0 * saved.movingCents / 100.0, 0.005)
    }

    /**
     * Correcting only the medical rate drags moving along with it. This is the everyday
     * way to hit the bug: the IRS revises one figure, the user types it in, and a class
     * he never touched changes underneath him.
     */
    @Test fun correctingTheMedicalRateSilentlyMovesTheMovingRateToo() {
        val row = shippedRates().first { it.label == "2026 Jan–Jun" }
        assertEquals(20.5, row.medicalCents, 0.0)
        assertEquals(20.5, row.movingCents, 0.0)

        val saved = rateDialogSave(row, business = "72.5", medical = "22.0", charity = "14.0")
        assertEquals(22.0, saved.medicalCents, 0.0)
        assertEquals("moving followed medical without being asked", 22.0, saved.movingCents, 0.0)
    }

    /**
     * Cancelling out of the dialog is safe, but tapping Save without typing anything is
     * not neutral: it still writes medical over moving.
     */
    @Test fun openingAndSavingWithoutTypingStillRewritesMoving() {
        val row = rate(
            "2025", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
            70.0, 21.0, 14.0, 19.0
        )
        val saved = rateDialogSave(
            row,
            business = row.businessCents.toString(),
            medical = row.medicalCents.toString(),
            charity = row.charityCents.toString()
        )
        assertEquals(19.0, row.movingCents, 0.0)
        assertEquals("a no-op save changed the moving rate", 21.0, saved.movingCents, 0.0)
    }
}
