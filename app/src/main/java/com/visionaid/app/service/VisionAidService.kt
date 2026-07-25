package com.visionaid.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.visionaid.app.MainActivity
import com.visionaid.app.R
import com.visionaid.app.VisionAidApp
import com.visionaid.app.assistant.CommandRouter
import com.visionaid.app.voice.WakeWordEngine
import com.visionaid.app.connection.ConnectionManagerState
import com.visionaid.app.connection.ConnectionStatus
import com.visionaid.app.connection.PiConnectionManager
import com.visionaid.app.connection.PiMessage
import com.visionaid.app.haptics.HapticEngine
import com.visionaid.app.settings.SettingsRepository
import com.visionaid.app.ui.gesture.VisionGesture
import com.visionaid.app.ui.gesture.VolumeController
import com.visionaid.app.voice.ParsedCommand
import com.visionaid.app.voice.TextToSpeechEngine
import com.visionaid.app.voice.VoiceCommandEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The always-alive foreground service — the heartbeat of VisionAid AI.
 *
 * This service:
 * - Runs as a persistent foreground service so Android doesn't kill it
 * - Hosts the [PiConnectionManager] for dual-transport communication
 * - Routes incoming messages to haptic feedback and UI state
 * - Handles obstacle warnings even when the screen is off
 * - Manages thermal throttling (Phase 6) and voice commands (Phase 4)
 *
 * The service uses [LifecycleService] for coroutine scope awareness,
 * so all background work is automatically cancelled when the service dies.
 */
@AndroidEntryPoint
class VisionAidService : LifecycleService() {

    companion object {
        private const val TAG = "VisionAidService"
        private const val NOTIFICATION_ID = 1001

        /** CPU temperature threshold for thermal throttling (°C). */
        private const val THERMAL_THROTTLE_TEMP = 75.0f
    }

    @Inject
    lateinit var hapticEngine: HapticEngine

    @Inject
    lateinit var connectionManager: PiConnectionManager

    @Inject
    lateinit var volumeController: VolumeController

    @Inject
    lateinit var voiceCommandEngine: VoiceCommandEngine

    @Inject
    lateinit var ttsEngine: TextToSpeechEngine

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var commandRouter: CommandRouter

    @Inject
    lateinit var wakeWordEngine: WakeWordEngine

    /** Tracks if we have already warned the user about low battery to avoid spamming. */
    private var hasWarnedBatteryLow = false

    /** Binder for activity ↔ service communication. */
    private val binder = LocalBinder()

    /** Current state of the service, observable by the UI layer. */
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    /** Latest telemetry from the Pi, observable by the UI layer. */
    private val _latestTelemetry = MutableStateFlow<PiMessage.Incoming.Telemetry?>(null)
    val latestTelemetry: StateFlow<PiMessage.Incoming.Telemetry?> = _latestTelemetry.asStateFlow()

    /** Whether vision AI is currently paused due to thermal throttling. */
    private var visionAIPaused = false

    /** Tracks if the UI activity is currently bound (user has app open). */
    private var isAppInForeground = false

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        isAppInForeground = true
        Log.i(TAG, "Activity bound — app is in foreground")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isAppInForeground = false
        Log.i(TAG, "Activity unbound — app is in background")
        // Stop any queued speech when user leaves the app
        ttsEngine.stop()
        // Return true so onRebind is called when the activity re-binds
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        isAppInForeground = true
        Log.i(TAG, "Activity rebound — app is back in foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Start as foreground immediately to avoid ANR
        startForeground(NOTIFICATION_ID, createNotification())
        Log.i(TAG, "VisionAidService started as foreground")

        // Start observing connection state and messages
        observeConnectionState()
        observeIncomingMessages()
        observeVoiceCommands()
        observeWakeWord() // Added to ensure wake word actually triggers!

        // Wire the command-session-complete callback to resume wake word listening
        voiceCommandEngine.onCommandSessionComplete = {
            Log.i(TAG, "Command session complete, resuming wake word engine")
            wakeWordEngine.resume()
        }

        // Start the Vosk-powered offline wake word engine
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            wakeWordEngine.start()
        }

        // Haptic feedback that the service is alive
        hapticEngine.pulseServiceStarted()

        // First-launch spoken tutorial for blind users
        lifecycleScope.launch {
            val isFirstLaunch = settingsRepository.isFirstLaunchFlow.first()
            if (isFirstLaunch) {
                delay(2000) // Let the system settle first
                ttsEngine.speak(
                    "Welcome to Vision Aid. " +
                    "Double tap to give a voice command. " +
                    "Long press to describe your surroundings. " +
                    "Swipe up or down with two fingers to adjust volume. " +
                    "You can reopen this app anytime by saying Hey Vision AI. " +
                    "To use the power button, set Vision Aid as your default assistant in phone settings."
                )
                settingsRepository.setFirstLaunch(false)
            }
        }

        // Start hardware connection
        startWithHardware()

        // START_STICKY: Android restarts the service if it's killed
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "VisionAidService destroyed")
        connectionManager.stop()
        wakeWordEngine.stop()
        voiceCommandEngine.shutdown()
        ttsEngine.shutdown()
        lifecycleScope.launch {
            _serviceState.emit(ServiceState.Idle)
        }
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════
    //  CONNECTION MANAGEMENT
    // ══════════════════════════════════════════════════════════════

    fun startWithHardware(
        bluetoothAddress: String? = null,
        webSocketAddress: String = "ws://192.168.42.1:8765"
    ) {
        Log.i(TAG, "Starting with hardware (bt=$bluetoothAddress, ws=$webSocketAddress)")
        connectionManager.start(
            btAddress = bluetoothAddress,
            wsAddress = webSocketAddress
        )
    }

    /**
     * Send a command to the Pi via the active transport.
     *
     * @return true if the message was sent successfully
     */
    suspend fun sendCommand(message: PiMessage.Outgoing): Boolean {
        val sent = connectionManager.send(message)
        if (sent) {
            hapticEngine.pulseCommandAck()
        }
        return sent
    }

    /**
     * Handles gestures detected by the GesturePadScreen.
     */
    fun handleGesture(gesture: VisionGesture) {
        lifecycleScope.launch {
            when (gesture) {
                VisionGesture.DoubleTap -> {
                    Log.i(TAG, "Gesture: Double tap -> Voice Command")
                    
                    // CRITICAL: We must pause the always-on Vosk wake word engine so it releases 
                    // the microphone, otherwise the Google SpeechRecognizer will throw an error 
                    // complaining that VisionAid is currently recording.
                    wakeWordEngine.pause()
                    kotlinx.coroutines.delay(300) // Wait for microphone to physically release
                    
                    hapticEngine.pulseCommandAck()
                    ttsEngine.speak("Listening")
                    voiceCommandEngine.startListening()
                }
                VisionGesture.LongPress -> {
                    Log.i(TAG, "Gesture: Long press -> Describe Scene")
                    hapticEngine.pulseCommandAck()
                    sendCommand(PiMessage.Outgoing.DescribeScene())
                }
                VisionGesture.TwoFingerSwipeUp -> {
                    val percent = volumeController.volumeUp()
                    ttsEngine.speak("Volume $percent percent")
                    hapticEngine.pulseConnected()
                }
                VisionGesture.TwoFingerSwipeDown -> {
                    val percent = volumeController.volumeDown()
                    ttsEngine.speak("Volume $percent percent")
                    hapticEngine.pulseConnected()
                }
                VisionGesture.ThreeFingerSwipeDown -> {
                    Log.i(TAG, "Gesture: Three finger swipe down -> Settings")
                    hapticEngine.pulseConnected()
                }
            }
        }
    }

    /**
     * Bypasses the voice engine and directly asks the Pi to find a specific object.
     * Used for the UI demo buttons.
     */
    fun findObject(objectName: String) {
        lifecycleScope.launch {
            ttsEngine.speak("Looking for $objectName")
            sendCommand(PiMessage.Outgoing.ResumeVisionAI())
            kotlinx.coroutines.delay(100)
            sendCommand(PiMessage.Outgoing.FindObject(objectName))
        }
    }

    /**
     * Shuts down the camera on the Pi.
     */
    fun stopCamera() {
        lifecycleScope.launch {
            sendCommand(PiMessage.Outgoing.PauseVisionAI())
            ttsEngine.speak("Camera stopped")
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  STATE OBSERVATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Observes [PiConnectionManager.connectionState] and maps it
     * to [ServiceState] for the UI layer.
     */
    private fun observeConnectionState() {
        lifecycleScope.launch {
            connectionManager.connectionState.collect { cmState ->
                val newState = mapConnectionState(cmState)
                _serviceState.emit(newState)

                // Haptic feedback on state transitions
                when (newState) {
                    is ServiceState.Connected -> hapticEngine.pulseConnected()
                    is ServiceState.Error -> hapticEngine.buzzError()
                    else -> { /* no haptic for transitional states */ }
                }
            }
        }
    }

    /**
     * Maps [ConnectionManagerState] to our UI-facing [ServiceState].
     */
    private fun mapConnectionState(cmState: ConnectionManagerState): ServiceState {
        return when (cmState.status) {
            ConnectionStatus.DISCONNECTED -> {
                if (cmState.lastError != null) {
                    ServiceState.Error(cmState.lastError)
                } else {
                    ServiceState.WaitingForPi
                }
            }
            ConnectionStatus.CONNECTING -> ServiceState.Connecting
            ConnectionStatus.CONNECTED -> ServiceState.Connected(
                piName = cmState.transportName,
                transportType = cmState.activeTransportType?.name ?: "Unknown"
            )
            ConnectionStatus.RECONNECTING -> ServiceState.Reconnecting(
                lastError = cmState.lastError ?: "Connection lost"
            )
        }
    }

    /**
     * Observes all incoming messages from the Pi and routes them
     * to the appropriate handler.
     */
    private fun observeIncomingMessages() {
        lifecycleScope.launch {
            connectionManager.incomingMessages.collect { message ->
                handleIncomingMessage(message)
            }
        }
    }

    /**
     * Routes incoming messages to their handlers.
     * This is the central message dispatcher.
     */
    private suspend fun handleIncomingMessage(message: PiMessage.Incoming) {
        when (message) {
            is PiMessage.Incoming.ObstacleWarning -> handleObstacleWarning(message)
            is PiMessage.Incoming.Telemetry -> handleTelemetry(message)
            is PiMessage.Incoming.HardwareButtonPress -> handleHardwareButton(message)
            is PiMessage.Incoming.SceneDescription -> {
                Log.i(TAG, "Scene: ${message.description}")
                speakIfForeground(message.description)
            }
            is PiMessage.Incoming.ObjectFound -> {
                Log.i(TAG, "Object '${message.objectName}': found=${message.found}")
                if (message.found) {
                    val dirStr = message.direction?.let { " to the $it" } ?: ""
                    val distStr = message.distance?.let { " at ${"%.1f".format(it)} meters" } ?: ""
                    speakIfForeground("${message.objectName} found$dirStr$distStr")
                } else {
                    speakIfForeground("${message.objectName} not found in view")
                }
            }
            is PiMessage.Incoming.Pong -> {
                val latency = System.currentTimeMillis() - message.timestamp
                Log.d(TAG, "Pong received, latency: ${latency}ms")
            }
            is PiMessage.Incoming.PiError -> {
                Log.e(TAG, "Pi error [${message.errorCode}]: ${message.message}")
                hapticEngine.buzzError()
            }
        }
    }

    /**
     * CRITICAL SAFETY HANDLER — obstacle detected by ToF sensors.
     * Triggers immediate haptic feedback scaled to proximity.
     * This works even when the screen is off and the phone is in the pocket.
     */
    private fun handleObstacleWarning(warning: PiMessage.Incoming.ObstacleWarning) {
        Log.d(TAG, "Obstacle: ${warning.direction} at ${warning.distanceCm}cm (${warning.proximity})")
        hapticEngine.pulseObstacleWarning(warning.proximity)
        
        // If the obstacle is very close (e.g. proximity > 0.8), announce it
        if (warning.proximity > 0.8f) {
            ttsEngine.speak("Warning, obstacle ${warning.direction}")
        }
    }

    /**
     * Handles telemetry updates and triggers thermal throttling if needed.
     */
    private suspend fun handleTelemetry(telemetry: PiMessage.Incoming.Telemetry) {
        _latestTelemetry.emit(telemetry)

        // Thermal throttling logic
        if (telemetry.cpuTempCelsius >= THERMAL_THROTTLE_TEMP && !visionAIPaused) {
            visionAIPaused = true
            Log.w(TAG, "THERMAL THROTTLE: CPU at ${telemetry.cpuTempCelsius}°C, pausing vision AI")
            connectionManager.send(PiMessage.Outgoing.PauseVisionAI())
            hapticEngine.buzzError()
            speakIfForeground("System running hot, pausing vision AI")
        } else if (telemetry.cpuTempCelsius < THERMAL_THROTTLE_TEMP - 5 && visionAIPaused) {
            visionAIPaused = false
            Log.i(TAG, "Thermal cooldown: CPU at ${telemetry.cpuTempCelsius}°C, resuming vision AI")
            connectionManager.send(PiMessage.Outgoing.ResumeVisionAI())
            hapticEngine.pulseConnected()
            speakIfForeground("System cooled, vision AI resumed")
        }

        // Low battery warning
        telemetry.batteryPercent?.let { battery ->
            if (battery <= 15) {
                if (!hasWarnedBatteryLow) {
                    hasWarnedBatteryLow = true
                    Log.w(TAG, "Pi battery low: $battery%")
                    hapticEngine.buzzError()
                    speakIfForeground("Wearable battery low, $battery percent remaining")
                }
            } else if (battery > 20) {
                // Reset the warning if battery goes above 20% (e.g. plugged in)
                hasWarnedBatteryLow = false
            }
        }
    }

    /**
     * Handles physical button press from the Pi wearable enclosure.
     */
    private suspend fun handleHardwareButton(button: PiMessage.Incoming.HardwareButtonPress) {
        Log.i(TAG, "Hardware button: ${button.buttonAction}")
        when (button.buttonAction) {
            "single_press" -> {
                // "What is in front of me?"
                hapticEngine.pulseCommandAck()
                ttsEngine.speak("Describing scene")
                sendCommand(PiMessage.Outgoing.DescribeScene())
            }
            "long_press" -> {
                // Emergency / detailed scene description
                hapticEngine.pulseCommandAck()
                ttsEngine.speak("Emergency mode activated")
            }
            "double_press" -> {
                // Request telemetry readout
                hapticEngine.pulseCommandAck()
                ttsEngine.speak("Checking system status")
                sendCommand(PiMessage.Outgoing.RequestTelemetry())
            }
        }
    }

    /**
     * Speaks text only if the app is in the foreground.
     * Safety-critical messages (obstacle warnings) bypass this check
     * and call ttsEngine.speak() directly.
     */
    private fun speakIfForeground(text: String) {
        if (isAppInForeground) {
            ttsEngine.speak(text)
        } else {
            Log.d(TAG, "Suppressed TTS (app in background): $text")
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  VOICE COMMAND HANDLING
    // ══════════════════════════════════════════════════════════════

    private fun observeWakeWord() {
        lifecycleScope.launch {
            wakeWordEngine.wakeWordDetected.collect {
                // Wake word detected by Vosk!
                Log.i(TAG, "Wake word detected, pausing Vosk and starting command listener")
                wakeWordEngine.pause()
                
                hapticEngine.pulseCommandAck()
                ttsEngine.speak("Yes?")
                
                // Start Android SpeechRecognizer for the actual command
                voiceCommandEngine.startListening()
            }
        }
    }

    private fun observeVoiceCommands() {
        lifecycleScope.launch {
            voiceCommandEngine.parsedCommands.collect { command ->
                handleVoiceCommand(command)
            }
        }
    }

    /**
     * Delegates all voice command handling to the [CommandRouter].
     *
     * The router manages call, SMS, WhatsApp, app launch, knowledge engine,
     * and Pi commands with full conversation context.
     */
    private suspend fun handleVoiceCommand(command: ParsedCommand) {
        // Haptic feedback for any recognized command
        if (command !is ParsedCommand.None) {
            hapticEngine.pulseCommandAck()
        }

        // Delegate to the central command router
        commandRouter.execute(command)
    }

    // ══════════════════════════════════════════════════════════════
    //  NOTIFICATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Creates the persistent notification that keeps the service alive.
     */
    private fun createNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, VisionAidApp.SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Typed binder that provides direct access to the service instance.
     */
    inner class LocalBinder : Binder() {
        fun getService(): VisionAidService = this@VisionAidService
    }
}

/**
 * Represents the current state of the VisionAid service.
 *
 * The UI layer observes this via [StateFlow] to update the gesture pad
 * display and trigger appropriate haptic feedback.
 */
sealed class ServiceState {
    /** Service is idle, not yet started or just created. */
    data object Idle : ServiceState()

    /** Service is running but waiting for Pi wearable to connect. */
    data object WaitingForPi : ServiceState()

    /** Actively attempting to connect to the Pi. */
    data object Connecting : ServiceState()

    /** Successfully connected to the Pi wearable. */
    data class Connected(
        val piName: String,
        val transportType: String = ""
    ) : ServiceState()

    /** Connection lost, attempting to reconnect. */
    data class Reconnecting(val lastError: String) : ServiceState()

    /** An error occurred (disconnect, timeout, etc.). */
    data class Error(val message: String) : ServiceState()
}
