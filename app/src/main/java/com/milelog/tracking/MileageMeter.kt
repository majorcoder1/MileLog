package com.milelog.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The mileage arithmetic, on its own.
 *
 * This is the part that decides what goes on a tax return, so it is kept clear of
 * Android: no Location, no service, no clock of its own. That means a whole day of
 * driving can be run through it on a desk, against a route whose true length is known
 * to the metre, and the answer checked. See VirtualDriveTest.
 */
class MileageMeter(
    private val settings: Settings = Settings()
) {
    /** One position report, stripped to what the arithmetic actually uses. */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        /** Milliseconds since the epoch, as the receiver reported it. */
        val timeMillis: Long,
        /** Metres per second from the receiver's own Doppler, when it offers one. */
        val speedMps: Double? = null,
        /** Reported horizontal accuracy in metres, when there is one. */
        val accuracyMeters: Double? = null
    )

    data class Settings(
        val maxAccuracyMeters: Double = 60.0,
        val stoppedMph: Double = 2.0,
        val noiseMeters: Double = 30.0,
        val minSegmentMeters: Double = 5.0,
        val maxPlausibleMph: Double = 120.0,
        /** Still for this long and the leg is finished. */
        val stopSplitMillis: Long = 2 * 60 * 1000L
    )

    /** What a fix did to the total. */
    enum class Outcome {
        /** Counted; [Result.miles] grew. */
        COUNTED,
        /** First fix of the leg; nothing to measure from yet. */
        ANCHORED,
        /** Too fuzzy to trust. */
        REJECTED_ACCURACY,
        /** Not moving. Wander at a stop light is not distance. */
        STATIONARY,
        /** Moved, but less than the floor. */
        BELOW_FLOOR,
        /** A jump too fast to be real. Re-anchored, distance lost. */
        IMPLAUSIBLE,
        /** Stationary long enough that the leg should end here. */
        LEG_ENDED
    }

    data class Result(val outcome: Outcome, val miles: Double, val addedMiles: Double)

    var miles: Double = 0.0
        private set
    var fixesCounted: Int = 0
        private set
    var droppedLegs: Int = 0
        private set
    var droppedMiles: Double = 0.0
        private set

    private var last: Fix? = null
    private var lastMovedAtMillis: Long = 0

    fun reset() {
        miles = 0.0
        fixesCounted = 0
        droppedLegs = 0
        droppedMiles = 0.0
        last = null
        lastMovedAtMillis = 0
    }

    /**
     * Feeds one fix in. [Result.outcome] says what became of it, which is what makes a
     * disagreement between the true distance and the measured one explainable rather
     * than merely visible.
     */
    fun accept(fix: Fix): Result {
        val accuracy = fix.accuracyMeters
        if (accuracy != null && accuracy > settings.maxAccuracyMeters) {
            return Result(Outcome.REJECTED_ACCURACY, miles, 0.0)
        }

        val previous = last
        if (previous == null) {
            last = fix
            lastMovedAtMillis = fix.timeMillis
            return Result(Outcome.ANCHORED, miles, 0.0)
        }

        val meters = metersBetween(previous, fix)
        val seconds = ((fix.timeMillis - previous.timeMillis) / 1000.0).coerceAtLeast(0.5)
        val mph = metersToMiles(meters) / (seconds / 3600.0)
        val speedMph = fix.speedMps?.times(MPH_PER_MPS) ?: mph

        if (speedMph < settings.stoppedMph && meters < settings.noiseMeters) {
            val stillFor = fix.timeMillis - lastMovedAtMillis
            return if (stillFor >= settings.stopSplitMillis) {
                Result(Outcome.LEG_ENDED, miles, 0.0)
            } else {
                Result(Outcome.STATIONARY, miles, 0.0)
            }
        }

        if (meters < settings.minSegmentMeters) {
            return Result(Outcome.BELOW_FLOOR, miles, 0.0)
        }

        if (seconds >= 1.0 && mph > settings.maxPlausibleMph) {
            droppedLegs++
            droppedMiles += metersToMiles(meters)
            last = fix
            return Result(Outcome.IMPLAUSIBLE, miles, 0.0)
        }

        val added = metersToMiles(meters)
        miles += added
        fixesCounted++
        last = fix
        lastMovedAtMillis = fix.timeMillis
        return Result(Outcome.COUNTED, miles, added)
    }

    companion object {
        const val MPH_PER_MPS = 2.236936
        private const val EARTH_RADIUS_METERS = 6_371_008.8
        private const val METERS_PER_MILE = 1609.344

        fun metersToMiles(meters: Double) = meters / METERS_PER_MILE

        /** Great-circle distance. Matches Location.distanceBetween closely enough at these scales. */
        fun metersBetween(a: Fix, b: Fix): Double =
            metersBetween(a.latitude, a.longitude, b.latitude, b.longitude)

        fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val s = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(s)))
        }
    }
}
