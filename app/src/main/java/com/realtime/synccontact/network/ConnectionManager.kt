package com.realtime.synccontact.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.realtime.synccontact.utils.CrashlyticsLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Advanced connection manager with network monitoring and intelligent reconnection
 */
class ConnectionManager(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState

    private val reconnectAttempts = mutableMapOf<String, AtomicInteger>()
    private val lastReconnectTime = mutableMapOf<String, Long>()
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class NetworkState(
        val isConnected: Boolean = false,
        val networkType: NetworkType = NetworkType.NONE,
        val signalStrength: SignalStrength = SignalStrength.UNKNOWN,
        val isMetered: Boolean = false
    )

    enum class NetworkType {
        WIFI,
        CELLULAR_5G,
        CELLULAR_4G,
        CELLULAR_3G,
        CELLULAR_2G,
        ETHERNET,
        VPN,
        NONE
    }

    enum class SignalStrength {
        EXCELLENT,
        GOOD,
        FAIR,
        POOR,
        UNKNOWN
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkState()
            CrashlyticsLogger.logNetworkChange("Available", true)
        }

        override fun onLost(network: Network) {
            updateNetworkState()
            CrashlyticsLogger.logNetworkChange("Lost", false)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetworkState()
        }
    }

    init {
        registerNetworkCallback()
        updateNetworkState()
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }
    }

    private fun updateNetworkState() {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        val newState = if (capabilities != null) {
            NetworkState(
                isConnected = true,
                networkType = detectNetworkType(capabilities),
                signalStrength = detectSignalStrength(capabilities),
                isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            )
        } else {
            NetworkState()
        }

        _networkState.value = newState

        // Log network change
        if (newState.isConnected) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "NetworkState",
                "Connected: ${newState.networkType}, Metered: ${newState.isMetered}, Signal: ${newState.signalStrength}"
            )
        }
    }

    private fun detectNetworkType(capabilities: NetworkCapabilities): NetworkType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                // Try to detect cellular generation
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    when {
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED) -> NetworkType.CELLULAR_4G
                        else -> NetworkType.CELLULAR_3G
                    }
                } else {
                    NetworkType.CELLULAR_4G // Default to 4G for older devices
                }
            }
            else -> NetworkType.NONE
        }
    }

    private fun detectSignalStrength(capabilities: NetworkCapabilities): SignalStrength {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val signalStrength = capabilities.signalStrength
            when {
                signalStrength >= -50 -> SignalStrength.EXCELLENT
                signalStrength >= -70 -> SignalStrength.GOOD
                signalStrength >= -85 -> SignalStrength.FAIR
                signalStrength >= -100 -> SignalStrength.POOR
                else -> SignalStrength.UNKNOWN
            }
        } else {
            SignalStrength.UNKNOWN
        }
    }

    fun isNetworkSuitableForSync(): Boolean {
        val state = _networkState.value
        return state.isConnected &&
               (state.networkType != NetworkType.CELLULAR_2G) && // Avoid 2G
               (state.signalStrength != SignalStrength.POOR) // Avoid poor signal
    }

    suspend fun waitForNetwork(timeoutMs: Long = 30000): Boolean {
        if (_networkState.value.isConnected) return true

        return withTimeoutOrNull(timeoutMs) {
            while (!_networkState.value.isConnected) {
                delay(1000)
            }
            true
        } ?: false
    }

    fun shouldReconnect(connectionId: String, errorAnalysis: NetworkErrorHandler.ErrorAnalysis): Boolean {
        val attempts = reconnectAttempts.getOrPut(connectionId) { AtomicInteger(0) }
        val lastTime = lastReconnectTime[connectionId] ?: 0L
        val currentTime = System.currentTimeMillis()

        // Reset attempt counter if enough time has passed (5 minutes)
        if (currentTime - lastTime > 300000) {
            attempts.set(0)
        }

        val attemptNumber = attempts.incrementAndGet()
        lastReconnectTime[connectionId] = currentTime

        // Check if we should retry based on error type and attempts
        if (!NetworkErrorHandler.shouldRetry(errorAnalysis.type, attemptNumber)) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.ERROR,
                "ConnectionManager",
                "Max retries reached for $connectionId, error type: ${errorAnalysis.type}"
            )
            return false
        }

        // Check network state for network-related errors
        if (errorAnalysis.shouldCheckNetwork && !_networkState.value.isConnected) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "ConnectionManager",
                "Waiting for network before reconnecting $connectionId"
            )
            connectionScope.launch {
                waitForNetwork()
            }
            return false
        }

        return true
    }

    fun getReconnectDelay(connectionId: String, baseDelayMs: Long): Long {
        val attempts = reconnectAttempts[connectionId]?.get() ?: 1
        return NetworkErrorHandler.calculateBackoffDelay(baseDelayMs, attempts)
    }

    fun resetReconnectAttempts(connectionId: String) {
        reconnectAttempts[connectionId]?.set(0)
        lastReconnectTime.remove(connectionId)
    }

    fun handleConnectionError(connectionId: String, throwable: Throwable): ConnectionRecoveryStrategy {
        val errorAnalysis = NetworkErrorHandler.analyzeError(throwable)
        NetworkErrorHandler.logError(errorAnalysis, connectionId)

        val shouldReconnect = shouldReconnect(connectionId, errorAnalysis)
        val delay = if (shouldReconnect) {
            getReconnectDelay(connectionId, errorAnalysis.recommendedDelayMs)
        } else {
            errorAnalysis.recommendedDelayMs
        }

        return ConnectionRecoveryStrategy(
            shouldReconnect = shouldReconnect,
            delayMs = delay,
            shouldResetConnection = errorAnalysis.shouldResetConnection,
            shouldWaitForNetwork = errorAnalysis.shouldCheckNetwork,
            errorMessage = errorAnalysis.errorMessage
        )
    }

    data class ConnectionRecoveryStrategy(
        val shouldReconnect: Boolean,
        val delayMs: Long,
        val shouldResetConnection: Boolean,
        val shouldWaitForNetwork: Boolean,
        val errorMessage: String
    )

    fun cleanup() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            connectionScope.cancel()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}