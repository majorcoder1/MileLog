package com.milelog.tracking

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A day's work around Cumberland County, Tennessee, over the roads actually driven:
 * Crossville out to Fairfield Glade, down to Crab Orchard, west through Pleasant Hill,
 * up to Monterey and back. Waypoints are real places, so the route has a real shape and
 * a real length, and the app can be held against it.
 */
class CumberlandCountyTest {

    private object Places {
        val crossville = 35.9487 to -85.0269
        val fairfieldGlade = 35.9895 to -84.8783
        val crabOrchard = 35.9134 to -84.8858
        val cumberlandPark = 35.8973 to -84.9986
        val lakeTansi = 35.8748 to -85.0847
        val pleasantHill = 35.9773 to -85.1936
        val monterey = 36.1470 to -85.2652
    }

    private fun VirtualDrive.stopAt(place: Pair<Double, Double>, mph: Double, waitSeconds: Int) =
        to(place.first, place.second, mph).idle(waitSeconds)

    @Test
    fun aDayAroundCumberlandCounty() {
        val route = VirtualDrive(
            startLat = Places.crossville.first,
            startLon = Places.crossville.second
        )

        // Morning: deliveries out east, town speeds with lights.
        route.stopAt(Places.fairfieldGlade, mph = 45.0, waitSeconds = 110)
        route.stopAt(Places.crabOrchard, mph = 50.0, waitSeconds = 95)
        route.redLight(50)
        route.stopAt(Places.cumberlandPark, mph = 40.0, waitSeconds = 120)

        // Midday: back through Crossville, a longer wait for lunch orders.
        route.stopAt(Places.crossville, mph = 42.0, waitSeconds = 100)
        route.stopAt(Places.lakeTansi, mph = 38.0, waitSeconds = 130)

        // Afternoon: the long west and north legs on the highway.
        route.stopAt(Places.pleasantHill, mph = 55.0, waitSeconds = 90)
        route.stopAt(Places.monterey, mph = 62.0, waitSeconds = 115)

        // Home.
        route.to(Places.crossville.first, Places.crossville.second, mph = 60.0)

        val meter = MileageMeter()
        var stationary = 0
        var legEnds = 0
        route.fixes().forEach { fix ->
            when (meter.accept(fix).outcome) {
                MileageMeter.Outcome.STATIONARY -> stationary++
                MileageMeter.Outcome.LEG_ENDED -> legEnds++
                else -> Unit
            }
        }

        val error = meter.miles - route.trueMiles
        val percent = error / route.trueMiles * 100

        println("=== Cumberland County, a full day ===")
        println("  route      Crossville, Fairfield Glade, Crab Orchard, Cumberland Mountain,")
        println("             Crossville, Lake Tansi, Pleasant Hill, Monterey, Crossville")
        println("  true       %.2f miles".format(route.trueMiles))
        println("  MileLog    %.2f miles".format(meter.miles))
        println("  error      %+.2f miles (%+.2f%%)".format(error, percent))
        println("  fixes      %d counted, %d while stopped".format(meter.fixesCounted, stationary))
        println("  dropped    %.2f miles over %d gaps".format(meter.droppedMiles, meter.droppedLegs))
        println("  duration   %d minutes of driving and waiting".format(route.elapsedMinutes()))
        println("  stops      %d long enough to end a leg".format(legEnds))

        assertTrue("no route was generated", route.trueMiles > 50)
        assertTrue("off by %.2f%%".format(percent), abs(percent) < 4.0)
    }
}
