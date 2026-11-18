package com.akash.appcloner.engine.compat

/**
 * Everything the engine knows about one installed package, all of it observed: either reported
 * by the package manager or read out of the APK's own central directory.
 *
 * Fields that could not be read are null rather than defaulted, so "unknown" never
 * masquerades as a value.
 */
data class ApkInspection(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val sourceApk: String?,
    val splitApks: List<String>,
    val nativeLibraryDir: String?,
    val extractNativeLibs: Boolean?,
    val apkAbis: List<String>,
    val nativeLibraries: List<NativeLibrary>,
    val engine: EngineDetection,
    val declaredFeatures: List<DeclaredFeature>,
    val declaredGlEsVersion: Int?,
    val graphics: GraphicsRequirement,
    val obb: ObbLayout?,
    val obbAccessFailure: String?,
    val externalDataDirExists: Boolean?,
    val apkReadFailure: String?,
    val inspectedAtMillis: Long,
) {
    /** True when the APK's own entries could not be read and detection is manifest-only. */
    val isDegraded: Boolean get() = apkReadFailure != null

    val totalNativeBytes: Long get() = nativeLibraries.sumOf { it.uncompressedSize }
}
