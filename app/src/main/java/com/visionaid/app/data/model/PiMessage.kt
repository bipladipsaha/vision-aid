package com.visionaid.app.data.model

import java.util.UUID

sealed class PiMessage {
    val id: String = UUID.randomUUID().toString()
    val timestamp: Long = System.currentTimeMillis()

    sealed class Outgoing : PiMessage() {
        object DescribeScene : Outgoing()
        data class FindObject(val objectName: String) : Outgoing()
        object RequestTelemetry : Outgoing()
        object Ping : Outgoing()
        object PauseVisionAI : Outgoing()
        object ResumeVisionAI : Outgoing()
    }

    sealed class Incoming : PiMessage() {
        data class SceneDescription(
            val description: String,
            val confidence: Float,
            val requestId: String
        ) : Incoming()

        data class ObjectFound(
            val objectName: String,
            val found: Boolean,
            val direction: String?,
            val distance: Float?,
            val requestId: String
        ) : Incoming()

        data class TelemetryData(val telemetry: Telemetry) : Incoming()
        data class Warning(val obstacleWarning: ObstacleWarning) : Incoming()
        data class HardwareButtonPress(val hardwareButton: HardwareButton) : Incoming()
        data class Pong(val pingId: String) : Incoming()
        data class PiError(val errorCode: String, val message: String) : Incoming()
    }
}
