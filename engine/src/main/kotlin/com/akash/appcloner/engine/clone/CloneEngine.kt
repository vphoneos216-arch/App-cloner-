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

    /**
     * Creates a clone, trying every mechanism this device actually supports.
     *
     * Backends that fit the goal are tried first, then the rest as fallbacks. Committing to a
     * single backend was a real defect: a ROM that advertises root but refuses the clone profile
     * type made the engine fail outright instead of dropping to a mechanism that works. A
     * fallback that does not meet the stated goal is used, but the result says so.
     */
    suspend fun create(request: CloneRequest, goal: CloneGoal): CloneResult {
        val all = options(goal)
        val usable = all.filter { it.availability is BackendAvailability.Available }
        val log = mutableListOf<String>()

        if (usable.isEmpty()) {
            return CloneResult.Failure(
                message = "No cloning mechanism is available on this device.",
                nextStep = all.joinToString("  |  ") { option ->
                    val why = when (val availability = option.availability) {
                        is BackendAvailability.Unavailable ->
                            availability.reason + (availability.remedy?.let { " $it" } ?: "")

                        is BackendAvailability.NeedsSetup -> availability.reason
                        BackendAvailability.Available -> "available"
                    }
                    "${option.backend.kind}: $why"
                },
                log = all.map { "${it.backend.kind}: ${it.availability}" },
            )
        }

        // Goal-fitting backends first; the rest are still tried, because a working clone beats
        // no clone, and the caller is told which property was given up.
        val ordered = usable.sortedByDescending { it.fitsGoal }
        val failures = mutableListOf<String>()

        for (option in ordered) {
            log += "--- attempting ${option.backend.kind} ---"
            when (val result = option.backend.createClone(request)) {
                is CloneResult.Success -> {
                    store.put(result.clone)
                    val note = if (option.fitsGoal) {
                        null
                    } else {
                        "Used ${option.backend.kind} as a fallback: ${option.mismatchReason}"
                    }
                    return CloneResult.Success(
                        clone = result.clone,
                        log = log + result.log + listOfNotNull(note),
                    )
                }

                is CloneResult.Failure -> {
                    failures += "${option.backend.kind}: ${result.message}"
                    log += result.log
                    result.nextStep?.let { log += it }
                }

                is CloneResult.Unsupported -> {
                    failures += "${option.backend.kind}: ${result.message}"
                    result.manualInstruction?.let { log += it }
                }
            }
        }

        return CloneResult.Failure(
            message = "Every available mechanism refused. " + failures.joinToString("  |  "),
            nextStep = "Run diagnostics from the Clones tab and send the report; it carries " +
                "each command and its exact output.",
            log = log,
        )
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
