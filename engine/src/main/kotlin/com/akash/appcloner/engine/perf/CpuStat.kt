package com.akash.appcloner.engine.perf

/** One `cpu`/`cpuN` line from /proc/stat, in jiffies. */
data class CpuTimes(
    val label: String,
    val user: Long,
    val nice: Long,
    val system: Long,
    val idle: Long,
    val ioWait: Long,
    val irq: Long,
    val softIrq: Long,
    val steal: Long,
) {
    val total: Long get() = user + nice + system + idle + ioWait + irq + softIrq + steal

    /** Idle includes iowait: a thread blocked on storage is not consuming the core. */
    val idleTotal: Long get() = idle + ioWait
}

/** CPU load over the interval between two samples. */
data class CpuLoad(
    val label: String,
    val busyFraction: Double,
    val intervalJiffies: Long,
)

/**
 * Pure parsing and differencing of /proc/stat.
 *
 * Load is only meaningful as a difference between two samples. There is deliberately no
 * single-sample "current CPU" value, because the only number a single read can give is average
 * load since boot, which on a phone is never what the reader thinks it is.
 */
object CpuStatParser {

    fun parse(content: String): List<CpuTimes> =
        content.lineSequence()
            .filter { it.startsWith("cpu") }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 8) return@mapNotNull null
                fun field(index: Int): Long = parts.getOrNull(index)?.toLongOrNull() ?: 0L
                CpuTimes(
                    label = parts[0],
                    user = field(1),
                    nice = field(2),
                    system = field(3),
                    idle = field(4),
                    ioWait = field(5),
                    irq = field(6),
                    softIrq = field(7),
                    steal = field(8),
                )
            }
            .toList()

    /**
     * Busy fraction between two samples of the same label.
     *
     * Returns null when the counters did not advance, or went backwards - which happens across a
     * suspend or a core hotplug. A null here means "no reading for this interval", never zero
     * load.
     */
    fun load(before: CpuTimes, after: CpuTimes): CpuLoad? {
        if (before.label != after.label) return null
        val totalDelta = after.total - before.total
        if (totalDelta <= 0) return null
        val idleDelta = after.idleTotal - before.idleTotal
        if (idleDelta < 0) return null
        val busy = (totalDelta - idleDelta).coerceIn(0, totalDelta)
        return CpuLoad(
            label = after.label,
            busyFraction = busy.toDouble() / totalDelta.toDouble(),
            intervalJiffies = totalDelta,
        )
    }

    /** Pairs up samples by label and differences each one. */
    fun loads(before: List<CpuTimes>, after: List<CpuTimes>): List<CpuLoad> {
        val beforeByLabel = before.associateBy { it.label }
        return after.mapNotNull { later ->
            beforeByLabel[later.label]?.let { load(it, later) }
        }
    }
}
