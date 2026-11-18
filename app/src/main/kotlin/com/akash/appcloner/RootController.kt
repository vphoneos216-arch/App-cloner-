package com.akash.appcloner

import com.akash.appcloner.engine.clone.TypedUser
import com.akash.appcloner.engine.nativebridge.NativeBridge
import com.akash.appcloner.engine.root.RootStatus
import com.akash.appcloner.engine.root.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Root state as evidence rather than as a badge.
 *
 * Every field here is the output of a command that actually ran. [idOutput] is the full `id`
 * line, which is what distinguishes a granted request from a denied one, and the user list is
 * read from the platform so a clone profile is only claimed when the platform reports its type.
 */
data class RootState(
    val status: RootStatus? = null,
    val idOutput: String? = null,
    val idExitCode: Int? = null,
    val users: List<TypedUser> = emptyList(),
    val nativeAvailable: Boolean = NativeBridge.isAvailable,
    val nativeAbi: String? = null,
    val nativeUid: Int? = null,
    val checking: Boolean = false,
    val diagnosticsRunning: Boolean = false,
    val report: String? = null,
    val reportPath: String? = null,
    val message: String? = null,
) {
    val cloneProfiles: List<TypedUser> get() = users.filter { it.isCloneProfile }
    val oemDualAppProfiles: List<TypedUser> get() = users.filter { it.isOemDualAppCandidate }
    val isRooted: Boolean get() = status?.available == true
}

class RootController(
    private val graph: EngineGraph,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(RootState())
    val state: StateFlow<RootState> = _state.asStateFlow()

    /**
     * Re-probes from scratch. The cached verdict is dropped first, because the usual reason to
     * press this is that the operator has just granted the permission in their root manager.
     */
    fun refresh() {
        scope.launch {
            _state.update { it.copy(checking = true, message = null) }
            val probe = withContext(Dispatchers.IO) {
                graph.rootProvider.invalidate()
                val status = graph.rootProvider.status()
                val id = if (status.available) {
                    Shell.exec("id", asRoot = true, timeoutSeconds = 10)
                } else {
                    null
                }
                val users = if (status.available) {
                    graph.cloneProfileBackend.users()
                } else {
                    emptyList()
                }
                Triple(status, id, users)
            }
            val (status, id, users) = probe
            _state.update {
                it.copy(
                    status = status,
                    idOutput = id?.stdout?.trim()?.ifBlank { id.stderr.trim() },
                    idExitCode = id?.exitCode,
                    users = users,
                    nativeAvailable = NativeBridge.isAvailable,
                    nativeAbi = NativeBridge.processAbi(),
                    nativeUid = NativeBridge.processUid(),
                    checking = false,
                    message = if (!status.available) {
                        "Root was not granted. " + status.evidence.joinToString("; ")
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * Runs the full capability probe and keeps the report, which is also written to a file so it
     * can be shared. This is the thing to send when cloning fails on a device nobody else can see.
     */
    fun runDiagnostics(packageName: String? = null) {
        scope.launch {
            _state.update { it.copy(diagnosticsRunning = true, message = null) }
            val report = runCatching { graph.cloneDiagnostics.probe(packageName) }
                .getOrElse { "diagnostics threw: ${it.stackTraceToString()}" }
            _state.update {
                it.copy(
                    diagnosticsRunning = false,
                    report = report,
                    reportPath = graph.cloneDiagnostics.reportFile().absolutePath,
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
