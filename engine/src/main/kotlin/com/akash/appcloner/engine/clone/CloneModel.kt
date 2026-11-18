package com.akash.appcloner.engine.clone

/** How a clone is isolated from the original installation. */
enum class CloneBackendKind {
    /**
     * An AOSP clone profile (android.os.usertype.profile.CLONE): concurrent with the original,
     * same package name and signing certificate, its own UID and data. Created with root.
     */
    CLONE_PROFILE,

    /** A secondary Android user: full isolation, root to create, not concurrent. */
    SECONDARY_USER,

    /** Per-clone data isolation by mount namespace, via a companion root module. */
    ROOT_DATA_ISOLATION,
}

/**
 * What a backend can actually do. Every flag here is a property of the mechanism, not a wish:
 * the UI gates features on these rather than offering something the backend cannot deliver.
 */
data class BackendCapabilities(
    val kind: CloneBackendKind,
    /** Clone and original usable at the same time, without switching users. */
    val concurrentWithOriginal: Boolean,
    /** The clone keeps the original package name and signing identity. */
    val preservesPackageIdentity: Boolean,
    /** A game update to the original applies to the clone without recreating it. */
    val survivesGameUpdate: Boolean,
    /** No interception layer between the app and the platform, so no interpretation overhead. */
    val runsOnUnmodifiedRuntime: Boolean,
    val requiresRoot: Boolean,
    /** Null when the ceiling is device-configured rather than fixed. */
    val maxClonesPerPackage: Int?,
    /** Root can be allowed or denied for this clone specifically. */
    val perCloneRootControl: Boolean,
)

/** Whether a backend can be used on this device right now. */
sealed interface BackendAvailability {
    data object Available : BackendAvailability

    /**
     * [remedy] is what would make it available. A backend that cannot work here says so with a
     * reason rather than failing later inside a clone creation flow.
     */
    data class Unavailable(val reason: String, val remedy: String? = null) : BackendAvailability

    /** Usable only after a one-time setup step the user has to confirm. */
    data class NeedsSetup(val reason: String, val action: SetupAction) : BackendAvailability

    enum class SetupAction {
        /** Root has not been granted to this app yet. */
        GRANT_ROOT,

        /**
         * The device allows only one clone profile and it is already in use, so another
         * instance of the same package needs the per-parent limit raised.
         */
        RAISE_CLONE_PROFILE_LIMIT,

        INSTALL_COMPANION_ROOT_MODULE,
    }
}

enum class CloneState { CREATED, INSTALLED, READY, RUNNING, BROKEN, REMOVED }

/** A clone that exists on the device. [androidUserId] is null for backends that do not use one. */
data class CloneRecord(
    val id: String,
    val packageName: String,
    val label: String,
    val backend: CloneBackendKind,
    val androidUserId: Int?,
    val state: CloneState,
    val rootAllowed: Boolean,
    val forcedAbi: String?,
    val createdAtMillis: Long,
)

data class CloneRequest(
    val packageName: String,
    val label: String,
    /** Pins the ABI at install time, as the 32-bit leg of the test matrix needs. */
    val forcedAbi: String? = null,
    val allowRoot: Boolean = false,
)

/** Result of a clone operation. [nextStep] carries the operator's next action on failure. */
sealed interface CloneResult {
    data class Success(val clone: CloneRecord, val log: List<String> = emptyList()) : CloneResult
    data class Failure(
        val message: String,
        val nextStep: String? = null,
        val log: List<String> = emptyList(),
    ) : CloneResult

    /** The mechanism cannot do this at all - distinct from "it failed". */
    data class Unsupported(val message: String, val manualInstruction: String? = null) : CloneResult
}
