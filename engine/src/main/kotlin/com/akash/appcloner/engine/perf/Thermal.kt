package com.akash.appcloner.engine.perf

/** How a raw thermal-zone reading was interpreted. Vendors are not consistent. */
enum class TemperatureScale { MILLI_CELSIUS, DECI_CELSIUS, CELSIUS, UNRECOGNISED }

/** One thermal zone, with the raw value kept next to the converted one. */
data class ThermalZoneReading(
    val zone: String,
    val type: String,
    val raw: Long,
    val celsius: Double?,
    val scale: TemperatureScale,
)

data class ThermalSnapshot(
    val zones: List<ThermalZoneReading>,
) {
    /**
     * The hottest zone whose type looks like an SoC or CPU sensor.
     *
     * Deliberately not "the device temperature": a phone has a dozen zones and no single one is
     * the device. The UI shows this labelled with the zone it came from.
     */
    val hottestSocZone: ThermalZoneReading?
        get() = zones
            .filter { reading ->
                val t = reading.type.lowercase()
                SOC_ZONE_HINTS.any { it in t }
            }
            .filter { it.celsius != null }
            .maxByOrNull { it.celsius ?: Double.NEGATIVE_INFINITY }

    private companion object {
        val SOC_ZONE_HINTS = listOf("cpu", "soc", "tsens", "apc", "gpu", "skin", "ap_therm")
    }
}

/**
 * Converts a raw thermal-zone value to Celsius.
 *
 * Linux documents milli-Celsius, but shipped devices also report deci-Celsius and plain
 * Celsius, and some zones report values that are not temperatures at all. The scale is inferred
 * from magnitude and reported alongside the value, so a reading of 42 is never silently
 * presented as 0.042 degrees.
 */
object TemperatureConverter {

    private const val PLAUSIBLE_MIN_C = -40.0
    private const val PLAUSIBLE_MAX_C = 150.0

    /**
     * Magnitude bands, not a best-fit search.
     *
     * A search over divisors is ambiguous: a raw 450 is plausible both as 0.45 C and as 45.0 C,
     * and picking the first that fits a range silently prefers the wrong one. These bands
     * resolve it by what a running phone actually reports, and anything outside them is marked
     * [TemperatureScale.UNRECOGNISED] rather than converted.
     */
    fun convert(raw: Long): Pair<Double?, TemperatureScale> {
        val scale = when {
            raw >= 1_000L || raw <= -1_000L -> TemperatureScale.MILLI_CELSIUS
            raw >= 100L -> TemperatureScale.DECI_CELSIUS
            raw >= -40L -> TemperatureScale.CELSIUS
            else -> TemperatureScale.UNRECOGNISED
        }
        val celsius = when (scale) {
            TemperatureScale.MILLI_CELSIUS -> raw / 1000.0
            TemperatureScale.DECI_CELSIUS -> raw / 10.0
            TemperatureScale.CELSIUS -> raw.toDouble()
            TemperatureScale.UNRECOGNISED -> null
        }
        return if (celsius != null && celsius in PLAUSIBLE_MIN_C..PLAUSIBLE_MAX_C) {
            celsius to scale
        } else {
            null to TemperatureScale.UNRECOGNISED
        }
    }
}

/**
 * Names for [android.os.PowerManager] thermal statuses, kept here so the UI and the report
 * renderer use one vocabulary. Values are the platform's own constants, inlined so this file
 * stays free of Android imports and unit-testable.
 */
object ThermalStatusNames {

    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
    const val EMERGENCY = 5
    const val SHUTDOWN = 6

    fun describe(status: Int): String = when (status) {
        NONE -> "none"
        LIGHT -> "light"
        MODERATE -> "moderate"
        SEVERE -> "severe"
        CRITICAL -> "critical"
        EMERGENCY -> "emergency"
        SHUTDOWN -> "shutdown"
        else -> "unknown ($status)"
    }

    /** True once the platform is actively throttling, which is when frame pacing suffers. */
    fun isThrottling(status: Int): Boolean = status >= MODERATE
}
