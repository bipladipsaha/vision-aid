package com.visionaid.app.connection

import android.util.Log
import org.json.JSONObject

/**
 * JSON serializer/deserializer for the [PiMessage] protocol.
 *
 * Wire format:
 * ```json
 * {
 *   "type": "describe_scene",
 *   "id": "a1b2c3d4",
 *   "timestamp": 1721484000000,
 *   "payload": { ... }
 * }
 * ```
 *
 * Uses Android's built-in [JSONObject] — no external library needed.
 * This keeps the dependency graph minimal and avoids Gson/Moshi proguard issues.
 */
object MessageSerializer {

    private const val TAG = "MessageSerializer"

    // ── Type constants (wire format identifiers) ─────────────────
    private const val TYPE_DESCRIBE_SCENE = "describe_scene"
    private const val TYPE_FIND_OBJECT = "find_object"
    private const val TYPE_REQUEST_TELEMETRY = "request_telemetry"
    private const val TYPE_PING = "ping"
    private const val TYPE_PAUSE_VISION = "pause_vision"
    private const val TYPE_RESUME_VISION = "resume_vision"

    private const val TYPE_SCENE_DESCRIPTION = "scene_description"
    private const val TYPE_OBJECT_FOUND = "object_found"
    private const val TYPE_OBSTACLE_WARNING = "obstacle_warning"
    private const val TYPE_TELEMETRY = "telemetry"
    private const val TYPE_PONG = "pong"
    private const val TYPE_HARDWARE_BUTTON = "hardware_button"
    private const val TYPE_PI_ERROR = "pi_error"

    // ══════════════════════════════════════════════════════════════
    //  SERIALIZE: PiMessage → JSON String
    // ══════════════════════════════════════════════════════════════

    /**
     * Serializes an [Outgoing][PiMessage.Outgoing] message to a JSON string
     * for transmission over the transport layer.
     */
    fun serialize(message: PiMessage.Outgoing): String {
        val json = JSONObject().apply {
            put("id", message.id)
            put("timestamp", message.timestamp)

            when (message) {
                is PiMessage.Outgoing.DescribeScene -> {
                    put("type", TYPE_DESCRIBE_SCENE)
                }

                is PiMessage.Outgoing.FindObject -> {
                    put("type", TYPE_FIND_OBJECT)
                    put("payload", JSONObject().apply {
                        put("object_name", message.objectName)
                    })
                }

                is PiMessage.Outgoing.RequestTelemetry -> {
                    put("type", TYPE_REQUEST_TELEMETRY)
                }

                is PiMessage.Outgoing.Ping -> {
                    put("type", TYPE_PING)
                }

                is PiMessage.Outgoing.PauseVisionAI -> {
                    put("type", TYPE_PAUSE_VISION)
                }

                is PiMessage.Outgoing.ResumeVisionAI -> {
                    put("type", TYPE_RESUME_VISION)
                }
            }
        }
        return json.toString()
    }

    // ══════════════════════════════════════════════════════════════
    //  DESERIALIZE: JSON String → PiMessage.Incoming
    // ══════════════════════════════════════════════════════════════

    /**
     * Deserializes a JSON string from the Pi into an [Incoming][PiMessage.Incoming]
     * message. Returns null if the message is malformed or unrecognized.
     */
    fun deserialize(jsonString: String): PiMessage.Incoming? {
        return try {
            val json = JSONObject(jsonString)
            val type = json.getString("type")
            val id = json.optString("id", PiMessage.generateId())
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val payload = json.optJSONObject("payload")

            when (type) {
                TYPE_SCENE_DESCRIPTION -> PiMessage.Incoming.SceneDescription(
                    description = payload?.getString("description") ?: "",
                    confidence = payload?.optDouble("confidence", 0.0)?.toFloat() ?: 0f,
                    requestId = payload?.optString("request_id", "") ?: "",
                    id = id,
                    timestamp = timestamp
                )

                TYPE_OBJECT_FOUND -> PiMessage.Incoming.ObjectFound(
                    objectName = payload?.getString("object_name") ?: "",
                    found = payload?.getBoolean("found") ?: false,
                    direction = payload?.optString("direction")?.ifEmpty { null },
                    distance = payload?.optDouble("distance", -1.0)?.toFloat()?.let { if (it < 0) null else it },
                    requestId = payload?.optString("request_id", "") ?: "",
                    id = id,
                    timestamp = timestamp
                )

                TYPE_OBSTACLE_WARNING -> PiMessage.Incoming.ObstacleWarning(
                    proximity = payload?.optDouble("proximity", 0.0)?.toFloat() ?: 0f,
                    direction = payload?.optString("direction", "center") ?: "center",
                    distanceCm = payload?.optInt("distance_cm", 0) ?: 0,
                    id = id,
                    timestamp = timestamp
                )

                TYPE_TELEMETRY -> PiMessage.Incoming.Telemetry(
                    cpuTempCelsius = payload?.optDouble("cpu_temp", 0.0)?.toFloat() ?: 0f,
                    batteryPercent = if (payload?.has("battery_percent") == true)
                        payload.optInt("battery_percent") else null,
                    cameraConnected = payload?.optBoolean("camera_connected", true) ?: true,
                    tofSensorActive = payload?.optBoolean("tof_active", true) ?: true,
                    visionAIPaused = payload?.optBoolean("vision_paused", false) ?: false,
                    id = id,
                    timestamp = timestamp
                )

                TYPE_PONG -> PiMessage.Incoming.Pong(
                    pingId = payload?.optString("ping_id", "") ?: "",
                    id = id,
                    timestamp = timestamp
                )

                TYPE_HARDWARE_BUTTON -> PiMessage.Incoming.HardwareButtonPress(
                    buttonAction = payload?.optString("action", "single_press") ?: "single_press",
                    id = id,
                    timestamp = timestamp
                )

                TYPE_PI_ERROR -> PiMessage.Incoming.PiError(
                    errorCode = payload?.optString("error_code", "UNKNOWN") ?: "UNKNOWN",
                    message = payload?.optString("message", "Unknown error") ?: "Unknown error",
                    id = id,
                    timestamp = timestamp
                )

                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize message: $jsonString", e)
            null
        }
    }
}
