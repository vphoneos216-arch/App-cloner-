package com.akash.appcloner.engine.clone

import android.content.Context
import android.os.Build
import com.akash.appcloner.engine.compat.ApkInspector
import com.akash.appcloner.engine.nativebridge.NativeBridge
import com.akash.appcloner.engine.root.CommandLog
import com.akash.appcloner.engine.root.RootPolicyManager
import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-shot capability probe, written as a report that can be sent to someone who cannot see the
 * device.
 *
 * Cloning depends on what a specific ROM allows, and the useful question after a failure is not
 * "did it work" but "which step refused, and in what words". Everything here is the output of a
 * command that ran; nothing is inferred.
 */
class CloneDiagnostics(
    private val context: Context,
    private val rootProvider: RootProvider,
    private val cloneProfileBackend: CloneProfileBackend,
    private val oemDualAppBackend: OemDualAppBackend,
    private val backends: List<CloneBackend>,
    private val apkInspector: ApkInspector,
    private val rootPolicyManager: RootPolicyManager,
) {

    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Where the report is written so it can be shared out of the app. */
    fun reportFile(): File =
        File(context.getExternalFilesDir(null), "rootclone-diagnostics.txt")

    suspend fun probe(packageName: String? = null): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()

        out.appendLine("RootClone diagnostics")
        out.appendLine("generated ${clock.format(Date())}")
        out.appendLine()

        out.appendLine("[device]")
        out.appendLine("  manufacturer   ${Build.MANUFACTURER}")
        out.appendLine("  model          ${Build.MODEL}")
        out.appendLine("  device         ${Build.DEVICE}")
        out.appendLine("  android        ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        out.appendLine("  abis           ${Build.SUPPORTED_ABIS.joinToString()}")
        out.appendLine("  soc            ${Build.SOC_MODEL}")
        out.appendLine("  fingerprint    ${Build.FINGERPRINT}")
        out.appendLine()

        val root = rootProvider.status()
        out.appendLine("[root]")
        out.appendLine("  available      ${root.available}")
        out.appendLine("  manager        ${root.flavor}")
        out.appendLine("  su -v          ${root.rawVersion ?: "not reported"}")
        root.evidence.forEach { out.appendLine("  evidence       $it") }
        if (root.available) {
            val id = Shell.exec("id", asRoot = true, timeoutSeconds = 10)
            out.appendLine("  id (exit ${id.exitCode})  ${id.stdout.trim().ifBlank { id.stderr.trim() }}")
        }
        out.appendLine()

        out.appendLine("[native bridge]")
        out.appendLine("  loaded         ${NativeBridge.isAvailable}")
        out.appendLine("  process abi    ${NativeBridge.processAbi() ?: "n/a"}")
        out.appendLine("  process uid    ${NativeBridge.processUid() ?: "n/a"}")
        out.appendLine()

        if (!root.available) {
            out.appendLine("[stopped]")
            out.appendLine("  Without uid 0 nothing below can be read. Grant superuser to this")
            out.appendLine("  app in your root manager and run this again.")
            return@withContext finish(out.toString())
        }

        val dump = Shell.exec("dumpsys user", asRoot = true, timeoutSeconds = 30).stdout
        val types = DumpsysUserParser.supportedUserTypes(dump)
        out.appendLine("[user types this ROM defines]")
        if (types.isEmpty()) {
            out.appendLine("  none parsed from dumpsys user - the output shape may differ on")
            out.appendLine("  this build. The raw dump is at the end of this report.")
        } else {
            types.sorted().forEach { out.appendLine("  $it") }
        }
        out.appendLine()
        out.appendLine("  clone profile type defined: ${UserTypes.CLONE_PROFILE in types}")
        out.appendLine()

        out.appendLine("[users on this device]")
        val users = cloneProfileBackend.users()
        users.forEach { user ->
            out.appendLine(
                "  id=${user.id} name=${user.name.ifBlank { "(unnamed)" }} " +
                    "type=${user.type ?: "not reported"} running=${user.running}" +
                    when {
                        user.isCloneProfile -> "  <- AOSP clone profile"
                        user.isOemDualAppCandidate -> "  <- OEM dual-app candidate"
                        else -> ""
                    },
            )
        }
        out.appendLine()
        out.appendLine("  max supported users: " +
            Shell.exec("pm get-max-users", asRoot = true).stdout.trim())
        out.appendLine("  clone profiles: ${users.count { it.isCloneProfile }}")
        out.appendLine("  oem candidates: ${users.count { it.isOemDualAppCandidate }}")
        out.appendLine()

        out.appendLine("[backend availability]")
        backends.forEach { backend ->
            val availability = runCatching { backend.availability() }
                .getOrElse { BackendAvailability.Unavailable("probe threw: ${it.message}") }
            out.appendLine("  ${backend.kind}")
            when (availability) {
                BackendAvailability.Available -> out.appendLine("    AVAILABLE")
                is BackendAvailability.NeedsSetup ->
                    out.appendLine("    NEEDS SETUP (${availability.action}): ${availability.reason}")

                is BackendAvailability.Unavailable -> {
                    out.appendLine("    UNAVAILABLE: ${availability.reason}")
                    availability.remedy?.let { out.appendLine("    remedy: $it") }
                }
            }
            val capabilities = backend.capabilities
            out.appendLine(
                "    concurrent=${capabilities.concurrentWithOriginal} " +
                    "identity=${capabilities.preservesPackageIdentity} " +
                    "root=${capabilities.requiresRoot}",
            )
        }
        out.appendLine()

        if (packageName != null) {
            out.appendLine("[target package] $packageName")
            val inUserZero = Shell.exec(
                "pm list packages --user 0 ${Shell.quote(packageName)}",
                asRoot = true,
            ).stdout.contains("package:$packageName")
            out.appendLine("  installed for user 0: $inUserZero")

            val uid = PackageUidParser.parse(
                Shell.exec(
                    "pm list packages -U --user 0 ${Shell.quote(packageName)}",
                    asRoot = true,
                ).stdout,
                packageName,
            )
            out.appendLine("  uid in user 0: ${uid ?: "not reported"}")
            uid?.let {
                val appId = PackageUidParser.appIdOf(it)
                out.appendLine("  app id: $appId")
                users.filter { user -> user.id != 0 }.forEach { user ->
                    out.appendLine(
                        "  uid it would get in user ${user.id}: " +
                            PackageUidParser.uidFor(user.id, appId),
                    )
                }
                rootPolicyManager.currentPolicy(it, packageName)?.let { policy ->
                    out.appendLine("  root policy for the original: $policy")
                }
            }

            val inspection = apkInspector.inspect(packageName)
            if (inspection == null) {
                out.appendLine("  package manager could not inspect it")
            } else {
                out.appendLine("  version: ${inspection.versionName} (${inspection.versionCode})")
                out.appendLine("  min/target sdk: ${inspection.minSdk}/${inspection.targetSdk}")
                out.appendLine("  abis in apk: ${inspection.apkAbis.joinToString().ifEmpty { "none" }}")
                out.appendLine("  splits: ${inspection.splitApks.size}")
                out.appendLine("  engine: ${inspection.engine.kind}")
                out.appendLine("  base apk: ${inspection.sourceApk}")
            }

            users.filter { it.id != 0 }.forEach { user ->
                val listed = Shell.exec(
                    "pm list packages --user ${user.id} ${Shell.quote(packageName)}",
                    asRoot = true,
                ).stdout.contains("package:$packageName")
                val verification = cloneProfileBackend.verifyClone(packageName, user.id)
                out.appendLine(
                    "  user ${user.id}: installed=$listed ce=${verification.credentialDataDir} " +
                        "de=${verification.deviceDataDir} external=${verification.externalDataDir} " +
                        "uid=${verification.uid}",
                )
            }
            out.appendLine()
        }

        out.appendLine("[create-user dry run]")
        out.appendLine("  The command the clone profile backend would run:")
        out.appendLine(
            "    pm create-user --profileOf 0 --user-type ${UserTypes.CLONE_PROFILE} AppCloneX",
        )
        out.appendLine("  Not executed by diagnostics, because creating a profile is a change.")
        out.appendLine("  Press Clone on an app to run it for real; the Logs tab keeps the output.")
        out.appendLine()

        out.appendLine("[command log]")
        out.appendLine(CommandLog.render())
        out.appendLine()

        out.appendLine("[raw dumpsys user]")
        out.appendLine(dump.take(20_000))

        finish(out.toString())
    }

    private fun finish(report: String): String {
        runCatching { reportFile().writeText(report) }
        return report
    }
}
