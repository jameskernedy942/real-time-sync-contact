package com.realtime.synccontact.data

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import com.realtime.synccontact.utils.CrashlyticsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactManager(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    suspend fun insertContact(
        displayName: String,
        phoneNumber: String,
        note: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val operations = ArrayList<ContentProviderOperation>()

            // Create raw contact
            operations.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            // Add display name
            operations.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build()
            )

            // Add phone number
            operations.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    )
                    .build()
            )

            // Add note if provided
            if (!note.isNullOrEmpty()) {
                operations.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
                        .build()
                )
            }

            // Execute batch operation
            val results = contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)

            val success = results.isNotEmpty() && results[0].uri != null

            if (success) {
                CrashlyticsLogger.logContactOperation(
                    "INSERT_SUCCESS",
                    phoneNumber,
                    true,
                    "Contact created: $displayName"
                )
            }

            success
        } catch (e: Exception) {
            // Check if storage is full
            if (e.message?.contains("storage", ignoreCase = true) == true) {
                CrashlyticsLogger.logCriticalError(
                    "ContactManager",
                    "Storage full, attempting to clear old contacts",
                    e
                )
                deleteOldContacts(10)
                // Retry once after clearing
                return@withContext retryInsert(displayName, phoneNumber, note)
            }

            CrashlyticsLogger.logContactOperation(
                "INSERT_ERROR",
                phoneNumber,
                false,
                "Error: ${e.message}"
            )
            false
        }
    }

    fun isContactExists(phoneNumber: String): Boolean {
        var cursor: Cursor? = null
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?"
            val selectionArgs = arrayOf(phoneNumber)

            cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
            val exists = cursor?.count ?: 0 > 0

            if (exists) {
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.DEBUG,
                    "ContactManager",
                    "Contact exists: $phoneNumber"
                )
            }

            exists
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "ContactManager",
                "Failed to check contact existence",
                e
            )
            false
        } finally {
            cursor?.close()
        }
    }

    private suspend fun retryInsert(
        displayName: String,
        phoneNumber: String,
        note: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Thread.sleep(500) // Brief delay before retry
            insertContact(displayName, phoneNumber, note)
        } catch (e: Exception) {
            false
        }
    }

    private fun deleteOldContacts(count: Int) {
        try {
            // Get oldest contacts without notes (likely not synced contacts)
            val uri = ContactsContract.Contacts.CONTENT_URI
            val projection = arrayOf(ContactsContract.Contacts._ID)
            val sortOrder = "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} ASC"

            val cursor = contentResolver.query(uri, projection, null, null, sortOrder)
            cursor?.use {
                var deleted = 0
                while (it.moveToNext() && deleted < count) {
                    val contactId = it.getLong(0)
                    if (!hasNote(contactId)) {
                        deleteContact(contactId)
                        deleted++
                    }
                }
                CrashlyticsLogger.log(
                    CrashlyticsLogger.LogLevel.INFO,
                    "ContactManager",
                    "Deleted $deleted old contacts to free space"
                )
            }
        } catch (e: Exception) {
            CrashlyticsLogger.logCriticalError(
                "ContactManager",
                "Failed to delete old contacts",
                e
            )
        }
    }

    private fun hasNote(contactId: Long): Boolean {
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Note.NOTE)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(
            contactId.toString(),
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
        )

        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val note = it.getString(0)
                return note?.contains("Synced from APK_SYNC", ignoreCase = true) == true
            }
        }
        return false
    }

    private fun deleteContact(contactId: Long) {
        try {
            val uri = ContactsContract.Contacts.CONTENT_URI.buildUpon()
                .appendPath(contactId.toString())
                .build()
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            // Ignore individual delete failures
        }
    }

    fun getContactCount(): Int {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )
            cursor?.count ?: 0
        } catch (e: Exception) {
            CrashlyticsLogger.log(
                CrashlyticsLogger.LogLevel.WARNING,
                "ContactManager",
                "Failed to get contact count: ${e.message}"
            )
            0
        } finally {
            cursor?.close()
        }
    }

    fun getSyncedContactCount(): Int {
        var cursor: Cursor? = null
        return try {
            val uri = ContactsContract.Data.CONTENT_URI
            val projection = arrayOf(ContactsContract.Data.CONTACT_ID)
            val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Note.NOTE} LIKE ?"
            val selectionArgs = arrayOf(
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                "%Synced from APK_SYNC%"
            )

            cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.count ?: 0
        } catch (e: Exception) {
            0
        } finally {
            cursor?.close()
        }
    }
}