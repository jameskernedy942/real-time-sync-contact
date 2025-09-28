package com.realtime.synccontact.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.realtime.synccontact.MainActivity
import com.realtime.synccontact.R

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    companion object {
        const val CHANNEL_ID_SERVICE = "sync_service_channel"
        const val CHANNEL_ID_ERROR = "sync_error_channel"
        const val CHANNEL_ID_STATUS = "sync_status_channel"
        const val CHANNEL_ID_CRITICAL = "sync_critical_channel"
        const val CHANNEL_ID_WARNING = "sync_warning_channel"

        private const val NOTIFICATION_ID_ERROR = 2001
        private const val NOTIFICATION_ID_STATUS = 2002
        private const val NOTIFICATION_ID_CRITICAL = 2003
        private const val NOTIFICATION_ID_WARNING = 2004
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Service channel (silent, persistent)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time sync service notifications"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // Error channel (with sound for critical errors)
            val errorChannel = NotificationChannel(
                CHANNEL_ID_ERROR,
                "Sync Errors",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical error notifications requiring user attention"
                setShowBadge(true)
                enableVibration(true)

                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }

            // Status channel (silent, informational)
            val statusChannel = NotificationChannel(
                CHANNEL_ID_STATUS,
                "Sync Status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Sync status updates"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // Critical channel (urgent alerts)
            val criticalChannel = NotificationChannel(
                CHANNEL_ID_CRITICAL,
                "Critical Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical service alerts requiring immediate attention"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
                lightColor = Color.RED

                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }

            // Warning channel
            val warningChannel = NotificationChannel(
                CHANNEL_ID_WARNING,
                "Service Warnings",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Service warning notifications"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
                lightColor = Color.YELLOW
            }

            notificationManager.createNotificationChannels(
                listOf(serviceChannel, errorChannel, statusChannel, criticalChannel, warningChannel)
            )
        }
    }

    fun showErrorNotification(title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setSmallIcon(R.drawable.ic_notification_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.ERROR,
            "Notification",
            "Error notification shown: $title - $message"
        )
    }

    fun showStatusNotification(title: String, message: String, ongoing: Boolean = false) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_STATUS, notification)
    }

    fun updateServiceNotification(notificationId: Int, title: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }

    fun showCriticalNotification(title: String, message: String, intent: Intent? = null) {
        val pendingIntent = if (intent != null) {
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CRITICAL)
            .setSmallIcon(R.drawable.ic_notification_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(NOTIFICATION_ID_CRITICAL, notification)

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.ERROR,
            "CriticalNotification",
            "$title - $message"
        )
    }

    fun showWarningNotification(title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_WARNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_WARNING, notification)
    }

    fun showNotification(title: String, message: String, intent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_STATUS, notification)
    }
}