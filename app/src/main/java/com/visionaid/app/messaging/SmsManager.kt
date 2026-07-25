package com.visionaid.app.messaging

import android.content.Context
import android.util.Log
import com.visionaid.app.contacts.ContactResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends SMS text messages using Android's built-in [android.telephony.SmsManager].
 *
 * Flow:
 * 1. Resolve the contact name to a phone number via [ContactResolver]
 * 2. Handle disambiguation if multiple contacts match
 * 3. Send the SMS using [android.telephony.SmsManager]
 *
 * Requires SEND_SMS permission.
 */
@Singleton
class SmsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactResolver: ContactResolver
) {
    companion object {
        private const val TAG = "VisionAidSmsManager"
    }

    /**
     * Result of attempting to send an SMS.
     */
    sealed class SmsResult {
        data class Success(val recipientName: String) : SmsResult()
        data class NeedsDisambiguation(val matches: List<ContactResolver.ResolvedContact>) : SmsResult()
        data object ContactNotFound : SmsResult()
        data class Error(val message: String) : SmsResult()
    }

    /**
     * Sends an SMS message to the given contact.
     *
     * @param contactName the spoken contact name ("mom", "john")
     * @param messageBody the message text to send
     * @return [SmsResult] indicating outcome
     */
    fun sendSms(contactName: String, messageBody: String): SmsResult {
        showDiagnosticToast("ENTRY: sendSms('$contactName', '$messageBody')")
        
        // If the contact is already a phone number, send directly
        if (contactResolver.isPhoneNumber(contactName)) {
            showDiagnosticToast("Contact is a phone number, sending directly")
            return sendToNumber(contactName, contactName, messageBody)
        }

        val matches = contactResolver.findContactsByName(contactName)
        showDiagnosticToast("Contact lookup: found ${matches.size} matches for '$contactName'")

        return when {
            matches.isEmpty() -> {
                showDiagnosticToast("NO CONTACT FOUND for '$contactName'!")
                Log.w(TAG, "No contact found for '$contactName'")
                SmsResult.ContactNotFound
            }
            matches.size == 1 -> {
                val contact = matches[0]
                showDiagnosticToast("Found: ${contact.displayName} (${contact.phoneNumber})")
                Log.i(TAG, "Sending SMS to ${contact.displayName} (${contact.phoneNumber})")
                sendToNumber(contact.phoneNumber, contact.displayName, messageBody)
            }
            else -> {
                showDiagnosticToast("Multiple matches (${matches.size}) for '$contactName'")
                Log.i(TAG, "Multiple matches (${matches.size}) for '$contactName'")
                SmsResult.NeedsDisambiguation(matches)
            }
        }
    }

    private fun showDiagnosticToast(msg: String) {
        Log.e(TAG, "DIAG: $msg")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun sendToNumber(number: String, displayName: String, messageBody: String): SmsResult {
        showDiagnosticToast("SMS Step 1: sendToNumber called for $displayName")

        if (messageBody.isBlank()) {
            showDiagnosticToast("SMS: message body is blank, opening app")
            return openSmsApp(number, displayName, "")
        }

        showDiagnosticToast("SMS Step 2: message='$messageBody'")

        // Check SEND_SMS permission at runtime
        val hasPerm = context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == 
            android.content.pm.PackageManager.PERMISSION_GRANTED
        showDiagnosticToast("SMS Step 3: SEND_SMS permission=$hasPerm")

        if (!hasPerm) {
            showDiagnosticToast("SMS: NO PERMISSION! Falling back to app")
            return openSmsApp(number, displayName, messageBody)
        }

        return try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }

            showDiagnosticToast("SMS Step 4: smsManager=${if (smsManager != null) "OK" else "NULL"}")

            if (smsManager != null) {
                showDiagnosticToast("SMS Step 5: Calling sendTextMessage NOW to $number")
                smsManager.sendTextMessage(number, null, messageBody, null, null)
                showDiagnosticToast("SMS Step 6: sendTextMessage returned OK!")
                return SmsResult.Success(displayName)
            }

            showDiagnosticToast("SMS: smsManager was null, opening app")
            return openSmsApp(number, displayName, messageBody)

        } catch (e: SecurityException) {
            showDiagnosticToast("SMS EXCEPTION: SecurityException - ${e.message}")
            Log.e(TAG, "Missing SEND_SMS permission", e)
            SmsResult.Error("I don't have permission to send SMS directly.")
        } catch (e: Exception) {
            showDiagnosticToast("SMS EXCEPTION: ${e.javaClass.simpleName} - ${e.message}")
            Log.e(TAG, "Error sending SMS", e)
            return openSmsApp(number, displayName, messageBody)
        }
    }

    private fun openSmsApp(number: String, displayName: String, messageBody: String): SmsResult {
        try {
            // Strategy A: Standard SENDTO
            val intentA = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:$number")
                if (messageBody.isNotEmpty()) putExtra("sms_body", messageBody)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intentA)
            Log.i(TAG, "Opened SMS app using SENDTO")
            return SmsResult.Success(displayName)
        } catch (e1: Exception) {
            Log.w(TAG, "SENDTO failed, trying ACTION_SEND", e1)
            try {
                // Strategy B: Share Intent explicitly targeting Google Messages
                val intentB = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra("address", number)
                    if (messageBody.isNotEmpty()) putExtra(android.content.Intent.EXTRA_TEXT, messageBody)
                    setPackage("com.google.android.apps.messaging")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intentB)
                Log.i(TAG, "Opened SMS app using ACTION_SEND")
                return SmsResult.Success(displayName)
            } catch (e2: Exception) {
                Log.w(TAG, "ACTION_SEND failed, just launching the app", e2)
                try {
                    // Strategy C: Just open Google Messages main activity
                    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.messaging")
                    if (launchIntent != null) {
                        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        Log.i(TAG, "Opened Google Messages raw launcher")
                        return SmsResult.Success(displayName)
                    }
                } catch (e3: Exception) {
                    Log.e(TAG, "All intent strategies failed", e3)
                }
            }
        }
        return SmsResult.Error("Could not open any messaging app.")
    }
}
