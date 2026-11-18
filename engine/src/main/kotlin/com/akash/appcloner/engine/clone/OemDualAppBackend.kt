package com.akash.appcloner.engine.clone

import android.content.Context
import com.akash.appcloner.engine.process.ProcessManager
import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloning into an OEM dual-app container that already exists on the device.
 *
 * Several vendors ship their own second-instance feature and back it with a hidden user:
 * OnePlus "Parallel Apps" has been observed as user 999, Samsung "Dual Messenger" as user 95
 * named `DUAL_APP`. These users are often not the AOSP clone type, so this backend never claims
 * they are - it reuses whatever the platform already has rather than creating anything.
 *
 * This matters on two kinds of device: ROMs that do not define the AOSP clone profile type at
 * all, and ROMs where the vendor has already consumed the single clone slot.
 *
 * Reuse only. The OEM created the container and owns it, so this backend installs into it and
 * uninstalls from it, and never removes the user itself.
 */
class OemDualAppBackend(
    private val context: Context,
    private val rootProvider: RootProvider = RootProvider(),
    private val processManager: ProcessManager = ProcessManager(rootProvider),
) : CloneBackend {

    private companion object {
        const val PARENT_USER = 0
    }

    override val kind = CloneBackendKind.OEM_DUAL_APP

    override val capabilities = BackendCapabilities(
        kind = kind,
        // These containers exist so the vendor's own dual-app feature can run alongside the
        // original, so concurrency is their design intent. Launch verifies it per device.
        concurrentWithOriginal = true,
        preservesPackageIdentity = true,
        survivesGameUpdate = true,
        runsOnUnmodifiedRuntime = true,
        requiresRoot = true,
        maxClonesPerPackage = null,
        perCloneRootControl = true,
    )

    /** Users that look like a vendor dual-app container, plus any untyped non-system profile. */
    fun candidates(): List<TypedUser> {
        val dump = Shell.exec("dumpsys user", asRoot = true, timeoutSeconds = 30).stdout
        val listed = PmOutputParser.parseUserList(
            Shell.exec("pm list users", asRoot = true, timeoutSeconds = 20).stdout,
        )
        return DumpsysUserParser.merge(listed, DumpsysUserParser.parse(dump))
            .filter { it.id != PARENT_USER }
            // Only untyped vendor containers. A real clone profile is reused by
            // CloneProfileBackend, and the two predicates are mutually exclusive by
            // construction, so there is no overlap to resolve here.
            .filter { it.isOemDualAppCandidate }
    }

    override suspend fun availability(): BackendAvailability = withContext(Dispatchers.IO) {
        if (!rootProvider.isRooted) {
            return@withContext BackendAvailability.NeedsSetup(
                reason = "Installing into another user needs uid 0.",
                action = BackendAvailability.SetupAction.GRANT_ROOT,
            )
        }
        val found = candidates()
        if (found.isEmpty()) {
            BackendAvailability.Unavailable(
                reason = "No OEM dual-app container user was found on this device.",
                remedy = "Turn on the vendor's own dual-app feature once for any app, which " +
                    "creates the container, then retry. Otherwise a secondary user is used.",
            )
        } else {
            BackendAvailability.Available
        }
    }

    override suspend fun listClones(): List<CloneRecord> = withContext(Dispatchers.IO) {
        if (!rootProvider.isRooted) return@withContext emptyList()
        candidates().flatMap { user ->
            Shell.exec("pm list packages -3 --user ${user.id}", asRoot = true, timeoutSeconds = 30)
                .lines
                .mapNotNull { it.removePrefix("package:").trim().ifBlank { null } }
                .map { pkg ->
                    CloneRecord(
                        id = "oem:${user.id}:$pkg",
                        packageName = pkg,
                        label = pkg,
                        backend = kind,
                        androidUserId = user.id,
                        state = if (processManager.isRunning(pkg, user.id)) {
                            CloneState.RUNNING
                        } else {
                            CloneState.READY
                        },
                        rootAllowed = false,
                        forcedAbi = null,
                        createdAtMillis = 0L,
                    )
                }
        }
    }

    override suspend fun createClone(request: CloneRequest): CloneResult =
        withContext(Dispatchers.IO) {
            val log = mutableListOf<String>()
            if (!rootProvider.isRooted) {
                return@withContext CloneResult.Failure("Root is not available.")
            }
            if (request.forcedAbi != null) {
                return@withContext CloneResult.Unsupported(
                    message = "Installing an existing package into another user cannot pin an ABI.",
                    manualInstruction = "Use the secondary-user backend for a pinned ABI.",
                )
            }

            val target = candidates().firstOrNull { user ->
                val installed = Shell.exec(
                    "pm list packages --user ${user.id} ${Shell.quote(request.packageName)}",
                    asRoot = true,
                ).stdout
                !installed.contains("package:${request.packageName}")
            } ?: return@withContext CloneResult.Unsupported(
                message = "Every OEM container already holds ${request.packageName}.",
                manualInstruction = "A further instance needs another container user, which " +
                    "only the vendor's own feature or a secondary user can provide.",
            )

            log += "reusing OEM container user ${target.id} (${target.name}), " +
                "type ${target.type ?: "not reported"}"

            Shell.exec("am start-user ${target.id}", asRoot = true, timeoutSeconds = 60)
                .also { log += "am start-user ${target.id} -> ${it.exitCode}" }

            val install = Shell.exec(
                "pm install-existing --user ${target.id} ${Shell.quote(request.packageName)}",
                asRoot = true,
                timeoutSeconds = 120,
            )
            log += "pm install-existing --user ${target.id} ${request.packageName}"
            install.stdout.trim().takeIf { it.isNotEmpty() }?.let { log += it }
            install.stderr.trim().takeIf { it.isNotEmpty() }?.let { log += "stderr: $it" }

            val listed = Shell.exec(
                "pm list packages --user ${target.id} ${Shell.quote(request.packageName)}",
                asRoot = true,
            ).stdout.contains("package:${request.packageName}")

            if (!listed) {
                return@withContext CloneResult.Failure(
                    message = "The package is not listed for user ${target.id} after install.",
                    nextStep = "Some vendors restrict which packages may enter their container. " +
                        "The log holds the exact output.",
                    log = log,
                )
            }

            CloneResult.Success(
                CloneRecord(
                    id = "oem:${target.id}:${request.packageName}",
                    packageName = request.packageName,
                    label = request.label,
                    backend = kind,
                    androidUserId = target.id,
                    state = CloneState.READY,
                    rootAllowed = false,
                    forcedAbi = null,
                    createdAtMillis = System.currentTimeMillis(),
                ),
                log = log,
            )
        }

    override suspend fun launch(clone: CloneRecord): CloneResult {
        val userId = clone.androidUserId
            ?: return CloneResult.Failure("Clone has no Android user id")
        val outcome = processManager.launch(clone.packageName, userId)
        return if (outcome.succeeded) {
            CloneResult.Success(clone.copy(state = CloneState.RUNNING), outcome.log)
        } else {
            CloneResult.Failure(
                message = outcome.message + " If this container is a full secondary user rather " +
                    "than a profile, it needs a user switch to become interactive.",
                log = outcome.log,
            )
        }
    }

    /** Uninstalls from the container. The container user itself is the vendor's, so it stays. */
    override suspend fun remove(clone: CloneRecord): CloneResult = withContext(Dispatchers.IO) {
        val userId = clone.androidUserId
            ?: return@withContext CloneResult.Failure("Clone has no Android user id")
        if (userId == PARENT_USER) {
            return@withContext CloneResult.Failure(
                message = "Refusing to uninstall from user $PARENT_USER.",
                nextStep = "That is the original installation.",
            )
        }
        processManager.forceStop(clone.packageName, userId)
        val result = Shell.exec(
            "pm uninstall --user $userId ${Shell.quote(clone.packageName)}",
            asRoot = true,
            timeoutSeconds = 90,
        )
        if (PmOutputParser.isSuccess(result.stdout)) {
            CloneResult.Success(
                clone.copy(state = CloneState.REMOVED),
                listOf("pm uninstall --user $userId ${clone.packageName}", "container user kept"),
            )
        } else {
            CloneResult.Failure(
                "Uninstall for user $userId failed: " +
                    result.stdout.trim().ifBlank { result.stderr.trim() },
            )
        }
    }

    override suspend fun setRootAccess(clone: CloneRecord, allowed: Boolean): CloneResult =
        CloneResult.Unsupported(
            message = "Root policy is applied through the root manager, not through this backend.",
            manualInstruction = "Use the Root tab, which writes the policy where the detected " +
                "manager keeps it and reads it back.",
        )
}
