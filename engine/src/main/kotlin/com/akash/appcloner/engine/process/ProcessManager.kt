package com.akash.appcloner.engine.process

import com.akash.appcloner.engine.clone.ResolveActivityParser
import com.akash.appcloner.engine.perf.Metric
import com.akash.appcloner.engine.perf.ProcessLocator
import com.akash.appcloner.engine.perf.TargetProcess
import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of a lifecycle operation, with the evidence that decided it. */
data class ProcessOutcome(
    val succeeded: Boolean,
    val message: String,
    val log: List<String>,
)

/**
 * Starting, stopping and observing an app inside a specific Android user.
 *
 * Every operation is scoped by `--user`, which is what keeps a clone's lifecycle from reaching
 * the original installation in user 0.
 */
class ProcessManager(
    private val rootProvider: RootProvider = RootProvider(),
    private val processLocator: ProcessLocator = ProcessLocator(rootProvider),
) {

    /**
     * Asks the package manager which activity to start, rather than assuming a class name.
     * Returns null when the package declares no launcher activity in that user.
     */
    fun resolveLaunchComponent(packageName: String, userId: Int): String? {
        val result = Shell.exec(
            "cmd package resolve-activity --brief --user $userId ${Shell.quote(packageName)}",
            asRoot = true,
            timeoutSeconds = 20,
        )
        return ResolveActivityParser.parse(result.stdout, packageName)
    }

    suspend fun launch(packageName: String, userId: Int): ProcessOutcome =
        withContext(Dispatchers.IO) {
            val log = mutableListOf<String>()
            if (!rootProvider.isRooted) {
                return@withContext ProcessOutcome(false, "Root is not available.", log)
            }

            // A profile that has been stopped will not accept an activity start.
            val start = Shell.exec("am start-user $userId", asRoot = true, timeoutSeconds = 40)
            log += "am start-user $userId -> ${start.exitCode}"

            val component = resolveLaunchComponent(packageName, userId)
            if (component == null) {
                return@withContext ProcessOutcome(
                    succeeded = false,
                    message = "No launcher activity for $packageName in user $userId. " +
                        "Confirm the package is installed for that user.",
                    log = log,
                )
            }
            log += "component: $component"

            val result = Shell.exec(
                "am start --user $userId -n ${Shell.quote(component)}",
                asRoot = true,
                timeoutSeconds = 40,
            )
            log += result.stdout.trim().ifEmpty { "am start exit ${result.exitCode}" }

            // `am start` reports success even for some failures, so the process list decides.
            val running = awaitProcess(packageName, userId)
            ProcessOutcome(
                succeeded = running,
                message = if (running) {
                    "Started $packageName in user $userId."
                } else {
                    "am start returned, but no process for $packageName appeared in user " +
                        "$userId. Output: ${result.stdout.trim().take(200)}"
                },
                log = log,
            )
        }

    suspend fun forceStop(packageName: String, userId: Int): ProcessOutcome =
        withContext(Dispatchers.IO) {
            if (!rootProvider.isRooted) {
                return@withContext ProcessOutcome(false, "Root is not available.", emptyList())
            }
            val result = Shell.exec(
                "am force-stop --user $userId ${Shell.quote(packageName)}",
                asRoot = true,
                timeoutSeconds = 30,
            )
            val stillRunning = runningProcesses(packageName, userId).isNotEmpty()
            ProcessOutcome(
                succeeded = result.isSuccess && !stillRunning,
                message = when {
                    !result.isSuccess -> "am force-stop failed: ${result.stderr.trim().take(160)}"
                    stillRunning -> "force-stop returned but a process is still listed."
                    else -> "Stopped $packageName in user $userId."
                },
                log = listOf("am force-stop --user $userId $packageName"),
            )
        }

    fun runningProcesses(packageName: String, userId: Int): List<TargetProcess> =
        when (val located = processLocator.processesFor(packageName, userId)) {
            is Metric.Value -> located.value
            is Metric.Unavailable -> emptyList()
        }

    fun isRunning(packageName: String, userId: Int): Boolean =
        runningProcesses(packageName, userId).isNotEmpty()

    /** Polls briefly, because process creation is not synchronous with `am start` returning. */
    private fun awaitProcess(packageName: String, userId: Int, attempts: Int = 10): Boolean {
        repeat(attempts) {
            if (isRunning(packageName, userId)) return true
            Thread.sleep(500)
        }
        return false
    }
}
