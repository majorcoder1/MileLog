package com.milelog.tracking

import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * A virtual automobile.
 *
 * Builds a drive out of legs — cruise at a speed for a distance, sit at a light, park —
 * and emits the position reports a phone would have produced along the way, complete
 * with the receiver noise a real one has. Because the road is laid out rather than
 * recorded, its length is known exactly, so what the app measures can be compared
 * against the truth instead of against another app's guess.
 */
class VirtualDrive(
    startLat: Double = 35.9487,
    startLon: Double = -85.0269,
    private val fixIntervalMillis: Long = 3000,
    startTimeMillis: Long = 1_756_000_000_000,
    seed: Int = 42
) {
    private val random = Random(seed)
    private var lat = startLat
    private var lon = startLon
    private var clock = startTimeMillis
    private var heading = 0.0

    private val fixes = mutableListOf<MileageMeter.Fix>()
    /** Distance actually travelled along the road, in metres. Ground truth. */
    var trueMeters: Double = 0.0
        private set

    val trueMiles: Double get() = MileageMeter.metersToMiles(trueMeters)
    fun fixes(): List<MileageMeter.Fix> = fixes.toList()

    /**
     * Drives [miles] at [mph]. [curvinessDegrees] turns the wheel a little between
     * fixes — a straight interstate is 0, a back road is 8 or so. Curves matter: a
     * tracker that samples too rarely cuts the corners off them and under-reports.
     */
    fun cruise(miles: Double, mph: Double, curvinessDegrees: Double = 0.0): VirtualDrive {
        val metres = miles * 1609.344
        val metresPerSecond = mph / MileageMeter.MPH_PER_MPS
        val step = metresPerSecond * (fixIntervalMillis / 1000.0)
        var travelled = 0.0
        while (travelled < metres) {
            val leg = minOf(step, metres - travelled)
            heading += (random.nextDouble() - 0.5) * 2 * curvinessDegrees
            advance(leg, heading)
            travelled += leg
            trueMeters += leg
            clock += fixIntervalMillis
            emit(speedMps = metresPerSecond, moving = true)
        }
        return this
    }

    /**
     * Sits still for [seconds]. The car does not move; the receiver still reports, and
     * what it reports wanders by a few metres. That wander is not distance, and a
     * tracker that counts it invents miles.
     */
    fun idle(seconds: Int, driftMeters: Double = 4.0): VirtualDrive {
        val ticks = (seconds * 1000L / fixIntervalMillis).toInt()
        val anchorLat = lat
        val anchorLon = lon
        repeat(ticks) {
            lat = anchorLat + metersToLat((random.nextDouble() - 0.5) * 2 * driftMeters)
            lon = anchorLon + metersToLon((random.nextDouble() - 0.5) * 2 * driftMeters, anchorLat)
            clock += fixIntervalMillis
            emit(speedMps = 0.0, moving = false)
        }
        lat = anchorLat
        lon = anchorLon
        return this
    }

    /** A delivery run: drive there, sit while the order is handed over, drive on. */
    fun delivery(miles: Double, mph: Double = 32.0, waitSeconds: Int = 90): VirtualDrive =
        cruise(miles, mph, curvinessDegrees = 6.0).idle(waitSeconds)

    /** A red light. Long enough to matter, short enough not to end the leg. */
    fun redLight(seconds: Int = 45): VirtualDrive = idle(seconds)

    /** A gap in the stream: a tunnel, a dead battery moment, the phone losing the sky. */
    fun blackout(seconds: Int, milesCoveredMeanwhile: Double, mph: Double): VirtualDrive {
        val metres = milesCoveredMeanwhile * 1609.344
        advance(metres, heading)
        trueMeters += metres
        clock += seconds * 1000L
        emit(speedMps = mph / MileageMeter.MPH_PER_MPS, moving = true)
        return this
    }

    private fun emit(speedMps: Double, moving: Boolean) {
        // Real receivers are never exact. A few metres of scatter on every fix.
        val noise = if (moving) 3.0 else 2.0
        fixes += MileageMeter.Fix(
            latitude = lat + metersToLat((random.nextDouble() - 0.5) * noise),
            longitude = lon + metersToLon((random.nextDouble() - 0.5) * noise, lat),
            timeMillis = clock,
            speedMps = speedMps,
            accuracyMeters = if (moving) 8.0 else 12.0
        )
    }

    private fun advance(meters: Double, headingDegrees: Double) {
        val radians = Math.toRadians(headingDegrees)
        lat += metersToLat(meters * cos(radians))
        lon += metersToLon(meters * Math.sin(radians), lat)
    }

    private fun metersToLat(meters: Double) = meters / 111_320.0
    private fun metersToLon(meters: Double, atLat: Double) =
        meters / (111_320.0 * cos(Math.toRadians(atLat)))

    /** Minutes of wall clock the drive took, for reporting. */
    fun elapsedMinutes(startMillis: Long = 1_756_000_000_000): Long =
        ((clock - startMillis) / 60000.0).roundToLong()
}
