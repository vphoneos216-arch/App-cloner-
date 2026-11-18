package com.akash.appcloner.engine.compat

enum class Severity { BLOCKER, WARNING, INFO }

/**
 * One observation. [nextStep] is the concrete thing to do about it - the point of a finding is
 * to shorten the work, not to decorate a screen.
 */
data class Finding(
    val code: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    val nextStep: String? = null,
)

/**
 * Deliberately not called "compatible".
 *
 * Static inspection can only rule things out. [NO_BLOCKERS_FOUND] means nothing in the package
 * metadata contradicts this device; whether the game launches, logs in and holds frame pacing
 * is a question only a run on the device answers.
 */
enum class StaticVerdict { BLOCKED, NO_BLOCKERS_FOUND, UNVERIFIABLE }

data class CompatibilityReport(
    val packageName: String,
    val label: String,
    val abi: AbiResolution,
    val forced32Bit: AbiResolution?,
    val findings: List<Finding>,
    val evaluatedAtMillis: Long,
) {
    val blockers: List<Finding> get() = findings.filter { it.severity == Severity.BLOCKER }
    val warnings: List<Finding> get() = findings.filter { it.severity == Severity.WARNING }

    val verdict: StaticVerdict
        get() = when {
            blockers.isNotEmpty() -> StaticVerdict.BLOCKED
            findings.any { it.code == CompatibilityCodes.APK_UNREADABLE } -> StaticVerdict.UNVERIFIABLE
            else -> StaticVerdict.NO_BLOCKERS_FOUND
        }
}

object CompatibilityCodes {
    const val NO_MATCHING_ABI = "abi.no_match"
    const val ABI_32_ON_64 = "abi.32bit_selected"
    const val ABI_32_UNAVAILABLE = "abi.32bit_unavailable"
    const val NO_NATIVE_CODE = "abi.no_native_code"
    const val MIN_SDK_TOO_HIGH = "sdk.min_above_device"
    const val SDK_UNVALIDATED = "sdk.newer_than_validated"
    const val VULKAN_REQUIRED_UNSUPPORTED = "gfx.vulkan_required_unsupported"
    const val VULKAN_VERSION_SHORTFALL = "gfx.vulkan_version_shortfall"
    const val GLES_VERSION_SHORTFALL = "gfx.gles_version_shortfall"
    const val OBB_UNVERIFIABLE = "obb.unverifiable"
    const val OBB_STALE = "obb.version_mismatch"
    const val OBB_ABSENT = "obb.absent"
    const val SPLIT_INSTALL = "install.split_apks"
    const val LIBS_NOT_EXTRACTED = "install.libs_in_apk"
    const val APK_UNREADABLE = "install.apk_unreadable"
    const val ENGINE_NOTE = "engine.note"
    const val MEMORY_HEADROOM = "perf.memory_headroom_heuristic"
}
