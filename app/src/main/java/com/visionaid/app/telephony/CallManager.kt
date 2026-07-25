package com.visionaid.app.telephony

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import com.visionaid.app.contacts.ContactResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages outgoing calls and incoming call actions (answer/reject).
 *
 * For outgoing calls:
 * - Resolves spoken contact names via [ContactResolver]
 * - Handles disambiguation when multiple contacts match
 * - Initiates calls using [Intent.ACTION_CALL]
 *
 * For incoming calls:
 * - Answers via [TelecomManager.acceptRingingCall] (API 26+)
 * - Rejects via [TelecomManager.endCall] (API 28+)
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactResolver: ContactResolver
) {
    companion object {
        private const val TAG = "CallManager"
    }

    /**
     * Result of attempting to make a call.
     */
    sealed class CallResult {
        data object Success : CallResult()
        data class NeedsDisambiguation(val matches: List<ContactResolver.ResolvedContact>) : CallResult()
        data object ContactNotFound : CallResult()
        data class Error(val message: String) : CallResult()
    }

    /**
     * Initiates a phone call to the given contact name or number.
     *
     * @param contactNameOrNumber spoken name ("mom") or phone number ("9876543210")
     * @return [CallResult] indicating success, disambiguation needed, or failure
     */
    fun makeCall(contactNameOrNumber: String): CallResult {
        // If it looks like a raw phone number, dial directly
        if (contactResolver.isPhoneNumber(contactNameOrNumber)) {
            return dialNumber(contactNameOrNumber)
        }

        // Otherwise, resolve the name to a number
        val matches = contactResolver.findContactsByName(contactNameOrNumber)

        return when {
            matches.isEmpty() -> {
                Log.w(TAG, "No contact found for '$contactNameOrNumber'")
                CallResult.ContactNotFound
            }
            matches.size == 1 -> {
                Log.i(TAG, "Single match: ${matches[0].displayName} → ${matches[0].phoneNumber}")
                dialNumber(matches[0].phoneNumber)
            }
            else -> {
                Log.i(TAG, "Multiple matches (${matches.size}) for '$contactNameOrNumber'")
                CallResult.NeedsDisambiguation(matches)
            }
        }
    }

    /**
     * Dials a phone number directly using ACTION_CALL.
     * Requires CALL_PHONE permission.
     */
    private fun dialNumber(phoneNumber: String): CallResult {
        return try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)
            Log.i(TAG, "Dialing $phoneNumber")
            CallResult.Success
        } catch (e: SecurityException) {
            Log.e(TAG, "CALL_PHONE permission not granted", e)
            CallResult.Error("Call permission not granted. Please enable it in settings.")
        } catch (e: Exception) {
            Log.e(TAG, "Error making call", e)
            CallResult.Error("Could not make the call: ${e.message}")
        }
    }

    /**
     * Answers the currently ringing call.
     * Requires ANSWER_PHONE_CALLS permission (API 26+).
     */
    fun answerCall(): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                telecomManager.acceptRingingCall()
                Log.i(TAG, "Call answered")
                true
            } else {
                Log.w(TAG, "TelecomManager not available or API too low")
                false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "ANSWER_PHONE_CALLS permission not granted", e)
            false
        }
    }

    /**
     * Rejects/ends the currently ringing or active call.
     * Requires ANSWER_PHONE_CALLS permission (API 28+).
     */
    @Suppress("DEPRECATION")
    fun rejectCall(): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
                Log.i(TAG, "Call rejected/ended")
                true
            } else {
                Log.w(TAG, "TelecomManager.endCall not available (requires API 28+)")
                false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for ending call", e)
            false
        }
    }
}
