package com.realtime.synccontact.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.realtime.synccontact.services.MainSyncService
import com.realtime.synccontact.utils.CrashlyticsLogger

class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        CrashlyticsLogger.logServiceStatus("DeviceAdmin", "ENABLED", "Device admin activated")
        Toast.makeText(context, "Device Admin enabled - App protected from force-stop", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        CrashlyticsLogger.logServiceStatus("DeviceAdmin", "DISABLE_REQUESTED", "User attempting to disable")
        return "Warning: Disabling Device Admin will stop Real Time Sync from working properly!"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        CrashlyticsLogger.logCriticalError("DeviceAdmin", "DISABLED - Service protection removed", null)
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