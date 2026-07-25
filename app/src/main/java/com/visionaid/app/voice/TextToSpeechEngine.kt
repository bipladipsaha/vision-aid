package com.visionaid.app.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.visionaid.app.settings.SettingsRepository
import java.io.File
import android.media.MediaPlayer
import kotlinx.coroutines.withContext

/**
 * Text-to-Speech (TTS) Engine for VisionAid AI.
 *
 * Provides spoken feedback for the blind-first experience.
 * Automatically handles initialization, language loading, and audio focus.
 * Uses a conversational voice when available.
 */
@Singleton
class TextToSpeechEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val elevenLabsClient: ElevenLabsClient
) {
    companion object {
        private const val TAG = "TextToSpeechEngine"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var initializationError = false
    private var mediaPlayer: MediaPlayer? = null

    init {
        // Initialize TTS engine asynchronously
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Force English for now (can map to system locale later)
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS Language is not supported or missing data")
                    initializationError = true
                } else {
                    Log.i(TAG, "TTS initialized successfully")
                    isInitialized = true
                    
                    // Set up utterance listener for tracking speaking state
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {}
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {}
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.e(TAG, "TTS error $errorCode for utterance $utteranceId")
                        }
                    })

                    // Observe settings
                    CoroutineScope(Dispatchers.Main).launch {
                        settingsRepository.voicePitchFlow.collect { pitch ->
                            tts?.setPitch(pitch)
                        }
                    }
                    CoroutineScope(Dispatchers.Main).launch {
                        settingsRepository.voiceSpeedFlow.collect { speed ->
                            tts?.setSpeechRate(speed)
                        }
                    }
                }
            } else {
                Log.e(TAG, "TTS Initialization failed!")
                initializationError = true
            }
        }
    }

    /**
     * Speaks the given text aloud. 
     * Stops any currently playing speech.
     *
     * @param text The string to speak
     * @param queueMode [TextToSpeech.QUEUE_FLUSH] to interrupt, [TextToSpeech.QUEUE_ADD] to append
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isInitialized) {
            Log.w(TAG, "Cannot speak: TTS not initialized yet. Text: $text")
            return
        }

        val utteranceId = "visionaid_utterance_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            // Use STREAM_MUSIC to ensure it goes through earbuds like our volume control
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }
        
        Log.e(TAG, "ASSISTANT SPEAKS: $text")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            val cacheFile = File(context.cacheDir, "elevenlabs_cache_${System.currentTimeMillis()}.mp3")
            val success = elevenLabsClient.fetchSpeechAudio(text, cacheFile)
            if (success && cacheFile.exists()) {
                withContext(Dispatchers.Main) {
                    playAudioFile(cacheFile)
                }
            } else {
                Log.w(TAG, "ElevenLabs failed, falling back to native TTS")
                tts?.speak(text, queueMode, params, utteranceId)
            }
        }
    }

    /**
     * Speaks text and suspends until speech completes.
     */
    suspend fun speakAndWait(text: String) {
        if (!isInitialized) {
            Log.w(TAG, "Cannot speak: TTS not initialized yet. Text: $text")
            return
        }

        val utteranceId = "visionaid_utterance_wait_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }

        suspendCoroutine<Unit> { continuation ->
            CoroutineScope(Dispatchers.IO).launch {
                val cacheFile = File(context.cacheDir, "elevenlabs_cache_wait_${System.currentTimeMillis()}.mp3")
                val success = elevenLabsClient.fetchSpeechAudio(text, cacheFile)
                
                withContext(Dispatchers.Main) {
                    if (success && cacheFile.exists()) {
                        playAudioFileAndWait(cacheFile, continuation)
                    } else {
                        Log.w(TAG, "ElevenLabs failed, falling back to native TTS")
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            
                            override fun onDone(id: String?) {
                                if (id == utteranceId) {
                                    tts?.setOnUtteranceProgressListener(null) // Reset
                                    continuation.resume(Unit)
                                }
                            }
                            
                            @Deprecated("Deprecated in Java")
                            override fun onError(id: String?) {
                                if (id == utteranceId) {
                                    tts?.setOnUtteranceProgressListener(null)
                                    continuation.resume(Unit)
                                }
                            }
                        })
                        
                        Log.d(TAG, "Speaking (wait fallback): $text")
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                    }
                }
            }
        }
    }

    private fun playAudioFile(file: File) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
            setOnCompletionListener { mp ->
                mp.release()
                mediaPlayer = null
                file.delete()
            }
        }
    }

    private fun playAudioFileAndWait(file: File, continuation: kotlin.coroutines.Continuation<Unit>) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
            setOnCompletionListener { mp ->
                mp.release()
                mediaPlayer = null
                file.delete()
                continuation.resume(Unit)
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                mediaPlayer = null
                file.delete()
                continuation.resume(Unit)
                true
            }
        }
    }

    fun stop() {
        tts?.stop()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        mediaPlayer?.release()
        mediaPlayer = null
        isInitialized = false
    }
}
