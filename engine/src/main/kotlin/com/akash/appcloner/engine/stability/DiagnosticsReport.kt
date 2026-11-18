package com.akash.appcloner.engine.stability

import com.akash.appcloner.engine.compat.ApkInspection
import com.akash.appcloner.engine.compat.CompatibilityReport
import com.akash.appcloner.engine.compat.DeviceProfile
import com.akash.appcloner.engine.compat.GraphicsCapability
import com.akash.appcloner.engine.perf.FrameStats
import com.akash.appcloner.engine.perf.Metric
import com.akash.appcloner.engine.perf.PerfSnapshot
import com.akash.appcloner.engine.root.RootStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything collected about one run, for export. */
data class DiagnosticsReport(
    val device: DeviceProfile,
    val root: RootStatus,
    val inspection: ApkInspection?,
    val compatibility: CompatibilityReport?,
    val nativeLibraries: NativeLoadReport?,
    val exitRecords: Metric<List<ProcessExitRecord>>,
    val memoryPressure: Metric<MemoryPressure>,
    val samples: List<PerfSnapshot>,
    val notes: List<String> = emptyList(),
)

/**
 * Renders a report as plain text.
 *
 * Unavailable measurements are printed with their reason rather than omitted, so a report that
 * was taken without root cannot be mistaken for one where the numbers were all zero.
 */
object DiagnosticsRenderer {

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun render(report: DiagnosticsReport): String = buildString {
        appendLine("App Cloner - gaming diagnostics")
        appendLine("generated: ${timestampFormat.format(Date())}")
        appendLine()

        appendLine("[device]")
        appendLine("  android: API ${report.device.sdkInt} (${report.device.releaseName})")
        appendLine("  abis: ${report.device.supportedAbis.joinToString()}")
        appendLine("  soc: ${report.device.socModel ?: "unreported"}, ${report.device.cpuCores} cores")
        report.device.totalRamBytes?.let { appendLine("  ram: ${it / (1024 * 1024)} MB") }
        appendLine("  vulkan: ${formatPacked(report.device.graphics.vulkanVersion)}")
        appendLine("  gles: ${formatPacked(report.device.graphics.glEsVersion)}")
        if (report.device.isNewerThanValidated) {
            appendLine(
                "  note: newer than the highest validated release " +
                    "(${DeviceProfile.HIGHEST_VALIDATED_SDK})",
            )
        }
        appendLine()

        appendLine("[root]")
        appendLine("  available: ${report.root.available}, flavor: ${report.root.flavor}")
        report.root.rawVersion?.let { appendLine("  su -v: $it") }
        report.root.evidence.forEach { appendLine("  evidence: $it") }
        appendLine()

        report.inspection?.let { inspection ->
            appendLine("[package]")
            appendLine("  ${inspection.packageName} (${inspection.label})")
            appendLine("  version: ${inspection.versionName} (${inspection.versionCode})")
            appendLine("  sdk: min ${inspection.minSdk}, target ${inspection.targetSdk}")
            appendLine("  abis in apk: ${inspection.apkAbis.joinToString().ifEmpty { "none" }}")
            appendLine("  engine: ${inspection.engine.kind}")
            inspection.engine.evidence.takeIf { it.isNotEmpty() }?.let {
                appendLine("  engine evidence: ${it.joinToString()}")
            }
            appendLine("  splits: ${inspection.splitApks.size}")
            appendLine("  extractNativeLibs: ${inspection.extractNativeLibs}")
            inspection.obb?.let { obb ->
                appendLine("  obb: ${obb.files.size} files, ${obb.totalBytes / (1024 * 1024)} MB")
                if (!obb.isConsistentWithInstall) {
                    appendLine("  obb stale: ${obb.staleFiles.joinToString { it.fileName }}")
                }
            } ?: appendLine("  obb: not inspected (${inspection.obbAccessFailure})")
            inspection.apkReadFailure?.let { appendLine("  apk read failure: $it") }
            appendLine()
        }

        report.compatibility?.let { compat ->
            appendLine("[compatibility] verdict: ${compat.verdict}")
            appendLine("  abi resolution: ${compat.abi}")
            compat.findings.forEach { finding ->
                appendLine("  ${finding.severity} ${finding.code}: ${finding.title}")
                appendLine("    ${finding.detail}")
                finding.nextStep?.let { appendLine("    next: $it") }
            }
            appendLine()
        }

        report.nativeLibraries?.let { native ->
            appendLine("[native libraries] abi: ${native.selectedAbi ?: "all"}")
            appendLine("  checked: ${native.checks.size}, unreadable: ${native.unreadable.size}")
            native.problems.forEach {
                appendLine("  PROBLEM ${it.library.path}: ${it.problem}")
            }
            native.unreadable.forEach { appendLine("  unreadable: $it") }
            if (native.isClean) appendLine("  no mismatches found")
            appendLine()
        }

        appendLine("[memory pressure]")
        when (val pressure = report.memoryPressure) {
            is Metric.Value -> {
                val value = pressure.value
                appendLine("  level: ${value.level}")
                appendLine("  available: ${value.availableBytes / (1024 * 1024)} MB")
                appendLine("  platform kill threshold: ${value.thresholdBytes / (1024 * 1024)} MB")
                appendLine("  platform lowMemory flag: ${value.platformLowMemoryFlag}")
            }

            is Metric.Unavailable -> appendLine("  unavailable: ${pressure.reason}")
        }
        appendLine()

        appendLine("[process exits]")
        when (val exits = report.exitRecords) {
            is Metric.Value -> if (exits.value.isEmpty()) {
                appendLine("  none recorded")
            } else {
                exits.value.forEach { record ->
                    appendLine(
                        "  ${timestampFormat.format(Date(record.timestampMillis))} " +
                            "${record.processName} pid=${record.pid} ${record.reasonName} " +
                            "rss=${record.rssKb / 1024}MB",
                    )
                    record.description?.takeIf { it.isNotBlank() }
                        ?.let { appendLine("    $it") }
                }
            }

            is Metric.Unavailable -> appendLine("  unavailable: ${exits.reason}")
        }
        appendLine()

        appendLine("[samples] ${report.samples.size} ticks")
        report.samples.forEach { snapshot -> appendLine(renderSample(snapshot)) }

        if (report.notes.isNotEmpty()) {
            appendLine()
            appendLine("[notes]")
            report.notes.forEach { appendLine("  $it") }
        }

        appendLine()
        appendLine("[limits of this report]")
        appendLine("  Static checks can only rule problems out. Launch, login, update and")
        appendLine("  sustained frame pacing are confirmed by running the game, not by this file.")
    }

    private fun renderSample(snapshot: PerfSnapshot): String = buildString {
        append("  t=${timestampFormat.format(Date(snapshot.timestampMillis))}")
        append(" cpu=")
        append(snapshot.overallCpuBusy?.let { "%.1f%%".format(it * 100) } ?: "n/a")
        append(" mem_avail=")
        append(
            snapshot.systemMemory.let { metric ->
                when (metric) {
                    is Metric.Value -> metric.value.availableKb?.let { "${it / 1024}MB" } ?: "n/a"
                    is Metric.Unavailable -> "n/a"
                }
            },
        )
        append(" thermal=")
        append(
            when (val status = snapshot.thermalStatus) {
                is Metric.Value -> status.value.toString()
                is Metric.Unavailable -> "n/a"
            },
        )
        append(" fps=")
        append(
            when (val frames = snapshot.frames) {
                is Metric.Value -> renderFrames(frames.value)
                is Metric.Unavailable -> "unavailable(${frames.reason.take(60)})"
            },
        )
    }

    private fun renderFrames(stats: FrameStats): String =
        "%.1f p95=%.1fms jank=%d/%d".format(
            stats.fps,
            stats.intervalMillis(stats.p95IntervalNanos),
            stats.jankFrames,
            stats.frameCount,
        )

    private fun formatPacked(packed: Int?): String =
        packed?.let { GraphicsCapability.formatPackedVersion(it) } ?: "unreported"
}
