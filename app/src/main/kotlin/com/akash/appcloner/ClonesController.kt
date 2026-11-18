package com.akash.appcloner

import com.akash.appcloner.engine.clone.CloneGoal
import com.akash.appcloner.engine.clone.CloneRecord
import com.akash.appcloner.engine.clone.CloneRequest
import com.akash.appcloner.engine.clone.CloneResult
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
