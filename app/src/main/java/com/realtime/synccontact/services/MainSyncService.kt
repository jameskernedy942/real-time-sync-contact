package com.realtime.synccontact.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.ComponentCallbacks2
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.*
import android.net.wifi.WifiManager
import android.provider.Settings
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.core.app.NotificationCompat
import com.realtime.synccontact.BuildConfig
import com.realtime.synccontact.DeviceAdminSetupActivity
import com.realtime.synccontact.admin.DeviceAdminReceiver
import com.realtime.synccontact.MainActivity
import com.realtime.synccontact.R
import com.realtime.synccontact.amqp.MessageProcessor
import com.realtime.synccontact.amqp.ThreadSafeAMQPConnection
import com.realtime.synccontact.data.LocalRetryQueue
import com.realtime.synccontact.monitoring.CloudAMQPMonitor
import com.realtime.synccontact.network.ConnectionManager
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.NotificationHelper
import com.realtime.synccontact.utils.SharedPrefsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicBoolean

class MainSyncService : Service(), ComponentCallbacks2 {

    private val isRunning = AtomicBoolean(false)
    private lateinit var sharedPrefsManager: SharedPrefsManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var connectionManager: ConnectionManager
    private lateinit var cloudAMQPMonitor: CloudAMQPMonitor
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var deviceAdminComponent: ComponentName

    private val wakeRenewHandler = Handler(Looper.getMainLooper())
    private val deviceAdminCheckHandler = Handler(Looper.getMainLooper())
    private var lastAliveNotificationTime = 0L
    private val ALIVE_NOTIFICATION_INTERVAL = 3600000L // 1 hour
    private val DEVICE_ADMIN_CHECK_INTERVAL = 300000L // 5 minutes

    private var connection1: ThreadSafeAMQPConnection? = null
    private var connection2: ThreadSafeAMQPConnection? = null
    private var processor1: MessageProcessor? = null
    private var processor2: MessageProcessor? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var lastErrorNotificationTime = 0L
    private val ERROR_NOTIFICATION_RATE_LIMIT = 60000L // 1 minute

    private val renewWakeLockRunnable = object : Runnable {
        override fun run() {
            try {
                if (::wakeLock.isInitialized) {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                    wakeLock.acquire(10 * 60 * 1000L) // 10 minutes

                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.DEBUG,
                        "WakeLock",
                        "Wake lock renewed successfully"
                    )
                }
                // Schedule next renewal in 9 minutes
                wakeRenewHandler.postDelayed(this, 9 * 60 * 1000L)
            } catch (e: Exception) {
                CrashlyticsLogger.logCriticalError("WakeLock", "Renewal failed", e)
                // Retry in 1 minute if failed
                wakeRenewHandler.postDelayed(this, 60000L)
            }
        }
    }

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

        // Initialize only essential lightweight components synchronously
        sharedPrefsManager = SharedPrefsManager(this)
        notificationHelper = NotificationHelper(this)
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        deviceAdminComponent = ComponentName(this, DeviceAdminReceiver::class.java)

        // Initialize wake locks (lightweight)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RealTimeSync::SyncWakeLock"
        )

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "RealTimeSync::WifiLock"
        )

        // Defer heavy initialization to background
        serviceScope.launch {
            try {
                // Initialize heavy components in background
                withContext(Dispatchers.IO) {
                    cloudAMQPMonitor = CloudAMQPMonitor(this@MainSyncService)
                    connectionManager = ConnectionManager(this@MainSyncService)
                }

                // Switch to main thread for UI-related operations
                withContext(Dispatchers.Main) {
                    // Acquire locks
                    if (!wakeLock.isHeld) {
                        wakeLock.acquire(10 * 60 * 1000L)
                    }
                    if (!wifiLock.isHeld) {
                        wifiLock.acquire()
                    }

                    // Start wake lock renewal
                    renewWakeLockRunnable.run()

                    // Register callbacks
                    registerNetworkCallback()

                    // Start monitoring
                    startDeviceAdminMonitoring()

                    // Check optimizations (non-blocking)
                    checkBatteryOptimization()
                    checkAndForceDeviceAdmin()
                }

                CrashlyticsLogger.logServiceStatus("MainSyncService", "INITIALIZED")
            } catch (e: Exception) {
                CrashlyticsLogger.logCriticalError("MainSyncService", "Initialization failed", e)
            }
        }

        CrashlyticsLogger.logServiceStatus("MainSyncService", "CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Must call startForeground within 5 seconds on Android 8+
        // Do this IMMEDIATELY to avoid ANR
        if (!isRunning.get()) {
            // Start foreground IMMEDIATELY
            startForegroundService()
            isRunning.set(true)

            // Set service start time
            sharedPrefsManager.setServiceStartTime()
            sharedPrefsManager.setServiceStatus("running")

            // Defer all heavy operations to background
            serviceScope.launch {
                try {
                    // Wait a bit for onCreate initialization to complete
                    delay(100)

                    // Ensure components are initialized
                    var retries = 0
                    while ((!::cloudAMQPMonitor.isInitialized || !::connectionManager.isInitialized) && retries < 50) {
                        delay(100)
                        retries++
                    }

                    if (!::cloudAMQPMonitor.isInitialized || !::connectionManager.isInitialized) {
                        CrashlyticsLogger.logCriticalError(
                            "MainSyncService",
                            "Components not initialized after 5 seconds",
                            IllegalStateException("Initialization timeout")
                        )
                        return@launch
                    }

                    // Now start actual sync operations
                    withContext(Dispatchers.IO) {
                        startSyncOperations()
                    }

                    // Send alive notification
                    sendAliveNotification()

                    // Start heartbeat
                    startHeartbeat()

                    // Schedule JobScheduler for resurrection
                    withContext(Dispatchers.Main) {
                        ServiceResurrectionJob.schedule(this@MainSyncService)
                    }

                    // Schedule health checks
                    scheduleHealthChecks()

                } catch (e: Exception) {
                    CrashlyticsLogger.logCriticalError("MainSyncService", "Failed to start operations", e)
                    updateNotification("Error: ${e.message}")
                }
            }
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        // Create a simple notification FAST to avoid ANR
        val notification = createSimpleNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Update with detailed notification later
        serviceScope.launch {
            delay(100)
            val detailedNotification = createNotification("Starting...")
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, detailedNotification)
        }
    }

    private fun createSimpleNotification(): Notification {
        // Create minimal notification FAST
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_SERVICE)
            .setContentTitle("Real Time Sync")
            .setContentText("Starting service...")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
    }

    private fun createNotification(status: String): Notification {
        return try {
            val (phone1, phone2) = sharedPrefsManager.getPhoneNumbers()
            val networkType = try {
                getNetworkType()
            } catch (e: Exception) {
                "Unknown"
            }

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

            NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_SERVICE)
                .setContentTitle("Real Time Sync Active")
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setShowWhen(false)
                .build()
        } catch (e: Exception) {
            // Fallback to simple notification on error
            createSimpleNotification()
        }
    }

    private fun startSyncOperations() {
        val (phone1, phone2) = sharedPrefsManager.getPhoneNumbers()

        // CRITICAL: Check if connections already exist WITH SAME PHONE NUMBERS
        val conn1Valid = connection1?.let {
            it.isConnected() && it.getPhoneNumber() == phone1
        } ?: false

        val conn2Valid = connection2?.let {
            it.isConnected() && it.getPhoneNumber() == phone2
        } ?: false

        // If phone numbers haven't changed and connections are active, skip
        if (conn1Valid && (phone2.isEmpty() || conn2Valid)) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "MainSyncService",
                "Connections already active with same phone numbers, skipping duplicate creation"
            )
            return
        }

        // If phone numbers changed, we need to cleanup old connections
        if (connection1 != null && connection1?.getPhoneNumber() != phone1) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "MainSyncService",
                "Phone1 changed from ${connection1?.getPhoneNumber()} to $phone1, cleaning up old connection"
            )
            connection1?.disconnect()
            connection1?.cleanup()
            connection1 = null
            processor1?.cleanup()
            processor1 = null
        }

        if (connection2 != null && connection2?.getPhoneNumber() != phone2) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "MainSyncService",
                "Phone2 changed from ${connection2?.getPhoneNumber()} to $phone2, cleaning up old connection"
            )
            connection2?.disconnect()
            connection2?.cleanup()
            connection2 = null
            processor2?.cleanup()
            processor2 = null
        }

        if (phone1.isEmpty()) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "MainSyncService",
                "Phone number not configured - waiting for configuration"
            )

            // Don't stop service, wait for configuration
            scheduleConfigCheck()
            updateNotification("Waiting for phone number configuration...")
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

        // CRITICAL: Don't create duplicate connection WITH SAME PHONE NUMBER
        if (connection1?.isConnected() == true && connection1?.getPhoneNumber() == phone) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "Connection1",
                "Already connected to queue $queueName, skipping duplicate creation"
            )
            return
        }

        // If phone number changed, log it
        if (connection1 != null && connection1?.getPhoneNumber() != phone) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "Connection1",
                "Phone number changed from ${connection1?.getPhoneNumber()} to $phone, creating new connection"
            )
        }

        // Check CloudAMQP limits before connecting
        if (!cloudAMQPMonitor.shouldAllowConnection()) {
            val delay = cloudAMQPMonitor.getRetryDelay()
            updateNotification("Rate limited. Retry in ${delay/1000}s")
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "Connection1",
                "Rate limited, waiting ${delay/1000}s"
            )
            delay(delay)
            return
        }

        // Record connection attempt
        cloudAMQPMonitor.recordConnection()

        // ALWAYS cleanup any existing connection first to prevent duplicates
        connection1?.let {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "Connection1",
                "Cleaning up existing connection before creating new one"
            )
            it.disconnect()
            it.cleanup()
        }
        connection1 = null
        processor1?.cleanup()
        processor1 = null

        // Small delay to ensure cleanup
        delay(100)

        // Create thread-safe connection to prevent deadlocks
        connection1 = ThreadSafeAMQPConnection(CONNECTION_URL, queueName, phone, connectionManager)
        processor1 = MessageProcessor(this@MainSyncService, queueName, phone)

        // Monitor connection state
        serviceScope.launch {
            connection1?.connectionState?.collect { state ->
                when (state) {
                    ThreadSafeAMQPConnection.ConnectionState.CONNECTED -> {
                        updateNotification("Queue 1 connected")
                        CrashlyticsLogger.logConnectionEvent("CONNECTED", queueName, "Connection 1 established")
                    }
                    ThreadSafeAMQPConnection.ConnectionState.ERROR -> {
                        val stats = connection1?.getConnectionStats()
                        val failures = stats?.get("consecutiveFailures") ?: 0
                        if (failures as Int > 50) {
                            notifyError("Queue 1 persistent error")
                        }
                    }
                    ThreadSafeAMQPConnection.ConnectionState.RECONNECTING -> {
                        updateNotification("Queue 1 reconnecting...")
                    }
                    ThreadSafeAMQPConnection.ConnectionState.DISCONNECTED -> {
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

                // Record error for CloudAMQP monitoring
                cloudAMQPMonitor.recordError(e)

                // Get appropriate delay based on error
                val retryDelay = cloudAMQPMonitor.getRetryDelay()
                delay(retryDelay)
            }
        }
    }

    private suspend fun startConnection2(phone: String) {
        val queueName = "APK_SYNC_$phone"

        // CRITICAL: Don't create duplicate connection WITH SAME PHONE NUMBER
        if (connection2?.isConnected() == true && connection2?.getPhoneNumber() == phone) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "Connection2",
                "Already connected to queue $queueName, skipping duplicate creation"
            )
            return
        }

        // If phone number changed, log it
        if (connection2 != null && connection2?.getPhoneNumber() != phone) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "Connection2",
                "Phone number changed from ${connection2?.getPhoneNumber()} to $phone, creating new connection"
            )
        }

        // Check CloudAMQP limits before connecting
        if (!cloudAMQPMonitor.shouldAllowConnection()) {
            val delay = cloudAMQPMonitor.getRetryDelay()
            updateNotification("Rate limited. Retry in ${delay/1000}s")
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "Connection2",
                "Rate limited, waiting ${delay/1000}s"
            )
            delay(delay)
            return
        }

        // Record connection attempt
        cloudAMQPMonitor.recordConnection()

        // ALWAYS cleanup any existing connection first to prevent duplicates
        connection2?.let {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "Connection2",
                "Cleaning up existing connection before creating new one"
            )
            it.disconnect()
            it.cleanup()
        }
        connection2 = null
        processor2?.cleanup()
        processor2 = null

        // Small delay to ensure cleanup
        delay(100)

        // Create thread-safe connection to prevent deadlocks
        connection2 = ThreadSafeAMQPConnection(CONNECTION_URL, queueName, phone, connectionManager)
        processor2 = MessageProcessor(this@MainSyncService, queueName, phone)

        // Monitor connection state
        serviceScope.launch {
            connection2?.connectionState?.collect { state ->
                when (state) {
                    ThreadSafeAMQPConnection.ConnectionState.CONNECTED -> {
                        updateNotification("Queue 2 connected")
                        CrashlyticsLogger.logConnectionEvent("CONNECTED", queueName, "Connection 2 established")
                    }
                    ThreadSafeAMQPConnection.ConnectionState.ERROR -> {
                        val stats = connection2?.getConnectionStats()
                        val failures = stats?.get("consecutiveFailures") ?: 0
                        if (failures as Int > 50) {
                            notifyError("Queue 2 persistent error")
                        }
                    }
                    ThreadSafeAMQPConnection.ConnectionState.RECONNECTING -> {
                        updateNotification("Queue 2 reconnecting...")
                    }
                    ThreadSafeAMQPConnection.ConnectionState.DISCONNECTED -> {
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

                // Record error for CloudAMQP monitoring
                cloudAMQPMonitor.recordError(e)

                // Get appropriate delay based on error
                val retryDelay = cloudAMQPMonitor.getRetryDelay()
                delay(retryDelay)
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

        // Update service status
        sharedPrefsManager.setServiceStatus("stopped")
        sharedPrefsManager.setServiceDeathTime()
        sharedPrefsManager.incrementServiceDeathCount()

        // Send death notification
        sendDeathNotification()

        // Cancel all coroutines
        serviceScope.cancel()

        // Stop wake lock renewal
        wakeRenewHandler.removeCallbacks(renewWakeLockRunnable)

        // Unregister network callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback!!)
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Disconnect AMQP connections
        connection1?.disconnect()
        connection2?.disconnect()
        connection1?.cleanup()
        connection2?.cleanup()

        // Cleanup processors
        processor1?.cleanup()
        processor2?.cleanup()

        // Cleanup connection manager
        connectionManager.cleanup()

        // Cleanup CloudAMQP monitor
        cloudAMQPMonitor.cleanup()

        // Record disconnections
        cloudAMQPMonitor.recordDisconnection()
        if (connection2 != null) {
            cloudAMQPMonitor.recordDisconnection()
        }

        // Release locks
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }

        if (::wifiLock.isInitialized && wifiLock.isHeld) {
            wifiLock.release()
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


    private fun scheduleConfigCheck() {
        serviceScope.launch {
            while (isRunning.get()) {
                delay(30000) // Check every 30 seconds

                val (phone1, _) = sharedPrefsManager.getPhoneNumbers()
                if (phone1.isNotEmpty()) {
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.INFO,
                        "MainSyncService",
                        "Configuration detected - starting sync"
                    )
                    startSyncOperations()
                    break
                }
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                notificationHelper.showNotification(
                    "Battery Optimization Warning",
                    "App may stop syncing! Tap to disable battery optimization",
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )

                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.WARNING,
                    "Battery",
                    "App not whitelisted from battery optimization"
                )
            }
        }
    }

    private fun checkAndForceDeviceAdmin() {
        if (!devicePolicyManager.isAdminActive(deviceAdminComponent)) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.ERROR,
                "DeviceAdmin",
                "Device Admin NOT active - forcing activation"
            )

            // Show critical notification
            notificationHelper.showCriticalNotification(
                "⚠️ DEVICE ADMIN REQUIRED",
                "Service cannot function without Device Admin! Tap to enable NOW."
            )

            // Launch setup activity
            try {
                val intent = Intent(this, DeviceAdminSetupActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback to device admin settings
                try {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "MANDATORY: Device Admin is REQUIRED for 24/7 operation")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e2: Exception) {
                    CrashlyticsLogger.logCriticalError(
                        "DeviceAdmin",
                        "Failed to launch Device Admin setup",
                        e2
                    )
                }
            }
        } else {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "DeviceAdmin",
                "Device Admin is active"
            )
        }
    }

    private fun startDeviceAdminMonitoring() {
        val checkRunnable = object : Runnable {
            override fun run() {
                if (!devicePolicyManager.isAdminActive(deviceAdminComponent)) {
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.ERROR,
                        "DeviceAdmin",
                        "Device Admin was disabled! Forcing re-activation"
                    )

                    // Update service status
                    sharedPrefsManager.setServiceStatus("device_admin_missing")

                    // Force re-enable
                    checkAndForceDeviceAdmin()

                    // Show persistent notification
                    notificationHelper.showCriticalNotification(
                        "❌ DEVICE ADMIN DISABLED",
                        "Service STOPPED! Re-enable Device Admin immediately!"
                    )

                    // Try to prevent service death by restarting with alarm
                    val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                    val intent = Intent(this@MainSyncService, AlarmReceiver::class.java)
                    val pendingIntent = PendingIntent.getBroadcast(
                        this@MainSyncService,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + 10000,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + 10000,
                            pendingIntent
                        )
                    }
                }

                // Schedule next check
                deviceAdminCheckHandler.postDelayed(this, DEVICE_ADMIN_CHECK_INTERVAL)
            }
        }

        // Start monitoring
        deviceAdminCheckHandler.post(checkRunnable)
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    super.onAvailable(network)
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.INFO,
                        "Network",
                        "Network available - triggering immediate reconnection"
                    )

                    // Immediate reconnection attempt
                    serviceScope.launch {
                        delay(500) // Small delay to let network stabilize
                        reconnectAll()
                    }
                }

                override fun onLost(network: android.net.Network) {
                    super.onLost(network)
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.WARNING,
                        "Network",
                        "Network lost - connections will be disrupted"
                    )

                    updateNotification("Network lost - waiting for reconnection...")
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    super.onCapabilitiesChanged(network, networkCapabilities)

                    val hasInternet = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    )
                    val isValidated = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )

                    if (hasInternet && isValidated) {
                        CrashlyticsLogger.log(
                            CrashlyticsLogger.LogLevel.DEBUG,
                            "Network",
                            "Network capabilities changed - Internet available"
                        )

                        // Check if connections need reconnection
                        if (connection1?.isConnected() != true || connection2?.isConnected() != true) {
                            serviceScope.launch {
                                reconnectAll()
                            }
                        }
                    }
                }
            }

            val networkRequest = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()

            try {
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "Network",
                    "Network callback registered successfully"
                )
            } catch (e: Exception) {
                CrashlyticsLogger.logCriticalError("Network", "Failed to register network callback", e)
            }
        }
    }

    private suspend fun reconnectAll() {
        withContext(Dispatchers.IO) {
            try {
                // Check if we should reconnect
                if (!isRunning.get()) return@withContext

                val (phone1, phone2) = sharedPrefsManager.getPhoneNumbers()

                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "Network",
                    "Checking connections for phones: $phone1, $phone2"
                )

                // Check if connection1 needs reconnection
                val conn1NeedsReconnect = connection1?.let {
                    !it.isConnected() || it.getPhoneNumber() != phone1
                } ?: true

                // Check if connection2 needs reconnection
                val conn2NeedsReconnect = if (phone2.isNotEmpty()) {
                    connection2?.let {
                        !it.isConnected() || it.getPhoneNumber() != phone2
                    } ?: true
                } else {
                    false // No second phone, no need to reconnect
                }

                // Only reconnect if needed
                if (conn1NeedsReconnect || conn2NeedsReconnect) {
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.INFO,
                        "Network",
                        "Reconnection needed - Conn1: $conn1NeedsReconnect, Conn2: $conn2NeedsReconnect"
                    )

                    // Clean up connections that need reconnection
                    if (conn1NeedsReconnect) {
                        connection1?.let {
                            CrashlyticsLogger.log(
                                CrashlyticsLogger.LogLevel.INFO,
                                "Network",
                                "Cleaning up connection1 (was: ${it.getPhoneNumber()})"
                            )
                            it.disconnect()
                            it.cleanup()
                        }
                        connection1 = null
                        processor1?.cleanup()
                        processor1 = null
                    }

                    if (conn2NeedsReconnect) {
                        connection2?.let {
                            CrashlyticsLogger.log(
                                CrashlyticsLogger.LogLevel.INFO,
                                "Network",
                                "Cleaning up connection2 (was: ${it.getPhoneNumber()})"
                            )
                            it.disconnect()
                            it.cleanup()
                        }
                        connection2 = null
                        processor2?.cleanup()
                        processor2 = null
                    }

                    // Small delay to ensure cleanup
                    delay(500)

                    // Now restart sync operations
                    startSyncOperations()
                } else {
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.INFO,
                        "Network",
                        "All connections are healthy with correct phone numbers, no reconnection needed"
                    )
                }

                updateNotification("Reconnected to network")
            } catch (e: Exception) {
                CrashlyticsLogger.logCriticalError("Network", "Failed to reconnect", e)
            }
        }
    }

    private fun sendAliveNotification() {
        serviceScope.launch {
            while (isRunning.get()) {
                delay(ALIVE_NOTIFICATION_INTERVAL) // Every hour

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastAliveNotificationTime > ALIVE_NOTIFICATION_INTERVAL) {
                    lastAliveNotificationTime = currentTime

                    val conn1Status = connection1?.isConnected() ?: false
                    val conn2Status = connection2?.isConnected() ?: false
                    val runtime = (currentTime - sharedPrefsManager.getServiceStartTime()) / 1000 / 60 // minutes
                    val totalSynced = sharedPrefsManager.getTotalSynced()

                    notificationHelper.showStatusNotification(
                        "✅ Service Running",
                        "Uptime: ${runtime}min | Synced: $totalSynced | Q1:${if(conn1Status) "✓" else "✗"} Q2:${if(conn2Status) "✓" else "✗"}"
                    )
                }
            }
        }
    }

    private fun sendDeathNotification() {
        try {
            val runtime = (System.currentTimeMillis() - sharedPrefsManager.getServiceStartTime()) / 1000 / 60
            val totalSynced = sharedPrefsManager.getTotalSynced()

            notificationHelper.showCriticalNotification(
                "⚠️ SYNC SERVICE STOPPED!",
                "Service died after ${runtime}min. Synced: $totalSynced. Tap to restart.",
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )

            // Also log to Crashlytics
            CrashlyticsLogger.logServiceStatus(
                "MainSyncService",
                "DEATH",
                "Service died after ${runtime} minutes"
            )
        } catch (e: Exception) {
            // Ignore errors during death notification
        }
    }

    private fun checkServiceHealth(): Boolean {
        // Check if all critical components are healthy
        val isHealthy = isRunning.get() &&
                       (connection1?.isConnected() == true || connection2?.isConnected() == true) &&
                       wakeLock.isHeld &&
                       wifiLock.isHeld

        if (!isHealthy) {
            notificationHelper.showWarningNotification(
                "⚠️ Service Unhealthy",
                "Some components are not working. Tap to check."
            )
        }

        return isHealthy
    }

    private fun startHeartbeat() {
        serviceScope.launch {
            while (isRunning.get()) {
                delay(30000) // Every 30 seconds

                // Update heartbeat
                sharedPrefsManager.setLastHeartbeat()

                // Check service health
                if (!checkServiceHealth()) {
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.WARNING,
                        "Heartbeat",
                        "Service unhealthy"
                    )
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.WARNING,
            "Memory",
            "onTrimMemory called with level: $level"
        )

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // System is extremely low on memory, release everything possible
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.ERROR,
                    "Memory",
                    "CRITICAL memory pressure!"
                )

                // Clear all caches
                clearAllCaches()
                performCleanup()

                // Force garbage collection
                System.gc()
                System.runFinalization()

                // Reduce connection pool if multiple connections
                if (connection2 != null && connection1?.isConnected() == true) {
                    connection2?.disconnect()
                    connection2 = null
                    processor2 = null
                }

                updateNotification("⚠️ Critical memory pressure")
            }

            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                // App is not visible, system running low on memory
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.WARNING,
                    "Memory",
                    "SEVERE memory pressure"
                )

                // Clear non-essential caches
                clearAllCaches()
                System.gc()
            }

            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                // System is running somewhat low on memory
                System.gc()
            }

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // App is running but system critically low on memory
                clearAllCaches()
                performCleanup()
                System.gc()

                notificationHelper.showWarningNotification(
                    "⚠️ Low Memory",
                    "System is low on memory. Service may be affected."
                )

                updateNotification("⚠️ Critical memory pressure")
            }
        }
    }

    private fun clearAllCaches() {
        try {
            // Clear any local caches
            processor1?.cleanup()
            processor2?.cleanup()

            // Connection stats are read-only, just log the cleanup
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "Memory",
                "Caches cleared"
            )
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError("Memory", "Failed to clear caches", e)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.ERROR,
            "Memory",
            "onLowMemory called - system is LOW on memory!"
        )

        CrashlyticsLogger.logMemoryWarning(0, Runtime.getRuntime().maxMemory())

        // Emergency memory release
        clearAllCaches()
        performCleanup()
        System.gc()

        // Notify user about memory pressure
        updateNotification("⚠️ Low memory - optimizing...")

        // Show critical notification
        notificationHelper.showCriticalNotification(
            "⚠️ CRITICAL: Low Memory",
            "Device is critically low on memory. Service may stop!"
        )
    }
}