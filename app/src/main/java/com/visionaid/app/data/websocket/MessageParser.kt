package com.visionaid.app.data.websocket

import android.util.Log
import com.visionaid.app.data.model.HardwareButton
import com.visionaid.app.data.model.ObstacleWarning
import com.visionaid.app.data.model.PiMessage
import com.visionaid.app.data.model.Telemetry
import org.json.JSONObject

object MessageParser {
    private const val TAG = "MessageParser"

    fun serialize(message: PiMessage.Outgoing): String {
        val json = JSONObject().apply {
            put("id", message.id)
            put("timestamp", message.timestamp)
            when (message) {
                is PiMessage.Outgoing.DescribeScene -> put("type", "describe_scene")
                is PiMessage.Outgoing.FindObject -> {
                    put("type", "find_object")
                    put("payload", JSONObject().apply { put("object_name", message.objectName) })
                }
                is PiMessage.Outgoing.RequestTelemetry -> put("type", "request_telemetry")
                is PiMessage.Outgoing.Ping -> put("type", "ping")
                is PiMessage.Outgoing.PauseVisionAI -> put("type", "pause_vision")
                is PiMessage.Outgoing.ResumeVisionAI -> put("type", "resume_vision")
            }
        }
        return json.toString()
    }

    fun deserialize(jsonString: String): PiMessage.Incoming? {
        return try {
            val json = JSONObject(jsonString)
            val type = json.getString("type")
            val payload = json.optJSONObject("payload")
            val requestId = payload?.optString("request_id", "") ?: ""
            
            when (type) {
                "scene_description" -> PiMessage.Incoming.SceneDescription(
                    description = payload?.getString("description") ?: "",
                    confidence = payload?.optDouble("confidence", 0.0)?.toFloat() ?: 0f,
                    requestId = requestId
                )
                "object_found" -> PiMessage.Incoming.ObjectFound(
                    objectName = payload?.getString("object_name") ?: "",
                    found = payload?.getBoolean("found") ?: false,
                    direction = payload?.optString("direction")?.ifEmpty { null },
                    distance = payload?.optDouble("distance", -1.0)?.toFloat()?.let { if (it < 0) null else it },
                    requestId = requestId
                )
                "obstacle_warning" -> PiMessage.Incoming.Warning(
                    ObstacleWarning(
                        proximity = payload?.optDouble("proximity", 0.0)?.toFloat() ?: 0f,
                        direction = payload?.optString("direction", "center") ?: "center",
                        distanceCm = payload?.optInt("distance_cm", 0) ?: 0
                    )
                )
                "telemetry" -> PiMessage.Incoming.TelemetryData(
                    Telemetry(
                        cpuTempCelsius = payload?.optDouble("cpu_temp", 0.0)?.toFloat() ?: 0f,
                        batteryPercent = if (payload?.has("battery_percent") == true) payload.optInt("battery_percent") else null,
                        cameraConnected = payload?.optBoolean("camera_connected", true) ?: true,
                        tofSensorActive = payload?.optBoolean("tof_active", true) ?: true,
                        visionAIPaused = payload?.optBoolean("vision_paused", false) ?: false
                    )
                )
                "pong" -> PiMessage.Incoming.Pong(pingId = payload?.optString("ping_id", "") ?: "")
                "hardware_button" -> PiMessage.Incoming.HardwareButtonPress(
                    HardwareButton(buttonAction = payload?.optString("action", "single_press") ?: "single_press")
                )
                "pi_error" -> PiMessage.Incoming.PiError(
                    errorCode = payload?.optString("error_code", "UNKNOWN") ?: "UNKNOWN",
                    message = payload?.optString("message", "Unknown error") ?: "Unknown error"
                )
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse: $jsonString", e)
            null
        }
    }
}
