package com.akash.appcloner.engine.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of a per-uid root policy change. */
sealed interface RootPolicyResult {

    /**
     * The policy store was written and read back. [readBack] is what the store now says.
     *
     * This is not proof that the clone has root: the only proof is the cloned app's own su
     * request being granted. The wording in [note] says so, and the UI repeats it.
     */
    data class Applied(
        val uid: Int,
        val readBack: String,
        val note: String,
        val log: List<String>,
    ) : RootPolicyResult

    /** This manager exposes no interface this app can safely drive. */
    data class NotSupported(val reason: String, val manualInstruction: String) : RootPolicyResult

    data class Failed(val message: String, val log: List<String> = emptyList()) : RootPolicyResult
}

/**
 * Per-uid superuser policy.
 *
 * None of the three root solutions documents a stable interface for this, so each path here is
 * written against what the manager actually stores, is attempted only after that manager has
 * been detected, and is verified by reading the store back. Where a manager keeps its policy in
 * a format this app cannot write safely, it says so and names the file instead of guessing at
 * the layout - a malformed policy row is worse than no row.
 *
 * The uid to use for a clone is `userId * 100000 + appId`, which is what the managers key on.
 */
class RootPolicyManager(private val rootProvider: RootProvider = RootProvider()) {

    private companion object {
        /** Magisk's policy table encodes allow as 2 and deny as 1. */
        const val MAGISK_ALLOW = 2
        const val MAGISK_DENY = 1
        const val APATCH_CONFIG = "/data/adb/ap/package_config"
    }

    suspend fun grant(uid: Int, packageName: String): RootPolicyResult = apply(uid, packageName, true)

    suspend fun revoke(uid: Int, packageName: String): RootPolicyResult = apply(uid, packageName, false)

    private suspend fun apply(
        uid: Int,
        packageName: String,
        allow: Boolean,
    ): RootPolicyResult = withContext(Dispatchers.IO) {
        val status = rootProvider.status()
        if (!status.available) {
            return@withContext RootPolicyResult.Failed("Root is not available.")
        }
        when (status.flavor) {
            RootFlavor.MAGISK -> applyMagisk(uid, allow)
            RootFlavor.KERNEL_SU -> applyKernelSu(uid, packageName, allow)
            RootFlavor.APATCH -> describeApatch(uid, packageName)
            RootFlavor.UNKNOWN, RootFlavor.NONE -> RootPolicyResult.NotSupported(
                reason = "The root manager did not identify itself, so its policy store is " +
                    "unknown and nothing will be written blindly.",
                manualInstruction = "Grant superuser to $packageName (uid $uid) in your root " +
                    "manager's own superuser list.",
            )
        }
    }

    /**
     * Magisk keeps its policies in a sqlite database reachable through `magisk --sqlite`.
     *
     * Undocumented but stable in practice, and the daemon consults it per request, so a row
     * written here takes effect without a reboot. Written with REPLACE so an existing row for
     * the uid is updated rather than duplicated.
     */
    private fun applyMagisk(uid: Int, allow: Boolean): RootPolicyResult {
        val log = mutableListOf<String>()
        val policy = if (allow) MAGISK_ALLOW else MAGISK_DENY
        val write = Shell.exec(
            "magisk --sqlite \"REPLACE INTO policies " +
                "(uid,policy,until,logging,notification) VALUES($uid,$policy,0,1,1)\"",
            asRoot = true,
            timeoutSeconds = 20,
        )
        log += "magisk --sqlite REPLACE INTO policies uid=$uid policy=$policy"
        write.stderr.trim().takeIf { it.isNotEmpty() }?.let { log += "stderr: $it" }

        val read = Shell.exec(
            "magisk --sqlite \"SELECT uid,policy FROM policies WHERE uid=$uid\"",
            asRoot = true,
            timeoutSeconds = 20,
        )
        val readBack = read.stdout.trim()
        log += "read back: ${readBack.ifBlank { "(no row)" }}"

        return if (readBack.contains("policy=$policy") || readBack.contains("|$policy")) {
            RootPolicyResult.Applied(
                uid = uid,
                readBack = readBack,
                note = "Magisk's policy row now says ${if (allow) "allow" else "deny"}. The " +
                    "clone having root is only confirmed when it actually requests su.",
                log = log,
            )
        } else {
            RootPolicyResult.Failed(
                message = "Magisk accepted no policy row for uid $uid. " +
                    write.stderr.trim().take(160).ifBlank { "Output: ${readBack.take(160)}" },
                log = log,
            )
        }
    }

    /**
     * KernelSU keeps its allowlist in the kernel, persisted as a binary file, and upstream
     * `ksud` has no documented command to grant a uid. Rather than write that binary blind,
     * this probes for a profile subcommand and otherwise reports the manual step.
     */
    private fun applyKernelSu(uid: Int, packageName: String, allow: Boolean): RootPolicyResult {
        val help = Shell.exec("ksud profile --help", asRoot = true, timeoutSeconds = 15)
        val hasProfileCommand = help.isSuccess &&
            (help.stdout.contains("set", ignoreCase = true) ||
                help.stdout.contains("template", ignoreCase = true))

        if (!hasProfileCommand) {
            return RootPolicyResult.NotSupported(
                reason = "This KernelSU build's ksud exposes no documented command for granting " +
                    "a uid; the allowlist lives in the kernel as a binary file and is not " +
                    "written blind.",
                manualInstruction = "Open the KernelSU manager, find $packageName (uid $uid) in " +
                    "the superuser list and ${if (allow) "allow" else "deny"} it. A clone's uid " +
                    "is userId * 100000 + appId, so it appears as a separate entry from the " +
                    "original app.",
            )
        }
        return RootPolicyResult.NotSupported(
            reason = "ksud on this build reports a profile subcommand, but its argument format " +
                "is not documented and differs between forks, so it is not driven from here.",
            manualInstruction = "Run `ksud profile --help` in a root shell to see this build's " +
                "exact syntax, or set it in the manager UI for $packageName (uid $uid).",
        )
    }

    /**
     * APatch stores per-package configuration as CSV synced by its own daemon. The column layout
     * is undocumented and has been reported not to take effect for secondary-user uids, so this
     * reads the file and reports rather than writing a row that may be malformed or ignored.
     */
    private fun describeApatch(uid: Int, packageName: String): RootPolicyResult {
        val read = Shell.exec(
            "grep -n ${Shell.quote(packageName)} ${Shell.quote(APATCH_CONFIG)} 2>/dev/null",
            asRoot = true,
            timeoutSeconds = 15,
        )
        val current = read.stdout.trim()
        return RootPolicyResult.NotSupported(
            reason = "APatch keeps per-package policy in $APATCH_CONFIG, whose column layout is " +
                "undocumented. Editing it has also been reported not to take effect for uids in " +
                "secondary users, which is exactly what a clone has." +
                if (current.isNotEmpty()) " Current row: $current" else " No row for this package.",
            manualInstruction = "Grant superuser to $packageName (uid $uid) in the APatch " +
                "manager UI.",
        )
    }

    /** Best-effort read of what the detected manager currently says about a uid. */
    fun currentPolicy(uid: Int, packageName: String): String? {
        if (!rootProvider.isRooted) return null
        return when (rootProvider.status().flavor) {
            RootFlavor.MAGISK -> Shell.exec(
                "magisk --sqlite \"SELECT uid,policy FROM policies WHERE uid=$uid\"",
                asRoot = true,
            ).stdout.trim().ifBlank { "no row for uid $uid" }

            RootFlavor.APATCH -> Shell.exec(
                "grep ${Shell.quote(packageName)} ${Shell.quote(APATCH_CONFIG)} 2>/dev/null",
                asRoot = true,
            ).stdout.trim().ifBlank { "no row for $packageName" }

            else -> null
        }
    }
}
