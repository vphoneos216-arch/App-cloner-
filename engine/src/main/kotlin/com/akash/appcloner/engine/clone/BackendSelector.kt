package com.akash.appcloner.engine.clone

/** What the operator wants out of the clone, which decides which mechanism fits. */
enum class CloneGoal {
    /** Both instances usable at once. Rules out secondary users. */
    CONCURRENT_WITH_ORIGINAL,

    /** Strongest separation of data and accounts, concurrency not required. */
    MAXIMUM_ISOLATION,

    /** Pin the ABI, as the 32-bit leg of the test matrix needs. */
    PINNED_ABI,
}

/** A backend together with why it is or is not usable here. */
data class BackendOption(
    val backend: CloneBackend,
    val availability: BackendAvailability,
    val fitsGoal: Boolean,
    val mismatchReason: String?,
) {
    val isUsable: Boolean get() = availability is BackendAvailability.Available && fitsGoal
}

/**
 * Ranks backends for a goal. Returns the whole picture rather than one answer: a UI that says
 * "unavailable because a work profile has not been provisioned" is worth more than one that
 * silently falls back to a mechanism with different properties.
 */
class BackendSelector(private val backends: List<CloneBackend>) {

    suspend fun options(goal: CloneGoal): List<BackendOption> =
        backends.map { backend ->
            val availability = backend.availability()
            val mismatch = mismatchFor(backend.capabilities, goal)
            BackendOption(
                backend = backend,
                availability = availability,
                fitsGoal = mismatch == null,
                mismatchReason = mismatch,
            )
        }.sortedWith(
            compareByDescending<BackendOption> { it.isUsable }
                .thenByDescending { it.availability is BackendAvailability.Available }
                .thenByDescending { it.fitsGoal },
        )

    suspend fun best(goal: CloneGoal): BackendOption? = options(goal).firstOrNull { it.isUsable }

    private fun mismatchFor(capabilities: BackendCapabilities, goal: CloneGoal): String? =
        when (goal) {
            CloneGoal.CONCURRENT_WITH_ORIGINAL ->
                if (capabilities.concurrentWithOriginal) {
                    null
                } else {
                    "Using this clone means switching Android users, so the original is not " +
                        "interactive at the same time."
                }

            CloneGoal.MAXIMUM_ISOLATION ->
                if (capabilities.kind == CloneBackendKind.SECONDARY_USER) {
                    null
                } else {
                    "A separate Android user gives stronger separation than this mechanism."
                }

            CloneGoal.PINNED_ABI ->
                if (capabilities.kind == CloneBackendKind.SECONDARY_USER) {
                    null
                } else {
                    "Only a staged session install can pin an ABI, which needs the " +
                        "secondary-user backend."
                }
        }
}
