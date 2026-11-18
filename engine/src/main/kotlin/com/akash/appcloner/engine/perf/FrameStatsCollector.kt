package com.akash.appcloner.engine.perf

import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads real frame timestamps for a target package.
 *
 * `dumpsys` is gated behind the DUMP permission, which is signature-or-privileged, so an
 * installed app cannot collect another app's frame data at all without a privileged shell.
 * Without root this returns [Metric.needsRoot]: there is no fallback that produces a frame rate,
 * and presenting an estimate would be worse than presenting nothing.
 *
 * Source order matters. A game draws into its own surface, so SurfaceFlinger's per-layer
 * latency is the only source that sees its frames; `gfxinfo framestats` only covers HWUI
 * drawing and is tried second for View-based apps.
 */
class FrameStatsCollector(private val rootProvider: RootProvider = RootProvider()) {

    suspend fun sample(packageName: String): Metric<FrameStats> = withContext(Dispatchers.IO) {
        if (!rootProvider.isRooted) {
            return@withContext Metric.needsRoot("Frame timestamps (dumpsys)")
        }

        val reasons = mutableListOf<String>()

        val list = Shell.exec("dumpsys SurfaceFlinger --list", asRoot = true, timeoutSeconds = 20)
        if (list.isSuccess) {
            val layer = SurfaceFlingerLatencyParser.chooseLayer(list.stdout, packageName)
            if (layer == null) {
                reasons += "no SurfaceFlinger layer matched $packageName (is it in the foreground?)"
            } else {
                val latency = Shell.exec(
                    "dumpsys SurfaceFlinger --latency ${Shell.quote(layer)}",
                    asRoot = true,
                    timeoutSeconds = 20,
                )
                if (latency.isSuccess) {
                    val window = SurfaceFlingerLatencyParser.parse(latency.stdout, layer)
                    val stats = window?.let { FramePacing.analyse(it) }
                    if (stats != null) {
                        return@withContext Metric.Value(stats, "SurfaceFlinger --latency")
                    }
                    reasons += "SurfaceFlinger returned no completed frames for the layer"
                } else {
                    reasons += "dumpsys SurfaceFlinger --latency failed: " +
                        latency.stderr.trim().take(120)
                }
            }
        } else {
            reasons += "dumpsys SurfaceFlinger --list failed: ${list.stderr.trim().take(120)}"
        }

        val gfx = Shell.exec(
            "dumpsys gfxinfo ${Shell.quote(packageName)} framestats",
            asRoot = true,
            timeoutSeconds = 20,
        )
        if (gfx.isSuccess) {
            val window = GfxInfoFrameStatsParser.parse(gfx.stdout)
            val stats = window?.let { FramePacing.analyse(it) }
            if (stats != null) {
                return@withContext Metric.Value(stats, "gfxinfo framestats")
            }
            reasons += "gfxinfo framestats held no comparable frames, which is expected for a " +
                "game that renders into its own surface"
        } else {
            reasons += "dumpsys gfxinfo failed: ${gfx.stderr.trim().take(120)}"
        }

        Metric.Unavailable(
            reason = reasons.joinToString("; "),
            remedy = "Sample while the game is in the foreground and rendering. On releases " +
                "where --latency was removed, enable SurfaceFlinger time stats instead: " +
                "dumpsys SurfaceFlinger --timestats -enable",
        )
    }
}
