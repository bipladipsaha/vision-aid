package com.visionaid.app.ui.gesture

import android.content.Context
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controls media volume for Bluetooth earbuds.
 *
 * Used by the two-finger swipe gesture to adjust volume without
 * the user needing to find physical buttons or on-screen controls.
 *
 * Uses [AudioManager.STREAM_MUSIC] which routes to Bluetooth A2DP
 * earbuds when connected.
 */
@Singleton
class VolumeController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VolumeController"
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** Current volume level (0 to maxVolume). */
    val currentVolume: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** Maximum volume level for the music stream. */
    val maxVolume: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    /** Current volume as a percentage (0–100). */
    val volumePercent: Int
        get() = if (maxVolume > 0) (currentVolume * 100) / maxVolume else 0

    /**
     * Increase volume by one step.
     *
     * @return New volume percentage after adjustment
     */
    fun volumeUp(): Int {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            0 // No UI flag — we provide our own feedback via TalkBack/haptics
        )
        val newPercent = volumePercent
        Log.d(TAG, "Volume up → $newPercent%")
        return newPercent
    }

    /**
     * Decrease volume by one step.
     *
     * @return New volume percentage after adjustment
     */
    fun volumeDown(): Int {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            0
        )
        val newPercent = volumePercent
        Log.d(TAG, "Volume down → $newPercent%")
        return newPercent
    }
}
