package com.akash.appcloner.engine.compat

/** One `lib/<abiDir>/<fileName>` entry actually present in an APK's central directory. */
data class NativeLibrary(
    val abiDir: String,
    val fileName: String,
    val uncompressedSize: Long,
    val stored: Boolean,
) {
    val path: String get() = "lib/$abiDir/$fileName"
}
