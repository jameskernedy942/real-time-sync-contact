package com.realtime.synccontact.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.SharedPrefsManager

/**
 * JobScheduler service that ensures MainSyncService is always running
 * Checks every 15 minutes and restarts if needed
 */
class ServiceResurrectionJob : JobService() {

    companion object {
        private const val JOB_ID = 9001
        private const val INTERVAL_MILLIS = 15 * 60 * 1000L // 15 minutes
        private const val FLEX_MILLIS = 5 * 60 * 1000L // 5 minutes flex

        /**
         * Schedule the resurrection job
         * This job will run every 15 minutes to check if service is alive
         */
        fun schedule(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

            // Check if already scheduled
            if (jobScheduler.getPendingJob(JOB_ID) != null) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.DEBUG,
                    "ServiceResurrectionJob",
                    "Job already scheduled"
                )
                return
            }

            val componentName = ComponentName(context, ServiceResurrectionJob::class.java)

            val jobInfo = JobInfo.Builder(JOB_ID, componentName).apply {
                // Job will run every 15 minutes
                setPeriodic(INTERVAL_MILLIS, FLEX_MILLIS)

                // Persist across reboots
                setPersisted(true)

                // Run even if device is idle
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setRequiresBatteryNotLow(false)
                    setRequiresStorageNotLow(false)
                }

                // Network not required
                setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)

                // Run ASAP when conditions are met
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setPrefetch(false)
                }

                // Backoff policy for failures
                setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            }.build()

            val result = jobScheduler.schedule(jobInfo)

            if (result == JobScheduler.RESULT_SUCCESS) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "ServiceResurrectionJob",
                    "Resurrection job scheduled successfully"
                )
            } else {
                CrashlyticsLogger.logCriticalError(
                    "ServiceResurrectionJob",
                    "Failed to schedule resurrection job",
                    null
                )
            }
        }

        /**
         * Cancel the resurrection job
         */
        fun cancel(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancel(JOB_ID)

            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "ServiceResurrectionJob",
                "Resurrection job cancelled"
            )
        }

        /**
         * Force run the job immediately (for testing)
         */
        fun forceRun(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

            val componentName = ComponentName(context, ServiceResurrectionJob::class.java)

            val jobInfo = JobInfo.Builder(JOB_ID + 1000, componentName).apply {
                // One-time job
                setMinimumLatency(0L)
                setOverrideDeadline(1000L)

                // Network not required
                setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            }.build()

            jobScheduler.schedule(jobInfo)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.INFO,
            "ServiceResurrectionJob",
            "Resurrection job started"
        )

        // Check if service should be running
        val sharedPrefsManager = SharedPrefsManager(this)
        if (!sharedPrefsManager.isServiceStarted()) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.DEBUG,
                "ServiceResurrectionJob",
                "Service not meant to be running, skipping resurrection"
            )
            return false // Job complete
        }

        // Check if service is actually running
        if (!MainSyncService.isRunning(this)) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "ServiceResurrectionJob",
                "Service is dead! Resurrecting..."
            )

            // Resurrect the service
            try {
                val intent = Intent(this, MainSyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                // Increment resurrection count
                sharedPrefsManager.incrementServiceDeathCount()

                CrashlyticsLogger.logServiceStatus(
                    "ServiceResurrectionJob",
                    "RESURRECTED",
                    "Service resurrected by JobScheduler"
                )
            } catch (e: Exception) {
                CrashlyticsLogger.logCriticalError(
                    "ServiceResurrectionJob",
                    "Failed to resurrect service",
                    e
                )
            }
        } else {
            // Service is running, check heartbeat
            val lastHeartbeat = sharedPrefsManager.getLastHeartbeat()
            val timeSinceHeartbeat = System.currentTimeMillis() - lastHeartbeat

            if (timeSinceHeartbeat > 5 * 60 * 1000) { // 5 minutes
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.WARNING,
                    "ServiceResurrectionJob",
                    "Service unresponsive (${timeSinceHeartbeat / 1000}s since heartbeat), restarting..."
                )

                // Stop and restart
                stopService(Intent(this, MainSyncService::class.java))

                // Use Handler instead of Thread.sleep to avoid blocking
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val intent = Intent(this, MainSyncService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }, 1000)
            } else {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.DEBUG,
                    "ServiceResurrectionJob",
                    "Service is healthy (heartbeat ${timeSinceHeartbeat / 1000}s ago)"
                )
            }
        }

        // Reschedule for next check
        schedule(this)

        return false // Job complete
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.INFO,
            "ServiceResurrectionJob",
            "Resurrection job stopped by system"
        )

        return true // Retry the job
    }
}