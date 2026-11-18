package com.akash.appcloner.engine.compat

/**
 * The device facts compatibility decisions are made against. A plain data class so the
 * evaluator stays pure and unit-testable; [com.akash.appcloner.engine.compat.DeviceProfileReader]
 * fills it in on a real device.
 */
data class DeviceProfile(
    val sdkInt: Int,
    val releaseName: String,
    val supportedAbis: List<String>,
    val supported64BitAbis: List<String>,
    val supported32BitAbis: List<String>,
    val graphics: GraphicsCapability,
    val totalRamBytes: Long?,
    val cpuCores: Int,
    val socModel: String?,
) {
    val is64BitCapable: Boolean get() = supported64BitAbis.isNotEmpty()

    /** A 64-bit-only device cannot host 32-bit native code at all, however the APK is pinned. */
    val is64BitOnly: Boolean get() = is64BitCapable && supported32BitAbis.isEmpty()

    /**
     * SDK levels this engine's behaviour has been reasoned about explicitly. Anything higher
     * still runs; it is reported as newer-than-validated instead of silently assumed equivalent.
     */
    val isNewerThanValidated: Boolean get() = sdkInt > HIGHEST_VALIDATED_SDK

    companion object {
        /** Android 12 - the floor the product targets. */
        const val LOWEST_SUPPORTED_SDK = 31

        /**
         * Highest SDK whose behaviour is encoded in this engine. Raise this only after the
         * test matrix has actually been run on that release; the code gates on SDK_INT
         * comparisons, never on compile-time SDK constants, so a newer release degrades to
         * "unvalidated" rather than to broken.
         */
        const val HIGHEST_VALIDATED_SDK = 35
    }
}
