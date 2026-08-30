package com.milelog.tracking

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

object Geo {

    const val METERS_PER_MILE = 1609.344

    fun metersToMiles(m: Double) = m / METERS_PER_MILE

    /** Straight-line miles between two points. */
    fun milesBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return metersToMiles(out[0].toDouble())
    }

    /** "35.9,-85.02;35.91,-85.03" -> list of pairs. */
    fun parsePath(csv: String): List<Pair<Double, Double>> =
        csv.split(';').mapNotNull { part ->
            val bits = part.split(',')
            if (bits.size != 2) return@mapNotNull null
            val la = bits[0].toDoubleOrNull() ?: return@mapNotNull null
            val lo = bits[1].toDoubleOrNull() ?: return@mapNotNull null
            la to lo
        }

    fun formatPoint(lat: Double, lon: Double) = String.format(Locale.US, "%.5f,%.5f", lat, lon)

    /**
     * Street address for a point. Returns "" when the device has no geocoder or no
     * network — the app never blocks on this.
     */
    suspend fun addressOf(context: Context, lat: Double, lon: Double): String =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext ""
            val geocoder = Geocoder(context, Locale.US)
            val result = withTimeoutOrNull(8000) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        suspendCancellableCoroutine { cont ->
                            geocoder.getFromLocation(lat, lon, 1) { list -> cont.resume(list) }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(lat, lon, 1)
                    }
                }.getOrNull()
            }
            val a = result?.firstOrNull() ?: return@withContext ""
            listOfNotNull(
                listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").ifBlank { null },
                a.locality,
                a.adminArea
            ).joinToString(", ")
        }
}
