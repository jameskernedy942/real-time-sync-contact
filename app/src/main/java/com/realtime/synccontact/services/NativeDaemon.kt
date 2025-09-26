package com.realtime.synccontact.services

import android.content.Context
import com.realtime.synccontact.utils.CrashlyticsLogger

object NativeDaemon {

    init {
        try {
            System.loadLibrary("guardian_daemon")
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "NativeDaemon",
                "Native daemon library loaded successfully"
            )
        } catch (e: UnsatisfiedLinkError) {
            CrashlyticsLogger.logCriticalError(
                "NativeDaemon",
                "Failed to load native daemon library",
                e
            )
        }
    }

    external fun startDaemon(context: Context)
    external fun stopDaemon()
    external fun isDaemonRunning(): Boolean
    external fun forceServiceRestart()

    fun initialize(context: Context) {
        try {
            if (!isDaemonRunning()) {
                startDaemon(context.applicationContext)
                CrashlyticsLogger.logServiceStatus(
                    "NativeDaemon",
                    "STARTED",
                    "Native daemon initialized"
                )
            }
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "NativeDaemon",
                "Failed to initialize native daemon",
                e
            )
        }
    }

    fun shutdown() {
        try {
            if (isDaemonRunning()) {
                stopDaemon()
                CrashlyticsLogger.logServiceStatus(
                    "NativeDaemon",
                    "STOPPED",
                    "Native daemon shutdown"
                )
            }
        } catch (e: Exception) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "NativeDaemon",
                "Error shutting down daemon: ${e.message}"
            )
        }
    }
}