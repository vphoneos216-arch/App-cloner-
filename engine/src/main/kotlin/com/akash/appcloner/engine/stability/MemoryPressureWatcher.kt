package com.akash.appcloner.engine.stability

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.ContextCompat
import com.akash.appcloner.engine.perf.Metric

enum class MemoryPressureLevel { OK, APPROACHING, AT_THRESHOLD, CRITICAL }

/**
 * Memory headroom against the platform's own kill threshold.
 *
 * [thresholdBytes] is the value the system itself uses to decide when to start killing
 * background processes, so this is a real comparison rather than a chosen percentage.
 * [approachingMultiplier] is the only tuned number here and it is labelled as such.
 */
data class MemoryPressure(
    val level: MemoryPressureLevel,
    val availableBytes: Long,
    val thresholdBytes: Long,
    val totalBytes: Long,
    val platformLowMemoryFlag: Boolean,
    val approachingMultiplier: Double,
) {
    val availableFraction: Double?
        get() = if (totalBytes > 0) availableBytes.toDouble() / totalBytes.toDouble() else null
}

/**
 * Watches free memory while two instances of a game are up, which is the condition that
 * produces a low-memory kill on a mid-range device.
 */
class MemoryPressureWatcher(private val context: Context) {

    companion object {
        /**
         * How far above the platform's kill threshold counts as "approaching". Tuned, not
         * measured: the authoritative signals are [MemoryPressure.thresholdBytes] and
         * [MemoryPressure.platformLowMemoryFlag].
         */
        const val APPROACHING_MULTIPLIER = 2.0

        /** Pure assessment, so the levels are covered by unit tests. */
        fun assess(
            availableBytes: Long,
            thresholdBytes: Long,
            totalBytes: Long,
            platformLowMemoryFlag: Boolean,
            approachingMultiplier: Double = APPROACHING_MULTIPLIER,
        ): MemoryPressure {
            val level = when {
                platformLowMemoryFlag -> MemoryPressureLevel.CRITICAL
                thresholdBytes <= 0L -> MemoryPressureLevel.OK
                availableBytes <= thresholdBytes -> MemoryPressureLevel.AT_THRESHOLD
                availableBytes <= (thresholdBytes * approachingMultiplier).toLong() ->
                    MemoryPressureLevel.APPROACHING

                else -> MemoryPressureLevel.OK
            }
            return MemoryPressure(
                level = level,
                availableBytes = availableBytes,
                thresholdBytes = thresholdBytes,
                totalBytes = totalBytes,
                platformLowMemoryFlag = platformLowMemoryFlag,
                approachingMultiplier = approachingMultiplier,
            )
        }
    }

    fun current(): Metric<MemoryPressure> {
        val am = ContextCompat.getSystemService(context, ActivityManager::class.java)
            ?: return Metric.Unavailable("No activity manager")
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            am.getMemoryInfo(info)
            Metric.Value(
                assess(
                    availableBytes = info.availMem,
                    thresholdBytes = info.threshold,
                    totalBytes = info.totalMem,
                    platformLowMemoryFlag = info.lowMemory,
                ),
                "ActivityManager.MemoryInfo",
            )
        }.getOrElse { Metric.Unavailable("getMemoryInfo threw: ${it.message}") }
    }
}
