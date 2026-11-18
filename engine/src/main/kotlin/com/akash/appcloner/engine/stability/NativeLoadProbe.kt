package com.akash.appcloner.engine.stability

import android.util.Log
import com.akash.appcloner.engine.compat.ApkInspection
import com.akash.appcloner.engine.compat.NativeLibrary
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** One native library checked against the ABI directory it ships in. */
data class NativeLibraryCheck(
    val library: NativeLibrary,
    val header: ElfHeader?,
    val matchesAbiDirectory: Boolean?,
    val problem: String?,
)

/**
 * Result of checking an install's native libraries. [problems] is what to act on.
 *
 * This is a static check of the shipped objects, not a load. Loading another app's library into
 * this process would fail for reasons that say nothing about the game - missing dependencies,
 * a different linker namespace - so a failure there would be noise, while a word-size or
 * machine mismatch here is always a real fault.
 */
data class NativeLoadReport(
    val packageName: String,
    val selectedAbi: String?,
    val checks: List<NativeLibraryCheck>,
    val unreadable: List<String>,
) {
    val problems: List<NativeLibraryCheck> get() = checks.filter { it.problem != null }
    val isClean: Boolean get() = problems.isEmpty() && unreadable.isEmpty()
}

/**
 * Verifies that the native libraries in an install are the architecture their directory claims.
 */
class NativeLoadProbe {

    private companion object {
        const val TAG = "AppCloner/NativeProbe"
        const val MAX_LIBRARIES = 64
    }

    fun probe(inspection: ApkInspection, selectedAbi: String?): NativeLoadReport {
        val relevant = inspection.nativeLibraries
            .filter { selectedAbi == null || it.abiDir == selectedAbi }
            .take(MAX_LIBRARIES)

        if (relevant.isEmpty()) {
            return NativeLoadReport(inspection.packageName, selectedAbi, emptyList(), emptyList())
        }

        val checks = mutableListOf<NativeLibraryCheck>()
        val unreadable = mutableListOf<String>()

        // Prefer the extracted libraries when the platform extracted them, and fall back to the
        // APK entries, which is the only option when extractNativeLibs is false.
        val extractedDir = inspection.nativeLibraryDir?.let(::File)?.takeIf { it.canRead() }

        val apkPaths = buildList {
            inspection.sourceApk?.let { add(it) }
            addAll(inspection.splitApks)
        }

        val zips = apkPaths.mapNotNull { path ->
            runCatching { ZipFile(File(path)) }.getOrElse {
                Log.d(TAG, "cannot open $path", it)
                null
            }
        }

        try {
            relevant.forEach { library ->
                val bytes = readHeaderBytes(library, extractedDir, zips)
                if (bytes == null) {
                    unreadable += library.path
                    return@forEach
                }
                val header = ElfHeaderReader.parse(bytes)
                val expected = ElfHeader.expectedFor(library.abiDir)
                val problem = when {
                    header == null -> "not an ELF object"
                    expected == null -> null
                    header.machine != expected.first ->
                        "machine is ${header.machineName}, but ${library.abiDir} requires " +
                            "machine 0x${expected.first.toString(16)}"

                    header.is64Bit != expected.second ->
                        "word size is ${if (header.is64Bit) 64 else 32}-bit, but " +
                            "${library.abiDir} requires ${if (expected.second) 64 else 32}-bit"

                    else -> null
                }
                checks += NativeLibraryCheck(
                    library = library,
                    header = header,
                    matchesAbiDirectory = if (expected == null) null else problem == null,
                    problem = problem,
                )
            }
        } finally {
            zips.forEach { runCatching { it.close() } }
        }

        return NativeLoadReport(inspection.packageName, selectedAbi, checks, unreadable)
    }

    private fun readHeaderBytes(
        library: NativeLibrary,
        extractedDir: File?,
        zips: List<ZipFile>,
    ): ByteArray? {
        extractedDir?.let { dir ->
            val file = File(dir, library.fileName)
            if (file.canRead()) {
                runCatching { file.inputStream().use(::readFully) }
                    .getOrNull()
                    ?.let { return it }
            }
        }
        zips.forEach { zip ->
            val entry = zip.getEntry(library.path) ?: return@forEach
            runCatching { zip.getInputStream(entry).use(::readFully) }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    /**
     * Reads exactly the header bytes, or returns null. A single read() call can legitimately
     * return fewer bytes than asked for, and a short buffer parsed as an ELF header would
     * produce a confident wrong answer about the architecture.
     */
    private fun readFully(stream: InputStream): ByteArray? {
        val buffer = ByteArray(ElfHeaderReader.MIN_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        return if (read == buffer.size) buffer else null
    }
}
