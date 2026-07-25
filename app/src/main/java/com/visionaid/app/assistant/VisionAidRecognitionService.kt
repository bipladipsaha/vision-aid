package com.visionaid.app.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * A stub RecognitionService.
 * Android requires a custom Digital Assistant to provide a RecognitionService
 * in its metadata to be considered valid in Settings.
 * We don't actually use this (we use the built-in [SpeechRecognizer] directly
 * in [VoiceCommandEngine]), so this just remains unimplemented.
 */
class VisionAidRecognitionService : RecognitionService() {
    
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Not used
    }

    override fun onCancel(listener: Callback?) {
        // Not used
    }

    override fun onStopListening(listener: Callback?) {
        // Not used
    }
}
