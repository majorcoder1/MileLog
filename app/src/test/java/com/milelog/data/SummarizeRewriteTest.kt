package com.milelog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Did the summarize() rewrite change any number?
 *
 * Repo.summarize used to call Repo.rateFor(day) once per trip (git 9b28676, Repo.kt:105).
 * It now builds a local rateOn(day) over one snapshot of the rate table (Repo.kt:93-102)
 * and runs the whole thing on Dispatchers.IO. Both lookups are transcribed below straight
 * from the two revisions, and driven over the rate rows Seed.seedRates actually inserts
 * (MileLogDb.kt:115-118).
 *
 * VERDICT: for the shipped rate table the two are identical, day for day and class for
 * class. The only input that separates them is a rate table whose rows overlap, and the
 * last test pins exactly why.
 */
class SummarizeRewriteTest {

    // ---- Seed.seedRates, MileLogDb.kt:115-118 ---------------------------------------
    private val seeded: List<MileageRate> = listOf(
        rate("2024", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 67.0, 21.0, 14.0, 21.0),
        rate("2025", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 70.0, 21.0, 14.0, 21.0),
        rate("2026 Jan–Jun", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), 72.5, 20.5, 14.0, 20.5),
        rate("2026 Jul–Dec", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31), 76.0, 23.5, 14.0, 23.5)
    )

    private fun rate(
        label: String, from: LocalDate, to: LocalDate,
        biz: Double, med: Double, chr: Double, mov: Double
    ) = MileageRate(
        label = label, fromEpochDay = from.toEpochDay(), toEpochDay = to.toEpochDay(),
        businessCents = biz, medicalCents = med, charityCents = chr, movingCents = mov
    )

    /**
     * BEFORE. Repo.rateFor, Repo.kt:61-71, still on disk and still what Exporter.kt:120
     * calls for the Trips sheet. Its first step is RateDao.forDay (Daos.kt:206-207),
     * "WHERE :day BETWEEN fromEpochDay AND toEpochDay LIMIT 1", which carries no ORDER BY;
     * [table] is the order SQLite hands rows back in.
     */
    private fun rateFor(table: List<MileageRate>, day: Long): Pair<MileageRate, Boolean> {
        table.firstOrNull { day in it.fromEpochDay..it.toEpochDay }?.let { return it to false }
        val nearest = table.filter { it.toEpochDay <= day }.maxByOrNull { it.toEpochDay }
            ?: table.minByOrNull { it.fromEpochDay }
            ?: MileageRate(
                label = "unset", fromEpochDay = day, toEpochDay = day,
                businessCents = 0.0, medicalCents = 0.0, charityCents = 0.0, movingCents = 0.0
            )
        return nearest to true
    }

    /**
     * AFTER. Repo.summarize's local rateOn, Repo.kt:93-102, over RateDao.allNow
     * (Daos.kt:204), which is "ORDER BY fromEpochDay".
     */
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

    private fun MileageRate.centsFor(cls: DeductionClass): Double = when (cls) {
        DeductionClass.BUSINESS -> businessCents
        DeductionClass.MEDICAL -> medicalCents
        DeductionClass.CHARITY -> charityCents
        DeductionClass.MOVING -> movingCents
        DeductionClass.PERSONAL -> 0.0
    }

    // ---- 1. every day, every class, both lookups --------------------------------------

    @Test fun theTwoLookupsAgreeOnEveryDayFrom2022To2030() {
        var day = LocalDate.of(2022, 1, 1)
        val end = LocalDate.of(2030, 12, 31)
        var checked = 0
        while (!day.isAfter(end)) {
            val d = day.toEpochDay()
            val before = rateFor(seeded, d)
            val after = rateOn(seeded, d)
            assertEquals("$day label", before.first.label, after.first.label)
            assertEquals("$day estimated flag", before.second, after.second)
            DeductionClass.entries.forEach { cls ->
                assertEquals(
                    "$day $cls cents",
                    before.first.centsFor(cls), after.first.centsFor(cls), 0.0
                )
            }
            checked++
            day = day.plusDays(1)
        }
        assertEquals(3_287, checked)
    }

    /** The empty-table path is the one place the two were re-typed rather than moved. */
    @Test fun anEmptyRateTableBehavesIdenticallyInBothVersions() {
        val day = LocalDate.of(2026, 8, 1).toEpochDay()
        val before = rateFor(emptyList(), day)
        val after = rateOn(emptyList(), day)
        assertEquals("unset", before.first.label)
        assertEquals("unset", after.first.label)
        assertEquals(0.0, before.first.businessCents, 0.0)
        assertEquals(0.0, after.first.businessCents, 0.0)
        assertEquals(true, before.second)
        assertEquals(true, after.second)
    }

    // ---- 2. a whole year, summarized both ways ----------------------------------------

    private data class FakeTrip(val day: LocalDate, val miles: Double, val cls: DeductionClass?)

    /** Repo.summarize, Repo.kt:104-163, with the rate lookup swapped in. */
    private fun summarize(
        range: DayRange,
        trips: List<FakeTrip>,
        lookup: (Long) -> Pair<MileageRate, Boolean>
    ): TaxSummary {
        val inRange = trips.filter { it.day.toEpochDay() in range.fromDay..range.toDay }
        var business = 0.0; var personal = 0.0; var unclassified = 0.0; var other = 0.0
        var estimated = false
        val buckets = LinkedHashMap<Pair<String, DeductionClass>, Triple<Double, Double, DeductionClass>>()

        for (t in inRange) {
            when {
                t.cls == null -> unclassified += t.miles
                t.cls == DeductionClass.BUSINESS -> business += t.miles
                t.cls == DeductionClass.PERSONAL -> personal += t.miles
                else -> other += t.miles
            }
            if (t.cls == null || t.cls == DeductionClass.PERSONAL) continue
            val (r, wasEstimated) = lookup(t.day.toEpochDay())
            if (wasEstimated) estimated = true
            val key = r.label to t.cls
            val prev = buckets[key]
            buckets[key] = Triple((prev?.first ?: 0.0) + t.miles, r.centsFor(t.cls), t.cls)
        }
        val slices = buckets.map { (key, v) ->
            RateSlice(label = key.first, deductionClass = v.third, miles = v.first, centsPerMile = v.second)
        }.sortedWith(compareBy({ it.label }, { it.deductionClass.name }))

        return TaxSummary(
            range = range,
            totalMiles = inRange.sumOf { it.miles },
            businessMiles = business, personalMiles = personal,
            unclassifiedMiles = unclassified, otherMiles = other,
            slices = slices, tripCount = inRange.size, ratesEstimated = estimated
        )
    }

    /** A year of driving that touches every class and both halves of 2026. */
    private fun aRealisticYear(): List<FakeTrip> {
        val classes = listOf(
            DeductionClass.BUSINESS, DeductionClass.BUSINESS, DeductionClass.BUSINESS,
            DeductionClass.PERSONAL, DeductionClass.MEDICAL, DeductionClass.CHARITY,
            DeductionClass.MOVING, null
        )
        val out = mutableListOf<FakeTrip>()
        var day = LocalDate.of(2026, 1, 1)
        var i = 0
        while (day.year == 2026) {
            repeat(3) { k ->
                out += FakeTrip(day, 4.0 + ((i * 7 + k * 13) % 91) / 10.0, classes[(i + k) % classes.size])
                i++
            }
            day = day.plusDays(1)
        }
        return out
    }

    @Test fun afullYear2026IsIdenticalBeforeAndAfterTheRewrite() {
        val trips = aRealisticYear()
        val range = DayRange.forYear(2026)
        val before = summarize(range, trips) { rateFor(seeded, it) }
        val after = summarize(range, trips) { rateOn(seeded, it) }

        assertEquals(before.totalMiles, after.totalMiles, 0.0)
        assertEquals(before.businessMiles, after.businessMiles, 0.0)
        assertEquals(before.personalMiles, after.personalMiles, 0.0)
        assertEquals(before.unclassifiedMiles, after.unclassifiedMiles, 0.0)
        assertEquals(before.otherMiles, after.otherMiles, 0.0)
        assertEquals(before.tripCount, after.tripCount)
        assertEquals(before.ratesEstimated, after.ratesEstimated)
        assertEquals(before.slices, after.slices)
        // Bit for bit, not just to the cent.
        assertEquals(before.deduction, after.deduction, 0.0)

        // Sanity: this year really does exercise the split and every deductible class.
        assertTrue("the year must produce both 2026 halves", before.slices.size >= 6)
        assertTrue(before.slices.any { it.label == "2026 Jan–Jun" })
        assertTrue(before.slices.any { it.label == "2026 Jul–Dec" })
        assertTrue(before.deduction > 0.0)
    }

    @Test fun aRangeRunningPastTheTableIsIdenticalBeforeAndAfter() {
        val trips = listOf(
            FakeTrip(LocalDate.of(2023, 6, 1), 100.0, DeductionClass.BUSINESS),
            FakeTrip(LocalDate.of(2026, 12, 15), 100.0, DeductionClass.BUSINESS),
            FakeTrip(LocalDate.of(2027, 1, 15), 100.0, DeductionClass.BUSINESS)
        )
        val range = DayRange.of(LocalDate.of(2023, 1, 1), LocalDate.of(2027, 12, 31))
        val before = summarize(range, trips) { rateFor(seeded, it) }
        val after = summarize(range, trips) { rateOn(seeded, it) }
        assertEquals(before.slices, after.slices)
        assertEquals(before.deduction, after.deduction, 0.0)
        assertEquals(true, before.ratesEstimated)
    }

    // ---- 3. where the rewrite is not a pure refactor ------------------------------------

    /**
     * FINDING (latent): the two lookups differ in how they pick a row when more than one
     * covers the same day. RateDao.forDay (Daos.kt:206) is "... LIMIT 1" with no ORDER BY,
     * so SQLite is free to return either row; RateDao.allNow (Daos.kt:204) is
     * "ORDER BY fromEpochDay", so the new rateOn always takes the earlier-starting row.
     *
     * Nothing in the app can create an overlap today — RateDialog (SettingsScreen.kt:873)
     * edits cents only and never the dates, and Seed only runs on an empty table — so this
     * is not a live wrong number. It is a difference in behaviour the rewrite introduced,
     * and it decides a real amount of money if a restored or hand-edited table ever
     * overlaps.
     */
    @Test fun overlappingRowsResolveDifferentlyDependingOnRowOrder() {
        val extended = rate(
            "2026 (whole year, stale)",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 70.0, 21.0, 14.0, 21.0
        )
        // Same rows, two orders. allNow always yields the fromEpochDay order; a bare
        // "LIMIT 1" scan yields whatever order the table is stored in.
        val byFromDay = (seeded + extended).sortedBy { it.fromEpochDay }
        val byRowid = seeded + extended

        val aug1 = LocalDate.of(2026, 8, 1).toEpochDay()
        val newPick = rateOn(byFromDay, aug1).first
        val oldPick = rateFor(byRowid, aug1).first

        assertNotEquals("the two orders disagree", newPick.label, oldPick.label)
        assertEquals(70.0, newPick.businessCents, 0.0)   // the stale whole-year row starts Jan 1
        assertEquals(76.0, oldPick.businessCents, 0.0)   // the Jul-Dec row comes first in the table

        // 10,000 miles in the second half of 2026: 7,000.00 against 7,600.00.
        assertEquals(7_000.00, 10_000.0 * newPick.businessCents / 100.0, 0.005)
        assertEquals(7_600.00, 10_000.0 * oldPick.businessCents / 100.0, 0.005)
    }

    /**
     * And the same overlap now splits the Taxes tab from the spreadsheet, because
     * Exporter.kt:120 still calls Repo.rateFor while Repo.summarize calls rateOn. Before
     * the rewrite both went through the same query.
     */
    @Test fun theSummaryAndTheTripsSheetCanNowDisagreeOnTheSameTrip() {
        val extended = rate(
            "2026 (whole year, stale)",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 70.0, 21.0, 14.0, 21.0
        )
        val day = LocalDate.of(2026, 9, 15).toEpochDay()
        val summaryCents = rateOn((seeded + extended).sortedBy { it.fromEpochDay }, day)
            .first.businessCents
        val tripsSheetCents = rateFor(seeded + extended, day).first.businessCents
        assertNotEquals(summaryCents, tripsSheetCents, 0.0)
    }
}
