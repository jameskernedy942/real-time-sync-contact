package com.realtime.synccontact.monitoring

import android.content.Context
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.NotificationHelper
import com.realtime.synccontact.utils.SharedPrefsManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Monitors CloudAMQP usage to prevent hitting limits
 * Implements rate limiting and backoff strategies
 */
class CloudAMQPMonitor(private val context: Context) {

    private val sharedPrefsManager = SharedPrefsManager(context)
    private val notificationHelper = NotificationHelper(context)

    // Counters
    private val messagesProcessed = AtomicLong(0)
    private val connectionAttempts = AtomicInteger(0)
    private val activeConnections = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)

    // Rate limiting
    private var lastRateLimitTime = 0L
    private var rateLimitBackoffMs = 1000L // Start with 1 second

    // Thresholds (adjust based on your plan)
    companion object {
        // Free tier limits
        const val MAX_CONNECTIONS_PER_DAY = 100
        const val MAX_MESSAGES_PER_DAY = 30000 // ~1M per month
        const val MAX_CONCURRENT_CONNECTIONS = 20
        const val MAX_QUEUE_DEPTH = 200
        const val MAX_ERRORS_PER_HOUR = 50

        // Warning thresholds (80% of limits)
        const val WARNING_CONNECTIONS_PER_DAY = 80
        const val WARNING_MESSAGES_PER_DAY = 24000
        const val WARNING_QUEUE_DEPTH = 160
        const val WARNING_ERRORS_PER_HOUR = 40

        // Rate limit error patterns
        val RATE_LIMIT_PATTERNS = listOf(
            "429",
            "Too Many Requests",
            "rate limit",
            "quota exceeded",
            "connection limit",
            "blocked",
            "maximum number of connections",
            "connection refused"
        )
    }

    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startMonitoring()
        loadDailyStats()
    }

    private fun startMonitoring() {
        // Reset daily counters at midnight
        monitoringScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val midnight = getMidnight(now)
                val timeUntilMidnight = midnight - now

                delay(timeUntilMidnight)
                resetDailyCounters()
            }
        }

        // Monitor usage every minute
        monitoringScope.launch {
            while (isActive) {
                delay(60000) // Every minute
                checkUsage()
            }
        }

        // Monitor errors every 5 minutes
        monitoringScope.launch {
            while (isActive) {
                delay(300000) // Every 5 minutes
                checkErrorRate()
            }
        }
    }

    /**
     * Check if we should allow a new connection
     */
    fun shouldAllowConnection(): Boolean {
        val dailyConnections = sharedPrefsManager.getDailyConnectionCount()

        if (dailyConnections >= MAX_CONNECTIONS_PER_DAY) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.ERROR,
                "CloudAMQPMonitor",
                "Daily connection limit reached: $dailyConnections"
            )

            notificationHelper.showCriticalNotification(
                "⚠️ CONNECTION LIMIT REACHED",
                "CloudAMQP daily limit hit! Service will resume tomorrow."
            )

            return false
        }

        if (activeConnections.get() >= MAX_CONCURRENT_CONNECTIONS) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "CloudAMQPMonitor",
                "Concurrent connection limit reached: ${activeConnections.get()}"
            )
            return false
        }

        return !isRateLimited()
    }

    /**
     * Check if we're rate limited
     */
    fun isRateLimited(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastRateLimitTime) < rateLimitBackoffMs
    }

    /**
     * Handle rate limit error
     */
    fun handleRateLimitError(error: String) {
        lastRateLimitTime = System.currentTimeMillis()

        // Exponential backoff
        rateLimitBackoffMs = minOf(rateLimitBackoffMs * 2, 300000L) // Max 5 minutes

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.WARNING,
            "CloudAMQPMonitor",
            "Rate limited! Backing off for ${rateLimitBackoffMs / 1000}s"
        )

        // Check if this is a quota error
        if (error.contains("quota", ignoreCase = true) ||
            error.contains("maximum", ignoreCase = true)) {

            notificationHelper.showCriticalNotification(
                "⚠️ CLOUDAMQP QUOTA EXCEEDED",
                "Message quota reached! Consider upgrading your CloudAMQP plan."
            )

            // Longer backoff for quota errors
            rateLimitBackoffMs = 3600000L // 1 hour
        }
    }

    /**
     * Detect if error is rate limit related
     */
    fun isRateLimitError(error: Throwable): Boolean {
        val message = error.message ?: return false
        return RATE_LIMIT_PATTERNS.any { pattern ->
            message.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * Record successful connection
     */
    fun recordConnection() {
        connectionAttempts.incrementAndGet()
        activeConnections.incrementAndGet()
        sharedPrefsManager.incrementDailyConnectionCount()

        val daily = sharedPrefsManager.getDailyConnectionCount()
        if (daily >= WARNING_CONNECTIONS_PER_DAY) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "CloudAMQPMonitor",
                "Approaching daily connection limit: $daily/$MAX_CONNECTIONS_PER_DAY"
            )
        }

        // Reset rate limit on successful connection
        if (rateLimitBackoffMs > 1000L) {
            rateLimitBackoffMs = 1000L
        }
    }

    /**
     * Record disconnection
     */
    fun recordDisconnection() {
        activeConnections.decrementAndGet()
    }

    /**
     * Record processed message
     */
    fun recordMessage() {
        messagesProcessed.incrementAndGet()
        sharedPrefsManager.incrementDailyMessageCount()

        val daily = sharedPrefsManager.getDailyMessageCount()
        if (daily >= WARNING_MESSAGES_PER_DAY) {
            if (daily % 1000 == 0L) { // Log every 1000 messages
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.WARNING,
                    "CloudAMQPMonitor",
                    "Approaching daily message limit: $daily/$MAX_MESSAGES_PER_DAY"
                )
            }
        }
    }

    /**
     * Record error
     */
    fun recordError(error: Throwable) {
        errorCount.incrementAndGet()
        sharedPrefsManager.incrementHourlyErrorCount()

        // Check for rate limit errors
        if (isRateLimitError(error)) {
            handleRateLimitError(error.message ?: "Rate limit error")
        }
    }

    /**
     * Get recommended delay before retry
     */
    fun getRetryDelay(): Long {
        return if (isRateLimited()) {
            rateLimitBackoffMs
        } else {
            // Base delay on error count
            val errors = sharedPrefsManager.getHourlyErrorCount()
            when {
                errors > 100 -> 60000L // 1 minute
                errors > 50 -> 30000L  // 30 seconds
                errors > 20 -> 10000L  // 10 seconds
                errors > 10 -> 5000L   // 5 seconds
                else -> 1000L          // 1 second
            }
        }
    }

    private fun checkUsage() {
        val dailyConnections = sharedPrefsManager.getDailyConnectionCount()
        val dailyMessages = sharedPrefsManager.getDailyMessageCount()
        val hourlyErrors = sharedPrefsManager.getHourlyErrorCount()

        // Log current usage
        CrashlyticsLogger.setCustomKeys(
            mapOf(
                "daily_connections" to dailyConnections,
                "daily_messages" to dailyMessages,
                "hourly_errors" to hourlyErrors,
                "active_connections" to activeConnections.get()
            )
        )

        // Check if approaching limits
        if (dailyConnections >= WARNING_CONNECTIONS_PER_DAY) {
            val remaining = MAX_CONNECTIONS_PER_DAY - dailyConnections
            notificationHelper.showWarningNotification(
                "Connection Limit Warning",
                "Only $remaining connections remaining today"
            )
        }

        if (dailyMessages >= WARNING_MESSAGES_PER_DAY) {
            val remaining = MAX_MESSAGES_PER_DAY - dailyMessages
            notificationHelper.showWarningNotification(
                "Message Limit Warning",
                "Only $remaining messages remaining today"
            )
        }
    }

    private fun checkErrorRate() {
        val errors = sharedPrefsManager.getHourlyErrorCount()

        if (errors >= WARNING_ERRORS_PER_HOUR) {
            notificationHelper.showWarningNotification(
                "High Error Rate",
                "$errors errors in last hour. Check CloudAMQP status."
            )

            // Increase backoff if too many errors
            if (errors >= MAX_ERRORS_PER_HOUR) {
                rateLimitBackoffMs = minOf(rateLimitBackoffMs * 2, 300000L)
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.ERROR,
                    "CloudAMQPMonitor",
                    "Error rate too high, increasing backoff to ${rateLimitBackoffMs / 1000}s"
                )
            }
        }
    }

    private fun getMidnight(now: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun resetDailyCounters() {
        sharedPrefsManager.resetDailyCounters()
        connectionAttempts.set(0)
        messagesProcessed.set(0)

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.INFO,
            "CloudAMQPMonitor",
            "Daily counters reset"
        )

        // Reset rate limit for new day
        rateLimitBackoffMs = 1000L
        lastRateLimitTime = 0L
    }

    private fun loadDailyStats() {
        // Load existing stats from SharedPreferences
        val connections = sharedPrefsManager.getDailyConnectionCount()
        val messages = sharedPrefsManager.getDailyMessageCount()
        val errors = sharedPrefsManager.getHourlyErrorCount()

        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.INFO,
            "CloudAMQPMonitor",
            "Loaded stats - Connections: $connections, Messages: $messages, Errors: $errors"
        )
    }

    fun cleanup() {
        monitoringScope.cancel()
    }

    /**
     * Get current usage statistics
     */
    fun getUsageStats(): UsageStats {
        return UsageStats(
            dailyConnections = sharedPrefsManager.getDailyConnectionCount(),
            dailyMessages = sharedPrefsManager.getDailyMessageCount(),
            hourlyErrors = sharedPrefsManager.getHourlyErrorCount(),
            activeConnections = activeConnections.get(),
            isRateLimited = isRateLimited(),
            rateLimitBackoffMs = rateLimitBackoffMs
        )
    }

    data class UsageStats(
        val dailyConnections: Int,
        val dailyMessages: Long,
        val hourlyErrors: Int,
        val activeConnections: Int,
        val isRateLimited: Boolean,
        val rateLimitBackoffMs: Long
    )
}

// Removed - these are now in SharedPrefsManager.kt