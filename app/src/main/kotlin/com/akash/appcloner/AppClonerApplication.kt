package com.akash.appcloner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AppClonerApplication : Application() {

    lateinit var graph: EngineGraph
        private set
    lateinit var dashboard: DashboardController
        private set
    lateinit var clones: ClonesController
        private set
    lateinit var compat: CompatController
        private set

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        graph = EngineGraph(this)
        dashboard = DashboardController(graph, appScope)
        clones = ClonesController(graph, appScope)
        compat = CompatController(graph, appScope)
        createMonitorChannel()
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }

    private fun createMonitorChannel() {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?: return
        val channel = NotificationChannel(
            MonitorService.CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.monitor_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
