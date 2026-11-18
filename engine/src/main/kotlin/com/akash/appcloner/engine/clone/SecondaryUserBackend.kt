package com.akash.appcloner.engine.clone

import android.content.Context
import android.os.UserManager
import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import com.akash.appcloner.engine.root.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Clones into a secondary Android user, created with root.
 *
 * Full platform isolation: separate data directories, separate external storage, separate
 * accounts, and the APK itself is shared, so a game update applies to every instance without
 * recreating anything.
 *
 * The honest limitation is concurrency. Secondary users are not foreground-concurrent on
 * Android, so using the clone means switching users; the original instance is not running at
 * that moment. For two instances live at once, use the managed-profile backend.
 */
class SecondaryUserBackend(
    private val context: Context,
    private val rootProvider: RootProvider = RootProvider(),
) : CloneBackend {

    private companion object {
        const val USER_NAME_PREFIX = "clone_"
    }

    override val kind = CloneBackendKind.SECONDARY_USER

    override val capabilities = BackendCapabilities(
        kind = kind,
        concurrentWithOriginal = false,
        preservesPackageIdentity = true,
        survivesGameUpdate = true,
        runsOnUnmodifiedRuntime = true,
        requiresRoot = true,
        maxClonesPerPackage = null,
        perCloneRootControl = true,
    )

    override suspend fun availability(): BackendAvailability = withContext(Dispatchers.IO) {
        if (!UserManager.supportsMultipleUsers()) {
            return@withContext BackendAvailability.Unavailable(
                reason = "This build of Android reports no multi-user support.",
                remedy = "Use the managed-profile backend.",
            )
        }
        val root = rootProvider.status()
        if (!root.available) {
            return@withContext BackendAvailability.NeedsSetup(
                reason = "Creating a secondary user needs root. " +
                    root.evidence.firstOrNull().orEmpty(),
                action = BackendAvailability.SetupAction.GRANT_ROOT,
            )
        }
        val max = maxUsers()
        val current = listUsers().size
        if (max != null && current >= max) {
            return@withContext BackendAvailability.Unavailable(
                reason = "Device user limit reached ($current of $max).",
                remedy = "Remove an existing user, or raise fw.max_users on a device you control.",
            )
        }
        BackendAvailability.Available
    }

    fun maxUsers(): Int? = PmOutputParser.parseMaxUsers(
        Shell.exec("pm get-max-users", asRoot = true).stdout,
    )

    fun listUsers(): List<AndroidUser> = PmOutputParser.parseUserList(
        Shell.exec("pm list users", asRoot = true).stdout,
    )

    override suspend fun listClones(): List<CloneRecord> = withContext(Dispatchers.IO) {
        if (!rootProvider.isRooted) return@withContext emptyList()
        val users = listUsers().filter { it.name.startsWith(USER_NAME_PREFIX) }
        users.flatMap { user ->
            val packages = Shell.exec(
                "pm list packages --user ${user.id}",
                asRoot = true,
            ).lines.mapNotNull { it.removePrefix("package:").trim().ifBlank { null } }

            packages.map { pkg ->
                CloneRecord(
                    id = "user:${user.id}:$pkg",
                    packageName = pkg,
                    label = pkg,
                    backend = kind,
                    androidUserId = user.id,
                    state = if (user.running) CloneState.RUNNING else CloneState.READY,
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
                return@withContext CloneResult.Failure(
                    message = "Root is not available.",
                    nextStep = "Grant this app root in KernelSU, Magisk or APatch and retry.",
                )
            }

            val userName = USER_NAME_PREFIX + request.packageName.substringAfterLast('.')
                .take(16).filter { it.isLetterOrDigit() || it == '_' }

            val create = run("pm create-user ${Shell.quote(userName)}", log)
            val userId = PmOutputParser.parseCreatedUserId(create.stdout)
                ?: return@withContext CloneResult.Failure(
                    message = "Could not create a user: ${create.stdout.trim().ifBlank { create.stderr.trim() }}",
                    nextStep = "Check pm get-max-users and remove an unused user.",
                    log = log,
                )

            val installResult = if (request.forcedAbi == null) {
                run("pm install-existing --user $userId ${Shell.quote(request.packageName)}", log)
            } else {
                installWithPinnedAbi(userId, request.packageName, request.forcedAbi, log)
            }

            if (!PmOutputParser.isSuccess(installResult.stdout) &&
                !installResult.stdout.contains("installed for user", ignoreCase = true)
            ) {
                run("pm remove-user $userId", log)
                return@withContext CloneResult.Failure(
                    message = "Install into user $userId failed: " +
                        installResult.stdout.trim().ifBlank { installResult.stderr.trim() },
                    nextStep = if (request.forcedAbi != null) {
                        "Retry without pinning the ABI; --abi is not accepted on every release."
                    } else {
                        "Confirm the package is installed for the primary user."
                    },
                    log = log,
                )
            }

            CloneResult.Success(
                CloneRecord(
                    id = "user:$userId:${request.packageName}",
                    packageName = request.packageName,
                    label = request.label,
                    backend = kind,
                    androidUserId = userId,
                    state = CloneState.READY,
                    rootAllowed = false,
                    forcedAbi = request.forcedAbi,
                    createdAtMillis = System.currentTimeMillis(),
                ),
                log = log,
            )
        }

    /**
     * Session install from the APK files already on the device, with the ABI pinned.
     *
     * `pm install-existing` cannot pin an ABI, so the 32-bit leg of the test matrix goes through
     * a staged session instead. Every split is written, because a split install whose ABI split
     * is missing loads no native code at all.
     *
     * Whether `--abi` is honoured varies between Android releases; the command output is kept in
     * [log] verbatim so a rejection is visible instead of being inferred from a later crash.
     */
    private fun installWithPinnedAbi(
        userId: Int,
        packageName: String,
        abi: String,
        log: MutableList<String>,
    ): ShellResult {
        val pathsResult = run("pm path --user 0 ${Shell.quote(packageName)}", log)
        val apkPaths = pathsResult.lines
            .mapNotNull { it.substringAfter("package:", "").trim().ifBlank { null } }
        if (apkPaths.isEmpty()) {
            return ShellResult.failure("pm path returned no APK for $packageName")
        }

        val create = run(
            "pm install-create --user $userId --abi ${Shell.quote(abi)} -r -i ${Shell.quote(packageName)}",
            log,
        )
        val session = PmOutputParser.parseSessionId(create.stdout)
            ?: return ShellResult.failure(
                "install-create gave no session id: ${create.stdout.trim()}",
            )

        apkPaths.forEachIndexed { index, path ->
            val size = Shell.exec(
                "stat -c %s ${Shell.quote(path)}",
                asRoot = true,
            ).stdout.trim().toLongOrNull()
            val splitName = File(path).name.ifBlank { "split_$index" }
            val sizeArg = if (size != null && size > 0) "-S $size " else ""
            run(
                "pm install-write $sizeArg$session ${Shell.quote(splitName)} ${Shell.quote(path)}",
                log,
            )
        }

        val commit = run("pm install-commit $session", log)
        if (!PmOutputParser.isSuccess(commit.stdout)) {
            run("pm install-abandon $session", log)
        }
        return commit
    }

    /**
     * Brings the clone to the foreground. This switches the foreground user, so the original
     * instance stops being interactive - that is inherent to secondary users, not a defect here.
     */
    override suspend fun launch(clone: CloneRecord): CloneResult = withContext(Dispatchers.IO) {
        val userId = clone.androidUserId
            ?: return@withContext CloneResult.Failure("Clone has no Android user id")
        if (!rootProvider.isRooted) {
            return@withContext CloneResult.Failure("Root is not available")
        }
        val log = mutableListOf<String>()
        run("am start-user $userId", log)
        val switch = run("am switch-user $userId", log)
        if (!switch.isSuccess) {
            return@withContext CloneResult.Failure(
                message = "Could not switch to user $userId: ${switch.stderr.trim()}",
                nextStep = "Switch users from the system UI, then start the game there.",
                log = log,
            )
        }
        val start = run(
            "monkey --pct-syskeys 0 -p ${Shell.quote(clone.packageName)} --user $userId 1",
            log,
        )
        if (!start.isSuccess) {
            return@withContext CloneResult.Failure(
                message = "User switched, but the game did not start automatically.",
                nextStep = "Launch it from the launcher in that user.",
                log = log,
            )
        }
        CloneResult.Success(clone.copy(state = CloneState.RUNNING), log)
    }

    override suspend fun remove(clone: CloneRecord): CloneResult = withContext(Dispatchers.IO) {
        val userId = clone.androidUserId
            ?: return@withContext CloneResult.Failure("Clone has no Android user id")
        if (userId == 0) {
            return@withContext CloneResult.Failure(
                message = "Refusing to remove user 0.",
                nextStep = "User 0 is the original installation.",
            )
        }
        val log = mutableListOf<String>()
        val result = run("pm remove-user $userId", log)
        if (PmOutputParser.isSuccess(result.stdout)) {
            CloneResult.Success(clone.copy(state = CloneState.REMOVED), log)
        } else {
            CloneResult.Failure(
                message = "pm remove-user failed: ${result.stdout.trim().ifBlank { result.stderr.trim() }}",
                log = log,
            )
        }
    }

    /**
     * Root managers do not expose a stable, documented interface for granting root to one uid,
     * so this reports what has to be done by hand instead of running an undocumented command
     * whose schema changes between manager versions.
     */
    override suspend fun setRootAccess(clone: CloneRecord, allowed: Boolean): CloneResult {
        val verb = if (allowed) "allow" else "deny"
        return CloneResult.Unsupported(
            message = "Per-uid root policy is owned by the installed root manager.",
            manualInstruction = "In KernelSU, Magisk or APatch, open the superuser list and " +
                "$verb ${clone.packageName} for user ${clone.androidUserId}. " +
                "The uid to look for is ${clone.androidUserId}xxxxx " +
                "(user id * 100000 + the app id).",
        )
    }

    private fun run(command: String, log: MutableList<String>): ShellResult {
        val result = Shell.exec(command, asRoot = true, timeoutSeconds = 60)
        log += "$ $command"
        result.stdout.trim().takeIf { it.isNotEmpty() }?.let { log += it }
        result.stderr.trim().takeIf { it.isNotEmpty() }?.let { log += "stderr: $it" }
        return result
    }
}
