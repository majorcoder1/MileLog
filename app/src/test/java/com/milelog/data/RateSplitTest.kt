package com.milelog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The 2026 mid-year rate change, end to end.
 *
 * Repo.summarize() and Repo.rateFor() need a Room database and an Android Context, so
 * they cannot be called from a JVM unit test. The two helpers below are line-for-line
 * transcriptions of the production code they name, over the exact rate rows
 * Seed.seedRates() inserts (MileLogDb.kt:114-117). Anything these prove about the
 * arithmetic holds for the real thing; the transcriptions are marked so they can be
 * diffed against the originals when either side changes.
 */
class RateSplitTest {

    // ---- Seed.seedRates, MileLogDb.kt:114-117 ---------------------------------------
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

    /** RateDao.forDay, Daos.kt:206 — "WHERE :day BETWEEN fromEpochDay AND toEpochDay". */
    private fun forDay(day: Long): MileageRate? =
        seeded.firstOrNull { day in it.fromEpochDay..it.toEpochDay }

    /** Repo.rateFor, Repo.kt:59-69. */
    private fun rateFor(day: Long): Pair<MileageRate, Boolean> {
        forDay(day)?.let { return it to false }
        val nearest = seeded.filter { it.toEpochDay <= day }.maxByOrNull { it.toEpochDay }
            ?: seeded.minByOrNull { it.fromEpochDay }
            ?: MileageRate(
                label = "unset", fromEpochDay = day, toEpochDay = day,
                businessCents = 0.0, medicalCents = 0.0, charityCents = 0.0, movingCents = 0.0
            )
        return nearest to true
    }

    /** Repo.summarize's centsFor, Repo.kt:71-77. */
    private fun MileageRate.centsFor(cls: DeductionClass): Double = when (cls) {
        DeductionClass.BUSINESS -> businessCents
        DeductionClass.MEDICAL -> medicalCents
        DeductionClass.CHARITY -> charityCents
        DeductionClass.MOVING -> movingCents
        DeductionClass.PERSONAL -> 0.0
    }

    private data class FakeTrip(val day: LocalDate, val miles: Double, val cls: DeductionClass?)

    /** Repo.summarize, Repo.kt:80-144, reduced to the parts that make numbers. */
    private fun summarize(range: DayRange, trips: List<FakeTrip>): TaxSummary {
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
            val (r, wasEstimated) = rateFor(t.day.toEpochDay())
            if (wasEstimated) estimated = true
            val cents = r.centsFor(t.cls)
            val key = r.label to t.cls
            val prev = buckets[key]
            buckets[key] = Triple((prev?.first ?: 0.0) + t.miles, cents, t.cls)
        }

        val slices = buckets.map { (key, v) ->
            RateSlice(label = key.first, deductionClass = v.third, miles = v.first, centsPerMile = v.second)
        }.sortedWith(compareBy({ it.label }, { it.deductionClass.name }))

        return TaxSummary(
            range = range,
            totalMiles = inRange.sumOf { it.miles },
            businessMiles = business,
            personalMiles = personal,
            unclassifiedMiles = unclassified,
            otherMiles = other,
            slices = slices,
            tripCount = inRange.size,
            ratesEstimated = estimated
        )
    }

    // ---- 1. the split itself ---------------------------------------------------------

    @Test fun june30AndJuly1LandOnDifferentRates() {
        val jun30 = rateFor(LocalDate.of(2026, 6, 30).toEpochDay())
        val jul1 = rateFor(LocalDate.of(2026, 7, 1).toEpochDay())

        assertEquals("Jun 30 2026 must be an exact hit, not a fallback", false, jun30.second)
        assertEquals("Jul 1 2026 must be an exact hit, not a fallback", false, jul1.second)
        assertEquals(72.5, jun30.first.businessCents, 0.0)
        assertEquals(76.0, jul1.first.businessCents, 0.0)
        assertNotEquals(jun30.first.label, jul1.first.label)
    }

    @Test fun everyBoundaryDayOf2026GetsTheRightBusinessRate() {
        val cases = listOf(
            LocalDate.of(2026, 1, 1) to 72.5,
            LocalDate.of(2026, 6, 29) to 72.5,
            LocalDate.of(2026, 6, 30) to 72.5,
            LocalDate.of(2026, 7, 1) to 76.0,
            LocalDate.of(2026, 7, 2) to 76.0,
            LocalDate.of(2026, 12, 31) to 76.0,
            LocalDate.of(2025, 12, 31) to 70.0,
            LocalDate.of(2024, 1, 1) to 67.0
        )
        cases.forEach { (day, want) ->
            val (r, est) = rateFor(day.toEpochDay())
            assertEquals("$day should not be a fallback", false, est)
            assertEquals("$day business rate", want, r.businessCents, 0.0)
        }
    }

    @Test fun everyDayOf2026IsCoveredExactlyOnce() {
        var d = LocalDate.of(2026, 1, 1)
        while (d.year == 2026) {
            val hits = seeded.count { d.toEpochDay() in it.fromEpochDay..it.toEpochDay }
            assertEquals("$d must be covered by exactly one rate row", 1, hits)
            d = d.plusDays(1)
        }
    }

    // ---- 2. a full-year summary adds both halves ------------------------------------

    @Test fun fullYear2026SummaryAddsBothHalves() {
        // 6,000 business miles before the change, 6,000 after.
        val trips = listOf(
            FakeTrip(LocalDate.of(2026, 3, 15), 6_000.0, DeductionClass.BUSINESS),
            FakeTrip(LocalDate.of(2026, 9, 15), 6_000.0, DeductionClass.BUSINESS)
        )
        val s = summarize(DayRange.forYear(2026), trips)

        assertEquals("two rate slices expected", 2, s.slices.size)
        assertEquals("2026 Jan–Jun", s.slices[0].label)
        assertEquals("2026 Jul–Dec", s.slices[1].label)
        assertEquals(6_000.0, s.slices[0].miles, 1e-9)
        assertEquals(6_000.0, s.slices[1].miles, 1e-9)
        assertEquals(72.5, s.slices[0].centsPerMile, 0.0)
        assertEquals(76.0, s.slices[1].centsPerMile, 0.0)

        // 6000 * 0.725 = 4350.00 ; 6000 * 0.76 = 4560.00 ; total 8910.00
        assertEquals(4_350.00, s.slices[0].dollars, 0.005)
        assertEquals(4_560.00, s.slices[1].dollars, 0.005)
        assertEquals(8_910.00, s.deduction, 0.005)
        assertEquals(12_000.0, s.businessMiles, 1e-9)
        assertEquals(false, s.ratesEstimated)
    }

    @Test fun aTripOnEachSideOfMidnightJune30GetsItsOwnRate() {
        val trips = listOf(
            FakeTrip(LocalDate.of(2026, 6, 30), 100.0, DeductionClass.BUSINESS),
            FakeTrip(LocalDate.of(2026, 7, 1), 100.0, DeductionClass.BUSINESS)
        )
        val s = summarize(DayRange.forYear(2026), trips)
        assertEquals(2, s.slices.size)
        // 100 * 0.725 = 72.50 ; 100 * 0.76 = 76.00
        assertEquals(72.50, s.slices[0].dollars, 0.005)
        assertEquals(76.00, s.slices[1].dollars, 0.005)
        assertEquals(148.50, s.deduction, 0.005)
    }

    @Test fun medicalAndCharityStayOnTheirOwnLinesAndDoNotBorrowTheBusinessRate() {
        val trips = listOf(
            FakeTrip(LocalDate.of(2026, 2, 1), 100.0, DeductionClass.BUSINESS),
            FakeTrip(LocalDate.of(2026, 2, 1), 100.0, DeductionClass.MEDICAL),
            FakeTrip(LocalDate.of(2026, 2, 1), 100.0, DeductionClass.CHARITY),
            FakeTrip(LocalDate.of(2026, 8, 1), 100.0, DeductionClass.CHARITY)
        )
        val s = summarize(DayRange.forYear(2026), trips)
        val byKey = s.slices.associateBy { it.label to it.deductionClass }
        assertEquals(72.5, byKey["2026 Jan–Jun" to DeductionClass.BUSINESS]!!.centsPerMile, 0.0)
        assertEquals(20.5, byKey["2026 Jan–Jun" to DeductionClass.MEDICAL]!!.centsPerMile, 0.0)
        // Charity is fixed by statute at 14 cents and must not move at the July split.
        assertEquals(14.0, byKey["2026 Jan–Jun" to DeductionClass.CHARITY]!!.centsPerMile, 0.0)
        assertEquals(14.0, byKey["2026 Jul–Dec" to DeductionClass.CHARITY]!!.centsPerMile, 0.0)
    }

    // ---- 3. deduction classes --------------------------------------------------------

    @Test fun personalAndUnclassifiedAreExcludedButStillVisible() {
        val trips = listOf(
            FakeTrip(LocalDate.of(2026, 2, 1), 1_000.0, DeductionClass.BUSINESS),
            FakeTrip(LocalDate.of(2026, 2, 2), 500.0, DeductionClass.PERSONAL),
            FakeTrip(LocalDate.of(2026, 2, 3), 250.0, null),
            FakeTrip(LocalDate.of(2026, 2, 4), 100.0, DeductionClass.MEDICAL),
            FakeTrip(LocalDate.of(2026, 2, 5), 100.0, DeductionClass.CHARITY),
            FakeTrip(LocalDate.of(2026, 2, 6), 100.0, DeductionClass.MOVING)
        )
        val s = summarize(DayRange.forYear(2026), trips)

        assertEquals("personal miles must be reported", 500.0, s.personalMiles, 1e-9)
        assertEquals("unclassified miles must be reported", 250.0, s.unclassifiedMiles, 1e-9)
        assertEquals(1_000.0, s.businessMiles, 1e-9)
        assertEquals("medical + charity + moving", 300.0, s.otherMiles, 1e-9)
        assertEquals(2_050.0, s.totalMiles, 1e-9)

        assertTrue(
            "no personal slice may exist",
            s.slices.none { it.deductionClass == DeductionClass.PERSONAL }
        )
        // 1000*0.725 + 100*0.205 + 100*0.14 + 100*0.205 = 725 + 20.50 + 14 + 20.50 = 780.00
        assertEquals(780.00, s.deduction, 0.005)
    }

    /**
     * FINDING (reporting, not arithmetic): TaxSummary.deduction lumps business,
     * medical, charity and moving into one dollar figure. TaxesScreen.kt:88-100 prints
     * that single number under the label "Mileage deduction" right beside the business
     * mile count, and Jobs.kt:104 repeats it in the year-end notification. Those three
     * classes go on different tax forms — Schedule C vs Schedule A — so the headline
     * number is not a Schedule C figure.
     */
    @Test fun headlineDeductionSilentlyMixesScheduleCAndScheduleA() {
        val trips = listOf(
            FakeTrip(LocalDate.of(2026, 2, 1), 1_000.0, DeductionClass.BUSINESS),
            FakeTrip(LocalDate.of(2026, 2, 5), 2_000.0, DeductionClass.CHARITY)
        )
        val s = summarize(DayRange.forYear(2026), trips)
        val businessOnly = s.slices
            .filter { it.deductionClass == DeductionClass.BUSINESS }
            .sumOf { it.dollars }

        assertEquals("business alone", 725.00, businessOnly, 0.005)
        assertEquals("what the screen prints", 1_005.00, s.deduction, 0.005)
        assertNotEquals(
            "the headline is 280 dollars bigger than the Schedule C number",
            businessOnly, s.deduction, 0.005
        )
    }

    // ---- 4. the fallback, and where the two implementations disagree ------------------

    /** TripsVm.groups' centsFor, Vm.kt:192-203. */
    private fun vmCentsFor(day: Long, cls: DeductionClass): Double {
        val r = seeded.firstOrNull { day in it.fromEpochDay..it.toEpochDay }
            ?: seeded.filter { it.toEpochDay <= day }.maxByOrNull { it.toEpochDay }
            ?: return 0.0
        return r.centsFor(cls)
    }

    /**
     * FINDING: for a day before the rate table starts, Repo.rateFor (Repo.kt:62-63)
     * falls back to the EARLIEST row, while TripsVm.groups (Vm.kt:193-194) has no such
     * fallback and returns 0.0. The same trip is worth two different amounts on two
     * screens.
     */
    @Test fun tripsTabAndTaxesTabDisagreeOnPreTableDays() {
        val day = LocalDate.of(2023, 6, 1).toEpochDay()
        val fromRepo = rateFor(day).first.centsFor(DeductionClass.BUSINESS)
        val fromVm = vmCentsFor(day, DeductionClass.BUSINESS)

        assertEquals("Taxes tab and the export price it at the 2024 rate", 67.0, fromRepo, 0.0)
        assertEquals("Trips tab Daily/Weekly/Monthly prices it at zero", 0.0, fromVm, 0.0)

        // 1,000 miles on 2023-06-01: 670.00 on one screen, 0.00 on the other.
        assertEquals(670.00, 1_000.0 * fromRepo / 100.0, 0.005)
        assertEquals(0.00, 1_000.0 * fromVm / 100.0, 0.005)
    }

    @Test fun daysPastTheTableFallBackToTheLastRowAndAreFlagged() {
        val (r, est) = rateFor(LocalDate.of(2027, 3, 1).toEpochDay())
        assertEquals("2026 Jul–Dec", r.label)
        assertEquals(76.0, r.businessCents, 0.0)
        assertTrue("must be flagged as estimated", est)
    }

    /**
     * FINDING: slices are keyed by rate LABEL, so a range that runs past the end of the
     * table folds the fallback miles into the real "2026 Jul-Dec" line. The label on
     * screen then claims miles that were not driven in that window.
     */
    @Test fun fallbackMilesAreMergedIntoTheLastRealRateLine() {
        val trips = listOf(
            FakeTrip(LocalDate.of(2026, 12, 15), 500.0, DeductionClass.BUSINESS),
            FakeTrip(LocalDate.of(2027, 1, 15), 500.0, DeductionClass.BUSINESS)
        )
        val range = DayRange.of(LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 31))
        val s = summarize(range, trips)

        assertEquals("both halves collapse into one line", 1, s.slices.size)
        assertEquals("2026 Jul–Dec", s.slices[0].label)
        assertEquals(
            "the line labelled 'Jul-Dec 2026' reports 1,000 miles, half of them in 2027",
            1_000.0, s.slices[0].miles, 1e-9
        )
        assertTrue(s.ratesEstimated)
    }

    // ---- 5. the RateSlice arithmetic itself -------------------------------------------

    @Test fun rateSliceDollarsIsMilesTimesCentsOverAHundred() {
        assertEquals(
            "12,345.6 mi at 72.5 c",
            8_950.56, RateSlice("x", DeductionClass.BUSINESS, 12_345.6, 72.5).dollars, 0.005
        )
        assertEquals(
            "12,345.6 mi at 76 c",
            9_382.66, RateSlice("x", DeductionClass.BUSINESS, 12_345.6, 76.0).dollars, 0.005
        )
    }

    @Test fun profitIsExactInCents() {
        val s = TaxSummary(range = DayRange.forYear(2026), revenueCents = 4_832_117, expenseCents = 1_119_982)
        assertEquals(3_712_135L, s.profitCents)
    }
}
