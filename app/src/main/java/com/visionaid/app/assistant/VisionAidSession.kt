package com.visionaid.app.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.visionaid.app.MainActivity

/**
 * The actual Assistant session.
 * Instead of drawing an overlay UI like Google Assistant, this session
 * instantly launches VisionAid's MainActivity with a special flag
 * to trigger the voice listening engine, then immediately closes itself.
 */
class VisionAidSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "VisionAidSession"
        const val EXTRA_ASSIST_TRIGGER = "extra_assist_trigger"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "Assistant Session Triggered (e.g., Power Button hold)")

        // Launch our MainActivity and tell it to start listening
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ASSIST_TRIGGER, true)
        }
        
        // Start the activity
        startAssistantActivity(intent)

        // Close the assistant session immediately since the app takes over
        finish()
    }
}
