package com.realtime.synccontact.network

import com.realtime.synccontact.utils.CrashlyticsLogger
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import com.rabbitmq.client.ShutdownSignalException
import com.rabbitmq.client.AlreadyClosedException
import com.rabbitmq.client.AuthenticationFailureException
import com.rabbitmq.client.PossibleAuthenticationFailureException

/**
 * Centralized network error handler with specific recovery strategies
 */
object NetworkErrorHandler {

    enum class ErrorType {
        NETWORK_UNAVAILABLE,
        DNS_RESOLUTION_FAILED,
        CONNECTION_TIMEOUT,
        READ_TIMEOUT,
        CONNECTION_REFUSED,
        CONNECTION_RESET,
        SSL_HANDSHAKE_FAILED,
        AUTHENTICATION_FAILED,
        QUEUE_DECLARATION_FAILED,
        CHANNEL_CLOSED,
        UNKNOWN_HOST,
        SOCKET_EXCEPTION,
        TEMPORARY_FAILURE,
        PERMANENT_FAILURE
    }

    data class ErrorAnalysis(
        val type: ErrorType,
        val isRecoverable: Boolean,
        val recommendedDelayMs: Long,
        val shouldResetConnection: Boolean,
        val shouldCheckNetwork: Boolean,
        val errorMessage: String
    )

    fun analyzeError(throwable: Throwable): ErrorAnalysis {
        val errorMsg = throwable.message?.lowercase() ?: ""

        return when {
            // Network unavailable errors
            errorMsg.contains("eai_nodata") ||
            errorMsg.contains("eai_noname") ||
            errorMsg.contains("network is unreachable") -> {
                ErrorAnalysis(
                    type = ErrorType.NETWORK_UNAVAILABLE,
                    isRecoverable = true,
                    recommendedDelayMs = 10000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = true,
                    errorMessage = "Network unavailable"
                )
            }

            // DNS resolution errors
            throwable is UnknownHostException ||
            errorMsg.contains("nodename nor servname provided") ||
            errorMsg.contains("temporary failure in name resolution") -> {
                ErrorAnalysis(
                    type = ErrorType.DNS_RESOLUTION_FAILED,
                    isRecoverable = true,
                    recommendedDelayMs = 15000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = true,
                    errorMessage = "DNS resolution failed"
                )
            }

            // Connection timeout
            throwable is SocketTimeoutException ||
            errorMsg.contains("connect timed out") ||
            errorMsg.contains("connection timed out") -> {
                ErrorAnalysis(
                    type = ErrorType.CONNECTION_TIMEOUT,
                    isRecoverable = true,
                    recommendedDelayMs = 5000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = false,
                    errorMessage = "Connection timeout"
                )
            }

            // Read timeout
            errorMsg.contains("read timed out") ||
            errorMsg.contains("socket timeout") -> {
                ErrorAnalysis(
                    type = ErrorType.READ_TIMEOUT,
                    isRecoverable = true,
                    recommendedDelayMs = 3000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = false,
                    errorMessage = "Read timeout - server may be slow"
                )
            }

            // Connection refused
            throwable is ConnectException ||
            errorMsg.contains("connection refused") ||
            errorMsg.contains("econnrefused") -> {
                ErrorAnalysis(
                    type = ErrorType.CONNECTION_REFUSED,
                    isRecoverable = true,
                    recommendedDelayMs = 30000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = false,
                    errorMessage = "Connection refused - server may be down"
                )
            }

            // Connection reset
            throwable is SocketException && errorMsg.contains("connection reset") ||
            errorMsg.contains("econnreset") ||
            errorMsg.contains("broken pipe") -> {
                ErrorAnalysis(
                    type = ErrorType.CONNECTION_RESET,
                    isRecoverable = true,
                    recommendedDelayMs = 5000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = false,
                    errorMessage = "Connection reset by server"
                )
            }

            // SSL/TLS errors
            throwable is SSLHandshakeException ||
            throwable is SSLException ||
            errorMsg.contains("ssl") ||
            errorMsg.contains("handshake") -> {
                ErrorAnalysis(
                    type = ErrorType.SSL_HANDSHAKE_FAILED,
                    isRecoverable = true,
                    recommendedDelayMs = 10000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = false,
                    errorMessage = "SSL/TLS handshake failed"
                )
            }

            // Authentication errors
            throwable is AuthenticationFailureException ||
            throwable is PossibleAuthenticationFailureException ||
            errorMsg.contains("access refused") ||
            errorMsg.contains("authentication") ||
            errorMsg.contains("403") -> {
                ErrorAnalysis(
                    type = ErrorType.AUTHENTICATION_FAILED,
                    isRecoverable = false,
                    recommendedDelayMs = 60000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = false,
                    errorMessage = "Authentication failed - check credentials"
                )
            }

            // RabbitMQ specific errors
            throwable is ShutdownSignalException -> {
                val isHardError = throwable.isHardError
                ErrorAnalysis(
                    type = if (isHardError) ErrorType.PERMANENT_FAILURE else ErrorType.CHANNEL_CLOSED,
                    isRecoverable = !isHardError,
                    recommendedDelayMs = if (isHardError) 30000 else 5000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = false,
                    errorMessage = "Channel shutdown: ${throwable.message}"
                )
            }

            throwable is AlreadyClosedException -> {
                ErrorAnalysis(
                    type = ErrorType.CHANNEL_CLOSED,
                    isRecoverable = true,
                    recommendedDelayMs = 3000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = false,
                    errorMessage = "Channel already closed"
                )
            }

            // Queue declaration errors
            errorMsg.contains("inequivalent arg") ||
            errorMsg.contains("precondition failed") -> {
                ErrorAnalysis(
                    type = ErrorType.QUEUE_DECLARATION_FAILED,
                    isRecoverable = false,
                    recommendedDelayMs = 60000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = false,
                    errorMessage = "Queue declaration failed - check queue configuration"
                )
            }

            // Generic socket errors
            throwable is SocketException -> {
                ErrorAnalysis(
                    type = ErrorType.SOCKET_EXCEPTION,
                    isRecoverable = true,
                    recommendedDelayMs = 5000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = true,
                    errorMessage = "Socket error: ${throwable.message}"
                )
            }

            // Generic IO errors
            throwable is IOException -> {
                ErrorAnalysis(
                    type = ErrorType.TEMPORARY_FAILURE,
                    isRecoverable = true,
                    recommendedDelayMs = 10000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = true,
                    errorMessage = "IO error: ${throwable.message}"
                )
            }

            // Unknown errors
            else -> {
                ErrorAnalysis(
                    type = ErrorType.TEMPORARY_FAILURE,
                    isRecoverable = true,
                    recommendedDelayMs = 15000,
                    shouldResetConnection = true,
                    shouldCheckNetwork = false,
                    errorMessage = "Unknown error: ${throwable.message}"
                )
            }
        }
    }

    fun logError(analysis: ErrorAnalysis, context: String) {
        val level = when {
            !analysis.isRecoverable -> CrashlyticsLogger.LogLevel.CRITICAL
            analysis.type == ErrorType.AUTHENTICATION_FAILED -> CrashlyticsLogger.LogLevel.ERROR
            analysis.shouldCheckNetwork -> CrashlyticsLogger.LogLevel.WARNING
            else -> CrashlyticsLogger.LogLevel.INFO
        }

        CrashlyticsLogger.log(
            level,
            "NetworkError-$context",
            "${analysis.type}: ${analysis.errorMessage} [Recoverable: ${analysis.isRecoverable}, Delay: ${analysis.recommendedDelayMs}ms]"
        )
    }

    fun shouldRetry(errorType: ErrorType, attemptNumber: Int): Boolean {
        return when (errorType) {
            ErrorType.AUTHENTICATION_FAILED,
            ErrorType.QUEUE_DECLARATION_FAILED,
            ErrorType.PERMANENT_FAILURE -> attemptNumber < 3 // Limited retries for permanent failures

            ErrorType.CONNECTION_REFUSED -> attemptNumber < 10 // More retries but not infinite

            else -> true // Infinite retries for temporary failures
        }
    }

    fun calculateBackoffDelay(baseDelayMs: Long, attemptNumber: Int): Long {
        val exponentialDelay = baseDelayMs * (1 shl minOf(attemptNumber - 1, 6)) // Cap at 2^6 = 64x
        val maxDelay = when {
            baseDelayMs < 5000 -> 30000L // 30 seconds max for short delays
            baseDelayMs < 15000 -> 60000L // 1 minute max for medium delays
            else -> 120000L // 2 minutes max for long delays
        }

        // Add jitter to prevent thundering herd
        val jitter = (Math.random() * 1000).toLong()

        return minOf(exponentialDelay + jitter, maxDelay)
    }
}