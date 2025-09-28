package com.realtime.synccontact.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserHandle
import com.realtime.synccontact.services.MainSyncService
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.NotificationHelper
import com.realtime.synccontact.utils.SharedPrefsManager

/**
 * Enhanced Device Admin that prevents app from being disabled
 * and provides additional protection mechanisms
 */
class EnhancedDeviceAdmin : DeviceAdminReceiver() {

    companion object {
        /**
         * Check if Device Admin is active
         */
        fun isAdminActive(context: Context): Boolean {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, EnhancedDeviceAdmin::class.java)
            return devicePolicyManager.isAdminActive(componentName)
        }

        /**
         * Request Device Admin activation
         */
        fun requestActivation(context: Context) {
            if (!isAdminActive(context)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    val componentName = ComponentName(context, EnhancedDeviceAdmin::class.java)
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        """
                        Device Admin Required for 24/7 Sync:

                        ✓ Prevents accidental force-stop
                        ✓ Protects service from being killed
                        ✓ Ensures continuous contact sync
                        ✓ Provides system-level persistence

                        Without this, Android may kill the sync service.
                        """.trimIndent()
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        }

        /**
         * Lock device if too many failed attempts (optional security feature)
         */
        fun lockDeviceIfNeeded(context: Context, reason: String) {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, EnhancedDeviceAdmin::class.java)

            if (devicePolicyManager.isAdminActive(componentName)) {
                try {
                    devicePolicyManager.lockNow()
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.WARNING,
                        "DeviceAdmin",
                        "Device locked: $reason"
                    )
                } catch (e: Exception) {
                    CrashlyticsLogger.logCriticalError("DeviceAdmin", "Failed to lock device", e)
                }
            }
        }

        /**
         * Set password quality requirements (for corporate use)
         */
        fun enforcePasswordPolicy(context: Context) {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, EnhancedDeviceAdmin::class.java)

            if (devicePolicyManager.isAdminActive(componentName)) {
                try {
                    // Require at least PIN
                    devicePolicyManager.setPasswordQuality(
                        componentName,
                        DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                    )

                    // Minimum 4 digits
                    devicePolicyManager.setPasswordMinimumLength(componentName, 4)

                    // Lock after 5 minutes of inactivity
                    devicePolicyManager.setMaximumTimeToLock(componentName, 5 * 60 * 1000L)

                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.INFO,
                        "DeviceAdmin",
                        "Password policy enforced"
                    )
                } catch (e: Exception) {
                    CrashlyticsLogger.logCriticalError("DeviceAdmin", "Failed to set password policy", e)
                }
            }
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.INFO,
            "DeviceAdmin",
            "Device Admin enabled successfully"
        )

        // Start service if not running
        if (!MainSyncService.isRunning(context)) {
            val serviceIntent = Intent(context, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        // Notify user
        NotificationHelper(context).showStatusNotification(
            "Device Admin Activated",
            "Your sync service is now protected from being force-stopped",
            false
        )

        // Save status
        SharedPrefsManager(context).setServiceStatus("device_admin_active")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.WARNING,
            "DeviceAdmin",
            "Device Admin disabled - service vulnerable!"
        )

        // Warn user
        NotificationHelper(context).showCriticalNotification(
            "⚠️ PROTECTION DISABLED",
            "Device Admin disabled! Sync service may be killed by system. Tap to re-enable."
        )

        // Update status
        SharedPrefsManager(context).setServiceStatus("device_admin_disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        // Show warning when user tries to disable
        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.WARNING,
            "DeviceAdmin",
            "User attempting to disable Device Admin"
        )

        // Try to restart service one more time
        val serviceIntent = Intent(context, MainSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
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

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)

        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, EnhancedDeviceAdmin::class.java)

        val failedAttempts = devicePolicyManager.getCurrentFailedPasswordAttempts()

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.WARNING,
            "DeviceAdmin",
            "Failed password attempt #$failedAttempts"
        )

        // Lock device after 10 failed attempts
        if (failedAttempts >= 10) {
            lockDeviceIfNeeded(context, "Too many failed password attempts")
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)

        // Password correct, ensure service is running
        if (!MainSyncService.isRunning(context)) {
            val serviceIntent = Intent(context, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)

        // Work profile created, start service there too
        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.INFO,
            "DeviceAdmin",
            "Work profile provisioned, starting service"
        )

        val serviceIntent = Intent(context, MainSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)

        // Device in kiosk mode, ensure service runs
        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.INFO,
            "DeviceAdmin",
            "Lock task mode entered, ensuring service runs"
        )

        if (!MainSyncService.isRunning(context)) {
            val serviceIntent = Intent(context, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    override fun onSystemUpdatePending(context: Context, intent: Intent, receivedTime: Long) {
        super.onSystemUpdatePending(context, intent, receivedTime)

        // System update pending, warn user
        NotificationHelper(context).showWarningNotification(
            "System Update Pending",
            "Sync service will restart after update"
        )

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.INFO,
            "DeviceAdmin",
            "System update pending at $receivedTime"
        )
    }
}