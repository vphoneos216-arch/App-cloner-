package com.akash.appcloner.engine.root

/** Which root implementation answered. [UNKNOWN] means root works but did not identify itself. */
enum class RootFlavor { KERNEL_SU, MAGISK, APATCH, UNKNOWN, NONE }

/**
 * Root availability, with the evidence kept alongside the verdict.
 *
 * [rawVersion] is whatever `su -v` actually printed. The flavour is classified from that string
 * by substring match; when the string is unfamiliar the flavour stays [RootFlavor.UNKNOWN]
 * rather than being guessed, because features are gated on capabilities below, not on the name.
 */
data class RootStatus(
    val available: Boolean,
    val flavor: RootFlavor,
    val rawVersion: String?,
    val evidence: List<String>,
) {
    companion object {
        val NONE = RootStatus(false, RootFlavor.NONE, null, emptyList())
    }
}

/**
 * Detects root once and caches the answer for the process lifetime.
 *
 * Detection is a real privileged command, not a file-existence guess: a device can carry
 * leftover su binaries that do not grant anything, and a su binary that exists but is denied
 * must read as "no root" so that root-gated features stay gated.
 */
class RootProvider {

    @Volatile
    private var cached: RootStatus? = null

    fun status(): RootStatus = cached ?: detect().also { cached = it }

    fun invalidate() {
        cached = null
    }

    val isRooted: Boolean get() = status().available

    private fun detect(): RootStatus {
        val evidence = mutableListOf<String>()

        // The authoritative check: can we actually run something as uid 0?
        val idResult = Shell.exec("id -u", asRoot = true, timeoutSeconds = 8)
        val uid = idResult.stdout.trim()
        if (!idResult.isSuccess || uid != "0") {
            val why = when {
                idResult.timedOut -> "su timed out - a permission prompt may be waiting"
                idResult.stderr.isNotBlank() -> idResult.stderr.trim().take(200)
                uid.isNotEmpty() -> "su returned uid $uid"
                else -> "su unavailable"
            }
            return RootStatus(false, RootFlavor.NONE, null, listOf(why))
        }
        evidence += "su -c 'id -u' returned 0"

        val version = Shell.exec("su -v", asRoot = false, timeoutSeconds = 8)
            .stdout.trim()
            .ifBlank { null }
        version?.let { evidence += "su -v: $it" }

        val haystack = version?.lowercase().orEmpty()
        val flavor = when {
            "kernelsu" in haystack || "ksu" in haystack -> RootFlavor.KERNEL_SU
            "apatch" in haystack -> RootFlavor.APATCH
            "magisk" in haystack -> RootFlavor.MAGISK
            else -> classifyByManagerFiles(evidence)
        }
        return RootStatus(true, flavor, version, evidence)
    }

    /**
     * Fallback classification from the manager's own data directory, checked as root because
     * /data/adb is not readable otherwise. Still best-effort: an unrecognised layout leaves the
     * flavour UNKNOWN.
     */
    private fun classifyByManagerFiles(evidence: MutableList<String>): RootFlavor {
        val probes = linkedMapOf(
            "/data/adb/ksud" to RootFlavor.KERNEL_SU,
            "/data/adb/ksu" to RootFlavor.KERNEL_SU,
            "/data/adb/ap" to RootFlavor.APATCH,
            "/data/adb/apd" to RootFlavor.APATCH,
            "/data/adb/magisk" to RootFlavor.MAGISK,
        )
        for ((path, flavor) in probes) {
            val hit = Shell.exec("test -e ${Shell.quote(path)}", asRoot = true, timeoutSeconds = 5)
            if (hit.isSuccess) {
                evidence += "found $path"
                return flavor
            }
        }
        return RootFlavor.UNKNOWN
    }
}
