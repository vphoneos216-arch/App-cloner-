package com.akash.appcloner

import com.akash.appcloner.engine.clone.BackendOption
import com.akash.appcloner.engine.clone.CloneRecord
import com.akash.appcloner.engine.compat.ApkInspection
import com.akash.appcloner.engine.compat.CompatibilityReport
import com.akash.appcloner.engine.compat.ResolvedGame
import com.akash.appcloner.engine.perf.Metric
import com.akash.appcloner.engine.perf.MonitorTarget
import com.akash.appcloner.engine.perf.PerfSnapshot
import com.akash.appcloner.engine.stability.MemoryPressure
import com.akash.appcloner.engine.stability.NativeLoadReport
import com.akash.appcloner.engine.stability.ProcessExitRecord

/** Rolling monitoring state, owned by the application and shared by the dashboard. */
data class DashboardState(
    val target: MonitorTarget? = null,
    val sampling: Boolean = false,
    val latest: PerfSnapshot? = null,
    val history: List<PerfSnapshot> = emptyList(),
    val memoryPressure: Metric<MemoryPressure>? = null,
    val exits: Metric<List<ProcessExitRecord>>? = null,
    val message: String? = null,
) {
    companion object {
        /** Enough for a few minutes at one-second sampling without unbounded growth. */
        const val HISTORY_LIMIT = 300
    }
}

/** State for the clone list and creation flow. */
data class ClonesState(
    val installedGames: List<ResolvedGame> = emptyList(),
    val clones: List<CloneRecord> = emptyList(),
    val backendOptions: List<BackendOption> = emptyList(),
    val busy: Boolean = false,
    val log: List<String> = emptyList(),
    val message: String? = null,
)

/** State for the compatibility screen. */
data class CompatState(
    val inspection: ApkInspection? = null,
    val report: CompatibilityReport? = null,
    val nativeReport: NativeLoadReport? = null,
    val inspecting: Boolean = false,
    val message: String? = null,
)
