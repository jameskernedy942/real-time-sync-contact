package com.realtime.synccontact.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.NotificationHelper
import com.realtime.synccontact.utils.SharedPrefsManager
import kotlinx.coroutines.*

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT" -> {
                handleBootCompleted(context)
            }
            "com.realtimeapksync.RESTART_SERVICE" -> {
                restartMainService(context)
            }
            "com.realtimeapksync.RESTART_GUARDIAN" -> {
                restartGuardianService(context)
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        CrashlyticsLogger.logServiceStatus("BootReceiver", "BOOT_COMPLETED", "Device booted, starting services")

        val sharedPrefsManager = SharedPrefsManager(context)
        val (phone1, phone2) = sharedPrefsManager.getPhoneNumbers()

        if (phone1.isNotEmpty()) {  // Only first phone number is required
            // Show notification
            val notificationHelper = NotificationHelper(context)
            val numberDisplay = if (phone2.isNotEmpty()) {
                "Numbers: ${phone1.takeLast(4)}, ${phone2.takeLast(4)}"
            } else {
                "Number: ${phone1.takeLast(4)}"
            }
            notificationHelper.showStatusNotification(
                "Real Time Sync Starting",
                "Contact Sync Starting...\n$numberDisplay",
                ongoing = true
            )

            // Start main service
            restartMainService(context)

            // Check guardian service status (don't open settings)
            GlobalScope.launch {
                delay(5000) // 5 second delay
                checkGuardianServiceStatus(context)
            }

            // Schedule WorkManager
            WorkerService.scheduleHealthCheck(context)

            // Schedule AlarmManager
            AlarmReceiver.scheduleAlarm(context)

            CrashlyticsLogger.logServiceStatus(
                "BootReceiver",
                "SERVICES_STARTED",
                "All services started after boot"
            )
        } else {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "BootReceiver",
                "First phone number not configured, skipping auto-start"
            )
        }
    }

    private fun restartMainService(context: Context) {
        try {
            val serviceIntent = Intent(context, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            CrashlyticsLogger.logServiceStatus(
                "BootReceiver",
                "MAIN_SERVICE_RESTARTED",
                "Main sync service restarted"
            )
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "BootReceiver",
                "Failed to restart main service",
                e
            )
        }
    }

    private fun checkGuardianServiceStatus(context: Context) {
        // Just check and log status, don't open settings
        if (!isAccessibilityServiceEnabled(context)) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "BootReceiver",
                "GuardianService not enabled - service will run without extra protection"
            )
        } else {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "BootReceiver",
                "GuardianService is enabled - extra protection active"
            )
        }
    }

    private fun restartGuardianService(context: Context) {
        // Check if accessibility service is already enabled
        if (!isAccessibilityServiceEnabled(context)) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "BootReceiver",
                "GuardianService (Accessibility) is not enabled. User needs to enable it manually."
            )

            // Only show a notification, don't open settings automatically
            val notificationHelper = NotificationHelper(context)
            notificationHelper.showStatusNotification(
                "Accessibility Service Required",
                "Please enable Real Time Sync accessibility service for better reliability",
                ongoing = false
            )
        } else {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "BootReceiver",
                "GuardianService (Accessibility) is already enabled"
            )
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val service = "${context.packageName}/${GuardianService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service) == true
    }
}