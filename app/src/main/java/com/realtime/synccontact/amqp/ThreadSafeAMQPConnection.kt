package com.realtime.synccontact.amqp

import com.rabbitmq.client.*
import com.realtime.synccontact.network.ConnectionManager
import com.realtime.synccontact.network.NetworkErrorHandler
import com.realtime.synccontact.utils.CrashlyticsLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as KotlinChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe AMQP connection that prevents deadlocks
 * Fixes art::ConditionVariable::WaitHoldingLocks error
 */
class ThreadSafeAMQPConnection(
    private val connectionUrl: String,
    private val queueName: String,
    private val phoneNumber: String,
    private val connectionManager: ConnectionManager
) {
    private var connection: Connection? = null
    private var channel: Channel? = null
    private val isConnected = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var consumerTag: String? = null

    // Use a dedicated single-thread executor for RabbitMQ operations
    // This prevents deadlocks by ensuring all RabbitMQ operations happen on the same thread
    private val rabbitMQExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RabbitMQ-$queueName").apply {
            isDaemon = true
        }
    }

    // Coroutine dispatcher for RabbitMQ operations
    private val rabbitMQDispatcher = rabbitMQExecutor.asCoroutineDispatcher()

    // Separate scope for non-blocking operations
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Kotlin Channel for thread-safe message passing
    private val messageChannel = KotlinChannel<MessageData>(capacity = 100)
    private val ackChannel = KotlinChannel<AckData>(capacity = 100)
    private val publishChannel = KotlinChannel<PublishData>(capacity = 50)

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val lastSuccessfulConnection = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

    data class MessageData(val body: ByteArray, val deliveryTag: Long, val envelope: Envelope)
    data class AckData(val deliveryTag: Long, val success: Boolean)
    data class PublishData(val message: String, val resultChannel: KotlinChannel<Boolean>)

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 100
        private const val CONNECTION_TIMEOUT_MS = 20000
        private const val SOCKET_TIMEOUT_MS = 30000
        private const val HEARTBEAT_INTERVAL = 30
        private const val PREFETCH_COUNT = 10 // Reduced to prevent overload
        private const val RECOVERY_INTERVAL_MS = 5000
    }

    suspend fun connect(messageHandler: suspend (String) -> Boolean): Boolean {
        _connectionState.value = ConnectionState.CONNECTING

        return withContext(rabbitMQDispatcher) {
            try {
                // Check network before attempting connection
                if (!connectionManager.isNetworkSuitableForSync()) {
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.WARNING,
                        "AMQPConnection",
                        "Network not suitable for connection to $queueName"
                    )
                    _connectionState.value = ConnectionState.ERROR
                    scheduleReconnect()
                    return@withContext false
                }

                CrashlyticsLogger.logConnectionEvent("CONNECTING", queueName, "Initiating connection")

                // Create connection
                val factory = createConnectionFactory()
                connection = establishConnection(factory)
                channel = createAndConfigureChannel(connection)

                // Start message processing coroutines
                startMessageProcessor(messageHandler)
                startAckProcessor()
                startPublishProcessor()

                // Setup consumer
                setupConsumer(channel!!)

                // Connection successful
                isConnected.set(true)
                consecutiveFailures.set(0)
                reconnectAttempts.set(0)
                lastSuccessfulConnection.set(System.currentTimeMillis())
                _connectionState.value = ConnectionState.CONNECTED
                connectionManager.resetReconnectAttempts(queueName)

                CrashlyticsLogger.logConnectionEvent("CONNECTED", queueName, "Successfully connected")
                true

            } catch (e: Exception) {
                handleConnectionError(e)
                false
            }
        }
    }

    private fun createConnectionFactory(): ConnectionFactory {
        val uri = URI(connectionUrl)
        return ConnectionFactory().apply {
            host = uri.host
            port = if (uri.port == -1) 5672 else uri.port
            username = uri.userInfo?.split(":")?.get(0) ?: "guest"
            password = uri.userInfo?.split(":")?.get(1) ?: "guest"
            virtualHost = uri.path?.removePrefix("/") ?: "/"

            if (uri.scheme == "amqps") {
                useSslProtocol()
                port = if (uri.port == -1) 5671 else uri.port
            }

            connectionTimeout = CONNECTION_TIMEOUT_MS
            requestedHeartbeat = HEARTBEAT_INTERVAL
            networkRecoveryInterval = RECOVERY_INTERVAL_MS.toLong()
            isAutomaticRecoveryEnabled = false // We handle recovery manually
            isTopologyRecoveryEnabled = false

            socketConfigurator = SocketConfigurator { socket ->
                configureTcpSocket(socket)
            }
        }
    }

    private fun configureTcpSocket(socket: Socket) {
        try {
            socket.keepAlive = true
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.tcpNoDelay = true
            socket.setPerformancePreferences(0, 1, 2)

            val networkState = connectionManager.networkState.value
            val bufferSize = when (networkState.networkType) {
                ConnectionManager.NetworkType.WIFI -> 64 * 1024
                ConnectionManager.NetworkType.CELLULAR_4G,
                ConnectionManager.NetworkType.CELLULAR_5G -> 32 * 1024
                else -> 16 * 1024
            }

            socket.receiveBufferSize = bufferSize
            socket.sendBufferSize = bufferSize
            socket.setSoLinger(true, 5)

        } catch (e: Exception) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "SocketConfig",
                "Failed to configure socket: ${e.message}"
            )
        }
    }

    private fun establishConnection(factory: ConnectionFactory): Connection {
        val connectionName = "RealTimeSync-$phoneNumber-${System.currentTimeMillis()}"
        return factory.newConnection(connectionName)
    }

    private fun createAndConfigureChannel(connection: Connection?): Channel? {
        return connection?.createChannel()?.apply {
            basicQos(PREFETCH_COUNT)

            addShutdownListener { cause ->
                connectionScope.launch {
                    handleShutdownSignal(cause)
                }
            }

            // Declare queues
            queueDeclare(queueName, true, false, false, null)
            queueDeclare("SYNC_SUCCESS", true, false, false, null)
        }
    }

    private fun setupConsumer(channel: Channel) {
        val consumer = object : DefaultConsumer(channel) {
            override fun handleDelivery(
                consumerTag: String,
                envelope: Envelope,
                properties: AMQP.BasicProperties,
                body: ByteArray
            ) {
                // Send message to channel for processing
                // This is non-blocking and thread-safe
                connectionScope.launch {
                    messageChannel.send(
                        MessageData(body, envelope.deliveryTag, envelope)
                    )
                }
            }

            override fun handleShutdownSignal(consumerTag: String, sig: ShutdownSignalException) {
                connectionScope.launch {
                    handleShutdownSignal(sig)
                }
            }

            override fun handleCancel(consumerTag: String) {
                CrashlyticsLogger.logConnectionEvent("CANCELLED", queueName)
                isConnected.set(false)
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        }

        consumerTag = "device_${phoneNumber}_${System.currentTimeMillis()}"
        channel.basicConsume(queueName, false, consumerTag, consumer)
    }

    private fun startMessageProcessor(messageHandler: suspend (String) -> Boolean) {
        connectionScope.launch {
            for (messageData in messageChannel) {
                try {
                    val message = String(messageData.body)

                    // Process message with timeout
                    val success = withTimeoutOrNull(60000) {
                        messageHandler(message)
                    } ?: false

                    // Send ACK/NACK through channel
                    ackChannel.send(AckData(messageData.deliveryTag, success))

                } catch (e: Exception) {
                    CrashlyticsLogger.logCriticalError(
                        "MessageProcessing",
                        "Failed to process message",
                        e
                    )
                    // NACK on error
                    ackChannel.send(AckData(messageData.deliveryTag, false))
                }
            }
        }
    }

    private fun startAckProcessor() {
        connectionScope.launch {
            for (ackData in ackChannel) {
                withContext(rabbitMQDispatcher) {
                    try {
                        channel?.let { ch ->
                            if (ch.isOpen) {
                                if (ackData.success) {
                                    ch.basicAck(ackData.deliveryTag, false)
                                    CrashlyticsLogger.logMessageProcessing(
                                        queueName, 0, "ACK_SENT"
                                    )
                                } else {
                                    ch.basicNack(ackData.deliveryTag, false, true)
                                    CrashlyticsLogger.logMessageProcessing(
                                        queueName, 0, "NACK_REQUEUED"
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        CrashlyticsLogger.logCriticalError(
                            "AckProcessor",
                            "Failed to ACK/NACK message",
                            e
                        )
                    }
                }
            }
        }
    }

    private fun startPublishProcessor() {
        connectionScope.launch {
            for (publishData in publishChannel) {
                withContext(rabbitMQDispatcher) {
                    val success = try {
                        channel?.let { ch ->
                            if (ch.isOpen) {
                                ch.basicPublish(
                                    "",
                                    "SYNC_SUCCESS",
                                    MessageProperties.PERSISTENT_TEXT_PLAIN,
                                    publishData.message.toByteArray()
                                )
                                lastSuccessfulConnection.set(System.currentTimeMillis())
                                true
                            } else false
                        } ?: false
                    } catch (e: Exception) {
                        CrashlyticsLogger.logCriticalError(
                            "PublishProcessor",
                            "Failed to publish message",
                            e
                        )
                        false
                    }
                    publishData.resultChannel.send(success)
                }
            }
        }
    }

    suspend fun publishToSyncSuccess(message: String): Boolean {
        return try {
            if (!isConnected.get()) {
                return false
            }

            val resultChannel = KotlinChannel<Boolean>(1)
            publishChannel.send(PublishData(message, resultChannel))

            // Wait for result with timeout
            withTimeoutOrNull(5000) {
                resultChannel.receive()
            } ?: false

        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "PublishSync",
                "Failed to publish to SYNC_SUCCESS",
                e
            )
            false
        }
    }

    private suspend fun handleShutdownSignal(sig: ShutdownSignalException) {
        val isHardError = sig.isHardError
        val message = sig.message ?: "Unknown shutdown reason"

        CrashlyticsLogger.logConnectionEvent(
            "SHUTDOWN",
            queueName,
            "Hard error: $isHardError, Reason: $message"
        )

        isConnected.set(false)
        _connectionState.value = ConnectionState.DISCONNECTED

        if (!isHardError || consecutiveFailures.get() < MAX_CONSECUTIVE_FAILURES) {
            scheduleReconnect()
        }
    }

    private fun handleConnectionError(throwable: Throwable) {
        val recovery = connectionManager.handleConnectionError(queueName, throwable)
        consecutiveFailures.incrementAndGet()

        CrashlyticsLogger.logCriticalError(
            "AMQPConnection",
            "Connection error for $queueName: ${recovery.errorMessage}",
            throwable
        )

        isConnected.set(false)
        _connectionState.value = ConnectionState.ERROR

        if (recovery.shouldReconnect && consecutiveFailures.get() < MAX_CONSECUTIVE_FAILURES) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        _connectionState.value = ConnectionState.RECONNECTING
        connectionScope.launch {
            val attempts = reconnectAttempts.incrementAndGet()
            val delay = NetworkErrorHandler.calculateBackoffDelay(5000L, attempts)

            CrashlyticsLogger.logRetryAttempt(
                "AMQPConnection-$queueName",
                attempts,
                MAX_CONSECUTIVE_FAILURES
            )

            delay(delay)
            disconnect()
            CrashlyticsLogger.logConnectionEvent("RECONNECT_SCHEDULED", queueName)
        }
    }

    fun isConnected(): Boolean = isConnected.get() && channel?.isOpen == true

    fun disconnect() {
        try {
            isConnected.set(false)
            _connectionState.value = ConnectionState.DISCONNECTED

            // Close channels first
            messageChannel.close()
            ackChannel.close()
            publishChannel.close()

            // Cancel consumer on RabbitMQ thread
            rabbitMQExecutor.submit {
                consumerTag?.let { tag ->
                    try {
                        channel?.basicCancel(tag)
                    } catch (e: Exception) {
                        // Ignore
                    }
                }

                channel?.close()
                connection?.close()
            }.get(5, TimeUnit.SECONDS) // Wait max 5 seconds

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

    fun cleanup() {
        synchronized(this) {
            // Cancel all coroutines
            connectionScope.cancel()

            // Shutdown executor properly
            rabbitMQExecutor.shutdown()
            try {
                if (!rabbitMQExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    rabbitMQExecutor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                rabbitMQExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }

            // Close dispatcher
            rabbitMQDispatcher.close()
        }
    }

    suspend fun getQueueDepth(): Long {
        return withContext(rabbitMQDispatcher) {
            try {
                channel?.messageCount(queueName)?.toLong() ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }

    fun getConnectionStats(): Map<String, Any> {
        return mapOf(
            "isConnected" to isConnected.get(),
            "consecutiveFailures" to consecutiveFailures.get(),
            "reconnectAttempts" to reconnectAttempts.get(),
            "queueDepth" to 0L, // Queue depth needs to be fetched asynchronously
            "connectionState" to _connectionState.value,
            "lastSuccessMs" to (System.currentTimeMillis() - lastSuccessfulConnection.get())
        )
    }
}