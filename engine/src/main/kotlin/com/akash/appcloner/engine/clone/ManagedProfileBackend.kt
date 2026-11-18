package com.akash.appcloner.engine.clone

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.PackageManager
import android.os.UserHandle
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clones into a managed (work) profile.
 *
 * This is the only backend that gives a second instance running at the same time as the
 * original without root. The profile is a real Android user, so the game runs on the stock
 * runtime with its own data directories and the original installation is not touched.
 *
 * Limits, stated rather than papered over:
 *  - one managed profile per device, so one clone per package;
 *  - the profile has to be provisioned by the user through a system flow;
 *  - launching the clone needs this app present in the profile too, otherwise the user starts
 *    it from the launcher's work tab.
 */
class ManagedProfileBackend(
    private val context: Context,
    private val adminComponent: ComponentName,
) : CloneBackend {

    private companion object {
        const val TAG = "AppCloner/ManagedProfile"
    }

    override val kind = CloneBackendKind.MANAGED_PROFILE

    override val capabilities = BackendCapabilities(
        kind = kind,
        concurrentWithOriginal = true,
        preservesPackageIdentity = true,
        survivesGameUpdate = true,
        runsOnUnmodifiedRuntime = true,
        requiresRoot = false,
        maxClonesPerPackage = 1,
        perCloneRootControl = false,
    )

    private val dpm: DevicePolicyManager?
        get() = ContextCompat.getSystemService(context, DevicePolicyManager::class.java)

    private val crossProfileApps: CrossProfileApps?
        get() = ContextCompat.getSystemService(context, CrossProfileApps::class.java)

    /** Intent that asks the system to create a work profile owned by this app. */
    fun provisioningIntent(): Intent =
        Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, false)
        }

    override suspend fun availability(): BackendAvailability = withContext(Dispatchers.IO) {
        val manager = dpm
            ?: return@withContext BackendAvailability.Unavailable("No device policy manager")

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)) {
            return@withContext BackendAvailability.Unavailable(
                reason = "This device does not support managed profiles.",
                remedy = "Use the secondary-user backend, which needs root.",
            )
        }

        if (manager.isProfileOwnerApp(context.packageName)) {
            return@withContext BackendAvailability.Available
        }

        // Whether provisioning will be accepted is not reliably knowable in advance:
        // DevicePolicyManager.isProvisioningAllowed is deprecated and the answer depends on
        // policy the app cannot see. The provisioning activity returns the real answer, so this
        // reports the setup step and lets the system refuse it if an existing profile blocks it.
        BackendAvailability.NeedsSetup(
            reason = "A work profile has to be created once before clones can be installed. " +
                "If the device already has one, the system will refuse and that is reported then.",
            action = BackendAvailability.SetupAction.PROVISION_MANAGED_PROFILE,
        )
    }

    /**
     * Clones visible from here. This backend runs in the personal profile, so it can enumerate
     * target profiles but cannot read the package list inside one; records are confirmed by
     * [CloneStore] and by whether a target profile still exists.
     */
    override suspend fun listClones(): List<CloneRecord> = emptyList()

    override suspend fun createClone(request: CloneRequest): CloneResult =
        withContext(Dispatchers.IO) {
            val manager = dpm ?: return@withContext CloneResult.Failure("No device policy manager")
            if (!manager.isProfileOwnerApp(context.packageName)) {
                return@withContext CloneResult.Failure(
                    message = "Not profile owner, so packages cannot be installed into the profile.",
                    nextStep = "Run the provisioning intent from provisioningIntent() first.",
                )
            }
            if (request.forcedAbi != null) {
                return@withContext CloneResult.Unsupported(
                    message = "A work-profile install reuses the existing package, so its ABI " +
                        "cannot be pinned.",
                    manualInstruction = "Use the secondary-user backend with " +
                        "pm install-existing --abi for the 32-bit leg of the matrix.",
                )
            }

            val installed = try {
                manager.installExistingPackage(adminComponent, request.packageName)
            } catch (t: Throwable) {
                Log.w(TAG, "installExistingPackage failed", t)
                return@withContext CloneResult.Failure(
                    message = "installExistingPackage threw: ${t.message ?: t::class.java.simpleName}",
                    nextStep = "Confirm this app is profile owner and the package is installed " +
                        "for the primary user.",
                )
            }

            if (!installed) {
                return@withContext CloneResult.Failure(
                    message = "The platform declined to install ${request.packageName} into the profile.",
                    nextStep = "Check that the package is installed for the primary user and is " +
                        "not restricted by policy.",
                )
            }

            runCatching {
                manager.setApplicationHidden(adminComponent, request.packageName, false)
            }.onFailure { Log.d(TAG, "setApplicationHidden failed", it) }

            CloneResult.Success(
                CloneRecord(
                    id = "profile:${request.packageName}",
                    packageName = request.packageName,
                    label = request.label,
                    backend = kind,
                    androidUserId = null,
                    state = CloneState.READY,
                    rootAllowed = false,
                    forcedAbi = null,
                    createdAtMillis = System.currentTimeMillis(),
                ),
                log = listOf("installExistingPackage(${request.packageName}) = true"),
            )
        }

    override suspend fun launch(clone: CloneRecord): CloneResult = withContext(Dispatchers.Main) {
        val apps = crossProfileApps
            ?: return@withContext CloneResult.Failure("No CrossProfileApps service")
        val targets: List<UserHandle> = runCatching { apps.targetUserProfiles }
            .getOrDefault(emptyList())
        val target = targets.firstOrNull()
            ?: return@withContext CloneResult.Failure(
                message = "No target profile is reachable from here.",
                nextStep = "Open the cloned game from the launcher's work tab.",
            )

        val launchComponent = context.packageManager
            .getLaunchIntentForPackage(clone.packageName)?.component
            ?: return@withContext CloneResult.Failure(
                message = "No launcher activity found for ${clone.packageName}.",
            )

        try {
            apps.startMainActivity(launchComponent, target)
            CloneResult.Success(clone.copy(state = CloneState.RUNNING))
        } catch (t: Throwable) {
            // startMainActivity requires this app to be installed in the target profile.
            Log.w(TAG, "startMainActivity failed", t)
            CloneResult.Failure(
                message = "Cross-profile launch failed: ${t.message ?: t::class.java.simpleName}",
                nextStep = "Install this app inside the work profile as well, or start the game " +
                    "from the launcher's work tab.",
            )
        }
    }

    override suspend fun remove(clone: CloneRecord): CloneResult = withContext(Dispatchers.IO) {
        val manager = dpm ?: return@withContext CloneResult.Failure("No device policy manager")
        if (!manager.isProfileOwnerApp(context.packageName)) {
            return@withContext CloneResult.Failure("Not profile owner")
        }
        val hidden = runCatching {
            manager.setApplicationHidden(adminComponent, clone.packageName, true)
        }.getOrDefault(false)
        if (hidden) {
            CloneResult.Success(clone.copy(state = CloneState.REMOVED))
        } else {
            CloneResult.Failure(
                message = "Could not hide ${clone.packageName} in the profile.",
                nextStep = "Uninstall it from inside the work profile, or remove the profile.",
            )
        }
    }

    override suspend fun setRootAccess(clone: CloneRecord, allowed: Boolean): CloneResult =
        CloneResult.Unsupported(
            message = "A work profile clone runs without root by design.",
            manualInstruction = "Use the secondary-user backend if the clone needs root.",
        )
}
