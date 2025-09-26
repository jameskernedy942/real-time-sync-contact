package com.realtime.synccontact.utils

import com.google.firebase.crashlytics.FirebaseCrashlytics

object  CrashlyticsLogger {

    private val crashlytics = FirebaseCrashlytics.getInstance()

    enum class LogLevel {
        DEBUG, INFO, WARNING, ERROR, CRITICAL
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val logMessage = "[${level.name}] [$tag] $message"
        crashlytics.log(logMessage)

        when (level) {
            LogLevel.ERROR, LogLevel.CRITICAL -> {
                crashlytics.recordException(Exception(logMessage))
            }
            else -> {}
        }
    }

    fun logConnectionEvent(event: String, queue: String, details: String = "") {
        val message = "CONNECTION: $event - Queue: $queue${if (details.isNotEmpty()) " - $details" else ""}"
        crashlytics.log(message)
    }

    fun logMessageProcessing(queue: String, syncId: Long, status: String, details: String = "") {
        val message = "MESSAGE: Queue: $queue - SyncId: $syncId - Status: $status${if (details.isNotEmpty()) " - $details" else ""}"
        crashlytics.log(message)
    }

    fun logContactOperation(operation: String, phoneNumber: String, success: Boolean, details: String = "") {
        val message = "CONTACT: $operation - Phone: $phoneNumber - Success: $success${if (details.isNotEmpty()) " - $details" else ""}"
        crashlytics.log(message)
        if (!success) {
            crashlytics.recordException(Exception(message))
        }
    }

    fun logPermissionIssue(permission: String, granted: Boolean) {
        val message = "PERMISSION: $permission - Granted: $granted"
        crashlytics.log(message)
        if (!granted) {
            crashlytics.recordException(Exception("Permission denied: $permission"))
        }
    }

    fun logServiceStatus(service: String, status: String, details: String = "") {
        val message = "SERVICE: $service - Status: $status${if (details.isNotEmpty()) " - $details" else ""}"
        crashlytics.log(message)
    }

    fun logNetworkChange(networkType: String, isConnected: Boolean) {
        val message = "NETWORK: Type: $networkType - Connected: $isConnected"
        crashlytics.log(message)
    }

    fun logSyncSuccess(syncId: Long, queueName: String, phoneNumber: String) {
        val message = "SYNC_SUCCESS: SyncId: $syncId - Queue: $queueName - DeviceId: $phoneNumber"
        crashlytics.log(message)
    }

    fun logCriticalError(component: String, error: String, throwable: Throwable? = null) {
        val message = "CRITICAL_ERROR: Component: $component - Error: $error"
        crashlytics.log(message)

        if (throwable != null) {
            crashlytics.recordException(throwable)
        } else {
            crashlytics.recordException(Exception(message))
        }

        // Set custom keys for better filtering in Crashlytics dashboard
        crashlytics.setCustomKey("last_critical_component", component)
        crashlytics.setCustomKey("last_critical_error", error)
        crashlytics.setCustomKey("last_critical_time", System.currentTimeMillis())
    }

    fun logSLAViolation(syncId: Long, elapsedSeconds: Long) {
        val message = "SLA_VIOLATION: SyncId: $syncId - Elapsed: ${elapsedSeconds}s (> 20s SLA)"
        crashlytics.log(message)
        crashlytics.recordException(Exception(message))

        crashlytics.setCustomKey("last_sla_violation_id", syncId)
        crashlytics.setCustomKey("last_sla_violation_seconds", elapsedSeconds)
        crashlytics.setCustomKey("last_sla_violation_time", System.currentTimeMillis())
    }

    fun setUserId(phoneNumber: String) {
        crashlytics.setUserId(phoneNumber)
    }

    fun setCustomKeys(keys: Map<String, Any>) {
        keys.forEach { (key, value) ->
            when (value) {
                is String -> crashlytics.setCustomKey(key, value)
                is Boolean -> crashlytics.setCustomKey(key, value)
                is Int -> crashlytics.setCustomKey(key, value)
                is Long -> crashlytics.setCustomKey(key, value)
                is Float -> crashlytics.setCustomKey(key, value)
                is Double -> crashlytics.setCustomKey(key, value)
                else -> crashlytics.setCustomKey(key, value.toString())
            }
        }
    }

    fun logAppStart(version: String, phone1: String, phone2: String) {
        crashlytics.log("APP_START: Version: $version - Phone1: $phone1 - Phone2: $phone2")
        setCustomKeys(mapOf(
            "app_version" to version,
            "phone_1" to phone1,
            "phone_2" to phone2,
            "start_time" to System.currentTimeMillis()
        ))
    }

    fun logMemoryWarning(availableMemory: Long, totalMemory: Long) {
        val message = "MEMORY_WARNING: Available: ${availableMemory / 1024 / 1024}MB - Total: ${totalMemory / 1024 / 1024}MB"
        crashlytics.log(message)
        if (availableMemory < 50 * 1024 * 1024) { // Less than 50MB
            crashlytics.recordException(Exception("Critical memory warning: $message"))
        }
    }

    fun logRetryAttempt(component: String, attempt: Int, maxAttempts: Int) {
        val message = "RETRY: Component: $component - Attempt: $attempt/$maxAttempts"
        crashlytics.log(message)
        if (attempt >= maxAttempts) {
            crashlytics.recordException(Exception("Max retries exceeded: $message"))
        }
    }
}