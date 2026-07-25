package com.visionaid.app.contacts

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves spoken contact names to phone numbers by searching
 * the device's Contacts database.
 *
 * Features:
 * - Case-insensitive fuzzy matching (partial name match)
 * - Returns multiple matches for disambiguation
 * - Resolves a caller ID (phone number) → display name for incoming calls
 */
@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ContactResolver"
    }

    /**
     * A resolved contact from the device's address book.
     */
    data class ResolvedContact(
        val displayName: String,
        val phoneNumber: String
    )

    /**
     * Searches the device contacts for names matching [spokenName].
     *
     * Uses a LIKE query for partial matching, so "mom" will match
     * contacts named "Mom", "Mommy", etc.
     *
     * @return list of matching contacts (may be empty)
     */
    fun findContactsByName(spokenName: String): List<ResolvedContact> {
        val results = mutableListOf<ResolvedContact>()

        try {
            val uri: Uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$spokenName%")
            val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

            val cursor: Cursor? = context.contentResolver.query(
                uri, projection, selection, selectionArgs, sortOrder
            )

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: continue
                    val number = it.getString(numberIndex) ?: continue
                    results.add(ResolvedContact(name, number))
                }
            }

            Log.i(TAG, "Search '$spokenName' → ${results.size} result(s)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for contacts access", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching contacts", e)
        }

        // De-duplicate by phone number (a contact may have multiple entries)
        return results.distinctBy { it.phoneNumber.replace(Regex("[^0-9+]"), "") }
    }

    /**
     * Reverse lookup: given a phone number, find the contact's display name.
     * Used for announcing incoming callers.
     *
     * @return display name, or null if the number is not in contacts
     */
    fun getNameForNumber(phoneNumber: String): String? {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    return it.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up number $phoneNumber", e)
        }
        return null
    }

    /**
     * Checks if the given string looks like a phone number (digits, +, spaces, dashes).
     */
    fun isPhoneNumber(input: String): Boolean {
        return input.matches(Regex("[+\\d\\s\\-()]{7,15}"))
    }
}
