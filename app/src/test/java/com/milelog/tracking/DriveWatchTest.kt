package com.milelog.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Google-free drive detector, driven on a desk.
 *
 * Positions are real ones around Crossville, offset by metres from the Cumberland County
 * courthouse, so the distances are the distances the phone would actually measure.
 */
class DriveWatchTest {

    private val lat0 = 35.94870
    private val lon0 = -85.02690
    /** Metres per degree at this latitude. */
    private val perDegLat = 111_132.0
    private val perDegLon = 90_148.0

    private fun fix(
        north: Double,
        east: Double,
        seconds: Double,
        speedMps: Double? = null,
        speedAccuracyMps: Double? = null,
        accuracyMeters: Double? = 8.0
    ) = DriveWatch.Fix(
        latitude = lat0 + north / perDegLat,
        longitude = lon0 + east / perDegLon,
        timeMillis = START + (seconds * 1000).toLong(),
        speedMps = speedMps,
        speedAccuracyMps = speedAccuracyMps,
        accuracyMeters = accuracyMeters
    )

    @Test fun `a phone sitting on a table is not driving`() {
        val watch = DriveWatch()
        // Twenty metres of wander, three minutes apart, the whole evening.
        assertEquals(DriveWatch.Verdict.STILL, watch.consider(fix(0.0, 0.0, 0.0, speedMps = 0.0)))
        assertEquals(DriveWatch.Verdict.STILL, watch.consider(fix(12.0, -9.0, 180.0, speedMps = 0.0)))
        assertEquals(DriveWatch.Verdict.STILL, watch.consider(fix(-7.0, 15.0, 360.0, speedMps = 0.0)))
        assertEquals(DriveWatch.Verdict.STILL, watch.consider(fix(3.0, 4.0, 540.0)))
    }

    @Test fun `a speed straight off the receiver is enough on its own`() {
        val watch = DriveWatch()
        // 13.4 m/s is 30 mph, and it is the first fix, so there is nothing to compare to.
        assertEquals(DriveWatch.Verdict.DRIVING, watch.consider(fix(0.0, 0.0, 0.0, speedMps = 13.4)))
        assertEquals(30.0, watch.lastMph!!, 0.1)
    }

    @Test fun `ten miles an hour is the line`() {
        val under = DriveWatch()
        // 4.4 m/s is 9.84 mph.
        assertEquals(DriveWatch.Verdict.STILL, under.consider(fix(0.0, 0.0, 0.0, speedMps = 4.4)))
        val over = DriveWatch()
        assertEquals(DriveWatch.Verdict.DRIVING, over.consider(fix(0.0, 0.0, 0.0, speedMps = 4.5)))
    }

    @Test fun `a fix with no speed is judged on how far it has come`() {
        val watch = DriveWatch()
        watch.consider(fix(0.0, 0.0, 0.0))
        // A mile of Highway 70 in a minute: 60 mph, and not a speed in sight.
        assertEquals(DriveWatch.Verdict.DRIVING, watch.consider(fix(1609.0, 0.0, 60.0)))
        assertEquals(60.0, watch.lastMph!!, 1.0)
    }

    @Test fun `walking to the car is not driving`() {
        val watch = DriveWatch()
        watch.consider(fix(0.0, 0.0, 0.0))
        // Fifty metres across a car park in forty seconds: 2.8 mph.
        assertEquals(DriveWatch.Verdict.STILL, watch.consider(fix(50.0, 0.0, 40.0)))
    }

    @Test fun `a fuzzy fix is ignored and never becomes the yardstick`() {
        val watch = DriveWatch()
        watch.consider(fix(0.0, 0.0, 0.0))
        // A cell-tower fix half a kilometre out, in the wrong direction.
        assertEquals(
            DriveWatch.Verdict.UNUSABLE,
            watch.consider(fix(-3000.0, 0.0, 30.0, accuracyMeters = 500.0))
        )
        assertNull(watch.lastMph)
        // Still measured from the first fix, so thirty metres in a minute is standing still.
        assertEquals(DriveWatch.Verdict.STILL, watch.consider(fix(30.0, 0.0, 60.0)))
    }

    @Test fun `a reported speed with a wide error bar is not trusted`() {
        val watch = DriveWatch()
        // 20 m/s is 45 mph, but the receiver says it could be out by 10 m/s, and the
        // phone has not moved, so this is a receiver guessing rather than a car.
        assertEquals(
            DriveWatch.Verdict.STILL,
            watch.consider(fix(0.0, 0.0, 0.0, speedMps = 20.0, speedAccuracyMps = 10.0))
        )
    }

    @Test fun `distance from hours ago proves nothing`() {
        val watch = DriveWatch()
        watch.consider(fix(0.0, 0.0, 0.0))
        // Parked at home last night, first fix of the morning is across town. That is a
        // stale yardstick, not a drive: the next fix a few minutes later will decide it.
        assertEquals(DriveWatch.Verdict.STILL, watch.consider(fix(8000.0, 0.0, 12.0 * 3600)))
    }

    @Test fun `an impossible jump is not a car`() {
        val watch = DriveWatch()
        watch.consider(fix(0.0, 0.0, 0.0))
        // Five hundred miles in a minute.
        assertEquals(DriveWatch.Verdict.STILL, watch.consider(fix(800_000.0, 0.0, 60.0)))
    }

    @Test fun `leaving where the car was parked is a new drive`() {
        val watch = DriveWatch()
        // The leg ended here, so this is the yardstick without being judged itself.
        watch.anchor(fix(0.0, 0.0, 0.0, speedMps = 0.0))
        assertNull(watch.lastMph)
        // Three hundred metres a minute later: 11 mph, pulling out of the car park.
        assertEquals(DriveWatch.Verdict.DRIVING, watch.consider(fix(300.0, 0.0, 60.0)))
    }

    @Test fun `a reset forgets where the phone was`() {
        val watch = DriveWatch()
        watch.consider(fix(0.0, 0.0, 0.0))
        watch.reset()
        assertEquals(DriveWatch.Verdict.STILL, watch.consider(fix(1609.0, 0.0, 60.0)))
    }

    private companion object {
        /** 3 September 2026, 09:00 UTC-ish. Only the gaps matter. */
        const val START = 1_788_000_000_000L
    }
}
