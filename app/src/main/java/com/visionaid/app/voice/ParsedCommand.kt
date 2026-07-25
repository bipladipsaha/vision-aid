package com.visionaid.app.voice

/**
 * Result of the VoiceCommandEngine parsing user speech.
 *
 * Each subclass represents a distinct user intent that the
 * [CommandRouter] can dispatch to the appropriate manager.
 */
sealed class ParsedCommand {

    // ── Vision / Pi Commands ─────────────────────────────────────

    /** "Describe scene", "What is in front of me", "What's this" */
    data object DescribeScene : ParsedCommand()

    /** "Find my keys", "Look for a chair", "Where is the door" */
    data class FindObject(val objectName: String) : ParsedCommand()

    /** "How far", "What is the distance", "How far is the obstacle" */
    data object GetDistance : ParsedCommand()

    /** "Read telemetry", "Battery level", "System status" */
    data object ReadTelemetry : ParsedCommand()

    /** "Pause camera", "Sleep camera", "Stop camera" */
    data object PauseVision : ParsedCommand()

    /** "Resume camera", "Wake camera", "Start camera" */
    data object ResumeVision : ParsedCommand()

    // ── Telephony ────────────────────────────────────────────────

    /** "Call mom", "Phone John", "Dial 9876543210" */
    data class MakeCall(val contactNameOrNumber: String) : ParsedCommand()

    /** "Answer", "Pick up" */
    data object AnswerCall : ParsedCommand()

    /** "Reject", "Decline", "Hang up" */
    data object RejectCall : ParsedCommand()

    // ── Messaging ────────────────────────────────────────────────

    /** "Send message to mom saying I will be late" */
    data class SendSMS(val contactName: String, val messageBody: String) : ParsedCommand()

    /** "Send WhatsApp message to John saying hello" */
    data class SendWhatsApp(val contactName: String, val messageBody: String) : ParsedCommand()

    // ── App Launcher ─────────────────────────────────────────────

    /** "Open YouTube", "Open messages", "Launch WhatsApp" */
    data class OpenApp(val appName: String) : ParsedCommand()

    // ── Conversational / General ─────────────────────────────────

    /** Any general question: "What is the capital of India?" */
    data class GeneralQuestion(val question: String) : ParsedCommand()

    // ── Control ──────────────────────────────────────────────────

    /** "Stop", "Cancel", "Shut up" */
    data object Stop : ParsedCommand()

    /** "Repeat", "Say that again" */
    data object Repeat : ParsedCommand()

    // ── System ───────────────────────────────────────────────────

    /** System launch hotword detected ("Hey Vision") */
    data object WakeWordDetected : ParsedCommand()

    /** Speech was recognized, but intent could not be parsed */
    data class Unknown(val rawText: String) : ParsedCommand()

    /** Empty or noise */
    data object None : ParsedCommand()
}
