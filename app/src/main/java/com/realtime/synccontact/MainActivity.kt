package com.realtime.synccontact

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.realtime.synccontact.admin.DeviceAdminReceiver
import com.realtime.synccontact.databinding.ActivityMainBinding
import com.realtime.synccontact.services.GuardianService
import com.realtime.synccontact.services.MainSyncService
import com.realtime.synccontact.utils.SharedPrefsManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPrefsManager: SharedPrefsManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var componentName: ComponentName
    private var permissionsGranted = false

    private val handler = Handler(Looper.getMainLooper())
    private val updateScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateServiceStatus()
            handler.postDelayed(this, 1000) // Update every second
        }
    }

    companion object {
        private const val REQUEST_CODE_DEVICE_ADMIN = 1001
        private const val REQUEST_CODE_OVERLAY = 1002
        private const val REQUEST_CODE_BATTERY_OPTIMIZATION = 1003
        private const val REQUEST_CODE_ACCESSIBILITY = 1004
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPrefsManager = SharedPrefsManager(this)
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(this, DeviceAdminReceiver::class.java)

        setupUI()
        checkExistingConfiguration()
    }

    private fun setupUI() {
        // Phone number validation
        binding.etPhone1.addTextChangedListener(PhoneNumberWatcher(binding.tilPhone1))
        binding.etPhone2.addTextChangedListener(PhoneNumberWatcher(binding.tilPhone2))

        binding.btnStart.setOnClickListener {
            when (binding.btnStart.text.toString()) {
                "START SYNC SERVICE" -> startService()
                "STOP SERVICE" -> stopService()
                "UPDATE & RESTART" -> updateAndRestart()
                "SERVICE IS DEAD - TAP TO RESTART" -> restartDeadService()
            }
        }

        // Enable phone number editing when service is running
        binding.etPhone1.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                checkIfPhoneNumbersChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.etPhone2.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                checkIfPhoneNumbersChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun startService() {
        val phone1 = binding.etPhone1.text.toString().trim()
        val phone2 = binding.etPhone2.text.toString().trim()

        if (validatePhoneNumbers(phone1, phone2)) {
            binding.progressBar.visibility = View.VISIBLE
            binding.btnStart.isEnabled = false
            updateStatus("Requesting permissions...")

            // Save phone numbers
            sharedPrefsManager.savePhoneNumbers(phone1, phone2)

            // Start permission flow
            requestAllPermissions()
        }
    }

    private fun stopService() {
        AlertDialog.Builder(this)
            .setTitle("Stop Service")
            .setMessage("Are you sure you want to stop the sync service?")
            .setPositiveButton("Yes") { _, _ ->
                // Stop the service
                val intent = Intent(this, MainSyncService::class.java)
                stopService(intent)

                // Update preferences
                sharedPrefsManager.setServiceStarted(false)

                // Update UI
                binding.btnStart.text = "START SYNC SERVICE"
                binding.btnStart.isEnabled = true
                binding.etPhone1.isEnabled = true
                binding.etPhone2.isEnabled = true
                updateStatus("Service stopped")
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun updateAndRestart() {
        val phone1 = binding.etPhone1.text.toString().trim()
        val phone2 = binding.etPhone2.text.toString().trim()

        if (validatePhoneNumbers(phone1, phone2)) {
            AlertDialog.Builder(this)
                .setTitle("Update Phone Numbers")
                .setMessage("This will restart the service with new phone numbers. Continue?")
                .setPositiveButton("Yes") { _, _ ->
                    // Stop current service
                    val stopIntent = Intent(this, MainSyncService::class.java)
                    stopService(stopIntent)

                    // Save new numbers
                    sharedPrefsManager.savePhoneNumbers(phone1, phone2)

                    // Restart service with a brief delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        val startIntent = Intent(this, MainSyncService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(startIntent)
                        } else {
                            startService(startIntent)
                        }

                        binding.btnStart.text = "STOP SERVICE"
                        updateStatus("Service restarted with new numbers")
                    }, 1000)
                }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun checkIfPhoneNumbersChanged() {
        if (MainSyncService.isRunning(this)) {
            val (savedPhone1, savedPhone2) = sharedPrefsManager.getPhoneNumbers()
            val currentPhone1 = binding.etPhone1.text.toString().trim()
            val currentPhone2 = binding.etPhone2.text.toString().trim()

            if (currentPhone1 != savedPhone1 || currentPhone2 != savedPhone2) {
                binding.btnStart.text = "UPDATE & RESTART"
                binding.btnStart.isEnabled = true
            } else {
                binding.btnStart.text = "STOP SERVICE"
                binding.btnStart.isEnabled = true
            }
        }
    }

    private fun checkExistingConfiguration() {
        // First, check if device admin is active
        if (!devicePolicyManager.isAdminActive(componentName)) {
            // Force enable device admin on app startup
            Handler(Looper.getMainLooper()).postDelayed({
                AlertDialog.Builder(this)
                    .setTitle("⚠️ ENABLE DEVICE ADMIN")
                    .setMessage("Device Admin must be enabled for the app to function.\n\n" +
                        "This is a one-time setup that ensures 24/7 operation.")
                    .setPositiveButton("ENABLE") { _, _ ->
                        requestDeviceAdminPermission()
                    }
                    .setCancelable(false)
                    .show()
            }, 500)
        }

        val (phone1, phone2) = sharedPrefsManager.getPhoneNumbers()
        if (phone1.isNotEmpty() && phone2.isNotEmpty()) {
            binding.etPhone1.setText(phone1)
            binding.etPhone2.setText(phone2)

            if (MainSyncService.isRunning(this)) {
                updateStatus("Service is running")
                binding.btnStart.text = "STOP SERVICE"
                binding.btnStart.isEnabled = true
                // Keep phone fields enabled for editing
                binding.etPhone1.isEnabled = true
                binding.etPhone2.isEnabled = true
            } else {
                binding.btnStart.text = "START SYNC SERVICE"
                binding.btnStart.isEnabled = true
            }
        }
    }

    private fun validatePhoneNumbers(phone1: String, phone2: String): Boolean {
        var isValid = true

        // First phone number is REQUIRED
        if (!phone1.startsWith("628") || phone1.length < 10) {
            binding.tilPhone1.error = "Invalid number. Must start with 628 and be at least 10 digits"
            isValid = false
        } else {
            binding.tilPhone1.error = null
        }

        // Second phone number is OPTIONAL
        if (phone2 == "628") return isValid

        if (phone2.isNotEmpty()) {
            // If provided, must be valid
            if (!phone2.startsWith("628") || phone2.length < 10) {
                binding.tilPhone2.error = "Invalid number. Must start with 628 and be at least 10 digits (or leave empty)"
                isValid = false
            } else if (phone1 == phone2) {
                binding.tilPhone2.error = "Numbers must be different"
                isValid = false
            } else {
                binding.tilPhone2.error = null
            }
        } else {
            // Empty second phone number is OK - clear any errors
            binding.tilPhone2.error = null
        }

        return isValid
    }

    private fun requestAllPermissions() {
        // Step 1: Request basic permissions
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.FOREGROUND_SERVICE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        Dexter.withContext(this)
            .withPermissions(permissions)
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                    if (report.areAllPermissionsGranted()) {
                        updateStatus("Basic permissions granted")
                        requestBatteryOptimizationExemption()
                    } else {
                        showPermissionError("Some permissions were denied. The app cannot function without all permissions.")
                        FirebaseCrashlytics.getInstance().log("Permissions denied: ${report.deniedPermissionResponses}")
                    }
                }

                override fun onPermissionRationaleShouldBeShown(
                    permissions: List<PermissionRequest>,
                    token: PermissionToken
                ) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Permissions Required")
                        .setMessage("This app requires all permissions to sync contacts in real-time 24/7. Please grant all permissions.")
                        .setPositiveButton("Grant") { _, _ -> token.continuePermissionRequest() }
                        .setNegativeButton("Cancel") { _, _ ->
                            token.cancelPermissionRequest()
                            showPermissionError("Permissions are required for the app to function")
                        }
                        .setCancelable(false)
                        .show()
                }
            }).check()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                updateStatus("Requesting battery optimization exemption...")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, REQUEST_CODE_BATTERY_OPTIMIZATION)
            } else {
                requestOverlayPermission()
            }
        } else {
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                updateStatus("Requesting overlay permission...")
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            } else {
                requestDeviceAdminPermission()
            }
        } else {
            requestDeviceAdminPermission()
        }
    }

    private fun requestDeviceAdminPermission() {
        // Force device admin - user guaranteed they will grant
        if (!devicePolicyManager.isAdminActive(componentName)) {
            updateStatus("Device Admin is REQUIRED...")
            AlertDialog.Builder(this)
                .setTitle("⚠️ DEVICE ADMIN REQUIRED")
                .setMessage("Device Admin permission is MANDATORY for 24/7 operation.\n\n" +
                    "This permission:\n" +
                    "• Prevents force-stop\n" +
                    "• Ensures service resurrection\n" +
                    "• Maintains persistent connection\n\n" +
                    "You MUST grant this permission to continue.")
                .setPositiveButton("ENABLE NOW") { _, _ ->
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "CRITICAL: Device Admin is REQUIRED for 24/7 contact sync. The app CANNOT function without it.")
                    startActivityForResult(intent, REQUEST_CODE_DEVICE_ADMIN)
                }
                .setCancelable(false)
                .show()
        } else {
            requestAccessibilityService()
        }
    }

    private fun requestAccessibilityService() {
        if (!isAccessibilityServiceEnabled()) {
            updateStatus("Accessibility Service")
            AlertDialog.Builder(this)
                .setTitle("Enable Accessibility Service")
                .setMessage("Enabling Real Time Sync accessibility service provides extra protection against the app being killed.")
                .setPositiveButton("Enable") { _, _ ->
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivityForResult(intent, REQUEST_CODE_ACCESSIBILITY)
                }
                .setCancelable(false)
                .show()
        } else {
            allPermissionsGranted()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${GuardianService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(service) == true
    }

    private fun allPermissionsGranted() {
        permissionsGranted = true
        updateStatus("All permissions granted! Starting service...")
        binding.progressBar.visibility = View.GONE

        // Start the main sync service
        startSyncService()
    }

    private fun startSyncService() {
        try {
            val intent = Intent(this, MainSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            // Mark service as started
            sharedPrefsManager.setServiceStarted(true)

            updateStatus("Service started successfully!")
            binding.btnStart.text = "STOP SERVICE"
            binding.btnStart.isEnabled = true

            // Show success dialog
            AlertDialog.Builder(this)
                .setTitle("Success")
                .setMessage("Real Time Sync service is now running. The app will sync contacts 24/7 in the background.\n\nYou can:\n• Close this app (service continues running)\n• Stop the service using the STOP button\n• Update phone numbers and restart")
                .setPositiveButton("OK", null)
                .show()

        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            updateStatus("Failed to start service: ${e.message}")
            binding.btnStart.isEnabled = true
        }
    }

    private fun updateStatus(status: String) {
        binding.tvStatus.text = "Status: $status"
    }

    private fun showPermissionError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.btnStart.isEnabled = true
        updateStatus("Permission error")

        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(message)
            .setPositiveButton("Retry") { _, _ ->
                binding.btnStart.performClick()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CODE_BATTERY_OPTIMIZATION -> {
                requestOverlayPermission()
            }
            REQUEST_CODE_OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    requestDeviceAdminPermission()
                } else {
                    showPermissionError("Overlay permission is required for the app to function")
                }
            }
            REQUEST_CODE_DEVICE_ADMIN -> {
                if (devicePolicyManager.isAdminActive(componentName)) {
                    requestAccessibilityService()
                } else {
                    // Force retry - Device Admin is mandatory
                    AlertDialog.Builder(this)
                        .setTitle("❌ DEVICE ADMIN NOT ENABLED")
                        .setMessage("Device Admin is MANDATORY!\n\n" +
                            "The app CANNOT work without it.\n\n" +
                            "Press 'RETRY' and GRANT the permission.")
                        .setPositiveButton("RETRY NOW") { _, _ ->
                            requestDeviceAdminPermission()
                        }
                        .setCancelable(false)
                        .show()
                }
            }
            REQUEST_CODE_ACCESSIBILITY -> {
                if (isAccessibilityServiceEnabled()) {
                    allPermissionsGranted()
                } else {
                    showPermissionError("Accessibility Service must be enabled for 24/7 operation")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        updateScope.cancel()
    }

    private fun updateServiceStatus() {
        val isRunning = MainSyncService.isRunning(this)
        val lastHeartbeat = sharedPrefsManager.getLastHeartbeat()
        val currentTime = System.currentTimeMillis()
        val timeSinceHeartbeat = currentTime - lastHeartbeat

        when {
            isRunning && timeSinceHeartbeat < 60000 -> { // Healthy
                binding.tvStatus.text = "Service: Running ✓"
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.btnStart.text = "STOP SERVICE"
            }
            isRunning && timeSinceHeartbeat < 300000 -> { // Warning
                binding.tvStatus.text = "Service: Running (Unresponsive)"
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                binding.btnStart.text = "UPDATE & RESTART"
            }
            isRunning -> { // Dead but still showing as running
                binding.tvStatus.text = "Service: DEAD (Process killed)"
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                binding.btnStart.text = "SERVICE IS DEAD - TAP TO RESTART"
            }
            else -> { // Not running
                binding.tvStatus.text = "Service: Stopped ✗"
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                binding.btnStart.text = "START SYNC SERVICE"
            }
        }

        // Update statistics
        updateStats()
    }

    private fun updateStats() {
        val totalSynced = sharedPrefsManager.getTotalSynced()
        val deathCount = sharedPrefsManager.getServiceDeathCount()
        val serviceStartTime = sharedPrefsManager.getServiceStartTime()
        val lastHeartbeat = sharedPrefsManager.getLastHeartbeat()
        val currentTime = System.currentTimeMillis()

        val uptime = if (serviceStartTime > 0 && lastHeartbeat > 0 && (currentTime - lastHeartbeat) < 60000) {
            val minutes = (currentTime - serviceStartTime) / 1000 / 60
            val hours = minutes / 60
            val days = hours / 24
            when {
                days > 0 -> "${days}d ${hours % 24}h"
                hours > 0 -> "${hours}h ${minutes % 60}m"
                else -> "${minutes}m"
            }
        } else {
            "N/A"
        }

        val statsText = """
            Uptime: $uptime
            Total Synced: $totalSynced
            Service Deaths: $deathCount
            Last Heartbeat: ${if (lastHeartbeat > 0) "${(currentTime - lastHeartbeat) / 1000}s ago" else "Never"}
        """.trimIndent()

        // Update UI with stats (assuming you have a TextView for stats)
        // binding.tvStats.text = statsText
    }

    private fun restartDeadService() {
        // Kill any zombie process
        stopService()

        // Wait a bit then restart
        Handler(Looper.getMainLooper()).postDelayed({
            startService()
        }, 1000)
    }

    inner class PhoneNumberWatcher(private val textInputLayout: TextInputLayout) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            s?.let {
                if (it.isNotEmpty() && !it.startsWith("628")) {
                    it.clear()
                    it.append("628")
                    textInputLayout.error = "Number must start with 628"
                } else {
                    textInputLayout.error = null
                }
            }
        }
    }
}