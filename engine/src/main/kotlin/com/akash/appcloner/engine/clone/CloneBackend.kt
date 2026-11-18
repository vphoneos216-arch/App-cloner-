package com.akash.appcloner.engine.clone

/**
 * One way of producing a second instance of an installed app.
 *
 * Every implementation uses platform isolation - a separate Android user or profile - rather
 * than running the game inside this app's process. That choice is what keeps the package name
 * and signature intact, so the game's own login and update paths are untouched, and what keeps
 * the game on the unmodified runtime so frame pacing is the device's own.
 */
interface CloneBackend {

    val kind: CloneBackendKind
    val capabilities: BackendCapabilities

    suspend fun availability(): BackendAvailability

    /** Clones this backend can see. Never invents entries from stored state alone. */
    suspend fun listClones(): List<CloneRecord>

    suspend fun createClone(request: CloneRequest): CloneResult

    suspend fun launch(clone: CloneRecord): CloneResult

    suspend fun remove(clone: CloneRecord): CloneResult

    /**
     * Allow or deny root for this clone specifically. Returns [CloneResult.Unsupported] with a
     * manual instruction when the installed root manager exposes no stable interface for it,
     * which is the normal case.
     */
    suspend fun setRootAccess(clone: CloneRecord, allowed: Boolean): CloneResult
}
