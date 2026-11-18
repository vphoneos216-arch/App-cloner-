package com.akash.appcloner.engine.policy

/**
 * The lines this engine does not cross, kept in code so they are not a README promise.
 *
 * Two of them are product requirements from the spec; the rest exist because the alternative is
 * lying to the operator about performance.
 */
object IntegrityPolicy {

    /**
     * No interference with a game's anti-cheat, integrity or security checks. Isolation is done
     * with platform mechanisms - separate users and profiles - which leave the package name,
     * signature and runtime untouched. No hooking of a game's process, no patching of its
     * libraries, no spoofing of device or integrity attestation.
     */
    const val NO_INTEGRITY_INTERFERENCE = true

    /**
     * No synthetic frame-rate unlocking and no GPU driver or vendor-blob modification. A frame
     * cap raised past what the display pipeline sustains produces worse pacing, not better, and
     * a number on a dashboard that the panel never showed is a lie.
     */
    const val NO_FRAME_RATE_UNLOCK = true
    const val NO_GPU_DRIVER_OVERRIDE = true

    /**
     * A metric is either measured or reported as unavailable. Nothing in the perf layer
     * interpolates, smooths over a gap, or substitutes a plausible value.
     */
    const val NO_SYNTHETIC_METRICS = true

    /** Short rationale for the UI, so a disabled control explains itself. */
    const val UI_EXPLANATION =
        "Isolation uses Android's own per-user separation. The game's package, signature and " +
            "runtime are untouched, and no frame-rate or GPU overrides are applied."
}
