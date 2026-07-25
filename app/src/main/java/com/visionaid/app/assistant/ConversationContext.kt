package com.visionaid.app.assistant

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers conversational context for follow-up commands.
 *
 * Example flow:
 * ```
 * User: "What's ahead?"
 * System: "A chair is directly ahead."
 *   → saves: lastObject="chair", lastDirection="ahead"
 *
 * User: "How far?"
 *   → ConversationContext provides: lastObject="chair", lastDirection="ahead"
 *   → System reads center ToF and says: "The chair ahead is about 1.2 metres away."
 * ```
 *
 * Context expires after [CONTEXT_TIMEOUT_MS] to prevent stale follow-ups.
 */
@Singleton
class ConversationContext @Inject constructor() {

    companion object {
        private const val TAG = "ConversationContext"
        
        /** Context expires after 60 seconds of no interaction. */
        private const val CONTEXT_TIMEOUT_MS = 60_000L
    }

    /** The last object mentioned in conversation (e.g., "chair", "bottle"). */
    var lastObject: String? = null
        private set

    /** The last direction mentioned (e.g., "left", "right", "ahead"). */
    var lastDirection: String? = null
        private set

    /** The last spoken response from the assistant (for "Repeat" command). */
    var lastResponse: String? = null
        private set

    /** The last action performed (e.g., "describe", "find", "call"). */
    var lastAction: String? = null
        private set

    /** Timestamp of the last context update. */
    private var lastUpdateTime: Long = 0L

    /**
     * Updates the context with new information from the latest interaction.
     */
    fun update(
        objectName: String? = null,
        direction: String? = null,
        response: String? = null,
        action: String? = null
    ) {
        objectName?.let { 
            this.lastObject = it
            Log.d(TAG, "Context updated: object='$it'")
        }
        direction?.let { 
            this.lastDirection = it
            Log.d(TAG, "Context updated: direction='$it'")
        }
        response?.let { 
            this.lastResponse = it
            Log.d(TAG, "Context updated: response (${it.take(50)}...)")
        }
        action?.let {
            this.lastAction = it
            Log.d(TAG, "Context updated: action='$it'")
        }
        lastUpdateTime = System.currentTimeMillis()
    }

    /**
     * Checks if the context is still fresh (within timeout).
     */
    fun isContextFresh(): Boolean {
        if (lastUpdateTime == 0L) return false
        return (System.currentTimeMillis() - lastUpdateTime) < CONTEXT_TIMEOUT_MS
    }

    /**
     * Clears all context (e.g., on "Stop" command).
     */
    fun clear() {
        lastObject = null
        lastDirection = null
        lastResponse = null
        lastAction = null
        lastUpdateTime = 0L
        Log.d(TAG, "Context cleared")
    }
}
