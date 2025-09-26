package com.realtime.synccontact.services

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import com.realtime.synccontact.data.LocalRetryQueue
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.SharedPrefsManager
import java.util.concurrent.TimeUnit

class SyncHealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            CrashlyticsLogger.logServiceStatus(
                "WorkManager",
                "HEALTH_CHECK",
                "Performing scheduled health check"
            )

            val sharedPrefsManager = SharedPrefsManager(applicationContext)

            // Check if service should be running
            if (sharedPrefsManager.isServiceStarted()) {
                // Check if main service is running
                if (!MainSyncService.isRunning(applicationContext)) {
                    CrashlyticsLogger.logServiceStatus(
                        "WorkManager",
                        "SERVICE_NOT_RUNNING",
                        "Main service not detected, restarting"
                    )

                    // Restart main service
                    val intent = Intent(applicationContext, MainSyncService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(intent)
                    } else {
                        applicationContext.startService(intent)
                    }
                }

                // Check memory status
                checkMemoryHealth()

                // Clean up retry queue
                cleanupRetryQueue()
            }

            Result.success()
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "WorkManager",
                "Health check failed: ${e.message}",
                e
            )
            Result.retry()
        }
    }

    private fun checkMemoryHealth() {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - usedMemory

        if (availableMemory < 50 * 1024 * 1024) { // Less than 50MB
            CrashlyticsLogger.logMemoryWarning(availableMemory, maxMemory)
            System.gc()
        }
    }

    private fun cleanupRetryQueue() {
        try {
            val retryQueue = LocalRetryQueue(applicationContext)
            retryQueue.cleanup()
        } catch (e: Exception) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "WorkManager",
                "Failed to cleanup retry queue: ${e.message}"
            )
        }
    }
}

class ImmediateRestartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            CrashlyticsLogger.logServiceStatus(
                "WorkManager",
                "IMMEDIATE_RESTART",
                "Performing immediate service restart"
            )

            val intent = Intent(applicationContext, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }

            Result.success()
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "WorkManager",
                "Immediate restart failed: ${e.message}",
                e
            )
            Result.failure()
        }
    }
}

object WorkerService {

    private const val HEALTH_CHECK_TAG = "sync_health_check"
    private const val IMMEDIATE_RESTART_TAG = "immediate_restart"
    private const val HEALTH_CHECK_INTERVAL_MINUTES = 15L

    fun scheduleHealthCheck(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .build()

            val healthCheckRequest = PeriodicWorkRequestBuilder<SyncHealthCheckWorker>(
                HEALTH_CHECK_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(HEALTH_CHECK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                HEALTH_CHECK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                healthCheckRequest
            )

            CrashlyticsLogger.logServiceStatus(
                "WorkManager",
                "SCHEDULED",
                "Health check scheduled every $HEALTH_CHECK_INTERVAL_MINUTES minutes"
            )
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "WorkManager",
                "Failed to schedule health check",
                e
            )
        }
    }

    fun scheduleImmediateRestart(context: Context) {
        try {
            val immediateRequest = OneTimeWorkRequestBuilder<ImmediateRestartWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(IMMEDIATE_RESTART_TAG)
                .build()

            WorkManager.getInstance(context).enqueue(immediateRequest)

            CrashlyticsLogger.logServiceStatus(
                "WorkManager",
                "IMMEDIATE_RESTART_SCHEDULED",
                "Immediate restart work scheduled"
            )
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "WorkManager",
                "Failed to schedule immediate restart",
                e
            )
        }
    }

    fun cancelAllWork(context: Context) {
        try {
            WorkManager.getInstance(context).cancelAllWorkByTag(HEALTH_CHECK_TAG)
            WorkManager.getInstance(context).cancelAllWorkByTag(IMMEDIATE_RESTART_TAG)
        } catch (e: Exception) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "WorkManager",
                "Failed to cancel work: ${e.message}"
            )
        }
    }

    fun ensureScheduled(context: Context) {
        try {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosByTag(HEALTH_CHECK_TAG).get()

            if (workInfos.isEmpty() || workInfos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                scheduleHealthCheck(context)
            }
        } catch (e: Exception) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "WorkManager",
                "Failed to ensure scheduled: ${e.message}"
            )
        }
    }
}