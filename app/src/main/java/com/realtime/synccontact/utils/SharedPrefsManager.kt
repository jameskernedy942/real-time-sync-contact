package com.realtime.synccontact.utils

import android.content.Context
import android.content.SharedPreferences

class SharedPrefsManager(private val context: Context) {

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "RealTimeSyncPrefs"
        private const val KEY_PHONE_1 = "phone_number_1"
        private const val KEY_PHONE_2 = "phone_number_2"
        private const val KEY_SERVICE_STARTED = "service_started"
        private const val KEY_FIRST_RUN = "first_run"
        private const val KEY_CONSUMER_TAG = "consumer_tag"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_TOTAL_SYNCED = "total_synced"
        private const val KEY_FAILED_SYNC_COUNT = "failed_sync_count"
    }

    fun savePhoneNumbers(phone1: String, phone2: String) {
        sharedPreferences.edit().apply {
            putString(KEY_PHONE_1, phone1)
            if (phone2 == "628") {
                putString(KEY_PHONE_2, "")
            } else {
                putString(KEY_PHONE_2, phone2)
            }
            apply()
        }
    }

    fun getPhoneNumbers(): Pair<String, String> {
        val phone1 = sharedPreferences.getString(KEY_PHONE_1, "") ?: ""
        val phone2 = sharedPreferences.getString(KEY_PHONE_2, "") ?: ""
        return Pair(phone1, phone2)
    }

    fun isServiceStarted(): Boolean {
        return sharedPreferences.getBoolean(KEY_SERVICE_STARTED, false)
    }

    fun setServiceStarted(started: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_SERVICE_STARTED, started).apply()
    }

    fun isFirstRun(): Boolean {
        return sharedPreferences.getBoolean(KEY_FIRST_RUN, true)
    }

    fun setFirstRun(firstRun: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_FIRST_RUN, firstRun).apply()
    }

    fun saveConsumerTag(tag: String) {
        sharedPreferences.edit().putString(KEY_CONSUMER_TAG, tag).apply()
    }

    fun getConsumerTag(): String {
        val (phone1, _) = getPhoneNumbers()
        val defaultTag = "device_${phone1}_${System.currentTimeMillis()}"
        return sharedPreferences.getString(KEY_CONSUMER_TAG, defaultTag) ?: defaultTag
    }

    fun updateLastSyncTime() {
        sharedPreferences.edit().putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis()).apply()
    }

    fun getLastSyncTime(): Long {
        return sharedPreferences.getLong(KEY_LAST_SYNC_TIME, 0)
    }

    fun incrementTotalSynced() {
        val current = sharedPreferences.getLong(KEY_TOTAL_SYNCED, 0)
        sharedPreferences.edit().putLong(KEY_TOTAL_SYNCED, current + 1).apply()
    }

    fun getTotalSynced(): Long {
        return sharedPreferences.getLong(KEY_TOTAL_SYNCED, 0)
    }

    fun incrementFailedSync() {
        val current = sharedPreferences.getInt(KEY_FAILED_SYNC_COUNT, 0)
        sharedPreferences.edit().putInt(KEY_FAILED_SYNC_COUNT, current + 1).apply()
    }

    fun resetFailedSyncCount() {
        sharedPreferences.edit().putInt(KEY_FAILED_SYNC_COUNT, 0).apply()
    }

    fun getFailedSyncCount(): Int {
        return sharedPreferences.getInt(KEY_FAILED_SYNC_COUNT, 0)
    }

    fun clear() {
        sharedPreferences.edit().clear().apply()
    }

    fun setServiceStartTime(time: Long = System.currentTimeMillis()) {
        sharedPreferences.edit().putLong("service_start_time", time).apply()
    }

    fun getServiceStartTime(): Long {
        return sharedPreferences.getLong("service_start_time", System.currentTimeMillis())
    }

    fun setLastHeartbeat(time: Long = System.currentTimeMillis()) {
        sharedPreferences.edit().putLong("last_heartbeat", time).apply()
    }

    fun getLastHeartbeat(): Long {
        return sharedPreferences.getLong("last_heartbeat", 0)
    }

    fun setServiceStatus(status: String) {
        sharedPreferences.edit().putString("service_status", status).apply()
    }

    fun getServiceStatus(): String {
        return sharedPreferences.getString("service_status", "unknown") ?: "unknown"
    }

    fun setServiceDeathTime(time: Long = System.currentTimeMillis()) {
        sharedPreferences.edit().putLong("service_death_time", time).apply()
    }

    fun getServiceDeathTime(): Long {
        return sharedPreferences.getLong("service_death_time", 0)
    }

    fun incrementServiceDeathCount() {
        val current = sharedPreferences.getInt("service_death_count", 0)
        sharedPreferences.edit().putInt("service_death_count", current + 1).apply()
    }

    fun getServiceDeathCount(): Int {
        return sharedPreferences.getInt("service_death_count", 0)
    }

    // CloudAMQP Monitoring Methods
    fun getDailyConnectionCount(): Int {
        return getCloudAMQPPrefs().getInt("daily_connections", 0)
    }

    fun incrementDailyConnectionCount() {
        val current = getDailyConnectionCount()
        getCloudAMQPPrefs().edit().putInt("daily_connections", current + 1).apply()
    }

    fun getDailyMessageCount(): Long {
        return getCloudAMQPPrefs().getLong("daily_messages", 0)
    }

    fun incrementDailyMessageCount() {
        val current = getDailyMessageCount()
        getCloudAMQPPrefs().edit().putLong("daily_messages", current + 1).apply()
    }

    fun getHourlyErrorCount(): Int {
        val lastReset = getCloudAMQPPrefs().getLong("error_count_reset", 0)
        val now = System.currentTimeMillis()

        // Reset if more than an hour
        if (now - lastReset > 3600000) {
            getCloudAMQPPrefs().edit()
                .putInt("hourly_errors", 0)
                .putLong("error_count_reset", now)
                .apply()
            return 0
        }

        return getCloudAMQPPrefs().getInt("hourly_errors", 0)
    }

    fun incrementHourlyErrorCount() {
        val current = getHourlyErrorCount()
        getCloudAMQPPrefs().edit().putInt("hourly_errors", current + 1).apply()
    }

    fun resetDailyCounters() {
        getCloudAMQPPrefs().edit()
            .putInt("daily_connections", 0)
            .putLong("daily_messages", 0)
            .putInt("hourly_errors", 0)
            .putLong("last_reset", System.currentTimeMillis())
            .apply()
    }

    private fun getCloudAMQPPrefs() =
        context.getSharedPreferences("cloudamqp_monitor", Context.MODE_PRIVATE)
}