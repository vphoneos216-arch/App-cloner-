package com.akash.appcloner.engine.compat

/**
 * Path construction for per-user app storage. Pure string building: these are the platform's
 * documented layouts, and every one of them is checked for existence before use rather than
 * assumed.
 *
 * A clone created as a separate Android user (secondary user or managed profile) gets its own
 * copy of each of these trees, which is exactly why that model does not disturb the original
 * installation.
 */
object StoragePaths {

    fun externalRoot(userId: Int): String = "/storage/emulated/$userId"

    fun obbDir(userId: Int, packageName: String): String =
        "${externalRoot(userId)}/Android/obb/$packageName"

    fun externalDataDir(userId: Int, packageName: String): String =
        "${externalRoot(userId)}/Android/data/$packageName"

    /** Credential-encrypted private data. Only readable as that app's uid, or as root. */
    fun privateDataDir(userId: Int, packageName: String): String = "/data/user/$userId/$packageName"

    /** Device-encrypted private data, available before first unlock. */
    fun privateDeDataDir(userId: Int, packageName: String): String =
        "/data/user_de/$userId/$packageName"
}

/** Role of an expansion file, as encoded in its name by the platform's own convention. */
enum class ObbRole { MAIN, PATCH, UNKNOWN }

/** One expansion file, with the version code lifted out of its file name. */
data class ObbFile(
    val fileName: String,
    val role: ObbRole,
    val versionCode: Long?,
    val packageName: String?,
    val sizeBytes: Long,
)

/**
 * The expansion-file picture for one package in one Android user.
 *
 * [staleFiles] is the practical reason clones break after a game update: the APK moves to a new
 * version code while the expansion files still carry the old one in their names.
 */
data class ObbLayout(
    val directory: String,
    val exists: Boolean,
    val files: List<ObbFile>,
    val totalBytes: Long,
    val installedVersionCode: Long?,
) {
    val staleFiles: List<ObbFile>
        get() = if (installedVersionCode == null) {
            emptyList()
        } else {
            files.filter { it.versionCode != null && it.versionCode != installedVersionCode }
        }

    val isConsistentWithInstall: Boolean
        get() = files.isEmpty() || staleFiles.isEmpty()

    companion object {
        fun absent(directory: String, installedVersionCode: Long?) = ObbLayout(
            directory = directory,
            exists = false,
            files = emptyList(),
            totalBytes = 0L,
            installedVersionCode = installedVersionCode,
        )
    }
}

/** Pure parsing of expansion-file names: `main.<versionCode>.<package>.obb`. */
object ObbNameParser {

    fun parse(fileName: String, sizeBytes: Long = 0L): ObbFile {
        val role = when {
            fileName.startsWith("main.") -> ObbRole.MAIN
            fileName.startsWith("patch.") -> ObbRole.PATCH
            else -> ObbRole.UNKNOWN
        }
        if (role == ObbRole.UNKNOWN || !fileName.endsWith(".obb")) {
            return ObbFile(fileName, role, null, null, sizeBytes)
        }
        // main.<version>.<package>.obb -> drop the role prefix and the .obb suffix.
        val body = fileName.substringAfter('.').removeSuffix(".obb")
        val version = body.substringBefore('.').toLongOrNull()
        val pkg = body.substringAfter('.').takeIf { it.isNotEmpty() && version != null }
        return ObbFile(fileName, role, version, pkg, sizeBytes)
    }
}
