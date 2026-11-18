package com.akash.appcloner.engine.nativebridge

import android.util.Log

/** Ownership and mode of a path, read without following symlinks. */
data class PathOwnership(
    val uid: Int,
    val gid: Int,
    val mode: Int,
    val sizeBytes: Long,
    val linkCount: Long,
    val isDirectory: Boolean,
) {
    /** Octal permission bits, as `ls` shows them. */
    val permissions: String get() = (mode and 0xFFF).toString(8).padStart(4, '0')

    /** The Android user this uid belongs to. */
    val androidUserId: Int get() = uid / 100_000

    /** The app id, which is the part of a uid that stays the same across users. */
    val appId: Int get() = uid % 100_000
}

/** ELF identity of a shared object. */
data class NativeElfIdentity(
    val is64Bit: Boolean,
    val isLittleEndian: Boolean,
    val machine: Int,
) {
    val machineName: String
        get() = when (machine) {
            0x28 -> "arm"
            0xB7 -> "aarch64"
            0x03 -> "x86"
            0x3E -> "x86_64"
            else -> "machine 0x${machine.toString(16)}"
        }
}

/**
 * The native half of the engine.
 *
 * Loading is attempted once and never retried, and every accessor returns null when the library
 * is absent, so a build without the native component degrades instead of crashing. Check
 * [isAvailable] before presenting any of this in the UI.
 */
object NativeBridge {

    private const val TAG = "AppCloner/NativeBridge"
    private const val LIBRARY = "appclonerbridge"

    val isAvailable: Boolean = try {
        System.loadLibrary(LIBRARY)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "lib$LIBRARY.so did not load; native checks are unavailable", t)
        false
    }

    /**
     * The SELinux label of a path, e.g.
     * `u:object_r:app_data_file:s0:c123,c256,c512,c768`.
     *
     * Null means the attribute could not be read, which for another app's data directory
     * normally means this process lacks the privilege rather than that no label exists.
     */
    fun selinuxContext(path: String): String? =
        if (isAvailable) runCatching { nativeSelinuxContext(path) }.getOrNull() else null

    fun ownership(path: String): PathOwnership? {
        if (!isAvailable) return null
        val values = runCatching { nativeStat(path) }.getOrNull() ?: return null
        if (values.size < 6) return null
        return PathOwnership(
            uid = values[0].toInt(),
            gid = values[1].toInt(),
            mode = values[2].toInt(),
            sizeBytes = values[3],
            linkCount = values[4],
            isDirectory = values[5] == 1L,
        )
    }

    fun elfIdentity(path: String): NativeElfIdentity? {
        if (!isAvailable) return null
        val values = runCatching { nativeElfHeader(path) }.getOrNull() ?: return null
        if (values.size < 3) return null
        return NativeElfIdentity(
            is64Bit = values[0] == 1,
            isLittleEndian = values[1] == 1,
            machine = values[2],
        )
    }

    /** The ABI the native library was compiled for, which is the ABI this process is running. */
    fun processAbi(): String? =
        if (isAvailable) runCatching { nativeAbi() }.getOrNull() else null

    fun processUid(): Int? =
        if (isAvailable) runCatching { nativeGetuid() }.getOrNull() else null

    @JvmStatic private external fun nativeSelinuxContext(path: String): String?
    @JvmStatic private external fun nativeStat(path: String): LongArray?
    @JvmStatic private external fun nativeElfHeader(path: String): IntArray?
    @JvmStatic private external fun nativeAbi(): String
    @JvmStatic private external fun nativeGetuid(): Int
}
