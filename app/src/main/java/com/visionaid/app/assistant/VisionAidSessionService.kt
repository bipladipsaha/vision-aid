package com.visionaid.app.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSessionService

/**
 * Service that creates the [VisionAidSession] when the user triggers the assistant
 * (e.g., by long-pressing the power button).
 */
class VisionAidSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): android.service.voice.VoiceInteractionSession {
        return VisionAidSession(this)
    }
}
