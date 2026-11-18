package com.akash.appcloner.engine.perf

/** CPU time of one process, in jiffies, from /proc/<pid>/stat. */
data class ProcessCpuTimes(
    val pid: Int,
    val comm: String,
    val utime: Long,
    val stime: Long,
    val numThreads: Long,
) {
    val total: Long get() = utime + stime
}

/** Resident memory of one process, from /proc/<pid>/status. */
data class ProcessMemory(
    val pid: Int,
    val vmRssKb: Long?,
    val rssAnonKb: Long?,
    val rssFileKb: Long?,
    val rssShmemKb: Long?,
)

/**
 * Pure parsers for per-process /proc entries.
 *
 * On Android 9 and later /proc is mounted with hidepid, so these files are unreadable for
 * another app's process without root. That is a permission fact, not a parsing problem: the
 * caller reports [Metric.needsRoot] rather than reporting zero usage.
 */
object ProcessStatParser {

    /**
     * Parses /proc/<pid>/stat. The `comm` field is in parentheses and may itself contain spaces
     * and parentheses, so the fields after it are located from the last `)` rather than by
     * splitting the whole line.
     */
    fun parseStat(content: String): ProcessCpuTimes? {
        val line = content.trim()
        val pid = line.substringBefore(' ').toIntOrNull() ?: return null
        val commStart = line.indexOf('(')
        val commEnd = line.lastIndexOf(')')
        if (commStart < 0 || commEnd <= commStart) return null
        val comm = line.substring(commStart + 1, commEnd)

        // Fields from `state` onward, i.e. field 3 in the procfs numbering.
        val rest = line.substring(commEnd + 1).trim().split(Regex("\\s+"))
        // utime is field 14, stime 15, num_threads 20; rest[0] is field 3.
        fun at(procfsField: Int): Long? = rest.getOrNull(procfsField - 3)?.toLongOrNull()

        val utime = at(14) ?: return null
        val stime = at(15) ?: return null
        return ProcessCpuTimes(
            pid = pid,
            comm = comm,
            utime = utime,
            stime = stime,
            numThreads = at(20) ?: 0L,
        )
    }

    fun parseStatus(pid: Int, content: String): ProcessMemory {
        val fields = HashMap<String, Long>(8)
        content.lineSequence().forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon)
            val kb = line.substring(colon + 1).trim().substringBefore(' ').toLongOrNull()
                ?: return@forEach
            fields[key] = kb
        }
        return ProcessMemory(
            pid = pid,
            vmRssKb = fields["VmRSS"],
            rssAnonKb = fields["RssAnon"],
            rssFileKb = fields["RssFile"],
            rssShmemKb = fields["RssShmem"],
        )
    }

    /**
     * Process CPU share of one core-equivalent over an interval.
     *
     * [intervalJiffies] must be the wall-clock interval expressed in the same jiffy unit, i.e.
     * elapsed seconds times the clock tick. Returns null when counters do not advance, so a
     * restarted process reads as no measurement rather than as idle.
     */
    fun cpuFraction(
        before: ProcessCpuTimes,
        after: ProcessCpuTimes,
        intervalJiffies: Double,
    ): Double? {
        if (before.pid != after.pid) return null
        if (intervalJiffies <= 0.0) return null
        val delta = after.total - before.total
        if (delta < 0) return null
        return delta / intervalJiffies
    }
}
