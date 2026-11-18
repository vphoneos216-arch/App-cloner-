package com.akash.appcloner.engine.clone

import android.content.Context
import com.akash.appcloner.engine.compat.StoragePaths
import com.akash.appcloner.engine.process.ProcessManager
import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A stored copy of one clone's private data. */
data class CloneBackupFile(
    val path: String,
    val packageName: String,
    val userId: Int,
    val sizeBytes: Long,
    val createdAtMillis: Long,
)

sealed interface BackupResult {
    data class Success(val file: CloneBackupFile, val log: List<String>) : BackupResult
    data class Failure(val message: String, val log: List<String> = emptyList()) : BackupResult
}

sealed interface RestoreResult {
    data class Success(val log: List<String>, val warnings: List<String>) : RestoreResult
    data class Failure(val message: String, val log: List<String> = emptyList()) : RestoreResult
}

/**
 * Backup and restore of a clone's private data.
 *
 * Only ever touches `/data/user/<id>/<pkg>` for a non-zero user id. The guard against user 0 is
 * explicit and unconditional, because a restore that wrote into user 0 would overwrite the
 * original app's data, which is the one outcome this project must never produce.
 *
 * The archive carries ownership and contents but not SELinux labels - toybox tar does not
 * preserve them - so restore relabels afterwards and reports honestly when relabelling fails
 * rather than declaring success.
 */
class CloneBackup(
    private val context: Context,
    private val rootProvider: RootProvider = RootProvider(),
    private val processManager: ProcessManager = ProcessManager(rootProvider),
) {

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Backups live in the app's own external files dir so they can be copied off the device. */
    fun backupDirectory(): File =
        File(context.getExternalFilesDir(null), "clone-backups").apply { mkdirs() }

    fun list(): List<CloneBackupFile> =
        backupDirectory().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".tar") }
            ?.mapNotNull { file ->
                // name: <package>@u<userId>@<stamp>.tar
                val parts = file.name.removeSuffix(".tar").split("@")
                if (parts.size < 3) return@mapNotNull null
                val userId = parts[1].removePrefix("u").toIntOrNull() ?: return@mapNotNull null
                CloneBackupFile(file.absolutePath, parts[0], userId, file.length(), file.lastModified())
            }
            ?.sortedByDescending { it.createdAtMillis }
            .orEmpty()

    suspend fun backup(packageName: String, userId: Int): BackupResult =
        withContext(Dispatchers.IO) {
            val log = mutableListOf<String>()
            if (!rootProvider.isRooted) {
                return@withContext BackupResult.Failure("Root is not available.")
            }
            if (userId == 0) {
                return@withContext BackupResult.Failure(
                    "Refusing to back up user 0 here; that is the original app, not a clone.",
                )
            }
            val source = StoragePaths.privateDataDir(userId, packageName)
            if (!Shell.exec("test -d ${Shell.quote(source)}", asRoot = true).isSuccess) {
                return@withContext BackupResult.Failure("No data directory at $source.")
            }

            processManager.forceStop(packageName, userId)
            log += "stopped $packageName in user $userId"

            val out = File(backupDirectory(), "$packageName@u$userId@${stamp.format(Date())}.tar")
            val parent = StoragePaths.privateDataDir(userId, "").trimEnd('/')
            val result = Shell.exec(
                "tar -cf ${Shell.quote(out.absolutePath)} -C ${Shell.quote(parent)} " +
                    Shell.quote(packageName),
                asRoot = true,
                timeoutSeconds = 600,
            )
            log += "tar -cf ${out.name} -C $parent $packageName -> ${result.exitCode}"
            result.stderr.trim().takeIf { it.isNotEmpty() }?.let { log += "stderr: $it" }

            // The archive has to be readable by this app, not only by root.
            Shell.exec(
                "chmod 0644 ${Shell.quote(out.absolutePath)}",
                asRoot = true,
            )

            if (!out.exists() || out.length() == 0L) {
                return@withContext BackupResult.Failure(
                    "tar produced no archive: ${result.stderr.trim().take(200)}",
                    log,
                )
            }
            BackupResult.Success(
                CloneBackupFile(out.absolutePath, packageName, userId, out.length(), out.lastModified()),
                log,
            )
        }

    suspend fun restore(backup: CloneBackupFile, intoUserId: Int): RestoreResult =
        withContext(Dispatchers.IO) {
            val log = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            if (!rootProvider.isRooted) {
                return@withContext RestoreResult.Failure("Root is not available.")
            }
            if (intoUserId == 0) {
                return@withContext RestoreResult.Failure(
                    "Refusing to restore into user 0. That would overwrite the original app's data.",
                )
            }
            if (!File(backup.path).exists()) {
                return@withContext RestoreResult.Failure("Archive is missing: ${backup.path}")
            }

            val target = StoragePaths.privateDataDir(intoUserId, backup.packageName)
            if (!Shell.exec("test -d ${Shell.quote(target)}", asRoot = true).isSuccess) {
                return@withContext RestoreResult.Failure(
                    "No clone at $target. Create the clone first, then restore into it.",
                )
            }

            processManager.forceStop(backup.packageName, intoUserId)
            log += "stopped ${backup.packageName} in user $intoUserId"

            // Keep the existing data aside rather than deleting it, so a bad restore is undoable.
            val aside = "$target.before-restore"
            Shell.exec("rm -rf ${Shell.quote(aside)}", asRoot = true)
            Shell.exec("mv ${Shell.quote(target)} ${Shell.quote(aside)}", asRoot = true)
            log += "previous data moved to $aside"

            val parent = StoragePaths.privateDataDir(intoUserId, "").trimEnd('/')
            val extract = Shell.exec(
                "tar -xf ${Shell.quote(backup.path)} -C ${Shell.quote(parent)}",
                asRoot = true,
                timeoutSeconds = 600,
            )
            log += "tar -xf ${File(backup.path).name} -C $parent -> ${extract.exitCode}"
            if (!extract.isSuccess) {
                Shell.exec("rm -rf ${Shell.quote(target)}", asRoot = true)
                Shell.exec("mv ${Shell.quote(aside)} ${Shell.quote(target)}", asRoot = true)
                return@withContext RestoreResult.Failure(
                    "Extraction failed, previous data put back: ${extract.stderr.trim().take(200)}",
                    log,
                )
            }

            // Ownership and SELinux labels do not survive the archive and have to be rebuilt.
            val uid = PackageUidParser.parse(
                Shell.exec(
                    "pm list packages -U --user $intoUserId ${Shell.quote(backup.packageName)}",
                    asRoot = true,
                ).stdout,
                backup.packageName,
            )
            if (uid != null) {
                Shell.exec("chown -R $uid:$uid ${Shell.quote(target)}", asRoot = true)
                log += "chown -R $uid:$uid $target"
            } else {
                warnings += "Could not resolve the clone's uid, so ownership was left as the " +
                    "archive had it. The app may fail to read its own data."
            }

            val relabel = Shell.exec("restorecon -RF ${Shell.quote(target)}", asRoot = true)
            log += "restorecon -RF $target -> ${relabel.exitCode}"
            if (!relabel.isSuccess) {
                warnings += "restorecon failed (${relabel.stderr.trim().take(120)}). SELinux " +
                    "labels may be wrong; the app can be denied access to its own files."
            }

            warnings += "Previous data is kept at $aside. Delete it once the clone starts cleanly."
            RestoreResult.Success(log, warnings)
        }
}
