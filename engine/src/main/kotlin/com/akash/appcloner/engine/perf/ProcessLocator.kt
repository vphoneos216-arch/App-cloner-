package com.akash.appcloner.engine.perf

import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell

/** A process belonging to a target package. */
data class TargetProcess(
    val pid: Int,
    val name: String,
    val user: String?,
) {
    /** Android user id derived from the `uXX_aYYY` shell user name, when it has that shape. */
    val androidUserId: Int?
        get() = user
            ?.removePrefix("u")
            ?.substringBefore('_')
            ?.toIntOrNull()
}

/**
 * Finds the processes of another package.
 *
 * Android 9 onwards mounts /proc with hidepid, so an ordinary app cannot see another app's
 * processes at all - not its pid, not its memory, not its CPU time. Without root this reports
 * [Metric.needsRoot] rather than an empty list, because an empty list would read as "the game
 * is not running".
 */
class ProcessLocator(private val rootProvider: RootProvider = RootProvider()) {

    fun processesFor(packageName: String, androidUserId: Int? = null): Metric<List<TargetProcess>> {
        if (!rootProvider.isRooted) {
            return Metric.needsRoot("Another app's process list")
        }

        // ps carries the user column, which is what distinguishes the clone's process from the
        // original's when both are running the same package.
        val ps = Shell.exec("ps -A -o PID,USER,NAME", asRoot = true, timeoutSeconds = 15)
        if (ps.isSuccess) {
            val matches = ps.lines
                .asSequence()
                .drop(1)
                .mapNotNull { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    if (columns.size < 3) return@mapNotNull null
                    val pid = columns[0].toIntOrNull() ?: return@mapNotNull null
                    val name = columns.last()
                    if (!name.startsWith(packageName)) return@mapNotNull null
                    TargetProcess(pid, name, columns[1])
                }
                .filter { androidUserId == null || it.androidUserId == androidUserId }
                .toList()
            if (matches.isNotEmpty()) return Metric.Value(matches, "ps -A")
        }

        val pidof = Shell.exec("pidof ${Shell.quote(packageName)}", asRoot = true, timeoutSeconds = 10)
        val pids = pidof.stdout.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
        return if (pids.isEmpty()) {
            Metric.Value(emptyList(), "pidof")
        } else {
            Metric.Value(pids.map { TargetProcess(it, packageName, null) }, "pidof")
        }
    }
}
