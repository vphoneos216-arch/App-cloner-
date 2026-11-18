package com.akash.appcloner.engine.perf

/**
 * Pure parsing of the two dumpsys surfaces that expose real frame timestamps.
 *
 * Which one applies depends on how the app draws. A game renders into its own SurfaceView or
 * native window, so HWUI never sees its frames and `gfxinfo framestats` reports nothing useful
 * for it; SurfaceFlinger's per-layer latency is the source that works for games. The gfxinfo
 * parser is kept for View-based apps and for the cloner's own UI.
 */
object SurfaceFlingerLatencyParser {

    private const val PENDING_FENCE = Long.MAX_VALUE

    /**
     * Parses `dumpsys SurfaceFlinger --latency <layer>`.
     *
     * Line 1 is the refresh period in nanoseconds. Each later line holds three timestamps:
     * desired present, actual present, frame ready. Rows with a zero or still-pending actual
     * present time are frames that had not completed when the dump was taken and are dropped.
     */
    fun parse(output: String, layerName: String?): FrameWindow? {
        val lines = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return null

        val refresh = lines.first().toLongOrNull()?.takeIf { it > 0 }
        val rows = if (refresh != null) lines.drop(1) else lines

        val present = rows.mapNotNull { row ->
            val columns = row.split(Regex("[\\s,]+"))
            if (columns.size < 3) return@mapNotNull null
            val actual = columns[1].toLongOrNull() ?: return@mapNotNull null
            if (actual <= 0L || actual == PENDING_FENCE) null else actual
        }
        if (present.size < 3) return null

        return FrameWindow(
            presentTimestampsNanos = present.sorted(),
            refreshPeriodNanos = refresh,
            source = "SurfaceFlinger --latency",
            layerName = layerName,
        )
    }

    /**
     * Picks the layer to measure out of `dumpsys SurfaceFlinger --list`.
     *
     * Prefers a layer whose name carries the package and looks like the game's own window, and
     * returns null rather than a guess when nothing matches - measuring the wrong layer gives a
     * confident number about the wrong surface.
     */
    fun chooseLayer(listOutput: String, packageName: String): String? {
        val candidates = listOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(packageName) }
            .toList()
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.contains("SurfaceView", ignoreCase = true) }
            ?: candidates.firstOrNull { it.contains("ActivityRecord", ignoreCase = true) }
            ?: candidates.maxByOrNull { it.length }
    }
}

object GfxInfoFrameStatsParser {

    private const val MARKER = "---PROFILEDATA---"
    private const val FLAGS_COLUMN = 0
    private const val FRAME_COMPLETED_COLUMN = 13

    /**
     * Parses the PROFILEDATA block of `dumpsys gfxinfo <package> framestats`.
     *
     * Rows whose flags column is non-zero are frames HWUI itself marked as not comparable
     * (a layout pass, a skipped frame) and are excluded, as the platform's own documentation
     * requires. Present time is the FRAME_COMPLETED column.
     */
    fun parse(output: String, layerName: String? = null): FrameWindow? {
        val blocks = output.split(MARKER)
        if (blocks.size < 2) return null

        val timestamps = blocks.drop(1).flatMap { block ->
            block.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.first().isDigit() }
                .mapNotNull { row ->
                    val columns = row.split(',')
                    if (columns.size <= FRAME_COMPLETED_COLUMN) return@mapNotNull null
                    val flags = columns[FLAGS_COLUMN].trim().toLongOrNull() ?: return@mapNotNull null
                    if (flags != 0L) return@mapNotNull null
                    columns[FRAME_COMPLETED_COLUMN].trim().toLongOrNull()?.takeIf { it > 0 }
                }
                .toList()
        }
        if (timestamps.size < 3) return null

        return FrameWindow(
            presentTimestampsNanos = timestamps.sorted(),
            refreshPeriodNanos = null,
            source = "gfxinfo framestats",
            layerName = layerName,
        )
    }
}
