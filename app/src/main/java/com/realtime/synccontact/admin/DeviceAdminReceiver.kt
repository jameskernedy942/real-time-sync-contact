package com.realtime.synccontact.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.realtime.synccontact.MainActivity
import com.realtime.synccontact.services.MainSyncService
import com.realtime.synccontact.services.ServiceResurrectionJob
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.NotificationHelper
import com.realtime.synccontact.utils.SharedPrefsManager

class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        CrashlyticsLogger.logServiceStatus("DeviceAdmin", "ENABLED", "Device admin activated")

        // Start service if not running
        if (!MainSyncService.isRunning(context)) {
            val serviceIntent = Intent(context, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        // Schedule JobScheduler for extra protection
        ServiceResurrectionJob.schedule(context)

        // Notify user
        NotificationHelper(context).showStatusNotification(
            "✅ Device Admin Activated",
            "Your sync service is now protected from being force-stopped",
            false
        )

        // Save status
        SharedPrefsManager(context).setServiceStatus("device_admin_active")

        Toast.makeText(context, "Device Admin enabled - App protected from force-stop", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        CrashlyticsLogger.logServiceStatus("DeviceAdmin", "DISABLE_REQUESTED", "User attempting to disable")

        // Try to restart service one more time
        if (!MainSyncService.isRunning(context)) {
            val serviceIntent = Intent(context, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        return """
⚠️ WARNING: Disabling Device Admin will:

• Allow the sync service to be force-stopped
• Reduce battery life (service restarts consume more power)
• Cause gaps in contact syncing
• Remove protection against system kills

Are you sure you want to disable protection?
        """.trimIndent()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        CrashlyticsLogger.logCriticalError("DeviceAdmin", "DISABLED - Service protection removed", null)

        // Warn user with critical notification
        NotificationHelper(context).showCriticalNotification(
            "⚠️ PROTECTION DISABLED",
            "Device Admin disabled! Sync service may be killed. Tap to re-enable.",
            Intent(context, MainActivity::class.java)
        )

        // Update status
        SharedPrefsManager(context).setServiceStatus("device_admin_disabled")

        Toast.makeText(context, "Device Admin disabled - App may stop working", Toast.LENGTH_LONG).show()
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        CrashlyticsLogger.log(CrashlyticsLogger.LogLevel.INFO, "DeviceAdmin", "Password changed")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        CrashlyticsLogger.log(CrashlyticsLogger.LogLevel.WARNING, "DeviceAdmin", "Password attempt failed")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)

        // Ensure service is running after unlock
        if (!MainSyncService.isRunning(context)) {
            val serviceIntent = Intent(context, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}