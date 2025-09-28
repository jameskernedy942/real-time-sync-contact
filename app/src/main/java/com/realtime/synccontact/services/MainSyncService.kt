package com.realtime.synccontact.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import androidx.core.app.NotificationCompat
import com.realtime.synccontact.BuildConfig
import com.realtime.synccontact.MainActivity
import com.realtime.synccontact.R
import com.realtime.synccontact.amqp.MessageProcessor
import com.realtime.synccontact.amqp.ImprovedAMQPConnection
import com.realtime.synccontact.data.LocalRetryQueue
import com.realtime.synccontact.network.ConnectionManager
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.NotificationHelper
import com.realtime.synccontact.utils.SharedPrefsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicBoolean

class MainSyncService : Service() {

    private val isRunning = AtomicBoolean(false)
    private lateinit var sharedPrefsManager: SharedPrefsManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var connectionManager: ConnectionManager

    private var connection1: ImprovedAMQPConnection? = null
    private var connection2: ImprovedAMQPConnection? = null
    private var processor1: MessageProcessor? = null
    private var processor2: MessageProcessor? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastErrorNotificationTime = 0L
    private val ERROR_NOTIFICATION_RATE_LIMIT = 60000L // 1 minute

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CONNECTION_URL = "amqps://exvhisrd:YaOH1SKFrqZA4Bfilrm0Z3G5yGGUlmnE@windy-eagle-01.lmq.cloudamqp.com/exvhisrd?heartbeat=10"

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (MainSyncService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        sharedPrefsManager = SharedPrefsManager(this)
        notificationHelper = NotificationHelper(this)
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectionManager = ConnectionManager(this)

        // Acquire wake lock
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RealTimeSync::SyncWakeLock"
        )
        wakeLock.acquire()

        CrashlyticsLogger.logServiceStatus("MainSyncService", "CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning.get()) {
            startForegroundService()
            startSyncOperations()
            isRunning.set(true)
        }

        // Schedule health checks
        scheduleHealthChecks()

        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification("Initializing...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(status: String): Notification {
        val (phone1, phone2) = sharedPrefsManager.getPhoneNumbers()
        val networkType = getNetworkType()

        val activeNumbers = if (phone2.isNotEmpty()) {
            "${phone1.takeLast(4)}, ${phone2.takeLast(4)}"
        } else {
            phone1.takeLast(4)
        }

        val contentText = """
            Active: $activeNumbers
            Network: $networkType
            $status
        """.trimIndent()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_SERVICE)
            .setContentTitle("Real Time Sync Active")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun startSyncOperations() {
        val (phone1, phone2) = sharedPrefsManager.getPhoneNumbers()

        if (phone1.isEmpty()) {
            CrashlyticsLogger.logCriticalError(
                "MainSyncService",
                "First phone number not configured",
                null
            )
            stopSelf()
            return
        }

        CrashlyticsLogger.logAppStart(BuildConfig.VERSION_NAME, phone1, phone2)

        // Start connections in parallel
        serviceScope.launch {
            supervisorScope {
                // Connection 1
                launch {
                    startConnection1(phone1)
                }

                // Connection 2 (only if provided and different from phone1)
                if (phone2.isNotEmpty() && phone1 != phone2) {
                    launch {
                        startConnection2(phone2)
                    }
                }

                // Monitor connections
                launch {
                    monitorConnections()
                }

                // Periodic cleanup
                launch {
                    while (isActive) {
                        delay(3600000) // Every hour
                        performCleanup()
                    }
                }
            }
        }
    }

    private suspend fun startConnection1(phone: String) {
        val queueName = "APK_SYNC_$phone"

        // Create connection with improved error handling
        connection1 = ImprovedAMQPConnection(CONNECTION_URL, queueName, phone, connectionManager)
        processor1 = MessageProcessor(this@MainSyncService, queueName, phone)

        // Monitor connection state
        serviceScope.launch {
            connection1?.connectionState?.collect { state ->
                when (state) {
                    ImprovedAMQPConnection.ConnectionState.CONNECTED -> {
                        updateNotification("Queue 1 connected")
                        CrashlyticsLogger.logConnectionEvent("CONNECTED", queueName, "Connection 1 established")
                    }
                    ImprovedAMQPConnection.ConnectionState.ERROR -> {
                        val stats = connection1?.getConnectionStats()
                        val failures = stats?.get("consecutiveFailures") ?: 0
                        if (failures as Int > 50) {
                            notifyError("Queue 1 persistent error")
                        }
                    }
                    ImprovedAMQPConnection.ConnectionState.RECONNECTING -> {
                        updateNotification("Queue 1 reconnecting...")
                    }
                    ImprovedAMQPConnection.ConnectionState.DISCONNECTED -> {
                        updateNotification("Queue 1 disconnected")
                    }
                    else -> {}
                }
            }
        }

        // Connect with automatic retry
        while (isRunning.get()) {
            try {
                val connected = connection1?.connect { message ->
                    processor1?.processMessage(message, connection1!!) ?: false
                } ?: false

                if (connected) {
                    // Connection will maintain itself
                    // Just wait while it's connected
                    while (connection1?.isConnected() == true && isRunning.get()) {
                        delay(10000) // Check every 10 seconds
                    }
                }

                // If we're here, connection was lost
                // The ImprovedAMQPConnection will handle reconnection internally
                // We just need to wait for it to signal readiness
                if (!isRunning.get()) break

                // Wait a bit before checking connection state again
                delay(5000)

            } catch (e: Exception) {
                CrashlyticsLogger.logCriticalError(
                    "Connection1",
                    "Connection 1 unexpected error: ${e.message}",
                    e
                )
                delay(10000)
            }
        }
    }

    private suspend fun startConnection2(phone: String) {
        val queueName = "APK_SYNC_$phone"

        // Create connection with improved error handling
        connection2 = ImprovedAMQPConnection(CONNECTION_URL, queueName, phone, connectionManager)
        processor2 = MessageProcessor(this@MainSyncService, queueName, phone)

        // Monitor connection state
        serviceScope.launch {
            connection2?.connectionState?.collect { state ->
                when (state) {
                    ImprovedAMQPConnection.ConnectionState.CONNECTED -> {
                        updateNotification("Queue 2 connected")
                        CrashlyticsLogger.logConnectionEvent("CONNECTED", queueName, "Connection 2 established")
                    }
                    ImprovedAMQPConnection.ConnectionState.ERROR -> {
                        val stats = connection2?.getConnectionStats()
                        val failures = stats?.get("consecutiveFailures") ?: 0
                        if (failures as Int > 50) {
                            notifyError("Queue 2 persistent error")
                        }
                    }
                    ImprovedAMQPConnection.ConnectionState.RECONNECTING -> {
                        updateNotification("Queue 2 reconnecting...")
                    }
                    ImprovedAMQPConnection.ConnectionState.DISCONNECTED -> {
                        updateNotification("Queue 2 disconnected")
                    }
                    else -> {}
                }
            }
        }

        // Connect with automatic retry
        while (isRunning.get()) {
            try {
                val connected = connection2?.connect { message ->
                    processor2?.processMessage(message, connection2!!) ?: false
                } ?: false

                if (connected) {
                    // Connection will maintain itself
                    // Just wait while it's connected
                    while (connection2?.isConnected() == true && isRunning.get()) {
                        delay(10000) // Check every 10 seconds
                    }
                }

                // If we're here, connection was lost
                // The ImprovedAMQPConnection will handle reconnection internally
                // We just need to wait for it to signal readiness
                if (!isRunning.get()) break

                // Wait a bit before checking connection state again
                delay(5000)

            } catch (e: Exception) {
                CrashlyticsLogger.logCriticalError(
                    "Connection2",
                    "Connection 2 unexpected error: ${e.message}",
                    e
                )
                delay(10000)
            }
        }
    }

    private suspend fun monitorConnections() {
        while (isRunning.get()) {
            delay(10000) // Check every 10 seconds

            val conn1Status = connection1?.isConnected() ?: false
            val conn2Status = connection2?.isConnected() ?: false
            val networkState = connectionManager.networkState.value

            val status = when {
                !networkState.isConnected -> "No network"
                conn1Status && conn2Status -> "Both queues connected"
                conn1Status -> "Queue 1 connected"
                conn2Status -> "Queue 2 connected"
                else -> "Reconnecting..."
            }

            // Add network type to status if connected
            val fullStatus = if (networkState.isConnected) {
                "$status (${networkState.networkType.name})"
            } else {
                status
            }

            updateNotification(fullStatus)

            // Log health status with more details
            val conn1Stats = connection1?.getConnectionStats()
            val conn2Stats = connection2?.getConnectionStats()

            CrashlyticsLogger.logServiceStatus(
                "MainSyncService",
                "HEALTH_CHECK",
                "Network: ${networkState.networkType}, Conn1: $conn1Status (failures: ${conn1Stats?.get("consecutiveFailures")}), Conn2: $conn2Status (failures: ${conn2Stats?.get("consecutiveFailures")})"
            )

            // Check memory
            checkMemoryStatus()

            // Check if we should notify about persistent errors
            val conn1Failures = (conn1Stats?.get("consecutiveFailures") as? Int) ?: 0
            val conn2Failures = (conn2Stats?.get("consecutiveFailures") as? Int) ?: 0

            if (conn1Failures > 100 || conn2Failures > 100) {
                notifyError("Persistent connection errors - check CloudAMQP service")
            }
        }
    }

    private fun checkMemoryStatus() {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - usedMemory

        if (availableMemory < 50 * 1024 * 1024) { // Less than 50MB
            CrashlyticsLogger.logMemoryWarning(availableMemory, maxMemory)

            // Trigger garbage collection
            System.gc()

            // Reduce prefetch if memory is low
            // This would be implemented in the connection class
        }
    }

    private fun performCleanup() {
        try {
            // Cleanup retry queue
            val retryQueue = LocalRetryQueue(this)
            retryQueue.cleanup()

            // Log stats
            val stats1 = processor1?.getStats()
            val stats2 = processor2?.getStats()

            CrashlyticsLogger.setCustomKeys(
                mapOf(
                    "total_processed_1" to (stats1?.get("totalProcessed") ?: 0),
                    "total_processed_2" to (stats2?.get("totalProcessed") ?: 0),
                    "total_synced" to sharedPrefsManager.getTotalSynced()
                )
            )
        } catch (e: Exception) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "Cleanup",
                "Cleanup failed: ${e.message}"
            )
        }
    }

    private fun scheduleHealthChecks() {
        // Schedule WorkManager health check
        WorkerService.scheduleHealthCheck(this)

        // Schedule AlarmManager backup check
        AlarmReceiver.scheduleAlarm(this)
    }

    private fun getNetworkType(): String {
        return try {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            when {
                capabilities == null -> "None"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "Mobile"
                    } else {
                        "Mobile"
                    }
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun updateNotification(status: String) {
        mainHandler.post {
            val notification = createNotification(status)
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyError(error: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastErrorNotificationTime > ERROR_NOTIFICATION_RATE_LIMIT) {
            lastErrorNotificationTime = currentTime

            notificationHelper.showErrorNotification(
                "Connection Error",
                "$error - Check CloudAMQP or contact developer"
            )

            CrashlyticsLogger.logCriticalError(
                "ServiceError",
                error,
                null
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning.set(false)

        // Cancel all coroutines
        serviceScope.cancel()

        // Disconnect AMQP connections
        connection1?.disconnect()
        connection2?.disconnect()
        connection1?.cancelScope()
        connection2?.cancelScope()

        // Cleanup processors
        processor1?.cleanup()
        processor2?.cleanup()

        // Cleanup connection manager
        connectionManager.cleanup()

        // Release wake lock
        if (wakeLock.isHeld) {
            wakeLock.release()
        }

        CrashlyticsLogger.logServiceStatus("MainSyncService", "DESTROYED")

        // Try to restart
        if (sharedPrefsManager.isServiceStarted()) {
            sendBroadcast(Intent("com.realtimeapksync.RESTART_SERVICE"))
        }

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        CrashlyticsLogger.logServiceStatus("MainSyncService", "TASK_REMOVED")

        // Restart service when task is removed
        val restartIntent = Intent(this, MainSyncService::class.java)
        val pendingIntent = PendingIntent.getService(
            this,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )

        super.onTaskRemoved(rootIntent)
    }
}