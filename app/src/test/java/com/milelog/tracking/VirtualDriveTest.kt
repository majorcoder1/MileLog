package com.milelog.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Drives the virtual automobile through MileLog's real mileage code and checks the
 * reading against a road whose length is known exactly.
 *
 * Every figure printed here is measured-versus-true, not measured-versus-another-app.
 */
class VirtualDriveTest {

    private data class Reading(
        val trueMiles: Double,
        val measuredMiles: Double,
        val counted: Int,
        val stationary: Int,
        val rejected: Int,
        val legEnds: Int,
        val droppedMiles: Double
    ) {
        val errorMiles get() = measuredMiles - trueMiles
        val errorPercent get() = if (trueMiles > 0) errorMiles / trueMiles * 100 else 0.0
    }

    private fun drive(name: String, route: VirtualDrive): Reading {
        val meter = MileageMeter()
        var stationary = 0
        var rejected = 0
        var legEnds = 0
        route.fixes().forEach { fix ->
            when (meter.accept(fix).outcome) {
                MileageMeter.Outcome.STATIONARY -> stationary++
                MileageMeter.Outcome.REJECTED_ACCURACY -> rejected++
                MileageMeter.Outcome.LEG_ENDED -> legEnds++
                else -> Unit
            }
        }
        val r = Reading(
            route.trueMiles, meter.miles, meter.fixesCounted,
            stationary, rejected, legEnds, meter.droppedMiles
        )
        println(
            "%-34s true %7.2f   measured %7.2f   error %+6.2f mi (%+5.1f%%)   fixes %4d   still %4d   dropped %.2f"
                .format(name, r.trueMiles, r.measuredMiles, r.errorMiles, r.errorPercent, r.counted, r.stationary, r.droppedMiles)
        )
        return r
    }

    @Test
    fun aStraightHighwayRunIsMeasuredAccurately() {
        val r = drive("Interstate, 40 mi at 70", VirtualDrive().cruise(40.0, mph = 70.0))
        assertTrue("off by ${r.errorPercent}%", abs(r.errorPercent) < 2.0)
    }

    @Test
    fun aWindingBackRoadIsMeasuredAccurately() {
        val r = drive("Back road, 15 mi at 45", VirtualDrive().cruise(15.0, mph = 45.0, curvinessDegrees = 8.0))
        assertTrue("off by ${r.errorPercent}%", abs(r.errorPercent) < 3.0)
    }

    @Test
    fun sittingAtRedLightsAddsNoMiles() {
        // The phantom-mileage case: an hour of stop lights on a five mile route.
        val route = VirtualDrive()
        repeat(12) { route.cruise(0.42, mph = 28.0, curvinessDegrees = 4.0).redLight(45) }
        val r = drive("City, 5 mi with 12 red lights", route)
        assertTrue("stops were not recognised", r.stationary > 100)
        assertTrue("red lights invented ${r.errorMiles} miles", r.errorPercent < 3.0)
    }

    @Test
    fun aParkedPhoneNeverGainsMiles() {
        // Two hours parked outside a restaurant. This must read zero.
        val r = drive("Parked for 2 hours", VirtualDrive().idle(seconds = 7200, driftMeters = 6.0))
        assertEquals("a parked car gained miles", 0.0, r.measuredMiles, 0.001)
    }

    @Test
    fun aDeliveryDayIsMeasuredAccurately() {
        // What the app is actually for: twenty runs with a wait at each one.
        val route = VirtualDrive()
        repeat(20) { route.delivery(miles = 3.4, mph = 34.0, waitSeconds = 100) }
        val r = drive("Delivery day, 20 runs", route)
        assertTrue("off by ${r.errorPercent}%", abs(r.errorPercent) < 4.0)
        assertTrue("legs never ended at a stop", r.legEnds == 0)
    }

    @Test
    fun aLongStopEndsTheLeg() {
        val route = VirtualDrive().cruise(4.0, mph = 40.0).idle(seconds = 300)
        val r = drive("Run then a five minute stop", route)
        assertTrue("a five minute stop did not end the leg", r.legEnds > 0)
    }

    @Test
    fun aGapInTheSignalIsReportedNotSwallowed() {
        // Six miles covered while the phone had no sky. That distance cannot honestly
        // be measured, so it must be dropped AND counted, never silently absorbed.
        val route = VirtualDrive()
            .cruise(2.0, mph = 55.0)
            .blackout(seconds = 20, milesCoveredMeanwhile = 6.0, mph = 55.0)
            .cruise(2.0, mph = 55.0)
        val r = drive("Signal lost for 6 miles", route)
        assertTrue("the gap was not reported", r.droppedMiles > 5.0)
        assertTrue("the gap was counted as if measured", r.measuredMiles < r.trueMiles - 5.0)
    }

    @Test
    fun theWholeDayAddsUp() {
        val route = VirtualDrive()
            .cruise(12.0, mph = 62.0)
            .redLight(60)
            .cruise(3.0, mph = 30.0, curvinessDegrees = 7.0)
        repeat(8) { route.delivery(miles = 2.6, mph = 30.0, waitSeconds = 120) }
        route.cruise(14.0, mph = 65.0)
        val r = drive("A full working day", route)
        println("   -> %.1f true miles over %d minutes".format(r.trueMiles, route.elapsedMinutes()))
        assertTrue("a day's driving was off by ${r.errorPercent}%", abs(r.errorPercent) < 4.0)
    }
}
