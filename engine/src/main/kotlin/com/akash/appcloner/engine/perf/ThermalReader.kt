package com.akash.appcloner.engine.perf

import android.content.Context
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.akash.appcloner.engine.root.RootProvider

/**
 * Device thermal state, from two independent places: the platform's own throttling status,
 * which needs no permission, and the raw sysfs zones, which some vendors restrict.
 */
class ThermalReader(
    private val context: Context,
    rootProvider: RootProvider = RootProvider(),
    private val files: PrivilegedFiles = PrivilegedFiles(rootProvider),
) {

    private companion object {
        const val THERMAL_ROOT = "/sys/class/thermal"
    }

    /**
     * The platform's throttling status. This is the figure that actually explains a frame-rate
     * drop, because it is what the system acts on.
     */
    fun thermalStatus(): Metric<Int> {
        val pm = ContextCompat.getSystemService(context, PowerManager::class.java)
            ?: return Metric.Unavailable("No power manager")
        return runCatching { Metric.Value(pm.currentThermalStatus, "PowerManager") }
            .getOrElse { Metric.Unavailable("currentThermalStatus threw: ${it.message}") }
    }

    fun describeStatus(status: Int): String = ThermalStatusNames.describe(status)

    fun zones(): Metric<ThermalSnapshot> {
        val entries = files.listDirectory(THERMAL_ROOT)
            ?: return Metric.Unavailable(
                reason = "$THERMAL_ROOT is not listable on this device.",
                remedy = "Grant root, or rely on the platform thermal status instead.",
            )

        val zones = entries
            .filter { it.startsWith("thermal_zone") }
            .sortedBy { it.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { zone ->
                val raw = files.read("$THERMAL_ROOT/$zone/temp")?.content?.trim()?.toLongOrNull()
                    ?: return@mapNotNull null
                val type = files.read("$THERMAL_ROOT/$zone/type")?.content?.trim().orEmpty()
                val (celsius, scale) = TemperatureConverter.convert(raw)
                ThermalZoneReading(zone, type, raw, celsius, scale)
            }

        return if (zones.isEmpty()) {
            Metric.Unavailable("No readable thermal zones")
        } else {
            Metric.Value(ThermalSnapshot(zones), "sysfs $THERMAL_ROOT")
        }
    }
}
