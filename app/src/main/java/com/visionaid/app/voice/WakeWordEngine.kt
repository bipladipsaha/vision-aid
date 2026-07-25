package com.visionaid.app.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Always-on, offline wake word engine powered by Vosk.
 *
 * ## How it works
 *
 * Vosk runs entirely on-device using a lightweight acoustic model (~40MB)
 * bundled in the app's assets. By restricting the recognizer's grammar
 * to only `["hey vision", "[unk]"]`, it becomes an extremely efficient
 * wake word detector:
 *
 * - `[unk]` absorbs all non-wake-word speech (background noise, conversation)
 * - Only "hey vision" triggers a detection event
 * - No internet, no API keys, no sign-ups
 *
 * ## Battery Efficiency
 *
 * Because the grammar is restricted to just 2 tokens, Vosk's decoder
 * does very little work per audio frame compared to full speech-to-text.
 * Combined with 16kHz mono audio, this is much lighter than using
 * Android's SpeechRecognizer in continuous mode.
 *
 * ## Lifecycle
 *
 * ```
 * start()  → Background thread begins recording + processing
 * pause()  → Stops recording (while command is being processed)
 * resume() → Restarts recording after command processing
 * stop()   → Releases all resources
 * ```
 */
@Singleton
class WakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WakeWordEngine"

        /** Audio sample rate for Vosk (must be 16kHz). */
        private const val SAMPLE_RATE = 16000

        /** Valid variations of the wake word to check against the JSON result. */
        private val VALID_WAKE_WORDS = listOf("hey vision", "hey vision ai", "vision ai")

        /**
         * Grammar restricts Vosk to only recognize these tokens.
         * [unk] absorbs everything that isn't the wake word.
         * Including multiple variations drastically improves responsiveness.
         */
        private const val GRAMMAR = "[\"hey vision\", \"hey vision ai\", \"vision ai\", \"[unk]\"]"
    }

    /** Emits Unit each time the wake word is detected. */
    private val _wakeWordDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val wakeWordDetected: SharedFlow<Unit> = _wakeWordDetected.asSharedFlow()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listeningThread: Thread? = null

    @Volatile
    private var isListening = false

    @Volatile
    private var isPaused = false

    /**
     * Initializes the Vosk model and starts background listening.
     *
     * Must be called from a thread that can block briefly while the
     * model loads from assets (~1-2 seconds on first launch).
     */
    fun start() {
        if (isListening) {
            Log.w(TAG, "Already listening, ignoring start()")
            return
        }

        try {
            // Copy model from assets to internal storage (Vosk requires a file path)
            val modelPath = copyModelFromAssets()
            if (modelPath == null) {
                Log.e(TAG, "Failed to locate Vosk model in assets")
                return
            }

            Log.i(TAG, "Loading Vosk model from: $modelPath")
            model = Model(modelPath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), GRAMMAR)

            isListening = true
            isPaused = false
            startListeningThread()

            Log.i(TAG, "Wake word engine started — listening for ${VALID_WAKE_WORDS.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word engine", e)
            cleanup()
        }
    }

    /**
     * Pauses the microphone recording.
     * Call this when the wake word has been detected and the system
     * is now capturing a command via SpeechRecognizer.
     */
    fun pause() {
        isPaused = true
        stopAudioRecord()
        Log.i(TAG, "Wake word engine paused")
    }

    /**
     * Resumes microphone recording after command processing is complete.
     */
    fun resume() {
        if (!isListening) {
            Log.w(TAG, "Engine not started, calling start() instead of resume()")
            start()
            return
        }

        isPaused = false
        // Reset the recognizer state so old audio doesn't cause false triggers
        recognizer?.reset()
        startListeningThread()
        Log.i(TAG, "Wake word engine resumed")
    }

    /**
     * Stops the engine and releases all resources.
     */
    fun stop() {
        Log.i(TAG, "Stopping wake word engine")
        isListening = false
        isPaused = false
        stopAudioRecord()
        cleanup()
    }

    // ── Internal ──────────────────────────────────────────────────

    /**
     * Starts a background thread that continuously reads audio from
     * the microphone and feeds it to the Vosk recognizer.
     */
    private fun startListeningThread() {
        stopAudioRecord() // Clean up any previous recording

        listeningThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ),
                4096
            )

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    return@Thread
                }

                audioRecord?.startRecording()
                Log.d(TAG, "AudioRecord started (buffer=$bufferSize)")

                val buffer = ShortArray(bufferSize / 2)

                while (isListening && !isPaused) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val rec = recognizer ?: break

                        // Convert shorts to bytes for Vosk
                        val byteBuffer = ByteArray(read * 2)
                        for (i in 0 until read) {
                            byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                        }

                        // Feed audio to Vosk
                        if (rec.acceptWaveForm(byteBuffer, byteBuffer.size)) {
                            // Full result available
                            val result = rec.result
                            checkForWakeWord(result)
                        } else {
                            // Check partial results for faster detection
                            val partial = rec.partialResult
                            checkForWakeWord(partial)
                        }
                    } else {
                        // Sleep briefly to avoid busy loop if audio record fails or returns 0
                        Thread.sleep(100)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission not granted", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error in wake word listening thread", e)
            } finally {
                stopAudioRecord()
            }
        }, "WakeWordEngine-Listener")

        listeningThread?.isDaemon = true
        listeningThread?.start()
    }

    /**
     * Checks a Vosk JSON result string for the wake word.
     *
     * Vosk returns results like: `{"text" : "hey vision"}`
     * or partial: `{"partial" : "hey vision"}`
     */
    private fun checkForWakeWord(jsonResult: String) {
        val detected = VALID_WAKE_WORDS.any { jsonResult.contains(it) }
        
        if (detected) {
            Log.i(TAG, "🎤 Wake word detected! Result: $jsonResult")
            _wakeWordDetected.tryEmit(Unit)

            // Reset recognizer to prevent double-triggers
            recognizer?.reset()
        }
    }

    /**
     * Stops the AudioRecord and waits for the listening thread to finish.
     */
    private fun stopAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        listeningThread = null
    }

    /**
     * Releases the Vosk model and recognizer.
     */
    private fun cleanup() {
        try {
            recognizer?.close()
            model?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up Vosk resources", e)
        }
        recognizer = null
        model = null
    }

    /**
     * Copies the Vosk model from assets to internal storage.
     *
     * Vosk requires a file system path (not an asset stream),
     * so we copy the model directory to the app's internal files dir
     * on first launch. Subsequent launches skip the copy.
     *
     * @return the absolute path to the model directory, or null on failure
     */
    private fun copyModelFromAssets(): String? {
        val targetDir = File(context.filesDir, "vosk-model")

        // Skip copy if already exists
        if (targetDir.exists() && targetDir.list()?.isNotEmpty() == true) {
            Log.d(TAG, "Vosk model already copied to ${targetDir.absolutePath}")
            return targetDir.absolutePath
        }

        return try {
            Log.i(TAG, "Copying Vosk model from assets to internal storage...")
            targetDir.mkdirs()
            copyAssetDir("model", targetDir)
            Log.i(TAG, "Vosk model copied successfully")
            targetDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy Vosk model from assets", e)
            null
        }
    }

    /**
     * Recursively copies an asset directory to a file system directory.
     */
    private fun copyAssetDir(assetPath: String, targetDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: return

        if (entries.isEmpty()) {
            // It's a file, copy it
            assetManager.open(assetPath).use { input ->
                File(targetDir, "").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // It's a directory, recurse
            for (entry in entries) {
                val childAssetPath = "$assetPath/$entry"
                val childList = assetManager.list(childAssetPath)

                if (childList != null && childList.isNotEmpty()) {
                    // Sub-directory
                    val subDir = File(targetDir, entry)
                    subDir.mkdirs()
                    copyAssetDir(childAssetPath, subDir)
                } else {
                    // File
                    assetManager.open(childAssetPath).use { input ->
                        File(targetDir, entry).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}
