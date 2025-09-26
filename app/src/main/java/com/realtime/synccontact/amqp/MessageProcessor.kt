package com.realtime.synccontact.amqp

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.realtime.synccontact.BuildConfig
import com.realtime.synccontact.data.ContactManager
import com.realtime.synccontact.data.LocalRetryQueue
import com.realtime.synccontact.utils.CrashlyticsLogger
import com.realtime.synccontact.utils.SharedPrefsManager
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MessageProcessor(
    private val context: Context,
    private val queueName: String,
    private val phoneNumber: String
) {

    private val gson = Gson()
    private val contactManager = ContactManager(context)
    private val retryQueue = LocalRetryQueue(context)
    private val sharedPrefsManager = SharedPrefsManager(context)
    private val processingTimes = ConcurrentHashMap<Long, Long>()
    private val totalProcessed = AtomicLong(0)

    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class ContactMessage(
        val firstName: String?,
        val lastName: String?,
        val phoneNumber: String,
        val id: Long
    )

    data class SyncSuccessMessage(
        val syncedId: Long,
        val deviceId: String,
        val processedAt: Long,
        val queueName: String,
        val appVersion: String
    )

    suspend fun processMessage(messageBody: String, connection: ResilientAMQPConnection): Boolean {
        val startTime = System.currentTimeMillis()

        return try {
            // Parse message
            val message = gson.fromJson(messageBody, ContactMessage::class.java)

            if (message.phoneNumber.isNullOrEmpty()) {
                CrashlyticsLogger.logCriticalError(
                    "MessageProcessor",
                    "Invalid message: phoneNumber is null or empty",
                    IllegalArgumentException("phoneNumber is required")
                )
                // ACK invalid messages to prevent queue blocking
                return true
            }

            // Track processing start time for SLA monitoring
            processingTimes[message.id] = startTime

            // Check for duplicate contact
            if (contactManager.isContactExists(message.phoneNumber)) {
                CrashlyticsLogger.logContactOperation(
                    "SKIP_DUPLICATE",
                    message.phoneNumber,
                    true,
                    "Contact already exists"
                )

                // Still send SYNC_SUCCESS for duplicate
                publishSyncSuccess(message.id, connection)
                return true
            }

            // Insert contact
            val displayName = buildDisplayName(message.firstName, message.lastName)
            val success = contactManager.insertContact(
                displayName = displayName,
                phoneNumber = message.phoneNumber,
                note = "Synced from $queueName"
            )

            if (success) {
                CrashlyticsLogger.logContactOperation(
                    "INSERT",
                    message.phoneNumber,
                    true,
                    "SyncId: ${message.id}"
                )

                // Update stats
                sharedPrefsManager.incrementTotalSynced()
                sharedPrefsManager.updateLastSyncTime()
                totalProcessed.incrementAndGet()

                // Publish SYNC_SUCCESS
                publishSyncSuccess(message.id, connection)

                // Check SLA
                checkSLA(message.id, startTime)

                true
            } else {
                CrashlyticsLogger.logContactOperation(
                    "INSERT",
                    message.phoneNumber,
                    false,
                    "Failed to insert contact"
                )
                false
            }

        } catch (e: JsonSyntaxException) {
            CrashlyticsLogger.logCriticalError(
                "MessageProcessor",
                "JSON parsing failed: ${e.message}",
                e
            )
            // ACK malformed messages to prevent queue blocking
            true
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "MessageProcessor",
                "Processing failed: ${e.message}",
                e
            )
            false
        } finally {
            val processingTime = System.currentTimeMillis() - startTime
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.DEBUG,
                "Processing",
                "Message processed in ${processingTime}ms"
            )
        }
    }

    private fun buildDisplayName(firstName: String?, lastName: String?): String {
        return when {
            firstName != null && lastName != null -> "$firstName $lastName"
            firstName != null -> firstName
            lastName != null -> lastName
            else -> "Unknown"
        }
    }

    private fun publishSyncSuccess(syncId: Long, connection: ResilientAMQPConnection) {
        processingScope.launch {
            try {
                val successMessage = SyncSuccessMessage(
                    syncedId = syncId,
                    deviceId = phoneNumber,
                    processedAt = System.currentTimeMillis(),
                    queueName = queueName,
                    appVersion = BuildConfig.VERSION_NAME
                )

                val json = gson.toJson(successMessage)

                // Try to publish directly
                var published = connection.publishToSyncSuccess(json)

                // If failed, add to retry queue
                if (!published) {
                    retryQueue.addMessage(json)
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.WARNING,
                        "SyncSuccess",
                        "Added to retry queue: SyncId $syncId"
                    )
                } else {
                    CrashlyticsLogger.logSyncSuccess(syncId, queueName, phoneNumber)
                }

                // Process any pending messages in retry queue
                processRetryQueue(connection)

            } catch (e: Exception) {
                CrashlyticsLogger.logCriticalError(
                    "PublishSuccess",
                    "Failed to handle SYNC_SUCCESS for ID $syncId",
                    e
                )
                // Add to retry queue on any error
                try {
                    val failedMessage = SyncSuccessMessage(
                        syncedId = syncId,
                        deviceId = phoneNumber,
                        processedAt = System.currentTimeMillis(),
                        queueName = queueName,
                        appVersion = BuildConfig.VERSION_NAME
                    )
                    retryQueue.addMessage(gson.toJson(failedMessage))
                } catch (e2: Exception) {
                    // Ignore retry queue errors
                }
            }
        }
    }

    private suspend fun processRetryQueue(connection: ResilientAMQPConnection) {
        if (!connection.isConnected()) {
            return
        }

        val pendingMessages = retryQueue.getPendingMessages()
        for (message in pendingMessages) {
            if (connection.publishToSyncSuccess(message.message)) {
                retryQueue.markAsProcessed(message.id)
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "RetryQueue",
                    "Successfully sent retry message ID: ${message.id}"
                )
            } else {
                // Stop processing if connection is lost
                break
            }
            delay(100) // Small delay between retries
        }
    }

    private fun checkSLA(syncId: Long, startTime: Long) {
        // Assuming message was published recently (can't know exact publish time)
        val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000

        if (elapsedSeconds > 20) {
            CrashlyticsLogger.logSLAViolation(syncId, elapsedSeconds)
        }

        // Clean up tracking
        processingTimes.remove(syncId)
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "totalProcessed" to totalProcessed.get(),
            "lastSyncTime" to sharedPrefsManager.getLastSyncTime(),
            "totalSynced" to sharedPrefsManager.getTotalSynced()
        )
    }

    fun cleanup() {
        processingScope.cancel()
        retryQueue.cleanup()
    }
}