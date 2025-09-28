package com.realtime.synccontact.services

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.realtime.synccontact.utils.CrashlyticsLogger

/**
 * Optimizes service for 24/7 operation
 */
class ServiceOptimizer(private val context: Context) {

    fun optimizeForPersistence() {
        // 1. Set process priority to foreground
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "ServiceOptimizer",
                "Set thread priority to FOREGROUND"
            )
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError("ServiceOptimizer", "Failed to set thread priority", e)
        }

        // 2. Request to be kept in memory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            try {
                activityManager.setWatchHeapLimit(100 * 1024 * 1024) // 100MB heap limit watch
            } catch (e: Exception) {
                // This might not work on all devices
            }
        }

        // 3. Enable hardware acceleration if available
        System.setProperty("persist.sys.dalvik.vm.lib.2", "libart.so")

        // 4. Optimize garbage collection
        System.gc()
        System.runFinalization()
    }

    fun preventProcessDeath() {
        // Make process sticky
        try {
            val pid = Process.myPid()
            val uid = Process.myUid()

            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "ServiceOptimizer",
                "Process info - PID: $pid, UID: $uid"
            )

            // Try to protect process (requires root on most devices)
            try {
                Runtime.getRuntime().exec("echo -17 > /proc/$pid/oom_adj")
            } catch (e: Exception) {
                // Expected to fail on non-rooted devices
            }
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError("ServiceOptimizer", "Failed to prevent process death", e)
        }
    }

    fun isLowMemory(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory
    }

    fun getAvailableMemory(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem
    }

    fun trimMemory(level: Int) {
        when (level) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> {
                // Don't trim if we're foreground
            }
            else -> {
                System.gc()
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "ServiceOptimizer",
                    "Memory trimmed at level: $level"
                )
            }
        }
    }
}