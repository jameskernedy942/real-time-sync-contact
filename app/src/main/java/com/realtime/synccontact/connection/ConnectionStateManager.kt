package com.realtime.synccontact.connection

import com.realtime.synccontact.utils.CrashlyticsLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages connection states and prevents duplicate connections
 * Ensures thread-safety and prevents race conditions
 */
class ConnectionStateManager {

    enum class ConnectionSetupState {
        IDLE,           // No connection activity
        SETTING_UP,     // Currently setting up connection
        CONNECTED,      // Successfully connected
        CLEANING_UP,    // Currently cleaning up connection
        ERROR          // Error state
    }

    data class ConnectionInfo(
        val phoneNumber: String,
        val queueName: String,
        val state: ConnectionSetupState,
        val lastStateChange: Long = System.currentTimeMillis(),
        val connectionId: String = "${phoneNumber}_${System.currentTimeMillis()}"
    )

    // Track connection states for each phone number
    private val connectionStates = ConcurrentHashMap<String, AtomicReference<ConnectionInfo>>()

    // Mutex for each connection to prevent concurrent operations
    private val connectionMutexes = ConcurrentHashMap<String, Mutex>()

    // Global mutex for connection setup operations
    private val globalSetupMutex = Mutex()

    // Track active connection IDs to prevent duplicates
    private val activeConnectionIds = ConcurrentHashMap<String, String>()

    /**
     * Attempts to start connection setup for a phone number
     * Returns true if setup can proceed, false if already in progress or connected
     */
    suspend fun tryStartConnectionSetup(
        connectionKey: String,
        phoneNumber: String,
        queueName: String
    ): Boolean = globalSetupMutex.withLock {
        val mutex = connectionMutexes.getOrPut(connectionKey) { Mutex() }

        mutex.withLock {
            val currentState = connectionStates[connectionKey]?.get()

            // Check if we can proceed with setup
            val canProceed = when (currentState?.state) {
                null, ConnectionSetupState.IDLE, ConnectionSetupState.ERROR -> true
                ConnectionSetupState.CLEANING_UP -> {
                    // Wait for cleanup to complete
                    CrashlyticsLogger.log(
                        CrashlyticsLogger.LogLevel.INFO,
                        "ConnectionStateManager",
                        "Connection $connectionKey is cleaning up, cannot start new setup"
                    )
                    false
                }
                ConnectionSetupState.SETTING_UP -> {
                    // Already setting up, check if it's the same phone number
                    if (currentState.phoneNumber == phoneNumber) {
                        CrashlyticsLogger.log(
                            CrashlyticsLogger.LogLevel.INFO,
                            "ConnectionStateManager",
                            "Connection $connectionKey already setting up for same phone: $phoneNumber"
                        )
                        false
                    } else {
                        // Different phone number, need to wait
                        CrashlyticsLogger.log(
                            CrashlyticsLogger.LogLevel.WARNING,
                            "ConnectionStateManager",
                            "Connection $connectionKey setting up for different phone. Current: ${currentState.phoneNumber}, Requested: $phoneNumber"
                        )
                        false
                    }
                }
                ConnectionSetupState.CONNECTED -> {
                    // Already connected, check if it's the same phone number
                    if (currentState.phoneNumber == phoneNumber) {
                        CrashlyticsLogger.log(
                            CrashlyticsLogger.LogLevel.INFO,
                            "ConnectionStateManager",
                            "Connection $connectionKey already connected for phone: $phoneNumber"
                        )
                        false
                    } else {
                        // Different phone number, need cleanup first
                        CrashlyticsLogger.log(
                            CrashlyticsLogger.LogLevel.INFO,
                            "ConnectionStateManager",
                            "Connection $connectionKey connected to different phone. Need cleanup first"
                        )
                        false
                    }
                }
            }

            if (canProceed) {
                // Update state to SETTING_UP
                val newInfo = ConnectionInfo(
                    phoneNumber = phoneNumber,
                    queueName = queueName,
                    state = ConnectionSetupState.SETTING_UP
                )
                connectionStates[connectionKey] = AtomicReference(newInfo)
                activeConnectionIds[connectionKey] = newInfo.connectionId

                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "ConnectionStateManager",
                    "Started connection setup for $connectionKey with phone: $phoneNumber"
                )
            }

            canProceed
        }
    }

    /**
     * Marks a connection as successfully established
     */
    suspend fun markConnectionEstablished(connectionKey: String) {
        val mutex = connectionMutexes[connectionKey] ?: return

        mutex.withLock {
            val currentState = connectionStates[connectionKey]?.get() ?: return

            if (currentState.state == ConnectionSetupState.SETTING_UP) {
                val updatedInfo = currentState.copy(
                    state = ConnectionSetupState.CONNECTED,
                    lastStateChange = System.currentTimeMillis()
                )
                connectionStates[connectionKey]?.set(updatedInfo)

                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "ConnectionStateManager",
                    "Connection $connectionKey established successfully"
                )
            }
        }
    }

    /**
     * Marks a connection as failed
     */
    suspend fun markConnectionFailed(connectionKey: String, error: Throwable? = null) {
        val mutex = connectionMutexes[connectionKey] ?: return

        mutex.withLock {
            val currentState = connectionStates[connectionKey]?.get() ?: return

            val updatedInfo = currentState.copy(
                state = ConnectionSetupState.ERROR,
                lastStateChange = System.currentTimeMillis()
            )
            connectionStates[connectionKey]?.set(updatedInfo)

            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.ERROR,
                "ConnectionStateManager",
                "Connection $connectionKey failed: ${error?.message}"
            )
        }
    }

    /**
     * Starts cleanup process for a connection
     */
    suspend fun startCleanup(connectionKey: String): Boolean {
        val mutex = connectionMutexes[connectionKey] ?: return false

        return mutex.withLock {
            val currentState = connectionStates[connectionKey]?.get()

            val canCleanup = currentState?.state != ConnectionSetupState.CLEANING_UP

            if (canCleanup) {
                val updatedInfo = currentState?.copy(
                    state = ConnectionSetupState.CLEANING_UP,
                    lastStateChange = System.currentTimeMillis()
                ) ?: ConnectionInfo(
                    phoneNumber = "",
                    queueName = "",
                    state = ConnectionSetupState.CLEANING_UP
                )
                connectionStates[connectionKey]?.set(updatedInfo)

                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "ConnectionStateManager",
                    "Started cleanup for connection $connectionKey"
                )
            }

            canCleanup
        }
    }

    /**
     * Marks cleanup as complete
     */
    suspend fun markCleanupComplete(connectionKey: String) {
        val mutex = connectionMutexes[connectionKey] ?: return

        mutex.withLock {
            connectionStates.remove(connectionKey)
            activeConnectionIds.remove(connectionKey)

            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "ConnectionStateManager",
                "Cleanup complete for connection $connectionKey"
            )
        }
    }

    /**
     * Gets the current state of a connection
     */
    fun getConnectionState(connectionKey: String): ConnectionInfo? {
        return connectionStates[connectionKey]?.get()
    }

    /**
     * Checks if a connection is active (connected or setting up)
     */
    fun isConnectionActive(connectionKey: String): Boolean {
        val state = connectionStates[connectionKey]?.get()?.state
        return state == ConnectionSetupState.CONNECTED ||
               state == ConnectionSetupState.SETTING_UP
    }

    /**
     * Gets all active connections
     */
    fun getAllActiveConnections(): Map<String, ConnectionInfo> {
        return connectionStates
            .mapValues { it.value.get() }
            .filter {
                it.value.state == ConnectionSetupState.CONNECTED ||
                it.value.state == ConnectionSetupState.SETTING_UP
            }
    }

    /**
     * Checks if any connection operation is in progress
     */
    fun isAnyOperationInProgress(): Boolean {
        return connectionStates.any {
            val state = it.value.get().state
            state == ConnectionSetupState.SETTING_UP ||
            state == ConnectionSetupState.CLEANING_UP
        }
    }

    /**
     * Force cleanup all connections (used during service shutdown)
     */
    suspend fun cleanupAll() = globalSetupMutex.withLock {
        CrashlyticsLogger.log(
            CrashlyticsLogger.LogLevel.INFO,
            "ConnectionStateManager",
            "Starting cleanup of all ${connectionStates.size} connections"
        )

        connectionStates.clear()
        activeConnectionIds.clear()

        // Note: We don't clear mutexes as they might be in use
    }

    /**
     * Get statistics about connections
     */
    fun getStatistics(): Map<String, Any> {
        val states = connectionStates.mapValues { it.value.get() }

        return mapOf(
            "total_connections" to states.size,
            "connected" to states.count { it.value.state == ConnectionSetupState.CONNECTED },
            "setting_up" to states.count { it.value.state == ConnectionSetupState.SETTING_UP },
            "cleaning_up" to states.count { it.value.state == ConnectionSetupState.CLEANING_UP },
            "error" to states.count { it.value.state == ConnectionSetupState.ERROR },
            "idle" to states.count { it.value.state == ConnectionSetupState.IDLE },
            "active_connection_ids" to activeConnectionIds.size
        )
    }
}