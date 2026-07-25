package com.visionaid.app.assistant

import android.util.Log
import com.visionaid.app.connection.PiConnectionManager
import com.visionaid.app.connection.PiMessage
import com.visionaid.app.launcher.AppLauncher
import com.visionaid.app.messaging.SmsManager
import com.visionaid.app.messaging.WhatsAppManager
import com.visionaid.app.telephony.CallManager
import com.visionaid.app.voice.ParsedCommand
import com.visionaid.app.voice.TextToSpeechEngine
import com.visionaid.app.voice.VoiceCommandEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central command dispatcher for VisionAid.
 *
 * Routes every [ParsedCommand] to the appropriate manager and
 * speaks the result through [TextToSpeechEngine].
 *
 * Maintains [ConversationContext] so follow-up commands
 * ("How far?", "Repeat") work naturally.
 *
 * ```
 * User Speech
 *     ↓
 * VoiceCommandEngine.parseIntent()
 *     ↓
 * ParsedCommand
 *     ↓
 * CommandRouter.execute()    ←── YOU ARE HERE
 *     ↓
 * ┌──────────────────┐
 * │ CallManager       │  "Call mom"
 * │ SmsManager        │  "Text john hello"
 * │ WhatsAppManager   │  "WhatsApp mom hi"
 * │ AppLauncher       │  "Open YouTube"
 * │ KnowledgeEngine   │  "What time is it?"
 * │ PiConnectionMgr   │  "Describe scene"
 * └──────────────────┘
 *     ↓
 * TextToSpeechEngine.speak()
 *     ↓
 * Earbuds 🔊
 * ```
 */
@Singleton
class CommandRouter @Inject constructor(
    private val ttsEngine: TextToSpeechEngine,
    private val callManager: CallManager,
    private val smsManager: SmsManager,
    private val whatsAppManager: WhatsAppManager,
    private val appLauncher: AppLauncher,
    private val knowledgeEngine: KnowledgeEngine,
    private val conversationContext: ConversationContext,
    private val connectionManager: PiConnectionManager,
    private val voiceCommandEngine: VoiceCommandEngine
) {
    companion object {
        private const val TAG = "CommandRouter"
    }

    /**
     * Executes a parsed voice command.
     * This is the single entry point for all command handling.
     */
    suspend fun execute(command: ParsedCommand) {
        Log.i(TAG, "Executing: $command")

        when (command) {
            // ── Vision / Pi Commands ─────────────────────────────
            is ParsedCommand.DescribeScene -> {
                speak("Describing your surroundings")
                connectionManager.send(PiMessage.Outgoing.DescribeScene())
                conversationContext.update(action = "describe")
            }

            is ParsedCommand.FindObject -> {
                speak("Looking for ${command.objectName}")
                connectionManager.send(PiMessage.Outgoing.FindObject(command.objectName))
                conversationContext.update(
                    objectName = command.objectName,
                    action = "find"
                )
            }

            is ParsedCommand.GetDistance -> {
                if (conversationContext.isContextFresh() && conversationContext.lastObject != null) {
                    speak("Checking distance to ${conversationContext.lastObject}")
                } else {
                    speak("Checking distance ahead")
                }
                connectionManager.send(PiMessage.Outgoing.RequestTelemetry())
                conversationContext.update(action = "distance")
            }

            is ParsedCommand.ReadTelemetry -> {
                speak("Checking system status")
                connectionManager.send(PiMessage.Outgoing.RequestTelemetry())
                conversationContext.update(action = "telemetry")
            }

            // ── Telephony ────────────────────────────────────────
            is ParsedCommand.MakeCall -> {
                speak("Calling ${command.contactNameOrNumber}")
                val result = callManager.makeCall(command.contactNameOrNumber)
                handleCallResult(result, command.contactNameOrNumber)
            }

            is ParsedCommand.AnswerCall -> {
                val success = callManager.answerCall()
                if (success) {
                    speak("Call answered")
                } else {
                    speak("Could not answer the call")
                }
            }

            is ParsedCommand.RejectCall -> {
                val success = callManager.rejectCall()
                if (success) {
                    speak("Call rejected")
                } else {
                    speak("Could not reject the call")
                }
            }

            // ── Messaging ────────────────────────────────────────
            is ParsedCommand.SendSMS -> {
                speak("Sending message to ${command.contactName}")
                val result = smsManager.sendSms(command.contactName, command.messageBody)
                handleSmsResult(result)
            }

            is ParsedCommand.SendWhatsApp -> {
                speak("Opening WhatsApp for ${command.contactName}")
                val result = whatsAppManager.sendWhatsAppMessage(
                    command.contactName, command.messageBody
                )
                handleWhatsAppResult(result)
            }

            // ── App Launcher ─────────────────────────────────────
            is ParsedCommand.OpenApp -> {
                speak("Opening ${command.appName}")
                val result = appLauncher.launchApp(command.appName)
                handleLaunchResult(result, command.appName)
            }

            // ── Conversational / General ─────────────────────────
            is ParsedCommand.GeneralQuestion -> {
                val result = knowledgeEngine.answer(command.question)
                when (result) {
                    is KnowledgeEngine.AnswerResult.DirectAnswer -> {
                        speak(result.answer)
                        conversationContext.update(
                            response = result.answer,
                            action = "answer"
                        )
                    }
                    is KnowledgeEngine.AnswerResult.SearchSuggestion -> {
                        speak("I'm not sure about that. You can try asking Google.")
                        conversationContext.update(action = "search")
                    }
                }
            }

            // ── Control ──────────────────────────────────────────
            is ParsedCommand.Stop -> {
                ttsEngine.stop()
                conversationContext.clear()
                speak("Stopped")
            }

            is ParsedCommand.Repeat -> {
                val lastResponse = conversationContext.lastResponse
                if (lastResponse != null && conversationContext.isContextFresh()) {
                    speak(lastResponse)
                } else {
                    speak("I don't have anything to repeat")
                }
            }

            // ── System ───────────────────────────────────────────
            is ParsedCommand.WakeWordDetected -> {
                speak("Yes?")
                conversationContext.update(action = "wake")
            }

            is ParsedCommand.Unknown -> {
                speak("Sorry, I didn't understand that. Can you say it again?")
            }

            is ParsedCommand.None -> {
                // Ignore empty recognition
            }
        }
    }

    // ── Result Handlers ──────────────────────────────────────────

    private suspend fun handleCallResult(result: CallManager.CallResult, contactName: String) {
        when (result) {
            is CallManager.CallResult.Success -> {
                conversationContext.update(response = "Calling $contactName", action = "call")
            }
            is CallManager.CallResult.NeedsDisambiguation -> {
                val names = result.matches.take(3).joinToString(", ") { it.displayName }
                speak("I found multiple contacts: $names. Can you tell me more specifically?", waitForReply = true)
                conversationContext.update(action = "disambiguate")
            }
            is CallManager.CallResult.ContactNotFound -> {
                speak("I couldn't find a contact named $contactName. Can you tell me more specifically?", waitForReply = true)
            }
            is CallManager.CallResult.Error -> {
                speak(result.message)
            }
        }
    }

    private suspend fun handleSmsResult(result: SmsManager.SmsResult) {
        when (result) {
            is SmsManager.SmsResult.Success -> {
                val msg = "Message sent to ${result.recipientName}"
                speak(msg)
                conversationContext.update(response = msg, action = "sms")
            }
            is SmsManager.SmsResult.NeedsDisambiguation -> {
                val names = result.matches.take(3).joinToString(", ") { it.displayName }
                speak("I found multiple contacts: $names. Can you tell me more specifically?", waitForReply = true)
            }
            is SmsManager.SmsResult.ContactNotFound -> {
                speak("I couldn't find that contact in your address book.")
            }
            is SmsManager.SmsResult.Error -> {
                speak(result.message)
            }
        }
    }

    private suspend fun handleWhatsAppResult(result: WhatsAppManager.WhatsAppResult) {
        when (result) {
            is WhatsAppManager.WhatsAppResult.Success -> {
                val msg = "WhatsApp opened for ${result.recipientName}. Please tap send."
                speak(msg)
                conversationContext.update(response = msg, action = "whatsapp")
            }
            is WhatsAppManager.WhatsAppResult.NeedsDisambiguation -> {
                val names = result.matches.take(3).joinToString(", ") { it.displayName }
                speak("I found multiple contacts: $names. Can you tell me more specifically?", waitForReply = true)
            }
            is WhatsAppManager.WhatsAppResult.ContactNotFound -> {
                speak("I couldn't find that contact. Can you tell me more specifically?", waitForReply = true)
            }
            is WhatsAppManager.WhatsAppResult.WhatsAppNotInstalled -> {
                speak("WhatsApp is not installed on this device.")
            }
            is WhatsAppManager.WhatsAppResult.Error -> {
                speak(result.message)
            }
        }
    }

    private suspend fun handleLaunchResult(result: AppLauncher.LaunchResult, appName: String) {
        when (result) {
            is AppLauncher.LaunchResult.Success -> {
                val msg = "${result.appName} opened"
                conversationContext.update(response = msg, action = "open_app")
            }
            is AppLauncher.LaunchResult.AppNotFound -> {
                speak("I couldn't find an app called $appName")
            }
            is AppLauncher.LaunchResult.Error -> {
                speak(result.message)
            }
        }
    }

    /**
     * Speaks text and saves it to context for the "Repeat" command.
     * Also shows a Toast for visual debugging.
     * If waitForReply is true, it waits for speech to finish and instantly restarts listening.
     */
    private suspend fun speak(text: String, waitForReply: Boolean = false) {
        // Show a visual Toast so the user knows what's happening even if TTS is muted/broken
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Log.e(TAG, "ASSISTANT SPEAKS: $text")
        }
        
        conversationContext.update(response = text)
        
        if (waitForReply) {
            ttsEngine.speakAndWait(text)
            // Wait a tiny bit for audio buffer to clear
            kotlinx.coroutines.delay(200)
            voiceCommandEngine.startListening()
        } else {
            ttsEngine.speak(text)
        }
    }
}
