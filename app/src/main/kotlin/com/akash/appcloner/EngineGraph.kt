package com.akash.appcloner

import android.content.Context
import com.akash.appcloner.engine.clone.CloneEngine
import com.akash.appcloner.engine.clone.CloneBackup
import com.akash.appcloner.engine.clone.CloneProfileBackend
import com.akash.appcloner.engine.clone.CloneStore
import com.akash.appcloner.engine.clone.RootDataIsolationBackend
import com.akash.appcloner.engine.clone.SecondaryUserBackend
import com.akash.appcloner.engine.compat.ApkInspector
import com.akash.appcloner.engine.compat.DeviceProfile
import com.akash.appcloner.engine.compat.DeviceProfileReader
import com.akash.appcloner.engine.perf.PerformanceMonitor
import com.akash.appcloner.engine.process.ProcessManager
import com.akash.appcloner.engine.root.RootProvider
import com.akash.appcloner.engine.stability.CrashCollector
import com.akash.appcloner.engine.stability.MemoryPressureWatcher
import com.akash.appcloner.engine.stability.NativeLoadProbe

/**
 * Manual dependency graph. One root provider instance is shared so root is probed once per
 * process rather than once per collaborator - each probe can raise a permission prompt.
 */
class EngineGraph(context: Context) {

    val rootProvider = RootProvider()

    val apkInspector = ApkInspector(context, rootProvider)
    val deviceProfile: DeviceProfile by lazy { DeviceProfileReader(context).read() }

    val processManager = ProcessManager(rootProvider)
    val cloneBackup = CloneBackup(context, rootProvider, processManager)

    val performanceMonitor = PerformanceMonitor(context, rootProvider)
    val crashCollector = CrashCollector(context, rootProvider)
    val memoryPressureWatcher = MemoryPressureWatcher(context)
    val nativeLoadProbe = NativeLoadProbe()

    val cloneProfileBackend = CloneProfileBackend(context, rootProvider, processManager)

    val cloneEngine = CloneEngine(
        backends = listOf(
            cloneProfileBackend,
            SecondaryUserBackend(context, rootProvider),
            RootDataIsolationBackend(rootProvider),
        ),
        store = CloneStore(context),
    )
}
