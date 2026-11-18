package com.akash.appcloner.engine.perf

import kotlin.math.roundToLong

/**
 * A window of present timestamps for one surface.
 *
 * [presentTimestampsNanos] must be real completion timestamps, in order, with pending and
 * dropped rows already filtered out by the source.
 */
data class FrameWindow(
    val presentTimestampsNanos: List<Long>,
    val refreshPeriodNanos: Long?,
    val source: String,
    val layerName: String? = null,
)

/**
 * Frame delivery over one window.
 *
 * [fps] is frames divided by the measured span, not an instantaneous reading, and the
 * percentiles are what tell you whether pacing is even. A high average with a p99 of three
 * refresh periods is a stutter, and reporting only the average would hide it.
 */
data class FrameStats(
    val frameCount: Int,
    val spanNanos: Long,
    val fps: Double,
    val p50IntervalNanos: Long,
    val p95IntervalNanos: Long,
    val p99IntervalNanos: Long,
    val maxIntervalNanos: Long,
    val jankFrames: Int,
    val jankThresholdNanos: Long,
    val refreshPeriodNanos: Long?,
    val source: String,
    val layerName: String?,
) {
    val jankFraction: Double
        get() = if (frameCount > 0) jankFrames.toDouble() / frameCount.toDouble() else 0.0

    fun intervalMillis(nanos: Long): Double = nanos / 1_000_000.0
}

/**
 * Frame-pacing arithmetic. Pure, so the percentile and jank definitions are pinned by unit
 * tests rather than eyeballed on a device.
 */
object FramePacing {

    /**
     * A frame is counted as jank when its interval exceeds twice the display refresh period -
     * i.e. the frame missed at least one whole vsync. When the refresh period is unknown the
     * threshold falls back to 1.5x the window's own median interval, which detects stutter
     * relative to what the game was actually achieving.
     */
    private const val JANK_REFRESH_MULTIPLE = 2.0
    private const val JANK_MEDIAN_MULTIPLE = 1.5

    fun analyse(window: FrameWindow): FrameStats? {
        val timestamps = window.presentTimestampsNanos
        if (timestamps.size < 3) return null

        val intervals = ArrayList<Long>(timestamps.size - 1)
        for (i in 1 until timestamps.size) {
            val delta = timestamps[i] - timestamps[i - 1]
            // Non-monotonic or zero deltas mean the source gave us rows out of order; dropping
            // them is correct, inventing a value in their place is not.
            if (delta > 0) intervals += delta
        }
        if (intervals.size < 2) return null

        val sorted = intervals.sorted()
        val span = timestamps.last() - timestamps.first()
        if (span <= 0) return null

        val median = percentile(sorted, 50.0)
        val threshold = window.refreshPeriodNanos
            ?.takeIf { it > 0 }
            ?.let { (it * JANK_REFRESH_MULTIPLE).roundToLong() }
            ?: (median * JANK_MEDIAN_MULTIPLE).roundToLong()

        return FrameStats(
            frameCount = intervals.size + 1,
            spanNanos = span,
            fps = intervals.size.toDouble() * 1_000_000_000.0 / span.toDouble(),
            p50IntervalNanos = median,
            p95IntervalNanos = percentile(sorted, 95.0),
            p99IntervalNanos = percentile(sorted, 99.0),
            maxIntervalNanos = sorted.last(),
            jankFrames = intervals.count { it > threshold },
            jankThresholdNanos = threshold,
            refreshPeriodNanos = window.refreshPeriodNanos,
            source = window.source,
            layerName = window.layerName,
        )
    }

    /** Nearest-rank percentile on an already sorted list. No interpolation, no smoothing. */
    fun percentile(sorted: List<Long>, percentile: Double): Long {
        require(sorted.isNotEmpty()) { "percentile of an empty list" }
        if (sorted.size == 1) return sorted[0]
        val rank = Math.ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
