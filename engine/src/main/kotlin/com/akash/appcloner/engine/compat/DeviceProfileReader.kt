package com.akash.appcloner.engine.compat

import android.app.ActivityManager
import android.content.Context
import android.content.pm.FeatureInfo
import android.os.Build
import androidx.core.content.ContextCompat

/** Fills a [DeviceProfile] from the running device. */
class DeviceProfileReader(private val context: Context) {

    fun read(): DeviceProfile {
        val pm = context.packageManager
        val features = runCatching { pm.systemAvailableFeatures.orEmpty().toList() }
            .getOrDefault(emptyList())

        fun featureVersion(name: String): Int? =
            features.firstOrNull { it.name == name }?.version

        val glEs = features
            .firstOrNull { it.name == null && it.reqGlEsVersion != FeatureInfo.GL_ES_VERSION_UNDEFINED }
            ?.reqGlEsVersion

        val totalRam = runCatching {
            val am = ContextCompat.getSystemService(context, ActivityManager::class.java)
            ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }.totalMem
        }.getOrNull()?.takeIf { it > 0 }

        return DeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            releaseName = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            supportedAbis = Build.SUPPORTED_ABIS.orEmpty().toList(),
            supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.orEmpty().toList(),
            supported32BitAbis = Build.SUPPORTED_32_BIT_ABIS.orEmpty().toList(),
            graphics = GraphicsCapability(
                vulkanVersion = featureVersion(GraphicsRequirement.FEATURE_VULKAN_VERSION),
                vulkanLevel = featureVersion(GraphicsRequirement.FEATURE_VULKAN_LEVEL),
                glEsVersion = glEs,
            ),
            totalRamBytes = totalRam,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            socModel = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN },
        )
    }
}
