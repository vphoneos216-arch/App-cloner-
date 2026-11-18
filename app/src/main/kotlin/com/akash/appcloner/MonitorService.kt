package com.akash.appcloner

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Keeps the process foregrounded while a game is being sampled.
 *
 * The sampling itself lives in [DashboardController], which is application-scoped; this service
 * exists so the system does not freeze the process the moment the game takes the foreground,
 * which would make the collected series full of holes.
 */
class MonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "game"
        startForeground(NOTIFICATION_ID, buildNotification(label))
        return Service.START_STICKY
    }

    private fun buildNotification(label: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.monitor_notification_title, label))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "game_monitoring"
        const val NOTIFICATION_ID = 42
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String) {
            val intent = Intent(context, MonitorService::class.java)
                .putExtra(EXTRA_LABEL, label)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
