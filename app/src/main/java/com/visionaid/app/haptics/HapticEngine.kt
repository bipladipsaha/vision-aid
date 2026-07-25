package com.visionaid.app.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.visionaid.app.settings.SettingsRepository

/**
 * Haptic "Heartbeat" Engine — silent communication via vibration patterns.
 *
 * This replaces audio feedback for system state changes, preserving
 * the user's ears for voice commands and obstacle warnings. Each
 * vibration pattern has a distinct "feel" that the user learns over time.
 *
 * Pattern dictionary:
 * - [pulseConnected]: Short double-pulse — Pi is connected
 * - [pulseCommandAck]: Triple-pulse — command acknowledged
 * - [buzzError]: Long continuous buzz — error / disconnection
 * - [pulseObstacleWarning]: Intensity-scaled — closer = stronger
 * - [pulseServiceStarted]: Single soft pulse — service alive
 */
@Singleton
class HapticEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "HapticEngine"
    }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private var hapticMultiplier: Float = 1.0f

    init {
        CoroutineScope(Dispatchers.Main).launch {
            settingsRepository.hapticIntensityFlow.collect { intensity ->
                hapticMultiplier = intensity
            }
        }
    }

    /**
     * Short double-pulse: Pi wearable is connected.
     *
     * Pattern: ·· (two quick taps with a short gap)
     * Feel: Like a heartbeat confirming "I'm alive and connected"
     */
    fun pulseConnected() {
        vibrate(
            // [delay, vibrate, pause, vibrate]
            timings = longArrayOf(0, 80, 120, 80),
            amplitudes = intArrayOf(0, 180, 0, 180)
        )
        Log.d(TAG, "Haptic: pulseConnected")
    }

    /**
     * Triple-pulse: Voice command acknowledged and sent to Pi.
     *
     * Pattern: ··· (three quick taps)
     * Feel: "Got it, processing your request"
     */
    fun pulseCommandAck() {
        vibrate(
            timings = longArrayOf(0, 60, 80, 60, 80, 60),
            amplitudes = intArrayOf(0, 150, 0, 150, 0, 150)
        )
        Log.d(TAG, "Haptic: pulseCommandAck")
    }

    /**
     * Long continuous buzz: Error or disconnection.
     *
     * Pattern: ━━━ (one long strong vibration)
     * Feel: Unmistakable "something is wrong"
     */
    fun buzzError() {
        vibrate(
            timings = longArrayOf(0, 600),
            amplitudes = intArrayOf(0, 255)
        )
        Log.d(TAG, "Haptic: buzzError")
    }

    /**
     * Proximity-scaled obstacle warning.
     *
     * The closer the obstacle, the stronger and longer the pulse.
     * This creates a "thermal" sensation — getting "hotter" as you
     * approach the obstacle.
     *
     * @param proximity 0.0 (far away) to 1.0 (imminent collision)
     */
    fun pulseObstacleWarning(proximity: Float) {
        val clampedProximity = proximity.coerceIn(0f, 1f)
        val amplitude = (80 + (175 * clampedProximity)).toInt().coerceIn(1, 255)
        val duration = (50 + (200 * clampedProximity)).toLong()

        vibrate(
            timings = longArrayOf(0, duration),
            amplitudes = intArrayOf(0, amplitude)
        )
        Log.d(TAG, "Haptic: obstacleWarning proximity=$clampedProximity amplitude=$amplitude")
    }

    /**
     * Single soft pulse: Service has started successfully.
     *
     * Pattern: · (one gentle tap)
     * Feel: Subtle confirmation, "I'm here"
     */
    fun pulseServiceStarted() {
        vibrate(
            timings = longArrayOf(0, 100),
            amplitudes = intArrayOf(0, 120)
        )
        Log.d(TAG, "Haptic: serviceStarted")
    }

    /**
     * Executes a vibration pattern using [VibrationEffect].
     *
     * @param timings Array of timing values in ms [delay, on, off, on, ...]
     * @param amplitudes Array of amplitude values (0-255) matching timings
     */
    private fun vibrate(timings: LongArray, amplitudes: IntArray) {
        if (!vibrator.hasVibrator()) {
            Log.w(TAG, "Device has no vibrator")
            return
        }

        val scaledAmplitudes = amplitudes.map { (it * hapticMultiplier).toInt().coerceIn(0, 255) }.toIntArray()

        try {
            val effect = VibrationEffect.createWaveform(timings, scaledAmplitudes, -1)
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }
}
