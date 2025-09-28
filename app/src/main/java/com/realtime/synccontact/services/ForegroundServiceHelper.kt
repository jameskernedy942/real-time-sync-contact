package com.realtime.synccontact.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.realtime.synccontact.MainActivity
import com.realtime.synccontact.R

/**
 * Helper class to ensure foreground service stays active
 * with proper notification management
 */
class ForegroundServiceHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "realtime_sync_persistent"
        private const val NOTIFICATION_ID = 1001
        private const val HIGH_PRIORITY_CHANNEL_ID = "realtime_sync_high_priority"
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Main persistent channel
            val persistentChannel = NotificationChannel(
                CHANNEL_ID,
                "Persistent Sync Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows persistent notification for 24/7 contact sync"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // High priority channel for critical alerts
            val highPriorityChannel = NotificationChannel(
                HIGH_PRIORITY_CHANNEL_ID,
                "Critical Service Alerts",
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "Critical alerts when service needs attention"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            notificationManager.createNotificationChannel(persistentChannel)
            notificationManager.createNotificationChannel(highPriorityChannel)
        }
    }

    fun createPersistentNotification(
        title: String = "Real Time Sync Active",
        content: String,
        showProgress: Boolean = false
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (showProgress) {
            builder.setProgress(100, 0, true)
        }

        // Add actions for quick control
        val stopIntent = Intent(context, MainSyncService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restartIntent = Intent(context, MainSyncService::class.java).apply {
            action = "RESTART_SERVICE"
        }
        val restartPendingIntent = PendingIntent.getService(
            context,
            2,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        builder.addAction(
            android.R.drawable.ic_menu_rotate,
            "Restart",
            restartPendingIntent
        )

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            stopPendingIntent
        )

        return builder.build()
    }

    fun updateNotification(notification: Notification) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun showCriticalAlert(title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, HIGH_PRIORITY_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}