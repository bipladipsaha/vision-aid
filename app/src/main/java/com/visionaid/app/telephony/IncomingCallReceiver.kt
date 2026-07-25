package com.visionaid.app.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.visionaid.app.contacts.ContactResolver
import com.visionaid.app.voice.TextToSpeechEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Broadcast receiver that listens for incoming phone calls
 * and announces the caller's name through TTS.
 *
 * When the phone starts ringing:
 * 1. Extracts the incoming phone number from the intent
 * 2. Looks up the number in the device's contacts via [ContactResolver]
 * 3. Speaks "Incoming call from [Name]" via [TextToSpeechEngine]
 * 4. If the number is unknown, speaks "Incoming call from unknown number"
 *
 * This allows a visually impaired user to know who is calling
 * and decide whether to answer or reject via voice commands.
 */
@AndroidEntryPoint
class IncomingCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IncomingCallReceiver"
    }

    @Inject
    lateinit var contactResolver: ContactResolver

    @Inject
    lateinit var ttsEngine: TextToSpeechEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "Phone state: $state, number: $incomingNumber")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (incomingNumber != null) {
                    val callerName = contactResolver.getNameForNumber(incomingNumber)
                    val announcement = if (callerName != null) {
                        "Incoming call from $callerName"
                    } else {
                        "Incoming call from unknown number"
                    }
                    Log.i(TAG, announcement)
                    ttsEngine.speak(announcement)
                } else {
                    ttsEngine.speak("Incoming call")
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.d(TAG, "Call ended or missed")
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.d(TAG, "Call answered")
            }
        }
    }
}
