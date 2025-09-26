package com.realtime.synccontact.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.SharedPrefsManager
import kotlinx.coroutines.*

class GuardianService : AccessibilityService() {

    private lateinit var sharedPrefsManager: SharedPrefsManager
    private val guardianScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isMonitoring = false

    companion object {
        private const val CHECK_INTERVAL = 30000L // 30 seconds
        private const val SERVICE_CHECK_INTERVAL = 60000L // 1 minute
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        sharedPrefsManager = SharedPrefsManager(this)

        // Configure accessibility service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_CLICKED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        serviceInfo = info
        isMonitoring = true

        CrashlyticsLogger.logServiceStatus("GuardianService", "CONNECTED", "Accessibility service active")

        // Start monitoring main service
        startServiceMonitoring()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Process accessibility events to keep service alive
        // We don't actually need to do anything with these events
        // Just receiving them keeps our service active

        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Check if our app is being killed
                checkAppStatus()
            }
            else -> {
                // Ignore other events
            }
        }
    }

    override fun onInterrupt() {
        CrashlyticsLogger.logServiceStatus("GuardianService", "INTERRUPTED", "Accessibility service interrupted")
    }

    private fun startServiceMonitoring() {
        guardianScope.launch {
            while (isMonitoring) {
                try {
                    // Check if main service is running
                    if (!MainSyncService.Companion.isRunning(this@GuardianService)) {
                        if (sharedPrefsManager.isServiceStarted()) {
                            CrashlyticsLogger.logServiceStatus(
                                "GuardianService",
                                "RESTARTING",
                                "Main service not running, attempting restart"
                            )

                            // Restart main service
                            restartMainService()
                        }
                    }

                    // Perform health check
                    performHealthCheck()

                } catch (e: Exception) {
                    CrashlyticsLogger.logCriticalError(
                        "GuardianService",
                        "Monitoring error: ${e.message}",
                        e
                    )
                }

                delay(SERVICE_CHECK_INTERVAL)
            }
        }
    }

    private fun restartMainService() {
        try {
            val intent = Intent(this, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            CrashlyticsLogger.logServiceStatus(
                "GuardianService",
                "RESTART_SUCCESS",
                "Main service restarted successfully"
            )
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "GuardianService",
                "Failed to restart main service",
                e
            )

            // Try alternative restart method
            sendBroadcast(Intent("com.realtimeapksync.RESTART_SERVICE"))
        }
    }

    private fun checkAppStatus() {
        // Check if we're still alive and functioning
        if (!isMonitoring) {
            isMonitoring = true
            startServiceMonitoring()
        }
    }

    private fun performHealthCheck() {
        // Basic health check
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - usedMemory

        if (availableMemory < 30 * 1024 * 1024) { // Less than 30MB
            CrashlyticsLogger.logMemoryWarning(availableMemory, maxMemory)
            System.gc() // Request garbage collection
        }

        // Check if WorkManager is scheduled
        WorkerService.ensureScheduled(this)
    }

    override fun onDestroy() {
        isMonitoring = false
        guardianScope.cancel()

        CrashlyticsLogger.logServiceStatus("GuardianService", "DESTROYED", "Accessibility service destroyed")

        // Try to restart ourselves
        if (sharedPrefsManager.isServiceStarted()) {
            sendBroadcast(Intent("com.realtimeapksync.RESTART_GUARDIAN"))
        }

        super.onDestroy()
    }
}