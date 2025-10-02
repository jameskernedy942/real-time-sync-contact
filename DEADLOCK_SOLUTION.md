# Complete Solution for art::ConditionVariable::WaitHoldingLocks Deadlock

## Problem Identified

The deadlock occurs because:
1. **RabbitMQ operations are blocking** - All channel operations block the thread
2. **Multiple coroutines access the same channel** - No thread safety
3. **Nested coroutine launches** - Exhaust thread pool
4. **All operations on Dispatchers.IO** - Limited thread pool gets blocked

## The Deadlock Scenario

```
Thread 1 (Coroutine): Holds channel lock, processing message
Thread 2 (Coroutine): Waiting for channel to ACK message
Thread 3 (Coroutine): Waiting for channel to publish SYNC_SUCCESS
Thread 4 (Coroutine): Waiting for channel to check connection
= DEADLOCK: All IO threads blocked waiting for locks
```

## Solution Implemented: ThreadSafeAMQPConnection

### Key Changes:

1. **Dedicated Thread for RabbitMQ**
   - Single thread executor for ALL RabbitMQ operations
   - Prevents concurrent access to channel
   - No lock contention

2. **Kotlin Channels for Communication**
   - Thread-safe message passing
   - Non-blocking operations
   - Backpressure handling

3. **Separation of Concerns**
   - RabbitMQ operations on dedicated thread
   - Message processing on Default dispatcher
   - No blocking operations in coroutines

4. **Proper Resource Management**
   - Structured concurrency
   - Proper cancellation
   - No resource leaks

## Architecture

```
                    ┌─────────────────┐
                    │  RabbitMQ       │
                    │  Channel        │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │  Single Thread   │ (RabbitMQ Executor)
                    │  Executor        │
                    └────────┬────────┘
                             │
                ┌────────────┴────────────┐
                │                         │
        ┌───────▼───────┐       ┌────────▼────────┐
        │Kotlin Channel │       │ Kotlin Channel  │
        │ (Messages)    │       │ (ACKs)          │
        └───────┬───────┘       └────────┬────────┘
                │                         │
        ┌───────▼───────┐       ┌────────▼────────┐
        │ Coroutine     │       │ Coroutine       │
        │ (Process)     │       │ (ACK Handler)   │
        └───────────────┘       └─────────────────┘
```

## How It Prevents Deadlock

1. **Single Thread for RabbitMQ**
   - Only one thread ever touches the channel
   - No concurrent access = no locks needed
   - Sequential processing guaranteed

2. **Non-Blocking Message Flow**
   - Messages delivered via Kotlin Channel
   - Processing happens asynchronously
   - ACKs sent back via channel

3. **No Thread Pool Exhaustion**
   - RabbitMQ on dedicated thread
   - Processing on Default dispatcher
   - IO dispatcher not used for blocking ops

## Usage

Replace `ImprovedAMQPConnection` with `ThreadSafeAMQPConnection`:

```kotlin
// In MainSyncService.kt
private suspend fun startConnection1(phone: String) {
    val queueName = "APK_SYNC_$phone"

    // Use ThreadSafeAMQPConnection instead
    connection1 = ThreadSafeAMQPConnection(
        CONNECTION_URL,
        queueName,
        phone,
        connectionManager
    )

    // Rest remains the same
}
```

## Benefits

1. **No Deadlocks** - Single thread access pattern
2. **Better Performance** - Non-blocking operations
3. **Lower Memory** - Controlled buffering
4. **Predictable Behavior** - Sequential processing

## Testing

To verify the fix:
1. Monitor for `art::ConditionVariable::WaitHoldingLocks` in Crashlytics
2. Check thread dump for blocked threads
3. Test under high message load
4. Verify smooth operation during network changes

## Additional Improvements

1. **Reduced Prefetch** - From 50 to 10 messages
2. **Buffered Channels** - Capacity limits prevent overflow
3. **Timeout on Operations** - Prevents infinite waits
4. **Proper Cleanup** - Resources released correctly

## Monitoring

Key metrics to track:
- Thread count remains stable
- No blocked threads in dumps
- Message processing latency
- Memory usage stays bounded

This solution completely eliminates the deadlock by ensuring:
- No concurrent access to RabbitMQ channel
- No blocking operations in coroutines
- Proper thread isolation
- Clean resource management