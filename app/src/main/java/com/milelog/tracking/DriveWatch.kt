package com.milelog.tracking

/**
 * Decides whether a position report means a drive is under way.
 *
 * This is what stands in for Google's motion service on a phone that has no working
 * Google, so it is kept clear of Android for the same reason as [MileageMeter]: it can
 * be driven on a desk against known positions and the answer checked, rather than only
 * ever tested by going for a drive.
 *
 * Two signals, and whichever says driving first wins:
 *  - the speed the receiver reports, when it offers one it stands behind;
 *  - the speed implied by how far the phone has moved since the last fix worth trusting.
 *
 * The second one matters because the cheap fixes this runs on — one another app already
 * paid for — often arrive with no speed attached at all.
 *
 * Deliberately biased towards starting. A leg opened by mistake is thrown away at the
 * end for being under a tenth of a mile; a drive never noticed is mileage lost.
 */
class DriveWatch(private val settings: Settings = Settings()) {

    /** One position report, stripped to what the decision actually uses. */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val timeMillis: Long,
        /** Metres per second from the receiver's own Doppler, when it offers one. */
        val speedMps: Double? = null,
        /** How far out the receiver says that speed could be, in metres per second. */
        val speedAccuracyMps: Double? = null,
        val accuracyMeters: Double? = null
    )

    data class Settings(
        /** Above this you are in the car, not walking out to it. */
        val drivingMph: Double = 10.0,
        /** A fix fuzzier than this says nothing either way. */
        val maxAccuracyMeters: Double = 100.0,
        /** A reported speed with a wider error bar than this is not used. */
        val speedAccuracyLimitMps: Double = 4.0,
        /** How old the fix being measured from may be before it means nothing. */
        val referenceMaxAgeMillis: Long = 10 * 60 * 1000L,
        /** Under this, a move between two fixes is GPS wander rather than travel. */
        val minDisplacementMeters: Double = 40.0,
        /** An implied speed above this is a bad fix, not a car. */
        val maxPlausibleMph: Double = 150.0
    )

    enum class Verdict {
        /** Moving fast enough to be a vehicle. Open a leg. */
        DRIVING,
        /** A usable fix that is not going anywhere. */
        STILL,
        /** Too fuzzy to conclude anything from. The reference point is left alone. */
        UNUSABLE
    }

    /** The speed the last verdict was made on, in mph, so a decision can be explained. */
    var lastMph: Double? = null
        private set

    private var reference: Fix? = null

    fun reset() {
        reference = null
        lastMph = null
    }

    /**
     * Sets the point the next fix is measured against without passing judgement on this
     * one. Used when a leg ends: where the car was parked is the yardstick for noticing
     * that it has set off again.
     */
    fun anchor(fix: Fix) {
        if (usable(fix)) reference = fix
    }

    fun consider(fix: Fix): Verdict {
        if (!usable(fix)) return Verdict.UNUSABLE

        val mph = listOfNotNull(reportedMph(fix), impliedMph(fix)).maxOrNull()
        reference = fix
        lastMph = mph
        return if (mph != null && mph >= settings.drivingMph) Verdict.DRIVING else Verdict.STILL
    }

    private fun usable(fix: Fix): Boolean {
        val accuracy = fix.accuracyMeters
        return accuracy == null || accuracy <= settings.maxAccuracyMeters
    }

    private fun reportedMph(fix: Fix): Double? {
        val speed = fix.speedMps ?: return null
        if (speed < 0.0) return null
        val error = fix.speedAccuracyMps
        if (error != null && error > settings.speedAccuracyLimitMps) return null
        return speed * MileageMeter.MPH_PER_MPS
    }

    private fun impliedMph(fix: Fix): Double? {
        val ref = reference ?: return null
        val millis = fix.timeMillis - ref.timeMillis
        // Out of order, or so long ago that standing still and driving look the same.
        if (millis < 1000L || millis > settings.referenceMaxAgeMillis) return null

        val meters = MileageMeter.metersBetween(ref.latitude, ref.longitude, fix.latitude, fix.longitude)
        if (meters < settings.minDisplacementMeters) return null

        val mph = MileageMeter.metersToMiles(meters) / (millis / 3_600_000.0)
        return mph.takeIf { it <= settings.maxPlausibleMph }
    }
}
