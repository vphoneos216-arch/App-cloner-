package com.akash.appcloner

import com.akash.appcloner.engine.perf.MonitorTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the sampling loop and the rolling history.
 *
 * History is capped, and samples are kept exactly as the monitor produced them: a tick where a
 * metric was unavailable stays unavailable in the history rather than inheriting the previous
 * value, so a gap in a chart is a real gap.
 */
class DashboardController(
    private val graph: EngineGraph,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var samplingJob: Job? = null

    fun selectTarget(target: MonitorTarget?) {
        _state.update { it.copy(target = target, history = emptyList(), latest = null) }
    }

    fun start(intervalMillis: Long = 1_000L) {
        val target = _state.value.target ?: run {
            _state.update { it.copy(message = "Select a game to monitor first.") }
            return
        }
        if (samplingJob?.isActive == true) return

        _state.update { it.copy(sampling = true, message = null) }
        samplingJob = scope.launch {
            graph.performanceMonitor.snapshots(target, intervalMillis).collect { snapshot ->
                _state.update { current ->
                    val history = (current.history + snapshot)
                        .takeLast(DashboardState.HISTORY_LIMIT)
                    current.copy(latest = snapshot, history = history)
                }
            }
        }
        refreshDiagnostics()
    }

    fun stop() {
        samplingJob?.cancel()
        samplingJob = null
        _state.update { it.copy(sampling = false) }
    }

    /** Pulls the slower-moving facts: platform memory pressure and recorded process deaths. */
    fun refreshDiagnostics() {
        val target = _state.value.target ?: return
        scope.launch {
            val pressure = graph.memoryPressureWatcher.current()
            val exits = graph.crashCollector.exitReasons(target.packageName)
            _state.update { it.copy(memoryPressure = pressure, exits = exits) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
