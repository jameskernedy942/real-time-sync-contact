# Critical 24/7 Operation Checklist

## ✅ Already Implemented:
1. ✅ Wake Lock with renewal every 9 minutes
2. ✅ WiFi Lock for network persistence
3. ✅ Device Admin for force-stop protection
4. ✅ Accessibility Service for extra persistence
5. ✅ JobScheduler for periodic resurrection
6. ✅ START_STICKY service flag
7. ✅ Foreground Service with notification
8. ✅ Battery optimization exemption request
9. ✅ Boot receiver for auto-start
10. ✅ CloudAMQP monitoring and rate limiting
11. ✅ Dual-queue support for redundancy
12. ✅ Retry queue for failed messages
13. ✅ Heartbeat monitoring
14. ✅ Death detection and alerts

## ❌ CRITICAL - Still Need to Implement:

### 1. **Doze Mode Bypass (MOST CRITICAL)**
```kotlin
// In MainSyncService onCreate():
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        // Force user to whitelist app
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:$packageName")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }
}
```

### 2. **Multiple Alarm Managers for Redundancy**
- Use both `setExactAndAllowWhileIdle()` and `setAlarmClock()`
- Schedule alarms every 15 minutes (minimum in Doze)
- Use WorkManager as backup

### 3. **Process Priority Optimization**
```kotlin
// Add to service onCreate():
Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
// This is higher priority than FOREGROUND
```

### 4. **Dual Process Architecture**
```xml
<!-- In AndroidManifest.xml for GuardianService: -->
android:process=":guardian"
```
This runs Guardian in separate process - if main crashes, guardian survives.

### 5. **Network State Recovery**
```kotlin
// Add network callback for immediate reconnection:
val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        // Immediately reconnect AMQP
    }
}
```

### 6. **Memory Optimization**
```kotlin
// Implement ComponentCallbacks2:
override fun onTrimMemory(level: Int) {
    when (level) {
        TRIM_MEMORY_CRITICAL -> {
            // Release non-critical resources
            // Clear caches
            // Reduce connection pool
        }
    }
}
```

### 7. **Contact Batching**
Instead of processing one by one:
```kotlin
// Batch process contacts to reduce wake time
val batch = mutableListOf<ContactMessage>()
// Collect 10 messages or 5 seconds, whichever comes first
if (batch.size >= 10 || timeSinceLastBatch > 5000) {
    processBatch(batch)
}
```

### 8. **Connection Pool Management**
```kotlin
// Implement connection pooling to reduce overhead
class AMQPConnectionPool {
    private val maxConnections = 2
    private val connections = ConcurrentLinkedQueue<Connection>()

    fun getConnection(): Connection {
        return connections.poll() ?: createNewConnection()
    }
}
```

### 9. **Aggressive Keep-Alive**
```kotlin
// Heartbeat every 10 seconds (current) is good
// But also add TCP keep-alive:
socket.setKeepAlive(true)
socket.setSoTimeout(30000) // 30 second timeout
```

### 10. **System UI Integration**
```kotlin
// Show in recent apps even when swiped away
val intent = Intent(this, MainActivity::class.java)
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
```

## ⚠️ MOST IMPORTANT FIXES NEEDED:

### 1. **Fix Doze Mode Issue (TOP PRIORITY)**
Your app WILL stop after 15-30 minutes of screen off without this:
```kotlin
// Force whitelist from battery optimization
// This is MANDATORY for 24/7 operation
```

### 2. **Add Companion App/Widget**
Create a widget that periodically triggers the service:
```kotlin
class SyncWidget : AppWidgetProvider() {
    override fun onUpdate() {
        // Start service every widget update (30 min minimum)
    }
}
```

### 3. **Use Firebase Cloud Messaging as Backup**
When AMQP fails, use FCM high-priority messages to wake the app:
```kotlin
class FCMService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Wake up and reconnect AMQP
        startService(Intent(this, MainSyncService::class.java))
    }
}
```

### 4. **Implement Exponential Backoff Properly**
```kotlin
var retryDelay = 1000L
fun getNextDelay(): Long {
    val delay = retryDelay
    retryDelay = min(retryDelay * 2, 300000L) // Max 5 minutes
    // Add jitter to prevent thundering herd
    return delay + Random.nextLong(0, 1000)
}
```

### 5. **Add Process Monitor**
```kotlin
// In a separate process:
class ProcessMonitor : Service() {
    override fun onCreate() {
        // Check if main process is alive every 30 seconds
        // If dead, restart it
    }
}
```

## 🔴 IMMEDIATE ACTION REQUIRED:

1. **Battery Optimization Whitelist** - Without this, Doze mode kills your app
2. **Alarm Manager with setExactAndAllowWhileIdle()** - Current implementation doesn't bypass Doze
3. **Dual Process Architecture** - Guardian must run in separate process
4. **Network Callback Registration** - For instant reconnection when network returns
5. **Memory Management** - Implement onTrimMemory() to prevent OOM kills

## Testing 24/7 Operation:

1. **Test Doze Mode:**
```bash
adb shell dumpsys deviceidle force-idle
# Your app should still sync after 15 minutes
```

2. **Test App Standby:**
```bash
adb shell am set-standby-bucket com.realtime.synccontact restricted
# App should still work
```

3. **Test Memory Pressure:**
```bash
adb shell am send-trim-memory com.realtime.synccontact CRITICAL
# Service should survive
```

4. **Test Force Stop Recovery:**
- Settings > Apps > Your App > Force Stop
- Service should restart within 15 minutes

5. **Test Network Changes:**
- Toggle airplane mode
- Switch between WiFi and mobile data
- Service should reconnect immediately

## The Reality:

Even with ALL these implementations, Android 12+ can still kill your app if:
- User manually restricts background activity
- Device is in extreme battery saver mode
- OEM-specific optimizations (Xiaomi, Oppo, Vivo are aggressive)

For TRUE 24/7 guarantee, you need:
1. MDM (Mobile Device Management) deployment
2. System app privileges
3. Root access
4. Custom ROM

Without these, the best you can achieve is ~95% uptime with occasional gaps during extreme battery saving conditions.