package com.visionaid.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "visionaid_settings")

/**
 * Repository for managing persistent user settings via Jetpack DataStore.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_VOICE_SPEED = floatPreferencesKey("voice_speed")
        val KEY_VOICE_PITCH = floatPreferencesKey("voice_pitch")
        val KEY_HAPTIC_INTENSITY = floatPreferencesKey("haptic_intensity")
        val KEY_USE_MOCK = booleanPreferencesKey("use_mock_connection")
        val KEY_FIRST_LAUNCH = booleanPreferencesKey("first_launch")
    }

    val voiceSpeedFlow: Flow<Float> = context.dataStore.data.map { it[KEY_VOICE_SPEED] ?: 1.0f }
    val voicePitchFlow: Flow<Float> = context.dataStore.data.map { it[KEY_VOICE_PITCH] ?: 1.0f }
    val hapticIntensityFlow: Flow<Float> = context.dataStore.data.map { it[KEY_HAPTIC_INTENSITY] ?: 1.0f }
    val useMockConnectionFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_USE_MOCK] ?: false }
    val isFirstLaunchFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_FIRST_LAUNCH] ?: true }

    suspend fun setVoiceSpeed(speed: Float) {
        context.dataStore.edit { it[KEY_VOICE_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setVoicePitch(pitch: Float) {
        context.dataStore.edit { it[KEY_VOICE_PITCH] = pitch.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setHapticIntensity(intensity: Float) {
        context.dataStore.edit { it[KEY_HAPTIC_INTENSITY] = intensity.coerceIn(0.2f, 2.0f) }
    }

    suspend fun setUseMockConnection(useMock: Boolean) {
        context.dataStore.edit { it[KEY_USE_MOCK] = useMock }
    }

    suspend fun setFirstLaunch(isFirst: Boolean) {
        context.dataStore.edit { it[KEY_FIRST_LAUNCH] = isFirst }
    }
}
