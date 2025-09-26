package com.realtime.synccontact

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.realtime.synccontact.utils.CrashlyticsLogger

class RealTimeSyncApplication : Application(), Configuration.Provider {

    companion object {
        lateinit var instance: RealTimeSyncApplication
            private set
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Firebase
        FirebaseApp.initializeApp(this)

        // Enable Crashlytics
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)

        // WorkManager will be initialized automatically via Configuration.Provider

        // Log app initialization
        CrashlyticsLogger.logAppStart(
            version = BuildConfig.VERSION_NAME,
            phone1 = "", // Will be set when service starts
            phone2 = ""
        )

        // Set uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            CrashlyticsLogger.logCriticalError(
                component = "UncaughtException",
                error = "Thread: ${thread.name} - ${exception.message}",
                throwable = exception
            )
            // Let the default handler also process it
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, exception)
        }
    }
}