package com.realtime.synccontact

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.realtime.synccontact.admin.DeviceAdminReceiver
import com.realtime.synccontact.services.MainSyncService
import com.realtime.synccontact.utils.SharedPrefsManager

/**
 * Dedicated activity for mandatory Device Admin setup
 * This activity blocks all app functionality until Device Admin is enabled
 */
class DeviceAdminSetupActivity : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var componentName: ComponentName
    private lateinit var sharedPrefsManager: SharedPrefsManager

    private lateinit var titleText: TextView
    private lateinit var messageText: TextView
    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var retryRunnable: Runnable? = null
    private var statusCheckRunnable: Runnable? = null
    private var isRequestingAdmin = false

    companion object {
        private const val REQUEST_CODE_DEVICE_ADMIN = 1001
        private const val MAX_RETRY_COUNT = 100 // Practically unlimited
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_admin_setup)

        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(this, DeviceAdminReceiver::class.java)
        sharedPrefsManager = SharedPrefsManager(this)

        initViews()
        checkDeviceAdminStatus()
    }

    private fun initViews() {
        titleText = findViewById(R.id.tvTitle)
        messageText = findViewById(R.id.tvMessage)
        statusText = findViewById(R.id.tvStatus)
        enableButton = findViewById(R.id.btnEnable)
        progressBar = findViewById(R.id.progressBar)

        enableButton.setOnClickListener {
            requestDeviceAdminPermission()
        }
    }

    private fun checkDeviceAdminStatus() {
        // Cancel any pending retries
        retryRunnable?.let { handler.removeCallbacks(it) }

        if (devicePolicyManager.isAdminActive(componentName)) {
            // Device Admin is already active, proceed
            onDeviceAdminEnabled()
        } else {
            // Show setup UI
            updateStatus("Device Admin not enabled. Retry #$retryCount")

            // Auto-trigger after 2 seconds for persistence
            if (retryCount > 0 && !isRequestingAdmin) {
                retryRunnable = Runnable {
                    if (!isFinishing && !isDestroyed) {
                        requestDeviceAdminPermission()
                    }
                }
                handler.postDelayed(retryRunnable!!, 2000)
            }
        }
    }

    private fun requestDeviceAdminPermission() {
        if (isRequestingAdmin) {
            return // Already requesting, prevent duplicate
        }

        isRequestingAdmin = true
        retryCount++
        progressBar.visibility = View.VISIBLE
        enableButton.isEnabled = false
        updateStatus("Opening Device Admin settings... (Attempt #$retryCount)")

        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "⚠️ CRITICAL: This permission is REQUIRED. The app will NOT function without it. " +
                "Please tap 'Activate' to enable Device Admin.")
        }

        try {
            startActivityForResult(intent, REQUEST_CODE_DEVICE_ADMIN)
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            updateStatus("Error: ${e.message}")
            progressBar.visibility = View.GONE
            enableButton.isEnabled = true
        }
    }

    private fun onDeviceAdminEnabled() {
        updateStatus("✓ Device Admin ENABLED!")

        // Mark as configured
        sharedPrefsManager.setFirstRun(false)

        // Start main activity or service
        val intent = if (MainSyncService.isRunning(this)) {
            // Service already running, just go to MainActivity
            Intent(this, MainActivity::class.java)
        } else {
            // Start MainActivity for initial setup
            Intent(this, MainActivity::class.java)
        }

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun updateStatus(status: String) {
        statusText.text = "Status: $status"
        FirebaseCrashlytics.getInstance().log("DeviceAdminSetup: $status")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        isRequestingAdmin = false
        progressBar.visibility = View.GONE
        enableButton.isEnabled = true

        if (requestCode == REQUEST_CODE_DEVICE_ADMIN) {
            if (devicePolicyManager.isAdminActive(componentName)) {
                // Success!
                onDeviceAdminEnabled()
            } else {
                // Failed - show persistent retry
                updateStatus("NOT ENABLED - User declined (Attempt #$retryCount)")

                // Show why it's mandatory
                messageText.text = """
                    ❌ DEVICE ADMIN WAS NOT ENABLED!

                    The app CANNOT function without this permission.

                    You MUST enable Device Admin for:
                    • 24/7 contact synchronization
                    • Protection from Android system kills
                    • Automatic service resurrection
                    • Persistent RabbitMQ connections

                    Press 'ENABLE DEVICE ADMIN' and then tap 'Activate'

                    Retry attempt: #$retryCount
                """.trimIndent()

                // Auto-retry after delay
                retryRunnable?.let { handler.removeCallbacks(it) }
                retryRunnable = Runnable {
                    if (!isFinishing && !isDestroyed) {
                        checkDeviceAdminStatus()
                    }
                }
                handler.postDelayed(retryRunnable!!, 3000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Cancel any pending status checks
        statusCheckRunnable?.let { handler.removeCallbacks(it) }

        // Check status when returning to activity
        statusCheckRunnable = Runnable {
            if (!isFinishing && !isDestroyed) {
                if (devicePolicyManager.isAdminActive(componentName)) {
                    onDeviceAdminEnabled()
                }
            }
        }
        handler.postDelayed(statusCheckRunnable!!, 500)
    }

    override fun onBackPressed() {
        // Don't allow back press - Device Admin is mandatory
        updateStatus("Device Admin is MANDATORY - Cannot go back")
        messageText.text = """
            ⚠️ You cannot exit this setup!

            Device Admin is MANDATORY for the app to function.
            Please enable it to continue.
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up all pending callbacks
        retryRunnable?.let { handler.removeCallbacks(it) }
        statusCheckRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
    }
}