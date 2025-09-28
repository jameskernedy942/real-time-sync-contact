package com.realtime.synccontact.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.SharedPrefsManager

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HEALTH_CHECK -> performHealthCheck(context)
        }
    }

    private fun performHealthCheck(context: Context) {
        CrashlyticsLogger.logServiceStatus(
            "AlarmReceiver",
            "HEALTH_CHECK",
            "Performing alarm-based health check"
        )

        val sharedPrefsManager = SharedPrefsManager(context)

        if (sharedPrefsManager.isServiceStarted() && !MainSyncService.Companion.isRunning(context)) {
            CrashlyticsLogger.logServiceStatus(
                "AlarmReceiver",
                "RESTARTING_SERVICE",
                "Main service not running, restarting via alarm"
            )

            val serviceIntent = Intent(context, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        // Schedule next alarm
        scheduleAlarm(context)
    }

    companion object {
        private const val ACTION_HEALTH_CHECK = "com.realtimeapksync.realtimeapksync.ALARM_CHECK"
        private const val ALARM_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes (minimum for Doze mode)

        fun scheduleAlarm(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_HEALTH_CHECK
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val triggerTime = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }

                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.DEBUG,
                    "AlarmReceiver",
                    "Next alarm scheduled in ${ALARM_INTERVAL_MS / 1000} seconds"
                )
            } catch (e: Exception) {
                CrashlyticsLogger.logCriticalError(
                    "AlarmReceiver",
                    "Failed to schedule alarm",
                    e
                )
            }
        }

        fun cancelAlarm(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_HEALTH_CHECK
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                alarmManager.cancel(pendingIntent)
            } catch (e: Exception) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.WARNING,
                    "AlarmReceiver",
                    "Failed to cancel alarm: ${e.message}"
                )
            }
        }
    }
}