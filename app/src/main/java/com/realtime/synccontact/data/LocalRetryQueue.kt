package com.realtime.synccontact.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.realtime.synccontact.utils.CrashlyticsLogger
import java.util.concurrent.TimeUnit

class LocalRetryQueue(context: Context) {

    private val dbHelper = RetryQueueDatabaseHelper(context)

    data class RetryMessage(
        val id: Long,
        val message: String,
        val createdAt: Long,
        val retryCount: Int
    )

    fun addMessage(message: String) {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put(COLUMN_MESSAGE, message)
                put(COLUMN_CREATED_AT, System.currentTimeMillis())
                put(COLUMN_RETRY_COUNT, 0)
            }

            val id = db.insert(TABLE_NAME, null, values)
            if (id != -1L) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "RetryQueue",
                    "Added message to retry queue: ID=$id"
                )
            }
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "RetryQueue",
                "Failed to add message to retry queue",
                e
            )
        }
    }

    fun getPendingMessages(): List<RetryMessage> {
        val messages = mutableListOf<RetryMessage>()

        try {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                TABLE_NAME,
                null,
                "$COLUMN_PROCESSED = ?",
                arrayOf("0"),
                null,
                null,
                "$COLUMN_CREATED_AT ASC",
                "100" // Limit to 100 messages per batch
            )

            cursor.use {
                while (it.moveToNext()) {
                    messages.add(
                        RetryMessage(
                            id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID)),
                            message = it.getString(it.getColumnIndexOrThrow(COLUMN_MESSAGE)),
                            createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
                            retryCount = it.getInt(it.getColumnIndexOrThrow(COLUMN_RETRY_COUNT))
                        )
                    )
                }
            }

            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.DEBUG,
                "RetryQueue",
                "Found ${messages.size} pending messages"
            )
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "RetryQueue",
                "Failed to get pending messages",
                e
            )
        }

        return messages
    }

    fun markAsProcessed(id: Long) {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put(COLUMN_PROCESSED, 1)
                put(COLUMN_PROCESSED_AT, System.currentTimeMillis())
            }

            val updated = db.update(
                TABLE_NAME,
                values,
                "$COLUMN_ID = ?",
                arrayOf(id.toString())
            )

            if (updated > 0) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.DEBUG,
                    "RetryQueue",
                    "Marked message as processed: ID=$id"
                )
            }
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "RetryQueue",
                "Failed to mark message as processed",
                e
            )
        }
    }

    fun incrementRetryCount(id: Long) {
        try {
            val db = dbHelper.writableDatabase
            db.execSQL(
                "UPDATE $TABLE_NAME SET $COLUMN_RETRY_COUNT = $COLUMN_RETRY_COUNT + 1 WHERE $COLUMN_ID = ?",
                arrayOf(id)
            )
        } catch (e: Exception) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "RetryQueue",
                "Failed to increment retry count: ${e.message}"
            )
        }
    }

    fun cleanup() {
        try {
            val db = dbHelper.writableDatabase
            val threeDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)

            // Delete processed messages older than 3 days
            val deletedProcessed = db.delete(
                TABLE_NAME,
                "$COLUMN_PROCESSED = 1 AND $COLUMN_PROCESSED_AT < ?",
                arrayOf(threeDaysAgo.toString())
            )

            // Delete unprocessed messages older than 3 days (expired)
            val deletedExpired = db.delete(
                TABLE_NAME,
                "$COLUMN_PROCESSED = 0 AND $COLUMN_CREATED_AT < ?",
                arrayOf(threeDaysAgo.toString())
            )

            if (deletedProcessed > 0 || deletedExpired > 0) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "RetryQueue",
                    "Cleanup: Deleted $deletedProcessed processed and $deletedExpired expired messages"
                )
            }
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "RetryQueue",
                "Failed to cleanup retry queue",
                e
            )
        }
    }

    fun getQueueSize(): Int {
        return try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_NAME WHERE $COLUMN_PROCESSED = 0",
                null
            )
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    fun clearAll() {
        try {
            val db = dbHelper.writableDatabase
            db.delete(TABLE_NAME, null, null)
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.INFO,
                "RetryQueue",
                "Cleared all messages from retry queue"
            )
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "RetryQueue",
                "Failed to clear retry queue",
                e
            )
        }
    }

    private class RetryQueueDatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE_SQL)
            db.execSQL(CREATE_INDEX_SQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    companion object {
        private const val DATABASE_NAME = "retry_queue.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "retry_messages"

        private const val COLUMN_ID = "id"
        private const val COLUMN_MESSAGE = "message"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_PROCESSED = "processed"
        private const val COLUMN_PROCESSED_AT = "processed_at"
        private const val COLUMN_RETRY_COUNT = "retry_count"

        private const val CREATE_TABLE_SQL = """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_MESSAGE TEXT NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_PROCESSED INTEGER DEFAULT 0,
                $COLUMN_PROCESSED_AT INTEGER,
                $COLUMN_RETRY_COUNT INTEGER DEFAULT 0
            )
        """

        private const val CREATE_INDEX_SQL = """
            CREATE INDEX idx_processed ON $TABLE_NAME ($COLUMN_PROCESSED, $COLUMN_CREATED_AT)
        """
    }
}