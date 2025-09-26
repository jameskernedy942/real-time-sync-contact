package com.realtime.synccontact.utils

import android.content.Context
import android.content.SharedPreferences

class SharedPrefsManager(context: Context) {

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
}