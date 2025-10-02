# Auto-Update Implementation Guide

## Overview
This app now includes a comprehensive auto-update system that works without Google Play Store. The system leverages Device Admin privileges for more resilient operation, though it still requires user confirmation for APK installation (Android security requirement).

## Features

### 1. Automatic Update Checking
- Checks for updates every 6 hours (configurable)
- Compares version codes to determine if update is available
- Supports critical updates that force installation

### 2. APK Download Management
- Downloads APK files in the background
- Shows progress notifications
- Verifies checksums if provided
- Stores APKs in external files directory

### 3. Installation Process
- Uses PackageInstaller API for Android 10+
- FileProvider for Android 7-9
- Direct URI for Android 6 and below
- Device Admin helps maintain app persistence after updates

### 4. Update Settings
- Enable/disable auto-update
- Enable/disable auto-download
- Enable/disable auto-install (still requires user confirmation)
- Configure update server URL
- Set check interval

## Server Requirements

Your update server needs to provide a JSON endpoint that returns update information:

```json
{
  "versionCode": 2,
  "versionName": "1.0.2",
  "apkUrl": "https://your-server.com/updates/app-v1.0.2.apk",
  "releaseNotes": "Bug fixes and improvements",
  "minSupportedVersion": 1,
  "checksum": "sha256_hash_of_apk",
  "fileSize": 15728640,
  "isCritical": false,
  "publishedAt": 1700000000000
}
```

## Setup Instructions

### 1. Configure Update Server URL

In `UpdateChecker.kt`, update the server URL:

```kotlin
companion object {
    private const val UPDATE_SERVER_URL = "https://your-server.com/api/app/check-update"
}
```

### 2. Server Implementation

Your server should:
1. Accept GET requests with parameters:
   - `version_code`: Current app version code
   - `version_name`: Current app version name
   - `device`: Device model
   - `sdk`: Android SDK version

2. Return:
   - HTTP 200 with JSON update info if update available
   - HTTP 204 if no update available

### 3. Building Update APKs

1. Increment `versionCode` and `versionName` in `build.gradle.kts`
2. Build signed APK: `./gradlew assembleRelease`
3. Upload APK to your server
4. Update server database with new version info

### 4. Security Considerations

- **Always use HTTPS** for update server and APK downloads
- **Sign APKs** with the same certificate
- **Verify checksums** to ensure APK integrity
- **Use SSL pinning** for additional security (optional)

## How It Works

1. **App Start**: When the main service starts, the UpdateService also starts
2. **Periodic Checks**: UpdateService checks for updates every N hours
3. **Update Available**: If update found:
   - Shows notification to user
   - If auto-download enabled, downloads APK
   - If auto-install enabled, prompts for installation
4. **Installation**: User confirms installation through system dialog
5. **Post-Install**: App can auto-restart if Device Admin is active

## User Experience

### With Default Settings (Auto-Update Enabled)
1. App automatically checks for updates
2. Downloads updates in background
3. Shows notification when ready
4. User taps to install
5. App restarts after installation

### Manual Update Check
Users can force update check through:
- Update Settings dialog → "Check Now" button
- Or programmatically: `UpdateService.forceCheckUpdate(context)`

## Limitations

### Device Admin Cannot:
- Silently install APKs (requires root or system app)
- Bypass user confirmation for installation
- Update system apps

### Device Admin Can:
- Prevent app from being force-stopped
- Auto-restart app after installation
- Maintain service persistence
- Provide better update reliability

## Testing

1. **Local Testing**:
   - Set up a local server returning mock JSON
   - Use lower version code in app
   - Test update flow

2. **Production Testing**:
   - Deploy update server
   - Upload test APK with higher version
   - Verify checksum calculation
   - Test on multiple Android versions

## Troubleshooting

### Update Not Downloading
- Check internet permission
- Verify server URL is accessible
- Check server response format
- Review logcat for errors

### Installation Fails
- Ensure APK is signed correctly
- Check package name matches
- Verify REQUEST_INSTALL_PACKAGES permission
- Test FileProvider configuration

### Auto-Restart Not Working
- Confirm Device Admin is active
- Check if app has necessary permissions
- Verify restart logic in UpdateInstallReceiver

## Best Practices

1. **Version Management**: Use semantic versioning
2. **Release Notes**: Provide clear, user-friendly notes
3. **Rollback Plan**: Keep previous APK versions
4. **Gradual Rollout**: Test with small user group first
5. **Analytics**: Track update success rates
6. **Error Handling**: Implement robust error recovery

## Security Notes

The implementation includes several security measures:
- Checksum verification for downloaded APKs
- HTTPS enforcement for downloads
- Package name verification
- Signature verification by Android system

However, for maximum security, consider:
- Implementing additional APK signature verification
- Using certificate pinning
- Encrypting update metadata
- Implementing update authentication tokens