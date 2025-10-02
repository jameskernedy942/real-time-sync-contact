# Deployment Guide v1.2.2 - Critical Fixes Release

## 🚨 CRITICAL FIXES INCLUDED

This version includes critical fixes for:
1. **JobCancellationException** - Fixed duplicate connections causing resource exhaustion
2. **ForegroundServiceDidNotStopInTimeException** - Fixed blocking onDestroy()
3. **Multiple Consumer Prevention** - Guaranteed single consumer per queue
4. **Clean Shutdown Loops** - Fixed RabbitMQ 200 OK reconnection loops

## 📋 PRE-DEPLOYMENT CHECKLIST

### Version Update
Ensure `app/build.gradle` has version updated to 1.2.2:
```gradle
versionCode 7
versionName "1.2.2"
```

### Build Configuration
```bash
# Clean build to ensure all changes are included
./gradlew clean
./gradlew assembleRelease
```

## 🔧 CHANGES SUMMARY

### New Components Added:
1. **GlobalConnectionRegistry** - Prevents duplicate connections globally
2. **ConnectionStateManager** - Centralized connection state management
3. **Version Display** - Shows app version on main screen

### Modified Components:
1. **MainSyncService** - Non-blocking onDestroy(), mutex protection
2. **ThreadSafeAMQPConnection** - Consumer deduplication, clean shutdown handling
3. **MainActivity** - Dynamic version display

## 📱 INSTALLATION STEPS

### 1. **IMPORTANT: Clean Installation Required**
```bash
# Uninstall existing app to clear any corrupted state
adb uninstall com.realtime.synccontact

# Install new version
adb install app-release.apk
```

### 2. **Grant Permissions**
- Accessibility Service: Settings → Accessibility → Enable RealTimeSync
- Device Admin: Settings → Security → Device administrators → Enable
- Notification Access: Settings → Notifications → Special access
- Background Running: Disable battery optimization for the app

### 3. **Configure Phone Number**
- Open app
- Enter phone number with country code (e.g., +628111111111)
- Tap "Start Service"

## 🔍 VERIFICATION STEPS

### Immediate Checks (First 5 minutes):
1. **Check Version Display**
   - Bottom of main screen should show "v1.2.2"

2. **Check Initial Connection**
   ```bash
   adb logcat | grep -E "GlobalConnectionRegistry|ConnectionStateManager"
   ```
   Expected:
   - "Connection registered for APK_SYNC_[phone]"
   - No "rejected - already active" messages

3. **Check Health Status**
   ```bash
   adb logcat | grep "HEALTH_CHECK"
   ```
   Expected:
   - Regular health checks every 30 seconds
   - Status: CONNECTED

### Stability Tests (30 minutes):

1. **Network Toggle Test**
   - Turn airplane mode ON
   - Wait 10 seconds
   - Turn airplane mode OFF
   - Check logs for proper reconnection without duplicates

2. **Phone Number Change Test**
   - Change phone number in app
   - Verify old connection cleaned up
   - Verify new connection established
   - Check no duplicate connections

3. **Service Restart Test**
   - Force stop app from Settings
   - Restart service
   - Verify clean startup without errors

4. **Background Operation Test**
   - Start service
   - Press home button
   - Lock screen
   - Wait 30 minutes
   - Check if still connected

## ⚠️ CRITICAL MONITORING POINTS

### Good Signs ✅
```
GlobalConnectionRegistry: Connection registered for APK_SYNC_628111
ConnectionStateManager: State changed from IDLE to SETTING_UP
AMQP_CONNECTION: CONNECTED successfully to APK_SYNC_628111
HEALTH_CHECK: Status - CONNECTED
```

### Warning Signs ⚠️
```
GlobalConnectionRegistry: Connection attempt rejected - already active
AMQP_CONNECTION: SHUTDOWN: clean channel shutdown (200 OK)
Multiple "CONNECTING" events within seconds
```

### Critical Issues 🔴
```
JobCancellationException
ForegroundServiceDidNotStopInTimeException
Multiple consumers detected on queue
Resource exhaustion errors
```

## 📊 PERFORMANCE METRICS

Monitor these metrics for 24 hours:

1. **Connection Stability**
   - Target: 0 duplicate connection attempts
   - Target: <5 reconnections per 24 hours

2. **Resource Usage**
   - RAM: Should stay under 150MB
   - CPU: <5% average usage
   - Battery: <2% per hour

3. **Message Processing**
   - All messages delivered within 2 seconds
   - No message loss during reconnections

## 🐛 TROUBLESHOOTING

### Issue: Service stops after a few hours
**Solution**: Check battery optimization settings - must be disabled

### Issue: "Connection rejected" in logs
**Solution**: This is GOOD - it means duplicate prevention is working

### Issue: Not receiving messages
**Check**:
1. Connection status in logs
2. Network connectivity
3. RabbitMQ server accessibility
4. Phone number configuration

### Issue: App crashes on startup
**Solution**:
1. Clear app data
2. Uninstall and reinstall
3. Check for conflicting accessibility services

## 📝 POST-DEPLOYMENT MONITORING

### Day 1 (First 24 hours):
- Monitor for any JobCancellationException
- Check for ForegroundServiceDidNotStopInTimeException
- Verify no duplicate connections
- Confirm message delivery

### Week 1:
- Daily health check review
- Resource usage trends
- Reconnection frequency analysis
- Error log review

### Ongoing:
- Weekly log analysis
- Monthly performance review
- User feedback collection

## 🎯 SUCCESS CRITERIA

The deployment is considered successful when:
1. ✅ No JobCancellationException in 24 hours
2. ✅ No ForegroundServiceDidNotStopInTimeException
3. ✅ No duplicate connection attempts
4. ✅ Stable connection for 24+ hours
5. ✅ All messages delivered successfully
6. ✅ Service survives network changes
7. ✅ Service survives phone restarts

## 📞 SUPPORT

If issues persist after following this guide:
1. Collect logs: `adb logcat > debug_logs.txt`
2. Note device model and Android version
3. Document reproduction steps
4. Check GlobalConnectionRegistry statistics in logs

## 🔄 ROLLBACK PLAN

If critical issues occur:
1. Uninstall v1.2.2
2. Install previous stable version (v1.2.1)
3. Document issues encountered
4. Collect debug logs for analysis

---

**Version**: 1.2.2
**Release Date**: October 2024
**Critical Fixes**: Duplicate connections, Service timeout, Consumer management
**Status**: Ready for deployment with comprehensive protection system