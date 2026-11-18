package com.akash.appcloner

import com.akash.appcloner.engine.compat.AbiResolution
import com.akash.appcloner.engine.compat.CompatibilityEvaluator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs inspection and the compatibility rules for one package. */
class CompatController(
    private val graph: EngineGraph,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(CompatState())
    val state: StateFlow<CompatState> = _state.asStateFlow()

    fun inspect(packageName: String) {
        scope.launch {
            _state.update { it.copy(inspecting = true, message = null) }
            val inspection = withContext(Dispatchers.IO) {
                graph.apkInspector.inspect(packageName)
            }
            if (inspection == null) {
                _state.update {
                    it.copy(
                        inspecting = false,
                        inspection = null,
                        report = null,
                        nativeReport = null,
                        message = "$packageName is not installed for this user.",
                    )
                }
                return@launch
            }
            val report = CompatibilityEvaluator.evaluate(inspection, graph.deviceProfile)
            val selectedAbi = (report.abi as? AbiResolution.Resolved)?.abi
            val nativeReport = withContext(Dispatchers.IO) {
                graph.nativeLoadProbe.probe(inspection, selectedAbi)
            }
            _state.update {
                it.copy(
                    inspecting = false,
                    inspection = inspection,
                    report = report,
                    nativeReport = nativeReport,
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
