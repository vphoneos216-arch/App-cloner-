package com.akash.appcloner.engine.clone

/**
 * Entry point for clone operations: picks a backend for the goal, runs the operation, and keeps
 * the local record in step with what the backends report.
 */
class CloneEngine(
    private val backends: List<CloneBackend>,
    private val store: CloneStore,
) {

    private val selector = BackendSelector(backends)

    suspend fun options(goal: CloneGoal): List<BackendOption> = selector.options(goal)

    fun backendFor(kind: CloneBackendKind): CloneBackend? = backends.firstOrNull { it.kind == kind }

    /**
     * Clones as they really are. Entries this app recorded but that no backend can see are
     * marked [CloneState.BROKEN] rather than dropped, so a clone removed behind the app's back
     * is visible instead of silently missing.
     */
    suspend fun clones(): List<CloneRecord> {
        val live = backends.flatMap { runCatching { it.listClones() }.getOrDefault(emptyList()) }
        val liveIds = live.map { it.id }.toSet()
        val stored = store.all()

        val enriched = live.map { record ->
            val known = stored.firstOrNull { it.id == record.id }
            if (known == null) record else record.copy(
                label = known.label,
                createdAtMillis = known.createdAtMillis,
                forcedAbi = known.forcedAbi,
                rootAllowed = known.rootAllowed,
            )
        }

        // Every backend enumerates from the platform now, so a stored record that no backend
        // can see is a clone that was removed behind this app's back, not a blind spot.
        val unseen = stored
            .filterNot { it.id in liveIds }
            .map { it.copy(state = CloneState.BROKEN) }

        return (enriched + unseen).sortedBy { it.label.lowercase() }
    }

    suspend fun create(request: CloneRequest, goal: CloneGoal): CloneResult {
        val option = selector.best(goal)
            ?: return CloneResult.Failure(
                message = "No backend on this device can satisfy ${goal.name}.",
                nextStep = options(goal).joinToString("; ") { opt ->
                    val why = when (val a = opt.availability) {
                        is BackendAvailability.Unavailable -> a.reason
                        is BackendAvailability.NeedsSetup -> a.reason
                        BackendAvailability.Available -> opt.mismatchReason ?: "goal mismatch"
                    }
                    "${opt.backend.kind}: $why"
                },
            )

        return option.backend.createClone(request).also { result ->
            if (result is CloneResult.Success) store.put(result.clone)
        }
    }

    suspend fun launch(clone: CloneRecord): CloneResult {
        val backend = backendFor(clone.backend)
            ?: return CloneResult.Failure("Backend ${clone.backend} is not registered")
        return backend.launch(clone)
    }

    suspend fun remove(clone: CloneRecord): CloneResult {
        val backend = backendFor(clone.backend)
            ?: return CloneResult.Failure("Backend ${clone.backend} is not registered")
        return backend.remove(clone).also { result ->
            if (result is CloneResult.Success) store.remove(clone.id)
        }
    }

    suspend fun setRootAccess(clone: CloneRecord, allowed: Boolean): CloneResult {
        val backend = backendFor(clone.backend)
            ?: return CloneResult.Failure("Backend ${clone.backend} is not registered")
        return backend.setRootAccess(clone, allowed).also { result ->
            if (result is CloneResult.Success) store.put(result.clone)
        }
    }
}
