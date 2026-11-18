package com.akash.appcloner.engine.perf

/** System memory, in kibibytes as /proc/meminfo reports it. Null means the field was absent. */
data class MemInfoSnapshot(
    val totalKb: Long?,
    val freeKb: Long?,
    val availableKb: Long?,
    val cachedKb: Long?,
    val swapTotalKb: Long?,
    val swapFreeKb: Long?,
) {
    /**
     * MemAvailable is the kernel's own estimate of what can be allocated without swapping, and
     * is the figure that matters for whether a second game instance survives. It is not derived
     * from the other fields here: when the kernel does not report it, this stays null.
     */
    val usedKb: Long?
        get() = if (totalKb != null && availableKb != null) totalKb - availableKb else null

    val usedFraction: Double?
        get() {
            val total = totalKb ?: return null
            val used = usedKb ?: return null
            return if (total > 0) used.toDouble() / total.toDouble() else null
        }
}

/** Pure parser for /proc/meminfo, which is world-readable on every supported release. */
object MemInfoParser {

    fun parse(content: String): MemInfoSnapshot {
        val fields = HashMap<String, Long>(16)
        content.lineSequence().forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon)
            val valuePart = line.substring(colon + 1).trim()
            val kb = valuePart.substringBefore(' ').toLongOrNull() ?: return@forEach
            fields[key] = kb
        }
        return MemInfoSnapshot(
            totalKb = fields["MemTotal"],
            freeKb = fields["MemFree"],
            availableKb = fields["MemAvailable"],
            cachedKb = fields["Cached"],
            swapTotalKb = fields["SwapTotal"],
            swapFreeKb = fields["SwapFree"],
        )
    }
}
