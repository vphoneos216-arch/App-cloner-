package com.akash.appcloner.engine.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingTest {

    private val refresh60 = 16_666_667L

    private fun steadyTimestamps(count: Int, period: Long = 16_666_667L): List<Long> =
        (0 until count).map { 1_000_000_000L + it * period }

    @Test
    fun `steady 60hz reads as 60 fps with no jank`() {
        val stats = FramePacing.analyse(
            FrameWindow(steadyTimestamps(10), refresh60, "test"),
        )
        assertNotNull(stats)
        assertEquals(60.0, stats!!.fps, 0.01)
        assertEquals(10, stats.frameCount)
        assertEquals(0, stats.jankFrames)
    }

    @Test
    fun `a dropped frame is counted as jank against the refresh period`() {
        val timestamps = steadyTimestamps(5).toMutableList()
        // 50 ms gap: three refresh periods, so the frame missed at least one vsync.
        timestamps.add(timestamps.last() + 50_000_000L)
        timestamps.add(timestamps.last() + refresh60)

        val stats = FramePacing.analyse(FrameWindow(timestamps, refresh60, "test"))!!
        assertEquals(1, stats.jankFrames)
        assertEquals(50_000_000L, stats.maxIntervalNanos)
        assertTrue(stats.p99IntervalNanos >= stats.p50IntervalNanos)
    }

    @Test
    fun `without a refresh period jank falls back to the window median`() {
        // 20 ms period, so 1.5x the median is exactly 30 ms and the expectation needs no rounding.
        val timestamps = steadyTimestamps(8, 20_000_000L).toMutableList()
        timestamps.add(timestamps.last() + 40_000_000L)
        val stats = FramePacing.analyse(FrameWindow(timestamps, null, "test"))!!
        assertEquals(1, stats.jankFrames)
        assertEquals(30_000_000L, stats.jankThresholdNanos)
    }

    @Test
    fun `too few frames yields no statistics rather than a made-up number`() {
        assertNull(FramePacing.analyse(FrameWindow(listOf(1L, 2L), refresh60, "test")))
        assertNull(FramePacing.analyse(FrameWindow(emptyList(), refresh60, "test")))
    }

    @Test
    fun `non-advancing timestamps are dropped not interpolated`() {
        val timestamps = listOf(1_000L, 1_000L, 2_000L, 2_000L, 3_000L)
        val stats = FramePacing.analyse(FrameWindow(timestamps, null, "test"))!!
        // Two real intervals survive out of four candidate deltas.
        assertEquals(3, stats.frameCount)
    }

    @Test
    fun `percentile uses nearest rank with no interpolation`() {
        val sorted = listOf(1L, 2L, 3L, 4L, 5L)
        assertEquals(3L, FramePacing.percentile(sorted, 50.0))
        assertEquals(5L, FramePacing.percentile(sorted, 95.0))
        assertEquals(1L, FramePacing.percentile(sorted, 1.0))
    }

    @Test
    fun `30hz cap reads as 30 fps`() {
        val stats = FramePacing.analyse(
            FrameWindow(steadyTimestamps(20, 33_333_333L), refresh60, "test"),
        )!!
        assertEquals(30.0, stats.fps, 0.01)
        // Every frame spans two refresh periods, which the 2x rule treats as on-pace.
        assertEquals(0, stats.jankFrames)
    }
}
