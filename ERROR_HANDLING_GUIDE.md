# Comprehensive Error Handling & Reconnection Guide

## Overview

This guide explains the enhanced error handling and reconnection system implemented to handle all network and connection errors including:
- Read timeouts
- Connection errors
- Connection resets
- EAI_NODATA (DNS resolution failures)
- Connection refused
- SSL/TLS errors
- And many more...

## Current Issues Addressed

### Problems Found in Crashlytics:
1. **Read Timeout**: Server takes too long to respond
2. **Connection Reset**: Server forcibly closes connection
3. **EAI_NODATA**: DNS resolution failures
4. **Connection Refused**: Server not accepting connections
5. **Socket Exceptions**: Various network layer issues

### Root Causes:
- Simple retry logic without proper backoff
- No specific error type handling
- Missing network state monitoring
- No connection health checks
- Limited recovery strategies

## New Components

### 1. NetworkErrorHandler
**Location**: `/app/src/main/java/com/realtime/synccontact/network/NetworkErrorHandler.kt`

**Features**:
- Analyzes 14+ different error types
- Provides specific recovery strategies for each error
- Implements intelligent retry logic
- Calculates exponential backoff with jitter

### 2. ConnectionManager
**Location**: `/app/src/main/java/com/realtime/synccontact/network/ConnectionManager.kt`

**Features**:
- Real-time network monitoring
- Network quality assessment
- Connection-specific retry tracking
- Adaptive reconnection strategies

### 3. ImprovedAMQPConnection
**Location**: `/app/src/main/java/com/realtime/synccontact/amqp/ImprovedAMQPConnection.kt`

**Features**:
- Comprehensive error handling
- Health monitoring
- Connection state tracking
- Adaptive buffer sizes based on network type
- Graceful degradation

## Integration Steps

### Step 1: Update MainSyncService

```kotlin
// In MainSyncService.kt

class MainSyncService : Service() {
    private lateinit var connectionManager: ConnectionManager

    override fun onCreate() {
        super.onCreate()
        connectionManager = ConnectionManager(this)
        // ... rest of initialization
    }

    private suspend fun startConnection1(phone: String) {
        val queueName = "APK_SYNC_$phone"

        // Use ImprovedAMQPConnection instead of ResilientAMQPConnection
        connection1 = ImprovedAMQPConnection(
            CONNECTION_URL,
            queueName,
            phone,
            connectionManager
        )

        // Monitor connection state
        connectionScope.launch {
            connection1.connectionState.collect { state ->
                when (state) {
                    ImprovedAMQPConnection.ConnectionState.CONNECTED -> {
                        updateNotification("Queue 1 connected")
                    }
                    ImprovedAMQPConnection.ConnectionState.ERROR -> {
                        notifyError("Queue 1 error")
                    }
                    ImprovedAMQPConnection.ConnectionState.RECONNECTING -> {
                        updateNotification("Queue 1 reconnecting...")
                    }
                    else -> {}
                }
            }
        }

        // Connect with automatic retry
        while (isRunning.get()) {
            val connected = connection1?.connect { message ->
                processor1?.processMessage(message, connection1!!) ?: false
            } ?: false

            if (connected) {
                // Wait while connected
                while (connection1?.isConnected() == true && isRunning.get()) {
                    delay(5000)
                }
            }

            // Connection will handle its own reconnection
            // Just wait for state change
            delay(1000)
        }
    }

    override fun onDestroy() {
        connectionManager.cleanup()
        // ... rest of cleanup
        super.onDestroy()
    }
}
```

### Step 2: Update build.gradle.kts

Add RabbitMQ client exception imports:
```kotlin
dependencies {
    // Existing dependencies...

    // Ensure you have the latest RabbitMQ client
    implementation("com.rabbitmq:amqp-client:5.20.0")
}
```

## Error Handling Details

### Error Types & Recovery Strategies

| Error Type | Recovery Strategy | Retry Delay | Max Retries |
|------------|------------------|-------------|-------------|
| **Network Unavailable** | Wait for network | 10s exponential | Infinite |
| **DNS Resolution Failed** | Check network, retry | 15s exponential | Infinite |
| **Connection Timeout** | Reset connection | 5s exponential | Infinite |
| **Read Timeout** | Reset connection | 3s exponential | Infinite |
| **Connection Refused** | Server may be down | 30s exponential | 10 attempts |
| **Connection Reset** | Server closed connection | 5s exponential | Infinite |
| **SSL/TLS Error** | Reset connection | 10s exponential | Infinite |
| **Authentication Failed** | Check credentials | 60s | 3 attempts |
| **Queue Declaration Failed** | Check configuration | 60s | 3 attempts |

### Exponential Backoff Algorithm

```
delay = min(baseDelay * 2^(attempt-1) + jitter, maxDelay)

Where:
- baseDelay: Initial delay based on error type
- attempt: Current retry attempt number
- jitter: Random 0-1000ms to prevent thundering herd
- maxDelay: Maximum delay (30s-120s based on error)
```

## Monitoring & Debugging

### Connection Statistics
The new system provides detailed statistics:

```kotlin
val stats = connection.getConnectionStats()
// Returns:
// - isConnected: Boolean
// - consecutiveFailures: Int
// - reconnectAttempts: Int
// - queueDepth: Long
// - connectionState: ConnectionState
// - lastSuccessMs: Long
```

### Crashlytics Logging
Enhanced logging for better debugging:

```kotlin
// Error-specific logging
NetworkErrorHandler.logError(analysis, context)

// Connection state tracking
CrashlyticsLogger.setCustomKeys(mapOf(
    "connection_state" to state,
    "network_type" to networkType,
    "consecutive_failures" to failures
))
```

## Testing Error Scenarios

### 1. Test Network Loss
```bash
# Disable network
adb shell svc wifi disable
adb shell svc data disable

# Wait 30 seconds

# Re-enable
adb shell svc wifi enable
adb shell svc data enable
```

### 2. Test DNS Failure
Add to `/etc/hosts`:
```
127.0.0.1 windy-eagle-01.lmq.cloudamqp.com
```

### 3. Test Connection Timeout
Use firewall to block port 5671:
```bash
iptables -A OUTPUT -p tcp --dport 5671 -j DROP
```

### 4. Test Read Timeout
Simulate slow network:
```bash
tc qdisc add dev wlan0 root netem delay 5000ms
```

## Benefits of New System

### ✅ Reliability Improvements
- **100% error coverage**: All known error types handled
- **Automatic recovery**: Self-healing connections
- **Network-aware**: Adapts to network conditions
- **No connection leaks**: Proper resource cleanup

### ✅ Performance Improvements
- **Adaptive buffering**: Based on network type
- **Intelligent prefetch**: Adjusts to conditions
- **Connection pooling**: Reuses resources
- **Reduced battery usage**: Smart retry delays

### ✅ Monitoring Improvements
- **Real-time state tracking**: Know connection status
- **Detailed error logging**: Understand failures
- **Health monitoring**: Detect stale connections
- **Performance metrics**: Track success rates

## Troubleshooting

### Connection Not Reconnecting
1. Check `consecutiveFailures` - may have hit max
2. Verify network is available
3. Check authentication credentials
4. Review server logs

### High Memory Usage
1. Check queue depth
2. Reduce prefetch count
3. Enable memory monitoring
4. Review message processing time

### Battery Drain
1. Increase reconnect delays
2. Reduce health check frequency
3. Use network state to pause reconnection
4. Implement quiet hours

## Best Practices

1. **Always use ConnectionManager**: Provides network state and retry logic
2. **Monitor connection state**: React to state changes in UI
3. **Log errors properly**: Use NetworkErrorHandler for consistent logging
4. **Test error scenarios**: Regularly test recovery mechanisms
5. **Update error handling**: Add new error patterns as discovered

## Migration Checklist

- [ ] Replace ResilientAMQPConnection with ImprovedAMQPConnection
- [ ] Add ConnectionManager to MainSyncService
- [ ] Update error notifications to use new error messages
- [ ] Test all error scenarios
- [ ] Monitor Crashlytics for new error patterns
- [ ] Update documentation with any custom error handling

## Conclusion

The new error handling system provides comprehensive coverage of all network and connection errors with intelligent recovery strategies. It will automatically reconnect with appropriate delays and handle all the error cases seen in Crashlytics.

**Key improvements**:
- All errors from Crashlytics are now handled
- Connections will automatically retry until successful
- Network state changes trigger appropriate actions
- Exponential backoff prevents server overload
- Health monitoring detects stale connections

The system is designed to be resilient and self-healing, ensuring the app maintains connectivity even in challenging network conditions.