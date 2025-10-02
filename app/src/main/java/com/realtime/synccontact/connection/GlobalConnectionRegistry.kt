package com.realtime.synccontact.connection

import com.realtime.synccontact.utils.CrashlyticsLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Global singleton registry to track ALL active connections
 * Prevents duplicate connections across the entire app
 */
object GlobalConnectionRegistry {

    // Map of queue name to active connection count
    private val activeConnections = ConcurrentHashMap<String, AtomicInteger>()

    // Map of queue name to last connection timestamp
    private val lastConnectionTime = ConcurrentHashMap<String, Long>()

    // Minimum time between connection attempts (milliseconds)
    private const val MIN_CONNECTION_INTERVAL = 5000L

    /**
     * Try to register a new connection
     * Returns true if allowed, false if duplicate or too soon
     */
    fun tryRegisterConnection(queueName: String): Boolean {
        synchronized(this) {
            val currentTime = System.currentTimeMillis()
            val lastTime = lastConnectionTime[queueName] ?: 0L

            // Check if too soon since last connection
            if (currentTime - lastTime < MIN_CONNECTION_INTERVAL) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.WARNING,
                    "GlobalConnectionRegistry",
                    "Connection attempt for $queueName rejected - too soon (${currentTime - lastTime}ms since last attempt)"
                )
                return false
            }

            // Check current active connections
            val currentCount = activeConnections.getOrPut(queueName) { AtomicInteger(0) }
            val count = currentCount.get()

            if (count > 0) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.WARNING,
                    "GlobalConnectionRegistry",
                    "Connection attempt for $queueName rejected - already $count active connection(s)"
                )
                return false
            }

            // Register the connection
            currentCount.incrementAndGet()
            lastConnectionTime[queueName] = currentTime

            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "GlobalConnectionRegistry",
                "Connection registered for $queueName (total active: ${currentCount.get()})"
            )

            return true
        }
    }

    /**
     * Unregister a connection when it's closed
     */
    fun unregisterConnection(queueName: String) {
        synchronized(this) {
            val currentCount = activeConnections[queueName]
            if (currentCount != null) {
                val newCount = currentCount.decrementAndGet()
                if (newCount <= 0) {
                    activeConnections.remove(queueName)
                }

                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "GlobalConnectionRegistry",
                    "Connection unregistered for $queueName (remaining: $newCount)"
                )
            }
        }
    }

    /**
     * Check if a connection is already active
     */
    fun isConnectionActive(queueName: String): Boolean {
        val count = activeConnections[queueName]?.get() ?: 0
        return count > 0
    }

    /**
     * Get statistics about all connections
     */
    fun getStatistics(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        stats["total_queues"] = activeConnections.size
        stats["total_connections"] = activeConnections.values.sumOf { it.get() }

        activeConnections.forEach { (queue, count) ->
            stats["queue_$queue"] = count.get()
        }

        return stats
    }

    /**
     * Clear all registrations (use with caution)
     */
    fun clearAll() {
        synchronized(this) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "GlobalConnectionRegistry",
                "Clearing all ${activeConnections.size} connection registrations"
            )
            activeConnections.clear()
            lastConnectionTime.clear()
        }
    }
}