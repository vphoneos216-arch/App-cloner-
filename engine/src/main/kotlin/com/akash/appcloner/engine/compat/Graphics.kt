package com.akash.appcloner.engine.compat

/** A `uses-feature` entry as the package manager reported it. */
data class DeclaredFeature(
    val name: String,
    val version: Int,
    val required: Boolean,
)

/**
 * Graphics capability of the device, read once from the package manager.
 *
 * [vulkanVersion] and [vulkanLevel] are the highest values the device answers true for.
 * [glEsVersion] is packed major/minor as the platform packs it (0x00030002 == GLES 3.2).
 */
data class GraphicsCapability(
    val vulkanVersion: Int?,
    val vulkanLevel: Int?,
    val glEsVersion: Int?,
) {
    val supportsVulkan: Boolean get() = vulkanVersion != null

    companion object {
        /** Formats a packed GLES / Vulkan version word as "major.minor". */
        fun formatPackedVersion(packed: Int): String =
            "${(packed shr 16) and 0xFFFF}.${packed and 0xFFFF}"
    }
}

/** What the APK asks of the graphics stack, derived only from declared features. */
data class GraphicsRequirement(
    val requiresVulkan: Boolean,
    val requiredVulkanVersion: Int?,
    val requiredVulkanLevel: Int?,
    val requiredGlEsVersion: Int?,
) {
    companion object {
        const val FEATURE_VULKAN_VERSION = "android.hardware.vulkan.version"
        const val FEATURE_VULKAN_LEVEL = "android.hardware.vulkan.level"
        const val FEATURE_VULKAN_COMPUTE = "android.hardware.vulkan.compute"

        /**
         * Pure derivation from declared features.
         *
         * A declaration is the only honest signal available statically. Most shipped games
         * declare Vulkan as optional and choose the API at runtime from a device allow-list
         * baked into the build, so this never claims to know which API a given game will use.
         */
        fun fromDeclaredFeatures(
            features: List<DeclaredFeature>,
            declaredGlEsVersion: Int?,
        ): GraphicsRequirement {
            val vulkanVersion = features.firstOrNull { it.name == FEATURE_VULKAN_VERSION }
            val vulkanLevel = features.firstOrNull { it.name == FEATURE_VULKAN_LEVEL }
            return GraphicsRequirement(
                requiresVulkan = vulkanVersion?.required == true || vulkanLevel?.required == true,
                requiredVulkanVersion = vulkanVersion?.version,
                requiredVulkanLevel = vulkanLevel?.version,
                requiredGlEsVersion = declaredGlEsVersion,
            )
        }
    }
}
