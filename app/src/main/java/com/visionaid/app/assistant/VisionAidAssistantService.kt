package com.visionaid.app.assistant

import android.service.voice.VoiceInteractionService

/**
 * The main entry point for the VisionAid Digital Assistant.
 * Required for Android to list the app in Settings > Default Apps > Digital Assistant.
 */
class VisionAidAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }
}
