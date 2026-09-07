package com.milelog.data

/**
 * The jobs a vehicle needs, with how often each is normally due.
 *
 * These are starting points, not gospel — intervals vary by vehicle and by whether the
 * oil is synthetic — so every one of them is editable once added.
 */
object ServiceTypes {

    data class Preset(
        val name: String,
        val everyMiles: Double? = null,
        val everyDays: Int? = null
    )

    val presets: List<Preset> = listOf(
        Preset("Oil change", everyMiles = 5_000.0, everyDays = 180),
        Preset("Oil filter", everyMiles = 5_000.0),
        Preset("Tire rotation", everyMiles = 6_000.0),
        Preset("New tires", everyMiles = 45_000.0),
        Preset("Wheel alignment", everyMiles = 12_000.0),
        Preset("Brake pads", everyMiles = 40_000.0),
        Preset("Brake fluid", everyMiles = 30_000.0, everyDays = 730),
        Preset("Spark plugs", everyMiles = 60_000.0),
        Preset("Air filter", everyMiles = 15_000.0),
        Preset("Cabin air filter", everyMiles = 15_000.0),
        Preset("Fuel filter", everyMiles = 30_000.0),
        Preset("Transmission fluid", everyMiles = 60_000.0),
        Preset("Transmission solenoids", everyMiles = 100_000.0),
        Preset("Differential fluid", everyMiles = 50_000.0),
        Preset("Coolant flush", everyMiles = 50_000.0, everyDays = 1_095),
        Preset("Serpentine belt", everyMiles = 90_000.0),
        Preset("Timing belt", everyMiles = 100_000.0),
        Preset("Battery", everyDays = 1_460),
        Preset("Wiper blades", everyDays = 365),
        Preset("Registration or inspection", everyDays = 365),
        Preset("Other", everyMiles = 10_000.0)
    )

    fun byName(name: String): Preset? = presets.firstOrNull { it.name.equals(name, true) }

    /** What a fresh install starts with: the jobs a working driver actually hits. */
    val startingSet = listOf("Oil change", "Tire rotation", "Air filter", "Brake pads")
}
