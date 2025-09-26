package com.realtime.synccontact.amqp

import com.rabbitmq.client.*
import com.realtime.synccontact.utils.CrashlyticsLogger
import kotlinx.coroutines.*
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ResilientAMQPConnection(
    private val connectionUrl: String,
    private val queueName: String,
    private val phoneNumber: String
) {

    private var connection: Connection? = null
    private var channel: Channel? = null
    private val isConnected = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var consumerTag: String? = null

    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = Int.MAX_VALUE
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val PREFETCH_COUNT = 50
        private const val CONNECTION_TIMEOUT_MS = 10000
        private const val SOCKET_TIMEOUT_MS = 30000
        private const val HEARTBEAT_INTERVAL = 30 // Override from 10 to 30 for mobile stability
    }

    fun connect(messageHandler: suspend (String) -> Boolean): Boolean {
        return try {
            CrashlyticsLogger.logConnectionEvent("CONNECTING", queueName, "Initiating connection")

            // Parse connection URL
            val uri = URI(connectionUrl)
            val factory = ConnectionFactory().apply {
                host = uri.host
                port = if (uri.port == -1) 5672 else uri.port
                username = uri.userInfo?.split(":")?.get(0) ?: "guest"
                password = uri.userInfo?.split(":")?.get(1) ?: "guest"
                virtualHost = uri.path?.removePrefix("/") ?: "/"

                // SSL configuration
                if (uri.scheme == "amqps") {
                    useSslProtocol()
                    port = if (uri.port == -1) 5671 else uri.port
                }

                // Connection tuning for mobile networks
                connectionTimeout = CONNECTION_TIMEOUT_MS
                requestedHeartbeat = HEARTBEAT_INTERVAL
                networkRecoveryInterval = 5000
                isAutomaticRecoveryEnabled = true
                isTopologyRecoveryEnabled = true

                // Socket configuration for mobile stability
                socketConfigurator = SocketConfigurator { socket ->
                    configureTcpSocket(socket)
                }
            }

            // Establish connection
            connection = factory.newConnection("RealTimeSync-$phoneNumber")
            channel = connection?.createChannel()

            // Configure channel
            channel?.let { ch ->
                ch.basicQos(PREFETCH_COUNT)

                // Declare queue if it doesn't exist
                ch.queueDeclare(queueName, true, false, false, null)

                // Declare SYNC_SUCCESS queue
                ch.queueDeclare("SYNC_SUCCESS", true, false, false, null)

                // Set up consumer
                val consumer = object : DefaultConsumer(ch) {
                    override fun handleDelivery(
                        consumerTag: String,
                        envelope: Envelope,
                        properties: AMQP.BasicProperties,
                        body: ByteArray
                    ) {
                        val message = String(body)
                        val deliveryTag = envelope.deliveryTag

                        connectionScope.launch {
                            try {
                                val success = messageHandler(message)
                                if (success) {
                                    ch.basicAck(deliveryTag, false)
                                    CrashlyticsLogger.logMessageProcessing(
                                        queueName,
                                        0, // Will be extracted from message
                                        "ACK_SENT"
                                    )
                                } else {
                                    // Don't ACK if processing failed
                                    CrashlyticsLogger.logMessageProcessing(
                                        queueName,
                                        0,
                                        "PROCESSING_FAILED",
                                        "Message not acknowledged"
                                    )
                                }
                            } catch (e: Exception) {
                                CrashlyticsLogger.logCriticalError(
                                    "MessageProcessing",
                                    "Failed to process message: ${e.message}",
                                    e
                                )
                            }
                        }
                    }

                    override fun handleShutdownSignal(consumerTag: String, sig: ShutdownSignalException) {
                        CrashlyticsLogger.logConnectionEvent("SHUTDOWN", queueName, sig.message ?: "Unknown")
                        isConnected.set(false)
                        scheduleReconnect()
                    }

                    override fun handleCancel(consumerTag: String) {
                        CrashlyticsLogger.logConnectionEvent("CANCELLED", queueName)
                        isConnected.set(false)
                        scheduleReconnect()
                    }
                }

                // Start consuming with unique consumer tag
                consumerTag = "device_${phoneNumber}_${System.currentTimeMillis()}"
                ch.basicConsume(queueName, false, consumerTag, consumer)

                isConnected.set(true)
                reconnectAttempts.set(0)
                CrashlyticsLogger.logConnectionEvent("CONNECTED", queueName, "Successfully connected")
            }

            true
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "AMQPConnection",
                "Failed to connect to queue $queueName: ${e.message}",
                e
            )
            isConnected.set(false)
            scheduleReconnect()
            false
        }
    }

    private fun configureTcpSocket(socket: Socket) {
        try {
            // TCP Keep-Alive for mobile networks
            socket.keepAlive = true
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.tcpNoDelay = true

            // Platform-specific TCP tuning
            socket.setPerformancePreferences(0, 1, 2) // Latency over bandwidth
            socket.receiveBufferSize = 64 * 1024
            socket.sendBufferSize = 64 * 1024
        } catch (e: Exception) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "SocketConfig",
                "Failed to configure socket: ${e.message}"
            )
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
            return
        }

        connectionScope.launch {
            val attempt = reconnectAttempts.incrementAndGet()
            val delay = calculateReconnectDelay(attempt)

            CrashlyticsLogger.logRetryAttempt(
                "AMQPConnection-$queueName",
                attempt,
                MAX_RECONNECT_ATTEMPTS
            )

            delay(delay)
            reconnect()
        }
    }

    private fun calculateReconnectDelay(attempt: Int): Long {
        val exponentialDelay = INITIAL_RECONNECT_DELAY_MS * (1 shl (attempt - 1))
        return exponentialDelay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun reconnect() {
        if (isConnected.get()) {
            return
        }

        try {
            disconnect()
            // Reconnect will be handled by the service
            CrashlyticsLogger.logConnectionEvent("RECONNECT_SCHEDULED", queueName)
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "Reconnect",
                "Failed to schedule reconnect for $queueName",
                e
            )
        }
    }

    fun publishToSyncSuccess(message: String): Boolean {
        return try {
            if (!isConnected.get() || channel == null || !channel!!.isOpen) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.WARNING,
                    "PublishSync",
                    "Channel not available for SYNC_SUCCESS"
                )
                return false
            }

            channel?.basicPublish(
                "",
                "SYNC_SUCCESS",
                MessageProperties.PERSISTENT_TEXT_PLAIN,
                message.toByteArray()
            )

            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "PublishSync",
                "Published to SYNC_SUCCESS: $message"
            )
            true
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "PublishSync",
                "Failed to publish to SYNC_SUCCESS",
                e
            )
            false
        }
    }

    fun isConnected(): Boolean = isConnected.get() && channel?.isOpen == true

    fun disconnect() {
        try {
            isConnected.set(false)

            consumerTag?.let { tag ->
                try {
                    channel?.basicCancel(tag)
                } catch (e: Exception) {
                    // Ignore cancellation errors
                }
            }

            channel?.close()
            connection?.close()

            channel = null
            connection = null

            CrashlyticsLogger.logConnectionEvent("DISCONNECTED", queueName)
        } catch (e: Exception) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "Disconnect",
                "Error during disconnect: ${e.message}"
            )
        }
    }

    fun getQueueDepth(): Long {
        return try {
            channel?.messageCount(queueName)?.toLong() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun cancelScope() {
        connectionScope.cancel()
    }
}