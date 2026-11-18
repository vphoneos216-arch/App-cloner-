package com.akash.appcloner.engine.perf

import android.content.Context
import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The clone or original being watched. [androidUserId] separates two instances of one package. */
data class MonitorTarget(
    val packageName: String,
    val label: String,
    val androidUserId: Int? = null,
)

/** Per-process reading for one sampling interval. */
data class ProcessSample(
    val process: TargetProcess,
    val memory: ProcessMemory?,
    /** 1.0 means one core fully busy; a game on 8 cores can legitimately exceed 1.0. */
    val cpuFractionOfOneCore: Double?,
    val threads: Long?,
)

/** One sampling tick. Every field is a [Metric], so gaps are visible rather than filled in. */
data class PerfSnapshot(
    val timestampMillis: Long,
    val systemMemory: Metric<MemInfoSnapshot>,
    val cpuLoads: Metric<List<CpuLoad>>,
    val thermalStatus: Metric<Int>,
    val thermalZones: Metric<ThermalSnapshot>,
    val target: Metric<List<ProcessSample>>,
    val frames: Metric<FrameStats>,
    val clockTicksAssumed: Boolean,
) {
    val overallCpuBusy: Double?
        get() = cpuLoads.valueOrNull()?.firstOrNull { it.label == "cpu" }?.busyFraction
}

/**
 * Samples the device and, where permission allows, the target process.
 *
 * Load figures come from differencing two samples, so the first tick carries no CPU reading.
 * That first tick reports CPU as unavailable rather than as zero.
 */
class PerformanceMonitor(
    context: Context,
    private val rootProvider: RootProvider = RootProvider(),
    private val files: PrivilegedFiles = PrivilegedFiles(rootProvider),
    private val thermalReader: ThermalReader = ThermalReader(context, rootProvider, files),
    private val processLocator: ProcessLocator = ProcessLocator(rootProvider),
    private val frameStatsCollector: FrameStatsCollector = FrameStatsCollector(rootProvider),
) {

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 1_000L

        /**
         * Linux's USER_HZ. 100 on every Android build seen in practice, but it is an assumption,
         * so it is resolved from getconf when possible and flagged in the snapshot when not.
         */
        const val FALLBACK_CLOCK_TICKS = 100.0
    }

    @Volatile
    private var clockTicks: Double? = null

    @Volatile
    private var clockTicksAssumed: Boolean = true

    private fun clockTicksPerSecond(): Double {
        clockTicks?.let { return it }
        val resolved = Shell.exec("getconf CLK_TCK", asRoot = false, timeoutSeconds = 5)
            .stdout.trim().toDoubleOrNull()
            ?.takeIf { it > 0 }
        clockTicksAssumed = resolved == null
        return (resolved ?: FALLBACK_CLOCK_TICKS).also { clockTicks = it }
    }

    /**
     * Emits a snapshot every [intervalMillis]. Collect this from a foreground service while a
     * match is running; sampling from a backgrounded activity is itself a source of jitter.
     */
    fun snapshots(
        target: MonitorTarget? = null,
        intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    ): Flow<PerfSnapshot> = flow {
        var previousCpu: List<CpuTimes>? = null
        var previousProcesses: Map<Int, ProcessCpuTimes> = emptyMap()
        var previousNanos = 0L

        while (true) {
            val nowNanos = System.nanoTime()
            val memory = readSystemMemory()
            val currentCpu = readCpuTimes()

            val cpuLoads: Metric<List<CpuLoad>> = when {
                currentCpu == null -> Metric.Unavailable("/proc/stat is not readable")
                previousCpu == null -> Metric.Unavailable(
                    "First sample: CPU load needs two samples to difference.",
                )
                else -> Metric.Value(
                    CpuStatParser.loads(previousCpu!!, currentCpu),
                    "/proc/stat delta",
                )
            }

            val intervalSeconds = if (previousNanos == 0L) {
                0.0
            } else {
                (nowNanos - previousNanos) / 1_000_000_000.0
            }

            val targetSample: Metric<List<ProcessSample>>
            var nextProcesses: Map<Int, ProcessCpuTimes> = emptyMap()

            if (target == null) {
                targetSample = Metric.Unavailable("No target selected")
            } else {
                when (val located = processLocator.processesFor(target.packageName, target.androidUserId)) {
                    is Metric.Unavailable -> targetSample = located
                    is Metric.Value -> {
                        val samples = located.value.map { process ->
                            val stat = files.read("/proc/${process.pid}/stat")
                                ?.content
                                ?.let(ProcessStatParser::parseStat)
                            val status = files.read("/proc/${process.pid}/status")
                                ?.content
                                ?.let { ProcessStatParser.parseStatus(process.pid, it) }

                            if (stat != null) nextProcesses = nextProcesses + (process.pid to stat)

                            val fraction = if (stat != null && intervalSeconds > 0.0) {
                                previousProcesses[process.pid]?.let { before ->
                                    ProcessStatParser.cpuFraction(
                                        before = before,
                                        after = stat,
                                        intervalJiffies = intervalSeconds * clockTicksPerSecond(),
                                    )
                                }
                            } else {
                                null
                            }

                            ProcessSample(
                                process = process,
                                memory = status,
                                cpuFractionOfOneCore = fraction,
                                threads = stat?.numThreads,
                            )
                        }
                        targetSample = if (samples.isEmpty()) {
                            Metric.Unavailable("${target.packageName} is not running")
                        } else {
                            Metric.Value(samples, located.source)
                        }
                    }
                }
            }

            val frames = if (target == null) {
                Metric.Unavailable("No target selected")
            } else {
                frameStatsCollector.sample(target.packageName)
            }

            emit(
                PerfSnapshot(
                    timestampMillis = System.currentTimeMillis(),
                    systemMemory = memory,
                    cpuLoads = cpuLoads,
                    thermalStatus = thermalReader.thermalStatus(),
                    thermalZones = thermalReader.zones(),
                    target = targetSample,
                    frames = frames,
                    clockTicksAssumed = clockTicksAssumed,
                ),
            )

            previousCpu = currentCpu ?: previousCpu
            previousProcesses = nextProcesses
            previousNanos = nowNanos
            delay(intervalMillis)
        }
    }

    fun readSystemMemory(): Metric<MemInfoSnapshot> {
        val read = files.read("/proc/meminfo")
            ?: return Metric.Unavailable("/proc/meminfo is not readable")
        val snapshot = MemInfoParser.parse(read.content)
        return if (snapshot.totalKb == null) {
            Metric.Unavailable("/proc/meminfo held no MemTotal")
        } else {
            Metric.Value(snapshot, "/proc/meminfo")
        }
    }

    private fun readCpuTimes(): List<CpuTimes>? =
        files.read("/proc/stat")?.content?.let(CpuStatParser::parse)?.takeIf { it.isNotEmpty() }
}
