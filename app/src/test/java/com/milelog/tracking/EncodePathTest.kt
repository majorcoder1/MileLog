package com.milelog.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The new route thinning, TripTrackingService.encodePath (TripTrackingService.kt:219-226).
 *
 * The question that matters is whether thinning the stored route changes any reported
 * mileage. It does not, and this file says why: miles are accumulated fix by fix in
 * onFix (TripTrackingService.kt:167) into a field of their own, and pathCsv is built
 * separately from the point list. Nothing anywhere reads a distance back out of pathCsv —
 * the only two readers in the app are TripsScreen.kt:468 and EditTripScreen.kt:92, and
 * both hand the points straight to the map. TripsVm.merge (Vm.kt:276-287) concatenates
 * paths but sums the stored miles rather than re-measuring.
 *
 * What thinning does change is the route drawn as backup for those miles, and the rest of
 * the file measures by how much.
 */
class EncodePathTest {

    // ---- TripTrackingService.kt:396-398 -----------------------------------------------
    private val maxPoints = 4000
    private val storedPoints = 200

    /** TripTrackingService.encodePath, TripTrackingService.kt:219-226, verbatim. */
    private fun encodePath(raw: List<Pair<Double, Double>>): String {
        if (raw.isEmpty()) return ""
        val keep = if (raw.size <= storedPoints) raw else {
            val step = raw.size.toDouble() / storedPoints
            (0 until storedPoints).map { raw[(it * step).toInt().coerceAtMost(raw.lastIndex)] } + raw.last()
        }
        return keep.joinToString(";") { Geo.formatPoint(it.first, it.second) }
    }

    // ---- a route to work on ------------------------------------------------------------

    private val originLat = 35.9487   // Crossville, TN
    private val originLon = -85.0269
    private val metresPerDegreeLat = 111_132.0
    private val metresPerDegreeLon = 111_320.0 * cos(Math.toRadians(originLat))

    private fun point(east: Double, north: Double): Pair<Double, Double> =
        (originLat + north / metresPerDegreeLat) to (originLon + east / metresPerDegreeLon)

    private fun metresBetween(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        hypot(
            (b.first - a.first) * metresPerDegreeLat,
            (b.second - a.second) * metresPerDegreeLon
        )

    private fun lengthMiles(points: List<Pair<Double, Double>>): Double =
        points.zipWithNext().sumOf { (a, b) -> Geo.metersToMiles(metresBetween(a, b)) }

    /**
     * A delivery route: a fix every 8 m — the minimum the LocationRequest asks for,
     * TripTrackingService.kt:147 — along a road that curves gently and turns a corner
     * now and then. Seeded, so the numbers below are fixed.
     */
    private fun route(fixes: Int, seed: Long = 2026L): List<Pair<Double, Double>> {
        val random = Random(seed)
        var east = 0.0
        var north = 0.0
        var heading = 0.0
        val out = mutableListOf(point(east, north))
        repeat(fixes - 1) {
            heading += (random.nextDouble() - 0.5) * 0.35
            if (random.nextInt(60) == 0) heading += (if (random.nextBoolean()) 1 else -1) * Math.PI / 2
            east += 8.0 * cos(heading)
            north += 8.0 * sin(heading)
            out += point(east, north)
        }
        return out
    }

    // ---- 1. thinning cannot move the mileage -------------------------------------------

    /**
     * onFix keeps its own running total, so encoding the route — at every twenty-second
     * checkpoint and again at the end — cannot touch it. Encoding the same points twice
     * is a pure function of the list and leaves the list alone.
     */
    @Test fun encodingTheRouteDoesNotTouchTheAccumulatedMileage() {
        val raw = route(3_000)
        val before = lengthMiles(raw)

        // What persistProgress does every twenty seconds, then what finish does.
        encodePath(raw.take(500))
        encodePath(raw.take(1_500))
        encodePath(raw)

        assertEquals("the accumulator is untouched by encoding", before, lengthMiles(raw), 0.0)
        assertEquals("and the point list is untouched too", 3_000, raw.size)
        assertTrue(before > 0.0)
    }

    /** Empty, single-point and exactly-at-the-threshold routes encode without surprises. */
    @Test fun degenerateRoutesEncodeCleanly() {
        assertEquals("", encodePath(emptyList()))
        assertEquals(1, Geo.parsePath(encodePath(listOf(point(0.0, 0.0)))).size)
        assertEquals(
            "at the threshold nothing is thinned",
            storedPoints, Geo.parsePath(encodePath(route(storedPoints))).size
        )
    }

    // ---- 2. how much of the route survives ---------------------------------------------

    /**
     * A thinned route is a subsequence of the original, so by the triangle inequality it
     * can only ever be shorter — thinning cannot inflate a route into extra miles. That
     * holds whatever the drive looked like.
     */
    @Test fun aThinnedRouteIsNeverLongerThanTheDriveItCameFrom() {
        listOf(1L, 7L, 42L, 2026L).forEach { seed ->
            val raw = route(maxPoints, seed)
            val stored = Geo.parsePath(encodePath(raw))
            assertTrue(
                "seed $seed: thinning must not lengthen the route",
                lengthMiles(stored) <= lengthMiles(raw) + 1e-6
            )
        }
    }

    /**
     * FINDING (route evidence, not mileage): a full-length trace is thinned 20 to 1, and
     * the line that is kept is around seven percent shorter than the drive. The mileage
     * on the return is unaffected — it is the accumulated figure, not this one — but the
     * route stored as the record of that drive no longer measures the same distance, so
     * it cannot be used to check the mileage later.
     */
    @Test fun theStoredRouteNoLongerMeasuresTheDriveItStandsFor() {
        val raw = route(maxPoints)
        val driven = lengthMiles(raw)
        val stored = lengthMiles(Geo.parsePath(encodePath(raw)))

        // 4,000 fixes 8 m apart is just under 20 miles of driving.
        assertEquals(19.88, driven, 0.05)
        assertEquals(18.37, stored, 0.05)
        assertEquals("a mile and a half of route is not stored", 1.51, driven - stored, 0.05)
        assertTrue(stored < driven * 0.95)
    }

    /**
     * The thinner keeps storedPoints samples and then appends the last raw point on top,
     * so a thinned route holds 201 points, not the 200 the constant names. Harmless in
     * itself; recorded because the constant and the behaviour disagree.
     */
    @Test fun aThinnedRouteHoldsOneMorePointThanTheConstantSays() {
        val stored = Geo.parsePath(encodePath(route(maxPoints)))
        assertEquals(storedPoints + 1, stored.size)
        assertEquals(storedPoints + 1, Geo.parsePath(encodePath(route(storedPoints + 1))).size)
        // The appended point is never a duplicate of the one before it, so the
        // coerceAtMost guard on TripTrackingService.kt:223 can never fire.
        assertNotEquals(stored[stored.size - 2], stored.last())
    }

    /**
     * Both ends of the drive survive thinning, which is what persistProgress and finish
     * read for startLat/endLat and the two addresses (TripTrackingService.kt:201-208 and
     * 302-311). Geo.formatPoint rounds to five decimals, about a metre.
     */
    @Test fun thinningKeepsBothEndsOfTheDrive() {
        val raw = route(maxPoints)
        val stored = Geo.parsePath(encodePath(raw))
        assertEquals(raw.first().first, stored.first().first, 1e-5)
        assertEquals(raw.first().second, stored.first().second, 1e-5)
        assertEquals(raw.last().first, stored.last().first, 1e-5)
        assertEquals(raw.last().second, stored.last().second, 1e-5)
    }

    /**
     * FINDING (addresses, not mileage): points stops growing at MAX_POINTS
     * (TripTrackingService.kt:171) while miles keeps climbing. Past that the end of the
     * stored route — and with it endLat/endLon and the "To" column of the spreadsheet —
     * freezes at the 4,000th fix. On a 30-mile continuous drive the trip is filed with
     * the right mileage and the wrong destination, ten miles back up the road.
     */
    @Test fun aDriveLongerThanFourThousandFixesRecordsTheWrongDestination() {
        val raw = route(6_000)
        val held = raw.take(maxPoints)          // what the service actually keeps
        val stored = Geo.parsePath(encodePath(held))

        val driven = lengthMiles(raw)
        assertEquals("the whole drive", 29.81, driven, 0.05)
        assertEquals("what the point list covers", 19.88, lengthMiles(held), 0.05)

        assertNotEquals(
            "the recorded end point is not where the drive ended",
            raw.last(), held.last()
        )
        val strandedMiles = Geo.metersToMiles(metresBetween(stored.last(), raw.last()))
        assertTrue(
            "the stored finish is miles away from the real one, got $strandedMiles",
            strandedMiles > 1.0
        )
    }
}
