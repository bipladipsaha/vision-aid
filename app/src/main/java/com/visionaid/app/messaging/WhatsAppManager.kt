package com.visionaid.app.messaging

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.visionaid.app.contacts.ContactResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends WhatsApp messages by deep-linking into the WhatsApp app.
 *
 * Flow:
 * 1. Resolve the contact name to a phone number via [ContactResolver]
 * 2. Construct a WhatsApp API deep link with the phone number and message
 * 3. Launch WhatsApp with the pre-filled message
 *
 * Note: WhatsApp requires the phone number in international format (e.g. +91XXXXXXXXXX).
 * If the number doesn't start with '+', we assume Indian (+91) format.
 * The user may need to confirm and tap "Send" in WhatsApp.
 */
@Singleton
class WhatsAppManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactResolver: ContactResolver
) {
    companion object {
        private const val TAG = "WhatsAppManager"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    }

    /**
     * Result of attempting to send a WhatsApp message.
     */
    sealed class WhatsAppResult {
        data class Success(val recipientName: String) : WhatsAppResult()
        data class NeedsDisambiguation(val matches: List<ContactResolver.ResolvedContact>) : WhatsAppResult()
        data object ContactNotFound : WhatsAppResult()
        data object WhatsAppNotInstalled : WhatsAppResult()
        data class Error(val message: String) : WhatsAppResult()
    }

    /**
     * Sends a WhatsApp message by opening WhatsApp with a pre-filled message.
     *
     * @param contactName spoken contact name
     * @param messageBody the message to send
     * @return [WhatsAppResult] indicating outcome
     */
    fun sendWhatsAppMessage(contactName: String, messageBody: String): WhatsAppResult {
        // Check if WhatsApp is installed
        if (!isWhatsAppInstalled()) {
            Log.w(TAG, "WhatsApp is not installed")
            return WhatsAppResult.WhatsAppNotInstalled
        }

        // If the contact is already a phone number, send directly
        if (contactResolver.isPhoneNumber(contactName)) {
            return openWhatsAppChat(contactName, contactName, messageBody)
        }

        val matches = contactResolver.findContactsByName(contactName)

        return when {
            matches.isEmpty() -> {
                Log.w(TAG, "No contact found for '$contactName'")
                WhatsAppResult.ContactNotFound
            }
            matches.size == 1 -> {
                val contact = matches[0]
                Log.i(TAG, "Opening WhatsApp for ${contact.displayName}")
                openWhatsAppChat(contact.phoneNumber, contact.displayName, messageBody)
            }
            else -> {
                Log.i(TAG, "Multiple matches (${matches.size}) for '$contactName'")
                WhatsAppResult.NeedsDisambiguation(matches)
            }
        }
    }

    /**
     * Opens WhatsApp with a specific number and pre-filled message.
     */
    private fun openWhatsAppChat(
        phoneNumber: String,
        displayName: String,
        messageBody: String
    ): WhatsAppResult {
        return try {
            val formattedNumber = formatForWhatsApp(phoneNumber)
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(WHATSAPP_PACKAGE)
                putExtra(Intent.EXTRA_TEXT, messageBody)
                putExtra("jid", "$formattedNumber@s.whatsapp.net")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(intent)
            Log.i(TAG, "WhatsApp opened for $displayName ($formattedNumber)")
            WhatsAppResult.Success(displayName)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening WhatsApp", e)
            WhatsAppResult.Error("Could not open WhatsApp: ${e.message}")
        }
    }

    /**
     * Formats a phone number for WhatsApp's API.
     * WhatsApp expects the number without '+', spaces, or dashes.
     * If no country code is present, assumes India (+91).
     */
    private fun formatForWhatsApp(phoneNumber: String): String {
        // Strip everything except digits and '+'
        var cleaned = phoneNumber.replace(Regex("[^0-9+]"), "")
        
        // Remove leading '+' if present
        if (cleaned.startsWith("+")) {
            cleaned = cleaned.removePrefix("+")
        } else if (cleaned.length == 10) {
            // Assume Indian number if 10 digits
            cleaned = "91$cleaned"
        }
        
        return cleaned
    }

    /**
     * Checks if WhatsApp (regular or business) is installed.
     */
    private fun isWhatsAppInstalled(): Boolean {
        val pm = context.packageManager
        return try {
            pm.getPackageInfo(WHATSAPP_PACKAGE, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                pm.getPackageInfo(WHATSAPP_BUSINESS_PACKAGE, PackageManager.GET_ACTIVITIES)
                true
            } catch (e2: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
