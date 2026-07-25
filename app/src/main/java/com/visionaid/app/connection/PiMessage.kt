package com.visionaid.app.connection

/**
 * VisionAid Pi ↔ App Message Protocol.
 *
 * All communication between the Android app and the Raspberry Pi
 * wearable flows through this sealed hierarchy. Messages are
 * serialized to JSON for transport over WebSocket or Bluetooth.
 *
 * Direction conventions:
 * - [Outgoing]: App → Pi (commands, requests)
 * - [Incoming]: Pi → App (responses, telemetry, warnings)
 */
sealed class PiMessage {

    /** Unique message ID for request-response correlation. */
    abstract val id: String

    /** Unix timestamp in milliseconds when the message was created. */
    abstract val timestamp: Long

    // ══════════════════════════════════════════════════════════════
    //  OUTGOING: App → Pi
    // ══════════════════════════════════════════════════════════════

    /** Messages sent FROM the app TO the Pi wearable. */
    sealed class Outgoing : PiMessage() {

        /**
         * "What is in front of me?" — triggers the Pi camera to
         * capture and analyze the scene.
         */
        data class DescribeScene(
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Outgoing()

        /**
         * "Find my [object]" — asks the Pi to search for a specific
         * object using its camera.
         *
         * @param objectName The object to search for (e.g., "keys", "door", "chair")
         */
        data class FindObject(
            val objectName: String,
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Outgoing()

        /**
         * Request the Pi's current telemetry (CPU temp, battery, camera status).
         */
        data class RequestTelemetry(
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Outgoing()

        /**
         * Heartbeat ping — keeps the connection alive and measures latency.
         */
        data class Ping(
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Outgoing()

        /**
         * Pause heavy vision AI tasks (triggered by thermal throttling).
         */
        data class PauseVisionAI(
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Outgoing()

        /**
         * Resume vision AI tasks after cooldown.
         */
        data class ResumeVisionAI(
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Outgoing()
    }

    // ══════════════════════════════════════════════════════════════
    //  INCOMING: Pi → App
    // ══════════════════════════════════════════════════════════════

    /** Messages received FROM the Pi BY the app. */
    sealed class Incoming : PiMessage() {

        /**
         * Scene description result from the Pi's vision AI.
         *
         * @param description Human-readable scene description for TTS
         * @param confidence Confidence score 0.0–1.0
         * @param requestId The ID of the original [Outgoing.DescribeScene] request
         */
        data class SceneDescription(
            val description: String,
            val confidence: Float,
            val requestId: String,
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Incoming()

        /**
         * Object search result.
         *
         * @param objectName The object that was searched for
         * @param found Whether the object was detected
         * @param direction Relative direction ("left", "right", "ahead", "behind")
         * @param distance Estimated distance in meters, null if unknown
         * @param requestId The ID of the original [Outgoing.FindObject] request
         */
        data class ObjectFound(
            val objectName: String,
            val found: Boolean,
            val direction: String? = null,
            val distance: Float? = null,
            val requestId: String,
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Incoming()

        /**
         * CRITICAL SAFETY MESSAGE — obstacle detected by ToF sensors.
         *
         * This message is high-priority and triggers immediate haptic
         * feedback scaled to proximity.
         *
         * @param proximity 0.0 (far) to 1.0 (imminent collision)
         * @param direction Direction of obstacle ("left", "center", "right")
         * @param distanceCm Distance in centimeters
         */
        data class ObstacleWarning(
            val proximity: Float,
            val direction: String,
            val distanceCm: Int,
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Incoming()

        /**
         * Pi system telemetry data.
         *
         * @param cpuTempCelsius CPU temperature in °C
         * @param batteryPercent Battery level 0–100, null if not on battery
         * @param cameraConnected Whether the Pi Camera is active
         * @param tofSensorActive Whether the ToF distance sensors are active
         * @param visionAIPaused Whether heavy AI tasks are currently paused
         */
        data class Telemetry(
            val cpuTempCelsius: Float,
            val batteryPercent: Int? = null,
            val cameraConnected: Boolean = true,
            val tofSensorActive: Boolean = true,
            val visionAIPaused: Boolean = false,
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Incoming()

        /**
         * Heartbeat response to our [Outgoing.Ping].
         *
         * @param pingId The ID of the [Outgoing.Ping] we sent
         */
        data class Pong(
            val pingId: String,
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Incoming()

        /**
         * Physical hardware button pressed on the Pi enclosure.
         *
         * @param buttonAction "single_press", "long_press", or "double_press"
         */
        data class HardwareButtonPress(
            val buttonAction: String,
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Incoming()

        /**
         * Error from the Pi (camera failure, sensor error, etc.).
         *
         * @param errorCode Machine-readable error code
         * @param message Human-readable error description
         */
        data class PiError(
            val errorCode: String,
            val message: String,
            override val id: String = generateId(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Incoming()
    }

    companion object {
        /** Generates a unique message ID. */
        fun generateId(): String = java.util.UUID.randomUUID().toString().take(8)
    }
}
