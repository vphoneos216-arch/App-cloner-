package com.akash.appcloner.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.akash.appcloner.DashboardController
import com.akash.appcloner.engine.perf.CpuLoad
import com.akash.appcloner.engine.perf.FrameStats
import com.akash.appcloner.engine.perf.MemInfoSnapshot
import com.akash.appcloner.engine.perf.Metric
import com.akash.appcloner.engine.perf.MonitorTarget
import com.akash.appcloner.engine.perf.ProcessSample
import com.akash.appcloner.engine.perf.ThermalSnapshot
import com.akash.appcloner.engine.perf.ThermalStatusNames
import com.akash.appcloner.engine.stability.MemoryPressure
import com.akash.appcloner.engine.stability.MemoryPressureLevel
import com.akash.appcloner.engine.stability.ProcessExitRecord
import com.akash.appcloner.ui.KeyValueRow
import com.akash.appcloner.ui.Meter
import com.akash.appcloner.ui.MetricCard
import com.akash.appcloner.ui.SectionTitle
import com.akash.appcloner.ui.theme.StatusColors

/**
 * Live view of what the device is doing while a game runs.
 *
 * Every figure here is a measurement with its source named, or an explicit "unavailable" with
 * the reason. There is no synthetic FPS and no estimated per-process memory: on a device without
 * root, several of these cards will say so, which is the truthful outcome.
 */
@Composable
fun GamingDashboardScreen(
    controller: DashboardController,
    availableTargets: List<MonitorTarget>,
    modifier: Modifier = Modifier,
    onSamplingChanged: (Boolean, String) -> Unit,
) {
    val state by controller.state.collectAsState()

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            SectionTitle("Target")
            if (availableTargets.isEmpty()) {
                Text(
                    "No games found yet. Open the Clones tab to scan installed packages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.unknown,
                )
            }
        }

        items(availableTargets) { target ->
            val selected = state.target?.packageName == target.packageName &&
                state.target?.androidUserId == target.androidUserId
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.fillMaxWidth(0.68f)) {
                        Text(target.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            buildString {
                                append(target.packageName)
                                target.androidUserId?.let { append("  user $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.unknown,
                        )
                    }
                    Spacer(Modifier.fillMaxWidth(0.05f))
                    if (selected) {
                        Text("selected", color = StatusColors.ok, style = MaterialTheme.typography.labelMedium)
                    } else {
                        OutlinedButton(onClick = { controller.selectTarget(target) }) {
                            Text("Watch")
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = {
                        val label = state.target?.label ?: "game"
                        if (state.sampling) {
                            controller.stop()
                            onSamplingChanged(false, label)
                        } else {
                            controller.start()
                            onSamplingChanged(true, label)
                        }
                    },
                    enabled = state.target != null,
                ) {
                    Text(if (state.sampling) "Stop sampling" else "Start sampling")
                }
                Spacer(Modifier.fillMaxWidth(0.04f))
                OutlinedButton(onClick = controller::refreshDiagnostics) {
                    Text("Refresh diagnostics")
                }
            }
            state.message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = StatusColors.warn, style = MaterialTheme.typography.bodySmall)
            }
            state.latest?.let { snapshot ->
                if (snapshot.clockTicksAssumed) {
                    Text(
                        "Clock tick could not be read; per-process CPU assumes USER_HZ 100. " +
                            "Verify with: getconf CLK_TCK",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.warn,
                    )
                }
            }
        }

        item {
            SectionTitle("Frame delivery")
            MetricCard("FPS and pacing", state.latest?.frames) { stats: FrameStats ->
                FrameStatsBody(stats)
            }
        }

        item {
            SectionTitle("Device")
            MetricCard("System memory", state.latest?.systemMemory) { memory: MemInfoSnapshot ->
                SystemMemoryBody(memory)
            }
            MetricCard("CPU load", state.latest?.cpuLoads) { loads: List<CpuLoad> ->
                CpuLoadBody(loads)
            }
            MetricCard("Thermal status", state.latest?.thermalStatus) { status: Int ->
                val throttling = ThermalStatusNames.isThrottling(status)
                KeyValueRow(
                    "platform",
                    ThermalStatusNames.describe(status),
                    if (throttling) StatusColors.bad else StatusColors.ok,
                )
                if (throttling) {
                    Text(
                        "The platform is throttling. Frame drops from here are thermal, not the clone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warn,
                    )
                }
            }
            MetricCard("Thermal zones", state.latest?.thermalZones) { snapshot: ThermalSnapshot ->
                ThermalZonesBody(snapshot)
            }
        }

        item {
            SectionTitle("Game process")
            MetricCard("Per-process usage", state.latest?.target) { samples: List<ProcessSample> ->
                ProcessSamplesBody(samples)
            }
            MetricCard("Memory headroom", state.memoryPressure) { pressure: MemoryPressure ->
                MemoryPressureBody(pressure)
            }
        }

        item {
            SectionTitle("Recorded process deaths")
            MetricCard("Exit history", state.exits) { records: List<ProcessExitRecord> ->
                if (records.isEmpty()) {
                    Text("None recorded", style = MaterialTheme.typography.bodyMedium)
                } else {
                    records.take(8).forEach { record ->
                        KeyValueRow(
                            record.reasonName,
                            "pid ${record.pid}  rss ${record.rssKb / 1024} MB",
                            when {
                                record.isLowMemoryKill -> StatusColors.bad
                                record.isNativeCrash || record.isJavaCrash -> StatusColors.bad
                                record.isAnr -> StatusColors.warn
                                else -> null
                            },
                        )
                        record.description?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.unknown,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FrameStatsBody(stats: FrameStats) {
    KeyValueRow("fps (window)", "%.1f".format(stats.fps))
    KeyValueRow("frames", stats.frameCount.toString())
    KeyValueRow("p50 frame time", "%.2f ms".format(stats.intervalMillis(stats.p50IntervalNanos)))
    KeyValueRow("p95 frame time", "%.2f ms".format(stats.intervalMillis(stats.p95IntervalNanos)))
    KeyValueRow("p99 frame time", "%.2f ms".format(stats.intervalMillis(stats.p99IntervalNanos)))
    KeyValueRow("worst frame", "%.2f ms".format(stats.intervalMillis(stats.maxIntervalNanos)))
    KeyValueRow(
        "jank",
        "${stats.jankFrames} of ${stats.frameCount} (> %.2f ms)".format(
            stats.intervalMillis(stats.jankThresholdNanos),
        ),
        if (stats.jankFraction > 0.05) StatusColors.warn else StatusColors.ok,
    )
    stats.refreshPeriodNanos?.let {
        KeyValueRow("display refresh", "%.2f ms".format(stats.intervalMillis(it)))
    }
    stats.layerName?.let {
        Text(
            "layer: $it",
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.unknown,
        )
    }
}

@Composable
private fun SystemMemoryBody(memory: MemInfoSnapshot) {
    val total = memory.totalKb
    val available = memory.availableKb
    KeyValueRow("total", total?.let { "${it / 1024} MB" } ?: "n/a")
    KeyValueRow("available", available?.let { "${it / 1024} MB" } ?: "n/a")
    memory.swapTotalKb?.let { swapTotal ->
        val swapUsed = memory.swapFreeKb?.let { swapTotal - it }
        KeyValueRow("swap used", swapUsed?.let { "${it / 1024} MB" } ?: "n/a")
    }
    val usedFraction = memory.usedFraction
    Spacer(Modifier.height(4.dp))
    Meter(
        fraction = usedFraction?.toFloat(),
        color = when {
            usedFraction == null -> StatusColors.unknown
            usedFraction > 0.92 -> StatusColors.bad
            usedFraction > 0.80 -> StatusColors.warn
            else -> StatusColors.ok
        },
    )
}

@Composable
private fun CpuLoadBody(loads: List<CpuLoad>) {
    val overall = loads.firstOrNull { it.label == "cpu" }
    KeyValueRow("overall", overall?.let { "%.1f %%".format(it.busyFraction * 100) } ?: "n/a")
    Spacer(Modifier.height(4.dp))
    Meter(
        fraction = overall?.busyFraction?.toFloat(),
        color = when {
            overall == null -> StatusColors.unknown
            overall.busyFraction > 0.9 -> StatusColors.warn
            else -> StatusColors.ok
        },
    )
    Spacer(Modifier.height(6.dp))
    loads.filter { it.label != "cpu" }.forEach { load ->
        KeyValueRow(load.label, "%.0f %%".format(load.busyFraction * 100))
    }
}

@Composable
private fun ThermalZonesBody(snapshot: ThermalSnapshot) {
    val hottest = snapshot.hottestSocZone
    if (hottest != null) {
        KeyValueRow(
            "hottest (${hottest.type})",
            hottest.celsius?.let { "%.1f C".format(it) } ?: "unreadable",
            hottest.celsius?.let {
                when {
                    it >= 45.0 -> StatusColors.bad
                    it >= 40.0 -> StatusColors.warn
                    else -> StatusColors.ok
                }
            },
        )
    }
    snapshot.zones.take(8).forEach { zone ->
        KeyValueRow(
            zone.type.ifBlank { zone.zone },
            zone.celsius?.let { "%.1f C (%s)".format(it, zone.scale.name.lowercase()) }
                ?: "raw ${zone.raw}, scale unrecognised",
        )
    }
}

@Composable
private fun ProcessSamplesBody(samples: List<ProcessSample>) {
    samples.forEach { sample ->
        KeyValueRow(
            "pid ${sample.process.pid}",
            sample.process.name.takeLast(28),
        )
        KeyValueRow(
            "  rss",
            sample.memory?.vmRssKb?.let { "${it / 1024} MB" } ?: "n/a",
        )
        KeyValueRow(
            "  cpu",
            sample.cpuFractionOfOneCore?.let { "%.2f cores".format(it) } ?: "n/a (needs two ticks)",
        )
        KeyValueRow("  threads", sample.threads?.toString() ?: "n/a")
        sample.process.androidUserId?.let { KeyValueRow("  android user", it.toString()) }
    }
}

@Composable
private fun MemoryPressureBody(pressure: MemoryPressure) {
    KeyValueRow(
        "level",
        pressure.level.name,
        when (pressure.level) {
            MemoryPressureLevel.OK -> StatusColors.ok
            MemoryPressureLevel.APPROACHING -> StatusColors.warn
            MemoryPressureLevel.AT_THRESHOLD, MemoryPressureLevel.CRITICAL -> StatusColors.bad
        },
    )
    KeyValueRow("available", "${pressure.availableBytes / (1024 * 1024)} MB")
    KeyValueRow("kill threshold", "${pressure.thresholdBytes / (1024 * 1024)} MB")
    KeyValueRow("platform flag", pressure.platformLowMemoryFlag.toString())
    Text(
        "Threshold is the platform's own; the approaching band is a tuned multiple " +
            "(${pressure.approachingMultiplier}x).",
        style = MaterialTheme.typography.labelSmall,
        color = StatusColors.unknown,
    )
}
