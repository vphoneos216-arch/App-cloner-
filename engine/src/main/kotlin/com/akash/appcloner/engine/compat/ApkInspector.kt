package com.akash.appcloner.engine.compat

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.root.Shell
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Reads what is actually in an installed package: the package manager's record of it, and the
 * entries in its APK (base plus every split, since on a split install the native libraries
 * usually live in an ABI split rather than the base).
 *
 * Reading only the central directory keeps this cheap even for a multi-gigabyte game APK.
 */
class ApkInspector(
    private val context: Context,
    private val rootProvider: RootProvider = RootProvider(),
) {

    private companion object {
        const val TAG = "AppCloner/ApkInspector"
        const val LIB_PREFIX = "lib/"
        const val GLOBAL_METADATA = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
        const val MANAGED_PREFIX = "assets/bin/Data/Managed/"
        const val MAX_MANAGED_MARKERS = 5
    }

    /** The Android user this process runs in; OBB and external data are per user. */
    fun currentUserId(): Int = Process.myUid() / 100_000

    fun inspect(packageName: String, userId: Int = currentUserId()): ApkInspection? {
        val pm = context.packageManager
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_CONFIGURATIONS.toLong(),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_CONFIGURATIONS)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "not installed: $packageName", e)
            return null
        }

        val appInfo: ApplicationInfo = packageInfo.applicationInfo ?: return null
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrDefault(packageName)

        val apkPaths = buildList {
            appInfo.sourceDir?.let { add(it) }
            appInfo.splitSourceDirs?.filterNotNull()?.let { addAll(it) }
        }

        val scan = scanApks(apkPaths)
        val versionCode = packageInfo.longVersionCode

        val features = packageInfo.reqFeatures.orEmpty()
        val declaredFeatures = features.mapNotNull { fi ->
            fi.name?.let {
                DeclaredFeature(
                    name = it,
                    version = fi.version,
                    required = (fi.flags and FeatureInfo.FLAG_REQUIRED) != 0,
                )
            }
        }
        val declaredGlEs = features
            .firstOrNull { it.name == null && it.reqGlEsVersion != FeatureInfo.GL_ES_VERSION_UNDEFINED }
            ?.reqGlEsVersion

        val obbDir = StoragePaths.obbDir(userId, packageName)
        val obbResult = inspectObb(obbDir, versionCode)

        val externalData = probeDirectory(StoragePaths.externalDataDir(userId, packageName))

        return ApkInspection(
            packageName = packageName,
            label = label,
            versionName = packageInfo.versionName,
            versionCode = versionCode,
            minSdk = appInfo.minSdkVersion,
            targetSdk = appInfo.targetSdkVersion,
            sourceApk = appInfo.sourceDir,
            splitApks = appInfo.splitSourceDirs?.filterNotNull().orEmpty(),
            nativeLibraryDir = appInfo.nativeLibraryDir,
            extractNativeLibs = (appInfo.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0,
            apkAbis = scan.abis,
            nativeLibraries = scan.libraries,
            engine = GameEngineDetector.detect(scan.entryNames),
            declaredFeatures = declaredFeatures,
            declaredGlEsVersion = declaredGlEs,
            graphics = GraphicsRequirement.fromDeclaredFeatures(declaredFeatures, declaredGlEs),
            obb = obbResult.first,
            obbAccessFailure = obbResult.second,
            externalDataDirExists = externalData,
            apkReadFailure = scan.failure,
            inspectedAtMillis = System.currentTimeMillis(),
        )
    }

    /** Installed packages that declare a launchable activity, as package name to label. */
    fun installedLaunchableApps(): Map<String, String> {
        val pm = context.packageManager
        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getInstalledApplications failed", t)
            emptyList()
        }
        return installed
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .associate { it.packageName to runCatching { pm.getApplicationLabel(it).toString() }
                .getOrDefault(it.packageName) }
    }

    private data class ApkScan(
        val entryNames: List<String>,
        val libraries: List<NativeLibrary>,
        val abis: List<String>,
        val failure: String?,
    )

    private fun scanApks(paths: List<String>): ApkScan {
        if (paths.isEmpty()) {
            return ApkScan(emptyList(), emptyList(), emptyList(), "no APK path reported")
        }
        val entryNames = mutableListOf<String>()
        val libraries = mutableListOf<NativeLibrary>()
        var managedMarkers = 0
        val failures = mutableListOf<String>()

        for (path in paths) {
            try {
                ZipFile(File(path)).use { zip ->
                    val it = zip.entries()
                    while (it.hasMoreElements()) {
                        val entry: ZipEntry = it.nextElement()
                        val name = entry.name
                        when {
                            name.startsWith(LIB_PREFIX) && !entry.isDirectory -> {
                                val rest = name.removePrefix(LIB_PREFIX)
                                val abiDir = rest.substringBefore('/', "")
                                val fileName = rest.substringAfterLast('/')
                                if (abiDir.isNotEmpty() && fileName.isNotEmpty()) {
                                    libraries += NativeLibrary(
                                        abiDir = abiDir,
                                        fileName = fileName,
                                        uncompressedSize = entry.size,
                                        stored = entry.method == ZipEntry.STORED,
                                    )
                                    entryNames += name
                                }
                            }

                            name == GLOBAL_METADATA -> entryNames += name

                            name.startsWith(MANAGED_PREFIX) && name.endsWith(".dll") -> {
                                if (managedMarkers < MAX_MANAGED_MARKERS) {
                                    entryNames += name
                                    managedMarkers++
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // Another app's APK is normally readable, but a restricted install location or
                // an SELinux denial is possible. Degrade loudly rather than report an empty APK.
                Log.w(TAG, "cannot read APK $path", t)
                failures += "${File(path).name}: ${t.message ?: t::class.java.simpleName}"
            }
        }

        val abis = libraries.map { it.abiDir }.distinct().sorted()
        return ApkScan(
            entryNames = entryNames,
            libraries = libraries,
            abis = abis,
            failure = failures.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    /**
     * Expansion files for another package. From Android 11 onward `Android/obb/<other package>`
     * is not reachable by an ordinary app, so this tries direct access first and falls back to
     * root. When neither works it returns the reason instead of an empty layout, because
     * "no expansion files" and "could not look" lead to opposite conclusions.
     */
    private fun inspectObb(directory: String, versionCode: Long?): Pair<ObbLayout?, String?> {
        val dir = File(directory)
        if (dir.canRead()) {
            val children = dir.listFiles()
            if (children != null) {
                val files = children
                    .filter { it.isFile }
                    .map { ObbNameParser.parse(it.name, it.length()) }
                return ObbLayout(
                    directory = directory,
                    exists = true,
                    files = files,
                    totalBytes = files.sumOf { it.sizeBytes },
                    installedVersionCode = versionCode,
                ) to null
            }
        }
        if (!rootProvider.isRooted) {
            return null to "not readable without root (scoped storage blocks Android/obb " +
                "for other packages from Android 11 onward)"
        }
        val listing = Shell.exec(
            "ls -lL ${Shell.quote(directory)} 2>/dev/null",
            asRoot = true,
        )
        if (!listing.isSuccess) {
            val exists = Shell.exec("test -d ${Shell.quote(directory)}", asRoot = true).isSuccess
            return if (exists) {
                null to "root listing failed: ${listing.stderr.trim().take(160)}"
            } else {
                ObbLayout.absent(directory, versionCode) to null
            }
        }
        val files = listing.lines.mapNotNull { parseLsLine(it) }
            .map { (name, size) -> ObbNameParser.parse(name, size) }
        return ObbLayout(
            directory = directory,
            exists = true,
            files = files,
            totalBytes = files.sumOf { it.sizeBytes },
            installedVersionCode = versionCode,
        ) to null
    }

    /**
     * Pulls name and byte size out of one `ls -l` line. Toybox columns are
     * mode, links, owner, group, size, date, time, name.
     */
    private fun parseLsLine(line: String): Pair<String, Long>? {
        if (line.startsWith("total ") || line.startsWith("d")) return null
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 8) return null
        val size = columns[4].toLongOrNull() ?: return null
        val name = columns.drop(7).joinToString(" ")
        return if (name.isBlank()) null else name to size
    }

    private fun probeDirectory(path: String): Boolean? {
        val file = File(path)
        if (file.canRead()) return file.isDirectory
        if (!rootProvider.isRooted) return null
        return Shell.exec("test -d ${Shell.quote(path)}", asRoot = true).isSuccess
    }
}
