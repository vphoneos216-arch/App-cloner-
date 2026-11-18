package com.akash.appcloner.engine.compat

/**
 * Turns an [ApkInspection] plus a [DeviceProfile] into findings. Pure: no Android imports, so
 * every rule below is exercised by unit tests rather than by installing a game.
 *
 * The rules only encode platform behaviour that is documented and checkable. There is no table
 * of "known good" game builds here, because that table would be a guess the moment a game
 * updates.
 */
object CompatibilityEvaluator {

    /**
     * Device-class heuristic for hosting two instances of a heavy title at once. Flagged as a
     * heuristic in the finding text; the dashboard's own memory readings are the real evidence.
     */
    private const val DUAL_INSTANCE_RAM_HEURISTIC_BYTES = 6L * 1024 * 1024 * 1024

    fun evaluate(
        inspection: ApkInspection,
        device: DeviceProfile,
        forced32BitAbi: String = Abi.ARMEABI_V7A.directoryName,
    ): CompatibilityReport {
        val findings = mutableListOf<Finding>()

        val abi = AbiResolver.resolve(inspection.apkAbis, device.supportedAbis)
        val forced32 = if (inspection.apkAbis.isEmpty()) {
            null
        } else {
            AbiResolver.resolveForced(inspection.apkAbis, device.supportedAbis, forced32BitAbi)
        }

        evaluateAbi(abi, forced32, device, findings)
        evaluateSdk(inspection, device, findings)
        evaluateGraphics(inspection, device, findings)
        evaluateStorage(inspection, findings)
        evaluateInstallShape(inspection, findings)
        evaluateEngine(inspection, findings)
        evaluateMemoryHeadroom(device, findings)

        return CompatibilityReport(
            packageName = inspection.packageName,
            label = inspection.label,
            abi = abi,
            forced32Bit = forced32,
            findings = findings.sortedBy { it.severity.ordinal },
            evaluatedAtMillis = inspection.inspectedAtMillis,
        )
    }

    private fun evaluateAbi(
        abi: AbiResolution,
        forced32: AbiResolution?,
        device: DeviceProfile,
        findings: MutableList<Finding>,
    ) {
        when (abi) {
            is AbiResolution.NoMatchingAbi -> findings += Finding(
                code = CompatibilityCodes.NO_MATCHING_ABI,
                severity = Severity.BLOCKER,
                title = "No ABI in common with this device",
                detail = "APK ships ${abi.apkAbis.joinToString()}; device supports " +
                    "${abi.deviceAbis.joinToString()}.",
                nextStep = "This build cannot run here in any container. Obtain a build that " +
                    "ships one of the device ABIs.",
            )

            AbiResolution.NoNativeCode -> findings += Finding(
                code = CompatibilityCodes.NO_NATIVE_CODE,
                severity = Severity.INFO,
                title = "No native code in the install",
                detail = "No lib/<abi>/ entries were found, so ABI selection does not apply.",
            )

            is AbiResolution.Resolved -> {
                if (!abi.is64Bit && device.is64BitCapable) {
                    findings += Finding(
                        code = CompatibilityCodes.ABI_32_ON_64,
                        severity = Severity.INFO,
                        title = "32-bit ABI selected on a 64-bit device",
                        detail = "Platform preference order picked ${abi.abi}.",
                        nextStep = "Confirm against the live process rather than this " +
                            "prediction: readlink /proc/<pid>/exe, or check which linker " +
                            "mapped the libraries.",
                    )
                }
            }

            is AbiResolution.ForcedAbiUnavailable -> Unit
        }

        if (forced32 is AbiResolution.ForcedAbiUnavailable) {
            val reason = when (forced32.missingFrom) {
                AbiResolution.ForcedAbiUnavailable.Missing.APK ->
                    "the APK ships no ${forced32.requested} libraries"
                AbiResolution.ForcedAbiUnavailable.Missing.DEVICE ->
                    "the device reports no 32-bit ABI support"
                AbiResolution.ForcedAbiUnavailable.Missing.BOTH ->
                    "neither the APK nor the device provides ${forced32.requested}"
            }
            findings += Finding(
                code = CompatibilityCodes.ABI_32_UNAVAILABLE,
                severity = Severity.INFO,
                title = "32-bit leg of the test matrix not applicable",
                detail = "Cannot pin ${forced32.requested}: $reason.",
                nextStep = "Record this leg as not-applicable in the matrix rather than as a pass.",
            )
        }
    }

    private fun evaluateSdk(
        inspection: ApkInspection,
        device: DeviceProfile,
        findings: MutableList<Finding>,
    ) {
        val minSdk = inspection.minSdk
        if (minSdk != null && minSdk > device.sdkInt) {
            findings += Finding(
                code = CompatibilityCodes.MIN_SDK_TOO_HIGH,
                severity = Severity.BLOCKER,
                title = "Package requires a newer Android release",
                detail = "minSdk $minSdk, device API ${device.sdkInt} (Android ${device.releaseName}).",
            )
        }
        if (device.isNewerThanValidated) {
            findings += Finding(
                code = CompatibilityCodes.SDK_UNVALIDATED,
                severity = Severity.WARNING,
                title = "Android release newer than this engine has been validated on",
                detail = "Device API ${device.sdkInt}; highest validated is " +
                    "${DeviceProfile.HIGHEST_VALIDATED_SDK}. Clone creation paths and storage " +
                    "access rules are the parts that historically change between releases.",
                nextStep = "Run the full matrix on this release before relying on it, then " +
                    "raise HIGHEST_VALIDATED_SDK.",
            )
        }
    }

    private fun evaluateGraphics(
        inspection: ApkInspection,
        device: DeviceProfile,
        findings: MutableList<Finding>,
    ) {
        val need = inspection.graphics
        val have = device.graphics

        if (need.requiresVulkan && !have.supportsVulkan) {
            findings += Finding(
                code = CompatibilityCodes.VULKAN_REQUIRED_UNSUPPORTED,
                severity = Severity.BLOCKER,
                title = "Vulkan declared as required, device reports none",
                detail = "The package marks a Vulkan feature as required and the device " +
                    "advertises no Vulkan version.",
            )
        }

        val needVersion = need.requiredVulkanVersion
        val haveVersion = have.vulkanVersion
        if (needVersion != null && haveVersion != null && needVersion > haveVersion) {
            findings += Finding(
                code = CompatibilityCodes.VULKAN_VERSION_SHORTFALL,
                severity = if (need.requiresVulkan) Severity.BLOCKER else Severity.WARNING,
                title = "Declared Vulkan version above device support",
                detail = "Declared ${GraphicsCapability.formatPackedVersion(needVersion)}, " +
                    "device ${GraphicsCapability.formatPackedVersion(haveVersion)}.",
            )
        }

        val needGl = need.requiredGlEsVersion
        val haveGl = have.glEsVersion
        if (needGl != null && haveGl != null && needGl > haveGl) {
            findings += Finding(
                code = CompatibilityCodes.GLES_VERSION_SHORTFALL,
                severity = Severity.BLOCKER,
                title = "Declared OpenGL ES version above device support",
                detail = "Declared ${GraphicsCapability.formatPackedVersion(needGl)}, " +
                    "device ${GraphicsCapability.formatPackedVersion(haveGl)}.",
            )
        }
    }

    private fun evaluateStorage(inspection: ApkInspection, findings: MutableList<Finding>) {
        val obb = inspection.obb
        if (obb == null) {
            findings += Finding(
                code = CompatibilityCodes.OBB_UNVERIFIABLE,
                severity = Severity.WARNING,
                title = "Expansion files could not be inspected",
                detail = inspection.obbAccessFailure
                    ?: "Android/obb for another package is not reachable on this release.",
                nextStep = "Grant root, or run the check from inside the clone, before reading " +
                    "anything into the absence of expansion files.",
            )
            return
        }
        if (!obb.exists || obb.files.isEmpty()) {
            findings += Finding(
                code = CompatibilityCodes.OBB_ABSENT,
                severity = Severity.INFO,
                title = "No expansion files present",
                detail = "${obb.directory} holds no .obb files. Many current titles download " +
                    "assets into their own data directory instead.",
            )
            return
        }
        if (!obb.isConsistentWithInstall) {
            findings += Finding(
                code = CompatibilityCodes.OBB_STALE,
                severity = Severity.WARNING,
                title = "Expansion files do not match the installed version",
                detail = "Installed version code ${obb.installedVersionCode}; stale files: " +
                    obb.staleFiles.joinToString { it.fileName } + ".",
                nextStep = "This is the usual cause of a clone breaking after a game update. " +
                    "Let the game re-fetch assets in the clone instead of copying the old files.",
            )
        }
    }

    private fun evaluateInstallShape(inspection: ApkInspection, findings: MutableList<Finding>) {
        if (inspection.apkReadFailure != null) {
            findings += Finding(
                code = CompatibilityCodes.APK_UNREADABLE,
                severity = Severity.WARNING,
                title = "APK entries could not be read",
                detail = inspection.apkReadFailure,
                nextStep = "Engine and ABI detection fell back to package-manager metadata " +
                    "only. Treat the engine verdict as unconfirmed.",
            )
        }
        if (inspection.splitApks.isNotEmpty()) {
            findings += Finding(
                code = CompatibilityCodes.SPLIT_INSTALL,
                severity = Severity.INFO,
                title = "Split install (${inspection.splitApks.size} splits)",
                detail = "Native libraries and assets may live in config splits.",
                nextStep = "Use a backend that installs the existing package for another user; " +
                    "it carries the splits across. Any APK-rewriting approach has to reassemble " +
                    "the whole split set.",
            )
        }
        if (inspection.extractNativeLibs == false) {
            findings += Finding(
                code = CompatibilityCodes.LIBS_NOT_EXTRACTED,
                severity = Severity.INFO,
                title = "Native libraries are mapped from the APK",
                detail = "extractNativeLibs=false, so the libraries are loaded directly out of " +
                    "the archive and must stay page-aligned and uncompressed.",
                nextStep = "Do not re-zip this APK. Backends that reuse the installed package " +
                    "are unaffected.",
            )
        }
    }

    private fun evaluateEngine(inspection: ApkInspection, findings: MutableList<Finding>) {
        val engine = inspection.engine
        if (engine.notes.isEmpty() && engine.evidence.isEmpty()) return
        findings += Finding(
            code = CompatibilityCodes.ENGINE_NOTE,
            severity = Severity.INFO,
            title = "Engine detected: ${engine.kind}",
            detail = buildString {
                if (engine.evidence.isNotEmpty()) {
                    append("Evidence: ")
                    append(engine.evidence.joinToString())
                }
                engine.notes.forEach {
                    if (isNotEmpty()) append(' ')
                    append(it)
                }
            },
        )
    }

    private fun evaluateMemoryHeadroom(device: DeviceProfile, findings: MutableList<Finding>) {
        val ram = device.totalRamBytes ?: return
        if (ram >= DUAL_INSTANCE_RAM_HEURISTIC_BYTES) return
        findings += Finding(
            code = CompatibilityCodes.MEMORY_HEADROOM,
            severity = Severity.INFO,
            title = "Limited headroom for two instances at once",
            detail = "Device reports ${ram / (1024 * 1024)} MB total. This is a device-class " +
                "heuristic, not a measurement of this title.",
            nextStep = "Watch available memory and the low-memory-kill counter on the dashboard " +
                "during a real match before concluding anything.",
        )
    }
}
