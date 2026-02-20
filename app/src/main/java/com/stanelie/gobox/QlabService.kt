package com.stanelie.gobox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat

class QlabService : Service() {

    inner class QlabBinder : Binder() {
        fun getManager(): QlabManager = qlabManager
    }

    private val binder = QlabBinder()
    lateinit var qlabManager: QlabManager

    companion object {
        const val CHANNEL_ID = "gobox_connection"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        qlabManager = QlabManager()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("Gobox active"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        qlabManager.disconnect()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gobox")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "QLab Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the QLab connection alive in the background"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}