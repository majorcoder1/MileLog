package com.milelog.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The mileage accumulator in TripTrackingService.onFix (TripTrackingService.kt:138-158).
 *
 * onFix takes an android.location.Location, which is a stub in a JVM unit test, so the
 * filter chain is transcribed below over a flat-earth metre grid. prev.distanceTo(loc)
 * becomes a plane distance, which is what it is over the tens of metres between two
 * consecutive fixes. Everything else — the 60 m accuracy gate, the 5 m minimum, the
 * 0.5 s clamp, the 120 mph gate, which branches update `last` — is copied verbatim, and
 * the miles are converted with the production Geo.metersToMiles.
 */
class OnFixMileageTest {

    /** x and y are metres on a local grid; t is Location.getTime(), millis. */
    private data class Fix(
        val x: Double, val y: Double, val t: Long,
        val accuracy: Float = 8f, val hasAccuracy: Boolean = true
    )

    private class Accumulator {
        var miles = 0.0
        var last: Fix? = null
        var accepted = 0
        var droppedByAccuracy = 0
        var droppedByMinDistance = 0
        var droppedBySpeed = 0

        /** TripTrackingService.kt:138-158, verbatim. */
        fun onFix(loc: Fix) {
            if (loc.hasAccuracy && loc.accuracy > 60f) { droppedByAccuracy++; return }
            val prev = last
            if (prev != null) {
                val meters = hypot(loc.x - prev.x, loc.y - prev.y).toFloat()
                val seconds = ((loc.t - prev.t) / 1000.0).coerceAtLeast(0.5)
                val mph = Geo.metersToMiles(meters.toDouble()) / (seconds / 3600.0)
                if (meters < 5f) { droppedByMinDistance++; return }
                if (mph > 120) { droppedBySpeed++; last = loc; return }
                miles += Geo.metersToMiles(meters.toDouble())
            }
            last = loc
            accepted++
        }
    }

    private fun run(fixes: List<Fix>): Accumulator =
        Accumulator().also { acc -> fixes.forEach { acc.onFix(it) } }

    // ---- overcount: nothing stops a parked phone from earning miles -------------------

    /**
     * FINDING: there is no speed floor and no stop detection. The LocationRequest at
     * TripTrackingService.kt:126-130 asks for a fix every time the phone moves 8 m
     * (setMinUpdateDistanceMeters(8f)), so ordinary GPS wander at a red light or in a
     * parking lot is delivered, clears the 5 m minimum, is far under 120 mph, and is
     * added to the trip.
     */
    @Test fun aPhoneStandingStillAccumulatesMiles() {
        // Three minutes at a light. A fix each time the phone wanders 9 m, which urban
        // multipath does easily; it never leaves a 9 m circle.
        val fixes = (0 until 36).map { i ->
            val at = if (i % 2 == 0) 0.0 else 9.0
            Fix(x = at, y = 0.0, t = 5_000L * i, accuracy = 20f)
        }
        val acc = run(fixes)

        assertEquals("every wander step was accepted", 36, acc.accepted)
        assertEquals("the 5 m minimum never fired", 0, acc.droppedByMinDistance)
        assertEquals("the 120 mph gate never fired", 0, acc.droppedBySpeed)
        // 35 hops of 9 m = 315 m = 0.1957 miles, standing still.
        assertEquals(0.1957, acc.miles, 0.0005)
        assertTrue("miles were invented out of nothing", acc.miles > 0.19)
    }

    /**
     * FINDING: the 5 m minimum at TripTrackingService.kt:146 is dead code. It sits
     * below the 8 m gate the LocationRequest already applies, so no fix that reaches
     * onFix can ever be under 5 m from the previous accepted one — and when the
     * accuracy filter holds `last` back, the gap is larger still.
     */
    @Test fun theFiveMetreMinimumCannotFireBehindAnEightMetreRequest() {
        val fixes = (0 until 50).map { i -> Fix(x = 8.0 * i, y = 0.0, t = 4_000L * i) }
        val acc = run(fixes)
        assertEquals("not one fix was under 5 m", 0, acc.droppedByMinDistance)
        assertEquals(50, acc.accepted)
    }

    // ---- undercount: the 120 mph gate deletes real distance ---------------------------

    /**
     * FINDING: TripTrackingService.kt:144 clamps the time between two fixes to a
     * minimum of half a second. When the provider hands over two fixes with the same
     * timestamp — batched delivery, or a replayed last-known fix — the clamp turns the
     * gap into 0.5 s, the speed comes out in the hundreds, and line 147 throws the
     * distance away while still moving `last` forward. The miles are gone for good.
     */
    @Test fun equalTimestampsDeleteTheDistanceBetweenTwoFixes() {
        val same = 1_000L
        // 500 m of genuine travel delivered as one pair carrying one timestamp.
        val acc = run(listOf(Fix(0.0, 0.0, same), Fix(500.0, 0.0, same)))

        assertEquals("the segment was discarded", 1, acc.droppedBySpeed)
        assertEquals("no miles recorded for 500 m of driving", 0.0, acc.miles, 1e-12)

        // What the same 500 m is worth with an honest 20 s gap.
        val honest = run(listOf(Fix(0.0, 0.0, 0), Fix(500.0, 0.0, 20_000)))
        assertEquals(0.3107, honest.miles, 0.0005)
    }

    /** The cutoff is arbitrary: with a clamped gap, 26 m survives and 27 m does not. */
    @Test fun theClampMakesTheCutoffAnArbitraryTwentySixMetres() {
        val kept = run(listOf(Fix(0.0, 0.0, 500), Fix(26.0, 0.0, 500)))
        val lost = run(listOf(Fix(0.0, 0.0, 500), Fix(27.0, 0.0, 500)))
        assertEquals(0, kept.droppedBySpeed)
        assertEquals(1, lost.droppedBySpeed)
        assertTrue(kept.miles > 0.0)
        assertEquals(0.0, lost.miles, 1e-12)
    }

    /** A clock that steps backwards has the same effect, for the same reason. */
    @Test fun aBackwardsTimestampAlsoDeletesTheSegment() {
        val acc = run(listOf(Fix(0.0, 0.0, 60_000), Fix(800.0, 0.0, 30_000)))
        assertEquals(1, acc.droppedBySpeed)
        assertEquals(0.0, acc.miles, 1e-12)
    }

    // ---- the accuracy filter is the one that behaves ----------------------------------

    /**
     * Good behaviour, pinned so it does not regress: a run of fuzzy fixes is skipped
     * without moving `last`, so the distance across the gap is still counted when a
     * clean fix arrives.
     */
    @Test fun fuzzyFixesAreSkippedWithoutLosingTheDistanceAcrossThem() {
        val fixes = listOf(
            Fix(0.0, 0.0, 0, accuracy = 10f),
            Fix(200.0, 0.0, 10_000, accuracy = 90f),   // dropped
            Fix(400.0, 0.0, 20_000, accuracy = 120f),  // dropped
            Fix(600.0, 0.0, 30_000, accuracy = 10f)    // 600 m from the last good fix
        )
        val acc = run(fixes)
        assertEquals(2, acc.droppedByAccuracy)
        assertEquals("the full 600 m is kept", Geo.metersToMiles(600.0), acc.miles, 1e-9)
    }

    // ---- undercount: straight lines between fixes cut every corner ---------------------

    /**
     * Sampling every 4 s and joining the dots with straight lines under-measures any
     * turn. A right-angle corner taken between two fixes loses 29 percent of that
     * corner's distance.
     */
    @Test fun cornersAreCutBecauseOnlyStraightLinesAreSummed() {
        // Real path: 100 m east, then 100 m north = 200 m driven.
        val sampledAtTheCorner = run(
            listOf(Fix(0.0, 0.0, 0), Fix(100.0, 0.0, 8_000), Fix(100.0, 100.0, 16_000))
        )
        val sampledEitherSide = run(
            listOf(Fix(0.0, 0.0, 0), Fix(100.0, 100.0, 16_000))
        )
        assertEquals(Geo.metersToMiles(200.0), sampledAtTheCorner.miles, 1e-9)
        assertEquals(Geo.metersToMiles(141.42), sampledEitherSide.miles, 1e-4)
        assertTrue(sampledEitherSide.miles < sampledAtTheCorner.miles * 0.72)
    }

    // ---- undercount: short trips are deleted whole -------------------------------------

    /**
     * TripTrackingService.kt:230 deletes any finished trip under MIN_MILES. A real
     * 0.09 mile delivery hop is removed from the database rather than kept.
     */
    @Test fun aTripJustUnderATenthOfAMileIsDeleted() {
        val minMiles = 0.1
        val recorded = 0.09
        assertTrue("this trip is thrown away", recorded < minMiles)
        // 40 such hops over a year at 76 cents is 2.74 dollars — small, but it is also
        // 3.6 miles of evidence that never reaches the log.
        assertEquals(2.736, 40 * recorded * 76.0 / 100.0, 0.001)
    }

    // ---- what it adds up to -------------------------------------------------------------

    /**
     * A worked shift. Sixty stops long enough to wander, plus honest driving. The
     * phantom miles from the stops are what the return would claim.
     */
    @Test fun aShiftOfStopsInventsMeasurableMiles() {
        var phantom = 0.0
        repeat(60) {
            // A 90 second stop, a fix every 5 s once the phone has wandered 9 m.
            val acc = run((0 until 18).map { i ->
                Fix(x = if (i % 2 == 0) 0.0 else 9.0, y = 0.0, t = 5_000L * i, accuracy = 20f)
            })
            phantom += acc.miles
        }
        // 60 stops * 17 hops * 9 m = 9,180 m = 5.70 miles a day that were never driven.
        assertEquals(5.704, phantom, 0.01)
        val perYear = phantom * 250
        assertEquals(1_426.0, perYear, 3.0)
        // At the second-half 2026 business rate.
        assertEquals(1_083.8, perYear * 76.0 / 100.0, 3.0)
    }
}
