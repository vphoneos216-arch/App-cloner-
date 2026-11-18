package com.akash.appcloner.engine.clone

import android.content.Context
import android.os.Build
import com.akash.appcloner.engine.compat.StoragePaths
import com.akash.appcloner.engine.process.ProcessManager
import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import com.akash.appcloner.engine.root.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloning onto an AOSP clone profile.
 *
 * ## Why this mechanism
 *
 * `android.os.usertype.profile.CLONE` is a profile of the main user that AOSP added for exactly
 * this purpose: running a second instance of an app that only supports one account. It is not a
 * managed profile and no device policy controller is involved.
 *
 * What it gives, all at once, which no other approach does:
 *  - the clone keeps the original package name and the original signing certificate, so nothing
 *    that is bound to the app's identity is disturbed;
 *  - it gets its own UID (`userId * 100000 + appId`) and its own `/data/user/<id>/<pkg>`;
 *  - it runs at the same time as the original, because a profile starts with its parent and
 *    shows in the same launcher - unlike a secondary user, which needs `am switch-user`;
 *  - the APK is shared, so a game update applies to both instances with nothing to recreate.
 *
 * Root is required, and genuinely used: `UserManagerService.hasManageUsersPermission` accepts
 * `Process.ROOT_UID`, which is why `su -c pm create-user` is accepted where an ordinary app
 * call is rejected.
 *
 * ## The limit, stated plainly
 *
 * `UserTypeFactory` sets `maxAllowedPerParent(1)` for this type, so stock Android allows one
 * clone profile. One profile holds one copy of any given package, so:
 *  - cloning several *different* apps works inside the single profile;
 *  - a *second* instance of the *same* app needs a second clone profile, which stock Android
 *    refuses. That is reported as a limit with its remedy, never worked around silently.
 *
 * Two concurrent instances of one package inside one user is not a permission problem that root
 * can solve: ActivityManager keys process records by (process name, uid), and the uid is fixed
 * per user and app id. A different user, a different package identity, or a virtualisation
 * layer is required, and this backend chooses the first.
 */
class CloneProfileBackend(
    private val context: Context,
    private val rootProvider: RootProvider = RootProvider(),
    private val processManager: ProcessManager = ProcessManager(rootProvider),
) : CloneBackend {

    companion object {
        /** The user type exists from Android 12; it became public SDK API in 35. */
        const val MIN_SDK = 31
        const val PARENT_USER = 0
        private const val PROFILE_NAME_PREFIX = "AppClone"
    }

    override val kind = CloneBackendKind.CLONE_PROFILE

    override val capabilities = BackendCapabilities(
        kind = kind,
        concurrentWithOriginal = true,
        preservesPackageIdentity = true,
        survivesGameUpdate = true,
        runsOnUnmodifiedRuntime = true,
        requiresRoot = true,
        // Device-configured: stock is one profile, so one clone of a given package.
        maxClonesPerPackage = null,
        perCloneRootControl = false,
    )

    // ---------------------------------------------------------------- discovery

    /** Every Android user, with its type where the platform discloses one. */
    fun users(): List<TypedUser> {
        val listed = PmOutputParser.parseUserList(
            Shell.exec("pm list users", asRoot = true, timeoutSeconds = 20).stdout,
        )
        val typed = DumpsysUserParser.parse(
            Shell.exec("dumpsys user", asRoot = true, timeoutSeconds = 30).stdout,
        )
        return DumpsysUserParser.merge(listed, typed)
    }

    fun cloneProfiles(): List<TypedUser> = users().filter { it.isCloneProfile }

    /** Profiles that look like an OEM dual-app container and could be reused. */
    fun oemDualAppProfiles(): List<TypedUser> = users().filter { it.isOemDualAppCandidate }

    /** Third-party packages installed in a user. System packages are excluded. */
    fun thirdPartyPackages(userId: Int): List<String> =
        Shell.exec("pm list packages -3 --user $userId", asRoot = true, timeoutSeconds = 30)
            .lines
            .mapNotNull { it.removePrefix("package:").trim().ifBlank { null } }

    override suspend fun availability(): BackendAvailability = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            return@withContext BackendAvailability.Unavailable(
                reason = "The clone profile user type does not exist before Android 12.",
                remedy = "Use the secondary-user backend on this release.",
            )
        }
        val root = rootProvider.status()
        if (!root.available) {
            return@withContext BackendAvailability.NeedsSetup(
                reason = "Creating a clone profile needs uid 0. " +
                    root.evidence.firstOrNull().orEmpty(),
                action = BackendAvailability.SetupAction.GRANT_ROOT,
            )
        }
        BackendAvailability.Available
    }

    override suspend fun listClones(): List<CloneRecord> = withContext(Dispatchers.IO) {
        if (!rootProvider.isRooted) return@withContext emptyList()
        cloneProfiles().flatMap { profile ->
            thirdPartyPackages(profile.id).map { pkg ->
                CloneRecord(
                    id = "clone:${profile.id}:$pkg",
                    packageName = pkg,
                    label = pkg,
                    backend = kind,
                    androidUserId = profile.id,
                    state = if (processManager.isRunning(pkg, profile.id)) {
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

    // ---------------------------------------------------------------- creation

    override suspend fun createClone(request: CloneRequest): CloneResult =
        withContext(Dispatchers.IO) {
            val log = mutableListOf<String>()
            if (!rootProvider.isRooted) {
                return@withContext CloneResult.Failure(
                    message = "Root is not available, so no clone profile can be created.",
                    nextStep = "Grant this app root in KernelSU, Magisk or APatch and retry.",
                )
            }
            if (request.forcedAbi != null) {
                return@withContext CloneResult.Unsupported(
                    message = "A clone profile reuses the installed APK, so its ABI cannot be " +
                        "pinned.",
                    manualInstruction = "Use the secondary-user backend for the 32-bit leg of " +
                        "the test matrix; it installs through a staged session.",
                )
            }

            // Confirm the package exists in the parent user before touching anything.
            val parentPackages = Shell.exec(
                "pm list packages --user $PARENT_USER ${Shell.quote(request.packageName)}",
                asRoot = true,
            )
            if (!parentPackages.stdout.contains("package:${request.packageName}")) {
                return@withContext CloneResult.Failure(
                    message = "${request.packageName} is not installed for user $PARENT_USER.",
                    nextStep = "Install the app normally first; a clone reuses that install.",
                )
            }

            val existing = cloneProfiles()
            log += "existing clone profiles: " +
                (existing.joinToString { "${it.id}(${it.name})" }.ifEmpty { "none" })

            // A profile holds one copy of a package, so reuse any profile that lacks it.
            val reusable = existing.firstOrNull { profile ->
                request.packageName !in thirdPartyPackages(profile.id)
            }

            val profileId = if (reusable != null) {
                log += "reusing clone profile ${reusable.id}"
                reusable.id
            } else {
                when (val created = createCloneProfile(request.label, log)) {
                    is ProfileCreation.Created -> created.userId
                    is ProfileCreation.Failed -> {
                        val alreadyHasIt = existing.any {
                            request.packageName in thirdPartyPackages(it.id)
                        }
                        val message = if (alreadyHasIt) {
                            "${request.packageName} is already cloned in profile " +
                                "${existing.first().id}, and a second instance needs another " +
                                "clone profile."
                        } else {
                            "Could not create a clone profile: ${created.rawOutput.take(200)}"
                        }
                        return@withContext CloneResult.Unsupported(
                            message = message,
                            manualInstruction = CloneProfileErrorParser.remedyFor(created.failure),
                        )
                    }
                }
            }

            // Profiles start with their parent, but a stopped one will not accept an install.
            Shell.exec("am start-user $profileId", asRoot = true, timeoutSeconds = 60)
                .also { log += "am start-user $profileId -> ${it.exitCode}" }

            val install = Shell.exec(
                "pm install-existing --user $profileId ${Shell.quote(request.packageName)}",
                asRoot = true,
                timeoutSeconds = 120,
            )
            log += "pm install-existing --user $profileId ${request.packageName}"
            install.stdout.trim().takeIf { it.isNotEmpty() }?.let { log += it }
            install.stderr.trim().takeIf { it.isNotEmpty() }?.let { log += "stderr: $it" }

            // Verify against the platform rather than trusting the command's own output.
            val verification = verifyClone(request.packageName, profileId, log)
            if (!verification.installed) {
                return@withContext CloneResult.Failure(
                    message = "The package is not listed for user $profileId after install.",
                    nextStep = "The operation log holds the exact command output.",
                    log = log,
                )
            }

            CloneResult.Success(
                CloneRecord(
                    id = "clone:$profileId:${request.packageName}",
                    packageName = request.packageName,
                    label = request.label,
                    backend = kind,
                    androidUserId = profileId,
                    state = CloneState.READY,
                    rootAllowed = false,
                    forcedAbi = null,
                    createdAtMillis = System.currentTimeMillis(),
                ),
                log = log,
            )
        }

    private sealed interface ProfileCreation {
        data class Created(val userId: Int) : ProfileCreation
        data class Failed(val failure: CloneProfileFailure, val rawOutput: String) : ProfileCreation
    }

    /**
     * Creates the profile.
     *
     * `pm create-user` is the primary form, because some OEM builds moved user creation off
     * `cmd user`; the `cmd user` form is tried only as a fallback, and both outputs are kept.
     */
    private fun createCloneProfile(label: String, log: MutableList<String>): ProfileCreation {
        val name = PROFILE_NAME_PREFIX + label.filter { it.isLetterOrDigit() }.take(12)
        val args = "--profileOf $PARENT_USER --user-type ${UserTypes.CLONE_PROFILE} " +
            Shell.quote(name)

        val primary = Shell.exec("pm create-user $args", asRoot = true, timeoutSeconds = 90)
        log += "pm create-user $args"
        primary.stdout.trim().takeIf { it.isNotEmpty() }?.let { log += it }
        primary.stderr.trim().takeIf { it.isNotEmpty() }?.let { log += "stderr: $it" }

        PmOutputParser.parseCreatedUserId(primary.stdout)?.let {
            return ProfileCreation.Created(it)
        }

        val combined = primary.stdout + "\n" + primary.stderr
        val failure = CloneProfileErrorParser.classify(combined)
        if (failure != CloneProfileFailure.COMMAND_UNSUPPORTED) {
            return ProfileCreation.Failed(failure, combined)
        }

        val fallback = Shell.exec("cmd user create-user $args", asRoot = true, timeoutSeconds = 90)
        log += "cmd user create-user $args"
        fallback.stdout.trim().takeIf { it.isNotEmpty() }?.let { log += it }
        PmOutputParser.parseCreatedUserId(fallback.stdout)?.let {
            return ProfileCreation.Created(it)
        }
        val fallbackCombined = fallback.stdout + "\n" + fallback.stderr
        return ProfileCreation.Failed(
            CloneProfileErrorParser.classify(fallbackCombined),
            fallbackCombined,
        )
    }

    /** What was actually observed after an install attempt. */
    data class CloneVerification(
        val installed: Boolean,
        val credentialDataDir: Boolean,
        val deviceDataDir: Boolean,
        val externalDataDir: Boolean,
        val uid: Int?,
    )

    /**
     * Confirms the clone from the platform's own state: the package list for that user, the
     * per-user data directories, and the uid the app received there.
     */
    fun verifyClone(
        packageName: String,
        userId: Int,
        log: MutableList<String>? = null,
    ): CloneVerification {
        val listed = Shell.exec(
            "pm list packages --user $userId ${Shell.quote(packageName)}",
            asRoot = true,
        ).stdout.contains("package:$packageName")

        fun dirExists(path: String) =
            Shell.exec("test -d ${Shell.quote(path)}", asRoot = true).isSuccess

        val credential = dirExists(StoragePaths.privateDataDir(userId, packageName))
        val device = dirExists(StoragePaths.privateDeDataDir(userId, packageName))
        val external = dirExists(StoragePaths.externalDataDir(userId, packageName))

        val uid = PackageUidParser.parse(
            Shell.exec(
                "pm list packages -U --user $userId ${Shell.quote(packageName)}",
                asRoot = true,
            ).stdout,
            packageName,
        )

        log?.add(
            "verified: listed=$listed ce=$credential de=$device external=$external uid=$uid",
        )
        return CloneVerification(listed, credential, device, external, uid)
    }

    // ---------------------------------------------------------------- lifecycle

    override suspend fun launch(clone: CloneRecord): CloneResult {
        val userId = clone.androidUserId
            ?: return CloneResult.Failure("Clone has no Android user id")
        val outcome = processManager.launch(clone.packageName, userId)
        return if (outcome.succeeded) {
            CloneResult.Success(clone.copy(state = CloneState.RUNNING), outcome.log)
        } else {
            CloneResult.Failure(outcome.message, log = outcome.log)
        }
    }

    suspend fun stop(clone: CloneRecord): CloneResult {
        val userId = clone.androidUserId
            ?: return CloneResult.Failure("Clone has no Android user id")
        val outcome = processManager.forceStop(clone.packageName, userId)
        return if (outcome.succeeded) {
            CloneResult.Success(clone.copy(state = CloneState.READY), outcome.log)
        } else {
            CloneResult.Failure(outcome.message, log = outcome.log)
        }
    }

    /**
     * Removes the clone and nothing else.
     *
     * The uninstall is scoped with `--user`, which is what leaves the original installation and
     * its data in user 0 untouched. An empty clone profile this app created is then removed as
     * well, because stock Android allows only one and leaving it empty would consume the slot.
     */
    override suspend fun remove(clone: CloneRecord): CloneResult = withContext(Dispatchers.IO) {
        val userId = clone.androidUserId
            ?: return@withContext CloneResult.Failure("Clone has no Android user id")
        if (userId == PARENT_USER) {
            return@withContext CloneResult.Failure(
                message = "Refusing to uninstall from user $PARENT_USER.",
                nextStep = "That is the original installation, not a clone.",
            )
        }
        val log = mutableListOf<String>()
        processManager.forceStop(clone.packageName, userId)

        val uninstall = Shell.exec(
            "pm uninstall --user $userId ${Shell.quote(clone.packageName)}",
            asRoot = true,
            timeoutSeconds = 90,
        )
        log += "pm uninstall --user $userId ${clone.packageName}: ${uninstall.stdout.trim()}"

        if (!PmOutputParser.isSuccess(uninstall.stdout)) {
            return@withContext CloneResult.Failure(
                message = "Uninstall for user $userId failed: " +
                    uninstall.stdout.trim().ifBlank { uninstall.stderr.trim() },
                log = log,
            )
        }

        val remaining = thirdPartyPackages(userId)
        if (remaining.isEmpty()) {
            val removed = Shell.exec("pm remove-user $userId", asRoot = true, timeoutSeconds = 90)
            log += "clone profile $userId empty, removed: ${removed.stdout.trim()}"
        } else {
            log += "clone profile $userId kept, still holds: ${remaining.joinToString()}"
        }

        CloneResult.Success(clone.copy(state = CloneState.REMOVED), log)
    }

    override suspend fun setRootAccess(clone: CloneRecord, allowed: Boolean): CloneResult {
        val uid = clone.androidUserId?.let { userId ->
            verifyClone(clone.packageName, userId).uid
        }
        val verb = if (allowed) "allow" else "deny"
        return CloneResult.Unsupported(
            message = "Per-uid root policy belongs to the installed root manager; none of " +
                "KernelSU, Magisk or APatch exposes a stable documented interface for it.",
            manualInstruction = "Open your root manager's superuser list and $verb " +
                "${clone.packageName}" +
                (uid?.let { " (uid $it)" } ?: " in user ${clone.androidUserId}") + ".",
        )
    }
}
