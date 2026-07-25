package com.visionaid.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-shot voice command recognizer.
 *
 * This engine is **not** responsible for wake word detection — that is
 * handled by [WakeWordEngine] (Vosk, always-on, offline).
 *
 * This engine is triggered **only** after the wake word is detected:
 *
 * ```
 * WakeWordEngine (Vosk)
 *     ↓  "Hey Vision" detected
 * VoiceCommandEngine (Android SpeechRecognizer)
 *     ↓  Listens for one command
 * ParsedCommand
 *     ↓
 * CommandRouter
 * ```
 *
 * Uses Android's built-in [SpeechRecognizer] with [RecognizerIntent.EXTRA_PREFER_OFFLINE]
 * for the actual command capture.
 */
@Singleton
class VoiceCommandEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VoiceCommandEngine"
        
        // Supported hotwords (still used for text-level stripping in parseIntent)
        private val WAKE_WORDS = listOf("hey vision", "hey vision ai", "vision ai", "vision", "hey google open vision ai")
    }

    private var speechRecognizer: SpeechRecognizer? = null
    
    private val _parsedCommands = MutableSharedFlow<ParsedCommand>(extraBufferCapacity = 1)
    val parsedCommands: SharedFlow<ParsedCommand> = _parsedCommands.asSharedFlow()

    private var isListening = false

    /**
     * A callback invoked when a single-shot command session finishes.
     * Used by the service to know when to resume the wake word engine.
     */
    var onCommandSessionComplete: (() -> Unit)? = null

    /**
     * Start a single voice command listening session.
     *
     * This is triggered by:
     * - Wake word detection (via WakeWordEngine/Vosk)
     * - Double-tap gesture
     * - Power button assist intent
     *
     * After the command is recognized (or an error occurs), the session
     * ends and [onCommandSessionComplete] is called so the wake word
     * engine can resume.
     */
    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition is not available on this device.")
            onCommandSessionComplete?.invoke()
            return
        }

        // Force cleanup of any stuck previous instance
        if (isListening) {
            Log.w(TAG, "Was already listening (likely stuck), forcing restart")
        }
        shutdown()

        initializeRecognizer()
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // Force offline mode
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Give the user more time to speak their command
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }

        Log.i(TAG, "Starting single-shot command recognition...")
        isListening = true
        speechRecognizer?.startListening(intent)
    }

    /**
     * Stop listening (e.g., if the user says "Stop" or the session times out).
     */
    fun stopListening() {
        Log.i(TAG, "Stopping voice recognition")
        isListening = false
        speechRecognizer?.stopListening()
    }

    fun shutdown() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun initializeRecognizer() {
        // We now always create a fresh instance to avoid the Android SpeechRecognizer freezing bug
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Beginning of speech")
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "End of speech")
                }

                override fun onError(error: Int) {
                    val errorMsg = getErrorText(error)
                    Log.w(TAG, "Speech recognition error: $errorMsg")
                    isListening = false
                    // Session is over — let the wake word engine resume
                    onCommandSessionComplete?.invoke()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0].lowercase().trim()
                        Log.i(TAG, "Recognized text: $text")
                        
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context, "Heard: $text", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        
                        val command = parseIntent(text)
                        
                        // Emit to whoever is listening (VisionAidService)
                        _parsedCommands.tryEmit(command)
                    } else {
                        Log.w(TAG, "SpeechRecognizer returned empty results")
                    }
                    isListening = false
                    onCommandSessionComplete?.invoke()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // No hotword detection needed here — Vosk handles that.
                    // Just log partial results for debugging.
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialText = matches?.firstOrNull()?.lowercase() ?: ""
                    if (partialText.isNotEmpty()) {
                        Log.d(TAG, "Partial: '$partialText'")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    /**
     * Natural Language parsing logic.
     * Maps spoken text to strongly-typed ParsedCommands.
     *
     * Intent matching priority (highest to lowest):
     * 1. Hotword (strips "Hey Vision" prefix, recurses on remainder)
     * 2. Stop / Repeat (control commands)
     * 3. Answer / Reject call
     * 4. Make call
     * 5. Send WhatsApp message
     * 6. Send SMS
     * 7. Open app
     * 8. Get distance
     * 9. Describe scene
     * 10. Find object
     * 11. Telemetry / Status
     * 12. General question (fallback for any "what/who/how/why/when" sentence)
     * 13. Unknown
     */
    private fun parseIntent(rawText: String): ParsedCommand {
        val text = rawText.trim()
        if (text.isBlank()) return ParsedCommand.None

        // 1. Hotword Check
        for (hotword in WAKE_WORDS) {
            if (text.contains(hotword)) {
                val commandRemainder = text.substringAfter(hotword).trim()
                if (commandRemainder.isEmpty()) {
                    return ParsedCommand.WakeWordDetected
                } else {
                    return parseIntent(commandRemainder) // recursive parse of the rest
                }
            }
        }

        // 2. Stop / Cancel
        if (text == "stop" || text == "cancel" || text == "shut up" || 
            text == "be quiet" || text == "silence" || text == "enough") {
            return ParsedCommand.Stop
        }

        // 3. Repeat
        if (text == "repeat" || text.contains("say that again") || 
            text.contains("repeat that") || text.contains("come again") ||
            text.contains("what did you say")) {
            return ParsedCommand.Repeat
        }

        // 4. Answer / Reject incoming call
        if (text == "answer" || text == "pick up" || text.contains("answer the call") ||
            text.contains("accept the call") || text.contains("pick up the call")) {
            return ParsedCommand.AnswerCall
        }
        if (text == "reject" || text == "decline" || text == "hang up" ||
            text.contains("reject the call") || text.contains("decline the call") ||
            text.contains("hang up the call") || text.contains("end call")) {
            return ParsedCommand.RejectCall
        }

        // 5. Make a call: "call mom", "phone john", "dial 9876543210"
        val callTriggers = listOf("call ", "phone ", "dial ", "ring ")
        for (trigger in callTriggers) {
            if (text.startsWith(trigger)) {
                val target = cleanContactName(text.removePrefix(trigger).trim())
                if (target.isNotEmpty()) {
                    return ParsedCommand.MakeCall(target)
                }
            }
        }
        // Also catch "make a call to X"
        if (text.contains("make a call to") || text.contains("call to")) {
            val target = cleanContactName(
                text.substringAfter("call to").trim()
            )
            if (target.isNotEmpty()) return ParsedCommand.MakeCall(target)
        }

        // 6. WhatsApp message: "send whatsapp message to [contact] saying [message]"
        if (text.contains("whatsapp")) {
            val parsed = parseMessageCommand(text, isWhatsApp = true)
            if (parsed != null) return parsed
        }

        // 7. SMS / Message: "send message to [contact] saying [message]"
        //    Also: "text mom saying I'm coming", "message john hello"
        val smsTriggers = listOf("send message", "send a message", "send sms", "text ", "message ")
        for (trigger in smsTriggers) {
            if (text.contains(trigger)) {
                val parsed = parseMessageCommand(text, isWhatsApp = false)
                if (parsed != null) return parsed
            }
        }

        // 8. Open app: "open youtube", "launch whatsapp", "start maps"
        val openTriggers = listOf("open ", "launch ", "start ", "go to ")
        for (trigger in openTriggers) {
            if (text.startsWith(trigger)) {
                val appName = text.removePrefix(trigger).trim()
                if (appName.isNotEmpty()) {
                    return ParsedCommand.OpenApp(appName)
                }
            }
        }
        // "open the messages app"
        if (text.contains("open the") || text.contains("open my")) {
            val appName = text.substringAfter("open the").substringAfter("open my")
                .replace(" app", "").trim()
            if (appName.isNotEmpty()) return ParsedCommand.OpenApp(appName)
        }

        // 9. Get distance: "how far", "what is the distance"
        if (text.contains("how far") || text.contains("distance") ||
            text.contains("how close") || text.contains("how near")) {
            return ParsedCommand.GetDistance
        }

        // 10. Describe Scene Check
        if (text.contains("describe") ||
            text.contains("what is in front") ||
            text.contains("what's in front") ||
            text.contains("what is ahead") ||
            text.contains("what's ahead") ||
            text.contains("what is this") ||
            text.contains("what's this") ||
            text.contains("where am i") ||
            text.contains("look around") ||
            text.contains("what do you see") ||
            text.contains("what can you see") ||
            text.contains("what is around") ||
            text.contains("what's around") ||
            text.contains("describe surroundings") ||
            text.contains("describe my surroundings") ||
            text.contains("what is blocking")
        ) {
            return ParsedCommand.DescribeScene
        }

        // 11. Find Object Check
        val findTriggers = listOf("find", "look for", "where is", "search for", "locate", "spot")
        for (trigger in findTriggers) {
            if (text.contains(trigger)) {
                var objName = text.substringAfter(trigger).trim()
                objName = objName.removePrefix("my ").removePrefix("the ")
                    .removePrefix("a ").removePrefix("an ").trim()
                if (objName.isNotEmpty()) {
                    return ParsedCommand.FindObject(objName)
                }
            }
        }

        // 12. Telemetry / Status Check
        if (text.contains("battery") || text.contains("status") || 
            text.contains("telemetry") || text.contains("temperature") ||
            text.contains("system health")) {
            return ParsedCommand.ReadTelemetry
        }

        // 13. Pause/Resume Vision Check
        if (text.contains("pause camera") || text.contains("sleep camera") || 
            text.contains("stop camera") || text.contains("disable camera") ||
            text.contains("pause vision") || text.contains("sleep vision")) {
            return ParsedCommand.PauseVision
        }
        
        if (text.contains("resume camera") || text.contains("wake camera") || 
            text.contains("start camera") || text.contains("enable camera") ||
            text.contains("resume vision") || text.contains("wake vision") ||
            text.contains("open camera") || text.contains("turn on camera")) {
            return ParsedCommand.ResumeVision
        }

        // 14. General question fallback (send everything else to Gemini/LLM)
        return ParsedCommand.GeneralQuestion(text)
    }

    private fun parseMessageCommand(text: String, isWhatsApp: Boolean): ParsedCommand? {
        // Look for the target after " to "
        if (text.contains(" to ")) {
            val contactRaw = text.substringAfter(" to ")
            
            // Check if they dictated a message using "saying" or "that"
            val (contact, message) = when {
                contactRaw.contains(" saying ") -> {
                    Pair(contactRaw.substringBefore(" saying ").trim(), contactRaw.substringAfter(" saying ").trim())
                }
                contactRaw.contains(" that ") -> {
                    Pair(contactRaw.substringBefore(" that ").trim(), contactRaw.substringAfter(" that ").trim())
                }
                else -> {
                    Pair(contactRaw.trim(), "") // No message dictated!
                }
            }
            
            val cleanContact = cleanContactName(contact)
            if (cleanContact.isNotEmpty()) {
                return if (isWhatsApp) ParsedCommand.SendWhatsApp(cleanContact, message)
                else ParsedCommand.SendSMS(cleanContact, message)
            }
        }

        // Pattern: "text/message [contact] [message]" (no "to" or "saying")
        val shortTriggers = listOf("text ", "message ")
        for (trigger in shortTriggers) {
            if (text.startsWith(trigger)) {
                val remainder = text.removePrefix(trigger).trim()
                // First word is likely the contact, rest is the message
                val parts = remainder.split(" ", limit = 2)
                if (parts.isNotEmpty()) {
                    val contact = cleanContactName(parts[0])
                    val message = if (parts.size == 2) parts[1] else ""
                    if (contact.isNotEmpty()) {
                        return if (isWhatsApp) ParsedCommand.SendWhatsApp(contact, message)
                        else ParsedCommand.SendSMS(contact, message)
                    }
                }
            }
        }

        return null
    }

    /**
     * Strips common determiners and possessives from a contact name.
     */
    private fun cleanContactName(name: String): String {
        return name.removePrefix("my ").removePrefix("the ")
            .removePrefix("a ").removePrefix("to ").trim()
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
            SpeechRecognizer.ERROR_SERVER -> "Error from server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Didn't understand, please try again."
        }
    }
}
