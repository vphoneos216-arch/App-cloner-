package com.akash.appcloner

import com.akash.appcloner.engine.clone.CloneGoal
import com.akash.appcloner.engine.clone.CloneRecord
import com.akash.appcloner.engine.clone.CloneRequest
import com.akash.appcloner.engine.clone.CloneResult
import com.akash.appcloner.engine.clone.BackupResult
import com.akash.appcloner.engine.compat.GameProfileRegistry
import com.akash.appcloner.engine.compat.ResolvedGame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drives clone listing and creation, and surfaces each backend's own verdict about itself. */
class ClonesController(
    private val graph: EngineGraph,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(ClonesState())
    val state: StateFlow<ClonesState> = _state.asStateFlow()

    fun refresh(goal: CloneGoal = CloneGoal.CONCURRENT_WITH_ORIGINAL) {
        scope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val installed = withContext(Dispatchers.IO) {
                graph.apkInspector.installedLaunchableApps()
            }
            // Known titles first, then everything else, so the games are not buried.
            val resolved = GameProfileRegistry.resolveInstalled(installed)
                .sortedWith(
                    compareByDescending<ResolvedGame> { it.profile != null }
                        .thenBy { it.label.lowercase() },
                )
            val clones = graph.cloneEngine.clones()
            val options = graph.cloneEngine.options(goal)
            _state.update {
                it.copy(
                    installedGames = resolved,
                    clones = clones,
                    backendOptions = options,
                    busy = false,
                )
            }
        }
    }

    fun create(packageName: String, label: String, goal: CloneGoal, forcedAbi: String? = null) {
        scope.launch {
            _state.update { it.copy(busy = true, message = null, log = emptyList()) }
            val result = graph.cloneEngine.create(
                CloneRequest(
                    packageName = packageName,
                    label = label,
                    forcedAbi = forcedAbi,
                ),
                goal,
            )
            applyResult(result, "Clone of $label created.")
            refresh(goal)
        }
    }

    fun launch(clone: CloneRecord) {
        scope.launch {
            _state.update { it.copy(busy = true, message = null) }
            applyResult(graph.cloneEngine.launch(clone), "Launched ${clone.label}.")
        }
    }

    fun remove(clone: CloneRecord) {
        scope.launch {
            _state.update { it.copy(busy = true, message = null) }
            applyResult(graph.cloneEngine.remove(clone), "Removed ${clone.label}.")
            refresh()
        }
    }

    /** Force-stops the clone in its own user. The original in user 0 is not touched. */
    fun stop(clone: CloneRecord) {
        val userId = clone.androidUserId ?: run {
            _state.update { it.copy(message = "That clone has no Android user id.") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val outcome = graph.processManager.forceStop(clone.packageName, userId)
            _state.update {
                it.copy(busy = false, message = outcome.message, log = outcome.log)
            }
            refresh()
        }
    }

    /** Archives the clone's private data. Refuses on user 0 inside CloneBackup. */
    fun backup(clone: CloneRecord) {
        val userId = clone.androidUserId ?: run {
            _state.update { it.copy(message = "That clone has no Android user id.") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, message = null) }
            when (val result = graph.cloneBackup.backup(clone.packageName, userId)) {
                is BackupResult.Success -> _state.update {
                    it.copy(
                        busy = false,
                        log = result.log,
                        message = "Backed up to ${result.file.path} " +
                            "(${result.file.sizeBytes / (1024 * 1024)} MB).",
                    )
                }

                is BackupResult.Failure -> _state.update {
                    it.copy(busy = false, log = result.log, message = result.message)
                }
            }
        }
    }

    fun requestRootAccess(clone: CloneRecord, allowed: Boolean) {
        scope.launch {
            applyResult(
                graph.cloneEngine.setRootAccess(clone, allowed),
                "Root policy updated for ${clone.label}.",
            )
        }
    }

    private fun applyResult(result: CloneResult, successMessage: String) {
        when (result) {
            is CloneResult.Success -> _state.update {
                it.copy(busy = false, message = successMessage, log = result.log)
            }

            is CloneResult.Failure -> _state.update {
                it.copy(
                    busy = false,
                    message = listOfNotNull(result.message, result.nextStep).joinToString(" "),
                    log = result.log,
                )
            }

            is CloneResult.Unsupported -> _state.update {
                it.copy(
                    busy = false,
                    message = listOfNotNull(result.message, result.manualInstruction)
                        .joinToString(" "),
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
