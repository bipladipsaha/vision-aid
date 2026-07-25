package com.visionaid.app.voice

import android.util.Log
import com.visionaid.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ElevenLabsClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ElevenLabsClient"
        private const val API_URL = "https://api.elevenlabs.io/v1/text-to-speech"
    }

    suspend fun fetchSpeechAudio(text: String, cacheFile: File): Boolean = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.ELEVENLABS_API_KEY
        val voiceId = BuildConfig.ELEVENLABS_VOICE_ID

        if (apiKey.isBlank() || voiceId.isBlank()) {
            Log.e(TAG, "Missing ElevenLabs API Key or Voice ID")
            return@withContext false
        }

        try {
            val jsonPayload = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_turbo_v2") // Turbo is faster, good for real-time
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                })
            }

            // Using output_format=mp3_44100_128 for good balance of speed and quality
            val request = Request.Builder()
                .url("$API_URL/$voiceId?output_format=mp3_44100_128")
                .addHeader("xi-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "ElevenLabs API error: ${response.code} ${response.message} ${response.body?.string()}")
                return@withContext false
            }

            response.body?.byteStream()?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch speech from ElevenLabs", e)
            return@withContext false
        }
    }
}
