package com.realtime.synccontact.amqp

import com.rabbitmq.client.*
import com.realtime.synccontact.network.ConnectionManager
import com.realtime.synccontact.network.NetworkErrorHandler
import com.realtime.synccontact.utils.CrashlyticsLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Improved AMQP connection with comprehensive error handling and recovery
 */
class ImprovedAMQPConnection(
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

    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastSuccessfulConnection = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

    // Connection state flow for monitoring
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

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
        private const val PREFETCH_COUNT = 25
        private const val RECOVERY_INTERVAL_MS = 5000

        // Health check intervals
        private const val HEALTH_CHECK_INTERVAL_MS = 30000L
        private const val STALE_CONNECTION_THRESHOLD_MS = 120000L
    }

    fun connect(messageHandler: suspend (String) -> Boolean): Boolean {
        _connectionState.value = ConnectionState.CONNECTING

        return try {
            // Check network before attempting connection
            if (!connectionManager.isNetworkSuitableForSync()) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.WARNING,
                    "AMQPConnection",
                    "Network not suitable for connection to $queueName"
                )
                _connectionState.value = ConnectionState.ERROR
                scheduleReconnectWithBackoff()
                return false
            }

            CrashlyticsLogger.logConnectionEvent("CONNECTING", queueName, "Initiating connection")

            // Create connection with enhanced configuration
            val factory = createConnectionFactory()
            connection = establishConnection(factory)
            channel = createAndConfigureChannel(connection)

            // Setup consumer with error handling
            setupConsumer(channel!!, messageHandler)

            // Connection successful
            isConnected.set(true)
            consecutiveFailures.set(0)
            reconnectAttempts.set(0)
            lastSuccessfulConnection.set(System.currentTimeMillis())
            _connectionState.value = ConnectionState.CONNECTED
            connectionManager.resetReconnectAttempts(queueName)

            // Start health monitoring
            startHealthMonitoring()

            CrashlyticsLogger.logConnectionEvent("CONNECTED", queueName, "Successfully connected")
            true

        } catch (e: Exception) {
            handleConnectionError(e)
            false
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

            // SSL configuration
            if (uri.scheme == "amqps") {
                useSslProtocol()
                port = if (uri.port == -1) 5671 else uri.port
            }

            // Enhanced connection configuration
            connectionTimeout = CONNECTION_TIMEOUT_MS
            requestedHeartbeat = HEARTBEAT_INTERVAL
            networkRecoveryInterval = RECOVERY_INTERVAL_MS.toLong()
            isAutomaticRecoveryEnabled = true
            isTopologyRecoveryEnabled = true

            // Configure shutdown handler
            handshakeTimeout = 10000

            // Socket configuration
            socketConfigurator = SocketConfigurator { socket ->
                configureTcpSocket(socket)
            }

            // Exception handler
            exceptionHandler = object : ExceptionHandler {
                override fun handleUnexpectedConnectionDriverException(conn: Connection?, exception: Throwable?) {
                    exception?.let { handleConnectionError(it) }
                }

                override fun handleReturnListenerException(channel: Channel?, exception: Throwable?) {
                    exception?.let { handleChannelError(it) }
                }

                override fun handleConfirmListenerException(channel: Channel?, exception: Throwable?) {
                    exception?.let { handleChannelError(it) }
                }

                override fun handleBlockedListenerException(conn: Connection?, exception: Throwable?) {
                    exception?.let { handleConnectionError(it) }
                }

                override fun handleConsumerException(channel: Channel?, exception: Throwable?, consumer: Consumer?, consumerTag: String?, methodName: String?) {
                    exception?.let { handleConsumerError(it, methodName) }
                }

                override fun handleConnectionRecoveryException(conn: Connection?, exception: Throwable?) {
                    exception?.let { handleRecoveryError(it) }
                }

                override fun handleChannelRecoveryException(ch: Channel?, exception: Throwable?) {
                    exception?.let { handleRecoveryError(it) }
                }

                override fun handleTopologyRecoveryException(conn: Connection?, ch: Channel?, exception: TopologyRecoveryException?) {
                    exception?.let { handleRecoveryError(it as Throwable) }
                }
            }
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

            // Adaptive buffer sizes based on network type
            val networkState = connectionManager.networkState.value
            val bufferSize = when (networkState.networkType) {
                ConnectionManager.NetworkType.WIFI -> 128 * 1024
                ConnectionManager.NetworkType.CELLULAR_4G,
                ConnectionManager.NetworkType.CELLULAR_5G -> 64 * 1024
                else -> 32 * 1024
            }

            socket.receiveBufferSize = bufferSize
            socket.sendBufferSize = bufferSize

            // SO_LINGER to ensure graceful close
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
            // Set QoS for flow control
            basicQos(PREFETCH_COUNT)

            // Add shutdown listener
            addShutdownListener { cause ->
                handleShutdownSignal(cause)
            }

            // Add return listener for undeliverable messages
            addReturnListener { replyCode, replyText, exchange, routingKey, properties, body ->
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.WARNING,
                    "AMQPReturn",
                    "Message returned: $replyCode - $replyText"
                )
            }

            // Declare queues with error handling
            try {
                queueDeclare(queueName, true, false, false, null)
                queueDeclare("SYNC_SUCCESS", true, false, false, null)
            } catch (e: Exception) {
                throw Exception("Failed to declare queue: ${e.message}", e)
            }
        }
    }

    private fun setupConsumer(channel: Channel, messageHandler: suspend (String) -> Boolean) {
        val consumer = object : DefaultConsumer(channel) {
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
                        val success = withTimeout(60000) { // 60 second timeout for processing
                            messageHandler(message)
                        }

                        if (success) {
                            channel.basicAck(deliveryTag, false)
                            CrashlyticsLogger.logMessageProcessing(queueName, 0, "ACK_SENT")
                        } else {
                            // Requeue message for retry
                            channel.basicNack(deliveryTag, false, true)
                            CrashlyticsLogger.logMessageProcessing(queueName, 0, "NACK_REQUEUED")
                        }
                    } catch (e: TimeoutCancellationException) {
                        // Processing timeout - requeue message
                        try {
                            channel.basicNack(deliveryTag, false, true)
                        } catch (ex: Exception) {
                            CrashlyticsLogger.logCriticalError("MessageTimeout", "Failed to NACK message", ex)
                        }
                    } catch (e: Exception) {
                        CrashlyticsLogger.logCriticalError("MessageProcessing", "Failed to process message", e)
                        // Try to NACK the message
                        try {
                            channel.basicNack(deliveryTag, false, true)
                        } catch (ex: Exception) {
                            // Channel might be closed
                        }
                    }
                }
            }

            override fun handleShutdownSignal(consumerTag: String, sig: ShutdownSignalException) {
                handleShutdownSignal(sig)
            }

            override fun handleCancel(consumerTag: String) {
                CrashlyticsLogger.logConnectionEvent("CANCELLED", queueName)
                isConnected.set(false)
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnectWithBackoff()
            }

            override fun handleCancelOk(consumerTag: String) {
                CrashlyticsLogger.log(CrashlyticsLogger.LogLevel.DEBUG, "Consumer", "Cancel OK: $consumerTag")
            }
        }

        // Start consuming
        consumerTag = "device_${phoneNumber}_${System.currentTimeMillis()}"
        channel.basicConsume(queueName, false, consumerTag, consumer)
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
            if (recovery.shouldWaitForNetwork) {
                connectionScope.launch {
                    connectionManager.waitForNetwork()
                    scheduleReconnectWithDelay(recovery.delayMs)
                }
            } else {
                scheduleReconnectWithDelay(recovery.delayMs)
            }
        } else {
            CrashlyticsLogger.logCriticalError(
                "AMQPConnection",
                "Max failures reached or unrecoverable error for $queueName",
                null
            )
        }
    }

    private fun handleChannelError(throwable: Throwable) {
        CrashlyticsLogger.logCriticalError("ChannelError", "Channel error: ${throwable.message}", throwable)
        // Channel errors usually require reconnection
        handleConnectionError(throwable)
    }

    private fun handleConsumerError(throwable: Throwable, methodName: String?) {
        CrashlyticsLogger.logCriticalError("ConsumerError", "Consumer error in $methodName: ${throwable.message}", throwable)
    }

    private fun handleRecoveryError(throwable: Throwable) {
        CrashlyticsLogger.logCriticalError("RecoveryError", "Recovery failed: ${throwable.message}", throwable)
        handleConnectionError(throwable)
    }

    private fun handleShutdownSignal(sig: ShutdownSignalException) {
        val isHardError = sig.isHardError
        val message = sig.message ?: "Unknown shutdown reason"

        CrashlyticsLogger.logConnectionEvent(
            "SHUTDOWN",
            queueName,
            "Hard error: $isHardError, Reason: $message"
        )

        isConnected.set(false)
        _connectionState.value = ConnectionState.DISCONNECTED

        // Schedule reconnect unless it's an unrecoverable error
        if (!isHardError || consecutiveFailures.get() < MAX_CONSECUTIVE_FAILURES) {
            scheduleReconnectWithBackoff()
        }
    }

    private fun scheduleReconnectWithBackoff() {
        val attempts = reconnectAttempts.incrementAndGet()
        val baseDelay = 5000L
        val delay = NetworkErrorHandler.calculateBackoffDelay(baseDelay, attempts)
        scheduleReconnectWithDelay(delay)
    }

    private fun scheduleReconnectWithDelay(delayMs: Long) {
        if (_connectionState.value != ConnectionState.RECONNECTING) {
            _connectionState.value = ConnectionState.RECONNECTING

            connectionScope.launch {
                CrashlyticsLogger.logRetryAttempt(
                    "AMQPConnection-$queueName",
                    reconnectAttempts.get(),
                    MAX_CONSECUTIVE_FAILURES
                )

                delay(delayMs)

                // Clean up existing connection
                disconnect()

                // Signal for reconnection
                CrashlyticsLogger.logConnectionEvent("RECONNECT_SCHEDULED", queueName)
            }
        }
    }

    private fun startHealthMonitoring() {
        connectionScope.launch {
            while (isConnected.get()) {
                delay(HEALTH_CHECK_INTERVAL_MS)

                try {
                    // Check if connection is stale
                    val timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessfulConnection.get()
                    if (timeSinceLastSuccess > STALE_CONNECTION_THRESHOLD_MS) {
                        CrashlyticsLogger.log(
                            CrashlyticsLogger.LogLevel.WARNING,
                            "HealthCheck",
                            "Connection appears stale for $queueName"
                        )

                        // Try to verify connection
                        if (channel?.isOpen != true || connection?.isOpen != true) {
                            handleConnectionError(Exception("Connection health check failed"))
                        }
                    }

                    // Check queue depth
                    val queueDepth = getQueueDepth()
                    if (queueDepth > 1000) {
                        CrashlyticsLogger.log(
                            CrashlyticsLogger.LogLevel.WARNING,
                            "HealthCheck",
                            "High queue depth for $queueName: $queueDepth"
                        )
                    }

                } catch (e: Exception) {
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.DEBUG,
                        "HealthCheck",
                        "Health check error: ${e.message}"
                    )
                }
            }
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

            lastSuccessfulConnection.set(System.currentTimeMillis())

            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "PublishSync",
                "Published to SYNC_SUCCESS: $message"
            )
            true
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError("PublishSync", "Failed to publish to SYNC_SUCCESS", e)
            handleConnectionError(e)
            false
        }
    }

    fun isConnected(): Boolean = isConnected.get() && channel?.isOpen == true

    fun getQueueDepth(): Long {
        return try {
            channel?.messageCount(queueName)?.toLong() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun disconnect() {
        try {
            isConnected.set(false)
            _connectionState.value = ConnectionState.DISCONNECTED

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

    fun cancelScope() {
        connectionScope.cancel()
    }

    fun getConnectionStats(): Map<String, Any> {
        return mapOf(
            "isConnected" to isConnected(),
            "consecutiveFailures" to consecutiveFailures.get(),
            "reconnectAttempts" to reconnectAttempts.get(),
            "queueDepth" to getQueueDepth(),
            "connectionState" to _connectionState.value,
            "lastSuccessMs" to (System.currentTimeMillis() - lastSuccessfulConnection.get())
        )
    }
}