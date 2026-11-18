package com.akash.appcloner.engine.compat

/**
 * ABIs this engine reasons about by name. An APK directory under `lib/` that is not
 * one of these is still reported, verbatim, as an unknown ABI rather than dropped.
 */
enum class Abi(val directoryName: String, val is64Bit: Boolean) {
    ARM64_V8A("arm64-v8a", true),
    ARMEABI_V7A("armeabi-v7a", false),
    X86_64("x86_64", true),
    X86("x86", false),
    ;

    companion object {
        fun fromDirectoryName(name: String): Abi? = entries.firstOrNull { it.directoryName == name }
    }
}

/** Outcome of matching an APK's native code against a device's supported ABI list. */
sealed interface AbiResolution {

    /**
     * The ABI the platform installer would pick: the first entry of [deviceOrder] that the
     * APK also ships. This mirrors the platform rule, it is not a measurement of the
     * running process - confirm with the process's own `/proc/<pid>/exe` link if it matters.
     */
    data class Resolved(
        val abi: String,
        val is64Bit: Boolean,
        val apkAbis: List<String>,
        val deviceOrder: List<String>,
        val forced: Boolean = false,
    ) : AbiResolution

    /** The APK ships no `lib/<abi>/` entries at all, so any ABI can host it. */
    data object NoNativeCode : AbiResolution

    /** No overlap: the platform would reject this install with INSTALL_FAILED_NO_MATCHING_ABIS. */
    data class NoMatchingAbi(
        val apkAbis: List<String>,
        val deviceOrder: List<String>,
    ) : AbiResolution

    /** A forced ABI was requested that the APK or the device does not provide. */
    data class ForcedAbiUnavailable(
        val requested: String,
        val apkAbis: List<String>,
        val deviceOrder: List<String>,
        val missingFrom: Missing,
    ) : AbiResolution {
        enum class Missing { APK, DEVICE, BOTH }
    }
}

/**
 * Pure ABI matching. Deliberately free of Android imports so it can be unit tested on the JVM.
 *
 * [deviceOrder] must be `Build.SUPPORTED_ABIS` in its original order: the platform treats that
 * order as a preference list, 64-bit first on a 64-bit device.
 */
object AbiResolver {

    fun resolve(apkAbis: Collection<String>, deviceOrder: List<String>): AbiResolution {
        val apk = apkAbis.distinct()
        if (apk.isEmpty()) return AbiResolution.NoNativeCode
        val picked = deviceOrder.firstOrNull { it in apk }
            ?: return AbiResolution.NoMatchingAbi(apk, deviceOrder)
        return AbiResolution.Resolved(
            abi = picked,
            is64Bit = Abi.fromDirectoryName(picked)?.is64Bit ?: picked.contains("64"),
            apkAbis = apk,
            deviceOrder = deviceOrder,
        )
    }

    /**
     * Matching when an ABI is pinned, as `pm install --abi <abi>` does. Used by the 32-bit
     * leg of the test matrix: a game that ships both arm64-v8a and armeabi-v7a runs 64-bit by
     * default and only goes 32-bit when pinned.
     */
    fun resolveForced(
        apkAbis: Collection<String>,
        deviceOrder: List<String>,
        forced: String,
    ): AbiResolution {
        val apk = apkAbis.distinct()
        if (apk.isEmpty()) return AbiResolution.NoNativeCode
        val inApk = forced in apk
        val onDevice = forced in deviceOrder
        if (inApk && onDevice) {
            return AbiResolution.Resolved(
                abi = forced,
                is64Bit = Abi.fromDirectoryName(forced)?.is64Bit ?: forced.contains("64"),
                apkAbis = apk,
                deviceOrder = deviceOrder,
                forced = true,
            )
        }
        val missing = when {
            !inApk && !onDevice -> AbiResolution.ForcedAbiUnavailable.Missing.BOTH
            !inApk -> AbiResolution.ForcedAbiUnavailable.Missing.APK
            else -> AbiResolution.ForcedAbiUnavailable.Missing.DEVICE
        }
        return AbiResolution.ForcedAbiUnavailable(forced, apk, deviceOrder, missing)
    }
}
