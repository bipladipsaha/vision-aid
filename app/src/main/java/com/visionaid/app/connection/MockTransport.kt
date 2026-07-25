package com.visionaid.app.connection

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mock transport for testing the app without Pi hardware.
 *
 * Simulates:
 * - Connection with configurable delay
 * - Periodic telemetry data
 * - Obstacle warnings at random intervals
 * - Responses to commands (scene descriptions, object searches)
 * - Hardware button presses
 *
 * Enable this transport for development and demo purposes.
 * When real hardware is ready, the [PiConnectionManager] will
 * automatically prefer WebSocket/Bluetooth.
 */
@Singleton
class MockTransport @Inject constructor() : PiTransport {

    companion object {
        private const val TAG = "MockTransport"
        private const val TELEMETRY_INTERVAL_MS = 5000L
        private const val OBSTACLE_INTERVAL_MS = 3000L
    }

    override val transportName: String = "Mock (No Hardware)"

    private val _connectionState = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val connectionState: StateFlow<TransportState> = _connectionState.asStateFlow()

    private val _incomingChannel = Channel<PiMessage.Incoming>(Channel.BUFFERED)
    override val incomingMessages: Flow<PiMessage.Incoming> = _incomingChannel.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Track simulation jobs so we can cancel them on disconnect. */
    private var simulationJob: kotlinx.coroutines.Job? = null

    /** Simulated CPU temperature — gradually rises. */
    private var simulatedCpuTemp = 42.0f

    /** Simulated battery percentage — gradually drops. */
    private var simulatedBattery = 85

    override suspend fun connect(address: String): Boolean {
        _connectionState.value = TransportState.Connecting
        Log.i(TAG, "Mock connecting... (simulated delay)")

        // Simulate connection handshake
        delay(800)

        _connectionState.value = TransportState.Connected
        Log.i(TAG, "Mock connected as '$address'")

        // Start background simulations
        startSimulations()
        return true
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Mock disconnecting")
        simulationJob?.cancel()
        simulationJob = null
        _connectionState.value = TransportState.Disconnected
    }

    override suspend fun send(message: PiMessage.Outgoing): Boolean {
        if (_connectionState.value != TransportState.Connected) {
            Log.w(TAG, "Cannot send — mock not connected")
            return false
        }

        Log.d(TAG, "Mock received command: ${message::class.simpleName}")

        // Generate mock responses for each command type
        scope.launch {
            delay(300) // Simulate processing time
            val response = generateResponse(message)
            if (response != null) {
                _incomingChannel.send(response)
            }
        }

        return true
    }

    override suspend fun isAvailable(): Boolean = true // Mock is always available

    /**
     * Generates a realistic mock response for each outgoing command.
     */
    private fun generateResponse(message: PiMessage.Outgoing): PiMessage.Incoming? {
        return when (message) {
            is PiMessage.Outgoing.DescribeScene -> PiMessage.Incoming.SceneDescription(
                description = "You are in a well-lit room. There is a desk with a laptop " +
                        "directly ahead, approximately two meters away. A doorway is to " +
                        "your left. The floor is clear of obstacles.",
                confidence = 0.87f,
                requestId = message.id
            )

            is PiMessage.Outgoing.FindObject -> {
                val found = message.objectName.lowercase() in listOf(
                    "desk", "chair", "door", "laptop", "phone", "keys", "cup"
                )
                PiMessage.Incoming.ObjectFound(
                    objectName = message.objectName,
                    found = found,
                    direction = if (found) listOf("left", "right", "ahead").random() else null,
                    distance = if (found) (0.5f + (Math.random() * 3.0).toFloat()) else null,
                    requestId = message.id
                )
            }

            is PiMessage.Outgoing.RequestTelemetry -> PiMessage.Incoming.Telemetry(
                cpuTempCelsius = simulatedCpuTemp,
                batteryPercent = simulatedBattery,
                cameraConnected = true,
                tofSensorActive = true,
                visionAIPaused = false
            )

            is PiMessage.Outgoing.Ping -> PiMessage.Incoming.Pong(
                pingId = message.id
            )

            is PiMessage.Outgoing.PauseVisionAI -> {
                Log.i(TAG, "Mock: Vision AI paused")
                null
            }

            is PiMessage.Outgoing.ResumeVisionAI -> {
                Log.i(TAG, "Mock: Vision AI resumed")
                null
            }
        }
    }

    /**
     * Starts background coroutines that simulate the Pi sending
     * periodic telemetry and occasional obstacle warnings.
     */
    private fun startSimulations() {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            // Telemetry every 5 seconds
            launch {
                while (isActive) {
                    delay(TELEMETRY_INTERVAL_MS)
                    if (_connectionState.value == TransportState.Connected) {
                        // Simulate gradual temp rise and battery drain
                        simulatedCpuTemp = (simulatedCpuTemp + (Math.random() * 0.5).toFloat())
                            .coerceAtMost(85f)
                        simulatedBattery = (simulatedBattery - 1).coerceAtLeast(5)

                        _incomingChannel.send(
                            PiMessage.Incoming.Telemetry(
                                cpuTempCelsius = simulatedCpuTemp,
                                batteryPercent = simulatedBattery,
                                cameraConnected = true,
                                tofSensorActive = true,
                                visionAIPaused = simulatedCpuTemp > 75f
                            )
                        )
                    }
                }
            }

            // Occasional obstacle warnings
            launch {
                while (isActive) {
                    delay(OBSTACLE_INTERVAL_MS + (Math.random() * 4000).toLong())
                    if (_connectionState.value == TransportState.Connected) {
                        val proximity = (Math.random() * 0.8).toFloat() + 0.1f
                        _incomingChannel.send(
                            PiMessage.Incoming.ObstacleWarning(
                                proximity = proximity,
                                direction = listOf("left", "center", "right").random(),
                                distanceCm = ((1.0f - proximity) * 200).toInt()
                            )
                        )
                    }
                }
            }
        }
    }
}
