package com.akash.appcloner.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityEvaluatorTest {

    private fun device(
        sdkInt: Int = 34,
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        vulkan: Int? = 0x00400003,
        glEs: Int? = 0x00030002,
        ram: Long = 8L * 1024 * 1024 * 1024,
    ) = DeviceProfile(
        sdkInt = sdkInt,
        releaseName = "test",
        supportedAbis = abis,
        supported64BitAbis = abis.filter { it.contains("64") },
        supported32BitAbis = abis.filterNot { it.contains("64") },
        graphics = GraphicsCapability(vulkan, vulkan, glEs),
        totalRamBytes = ram,
        cpuCores = 8,
        socModel = "test-soc",
    )

    private fun inspection(
        apkAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        minSdk: Int? = 31,
        features: List<DeclaredFeature> = emptyList(),
        glEs: Int? = null,
        obb: ObbLayout? = ObbLayout.absent("/obb", 1L),
        obbFailure: String? = null,
        splits: List<String> = emptyList(),
        extractNativeLibs: Boolean? = true,
        apkFailure: String? = null,
    ) = ApkInspection(
        packageName = "com.example.game",
        label = "Example",
        versionName = "1.0",
        versionCode = 1L,
        minSdk = minSdk,
        targetSdk = 34,
        sourceApk = "/data/app/base.apk",
        splitApks = splits,
        nativeLibraryDir = "/data/app/lib/arm64",
        extractNativeLibs = extractNativeLibs,
        apkAbis = apkAbis,
        nativeLibraries = apkAbis.map { NativeLibrary(it, "libunity.so", 1024L, false) },
        engine = GameEngineDetector.detect(apkAbis.map { "lib/$it/libunity.so" }),
        declaredFeatures = features,
        declaredGlEsVersion = glEs,
        graphics = GraphicsRequirement.fromDeclaredFeatures(features, glEs),
        obb = obb,
        obbAccessFailure = obbFailure,
        externalDataDirExists = true,
        apkReadFailure = apkFailure,
        inspectedAtMillis = 0L,
    )

    @Test
    fun `clean package on a capable device finds no blockers`() {
        val report = CompatibilityEvaluator.evaluate(inspection(), device())
        assertEquals(StaticVerdict.NO_BLOCKERS_FOUND, report.verdict)
        assertTrue(report.blockers.isEmpty())
    }

    @Test
    fun `missing abi overlap is a blocker`() {
        val report = CompatibilityEvaluator.evaluate(
            inspection(apkAbis = listOf("x86_64")),
            device(),
        )
        assertEquals(StaticVerdict.BLOCKED, report.verdict)
        assertTrue(report.blockers.any { it.code == CompatibilityCodes.NO_MATCHING_ABI })
    }

    @Test
    fun `min sdk above the device is a blocker`() {
        val report = CompatibilityEvaluator.evaluate(inspection(minSdk = 36), device(sdkInt = 33))
        assertTrue(report.blockers.any { it.code == CompatibilityCodes.MIN_SDK_TOO_HIGH })
    }

    @Test
    fun `required vulkan on a device without it is a blocker`() {
        val required = listOf(
            DeclaredFeature(GraphicsRequirement.FEATURE_VULKAN_VERSION, 0x00400003, required = true),
        )
        val report = CompatibilityEvaluator.evaluate(
            inspection(features = required),
            device(vulkan = null),
        )
        assertTrue(
            report.blockers.any { it.code == CompatibilityCodes.VULKAN_REQUIRED_UNSUPPORTED },
        )
    }

    @Test
    fun `optional vulkan above device support is only a warning`() {
        val optional = listOf(
            DeclaredFeature(GraphicsRequirement.FEATURE_VULKAN_VERSION, 0x00401000, required = false),
        )
        val report = CompatibilityEvaluator.evaluate(
            inspection(features = optional),
            device(vulkan = 0x00400003),
        )
        assertTrue(report.blockers.isEmpty())
        assertTrue(report.warnings.any { it.code == CompatibilityCodes.VULKAN_VERSION_SHORTFALL })
    }

    @Test
    fun `unreadable expansion directory is a warning not an absence`() {
        val report = CompatibilityEvaluator.evaluate(
            inspection(obb = null, obbFailure = "scoped storage"),
            device(),
        )
        assertTrue(report.warnings.any { it.code == CompatibilityCodes.OBB_UNVERIFIABLE })
    }

    @Test
    fun `stale expansion files are reported as the update hazard`() {
        val stale = ObbLayout(
            directory = "/obb",
            exists = true,
            files = listOf(ObbNameParser.parse("main.1.com.example.game.obb", 5L)),
            totalBytes = 5L,
            installedVersionCode = 9L,
        )
        val report = CompatibilityEvaluator.evaluate(inspection(obb = stale), device())
        assertTrue(report.warnings.any { it.code == CompatibilityCodes.OBB_STALE })
    }

    @Test
    fun `unreadable apk makes the verdict unverifiable`() {
        val report = CompatibilityEvaluator.evaluate(
            inspection(apkFailure = "permission denied"),
            device(),
        )
        assertEquals(StaticVerdict.UNVERIFIABLE, report.verdict)
    }

    @Test
    fun `newer than validated android release warns rather than blocks`() {
        val report = CompatibilityEvaluator.evaluate(
            inspection(),
            device(sdkInt = DeviceProfile.HIGHEST_VALIDATED_SDK + 2),
        )
        assertTrue(report.blockers.isEmpty())
        assertTrue(report.warnings.any { it.code == CompatibilityCodes.SDK_UNVALIDATED })
    }

    @Test
    fun `split install and unextracted libraries are flagged for the backend choice`() {
        val report = CompatibilityEvaluator.evaluate(
            inspection(splits = listOf("/data/app/split_config.arm64_v8a.apk"), extractNativeLibs = false),
            device(),
        )
        val codes = report.findings.map { it.code }
        assertTrue(CompatibilityCodes.SPLIT_INSTALL in codes)
        assertTrue(CompatibilityCodes.LIBS_NOT_EXTRACTED in codes)
    }

    @Test
    fun `32-bit pin is reported not applicable when the apk is 64-bit only`() {
        val report = CompatibilityEvaluator.evaluate(
            inspection(apkAbis = listOf("arm64-v8a")),
            device(),
        )
        assertTrue(report.findings.any { it.code == CompatibilityCodes.ABI_32_UNAVAILABLE })
    }
}
