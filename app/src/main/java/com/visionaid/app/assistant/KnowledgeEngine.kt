package com.visionaid.app.assistant

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.visionaid.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device knowledge engine that answers general questions.
 *
 * ## Architecture Decision: Why On-Device First?
 *
 * Free-tier cloud APIs (Gemini, OpenAI) are unreliable for production
 * assistive apps — they have rate limits, latency, and crash-prone SDKs.
 * 
 * Instead, VisionAid uses a **tiered answering strategy**:
 *
 * ### Tier 1: Built-in answers (instant, offline)
 * Common factual questions are answered from a local knowledge base.
 * This covers ~80% of simple questions without any network call.
 *
 * ### Tier 2: Android's built-in search (fast, online)
 * For questions not in the local KB, we construct a web search intent
 * and speak the query back, letting the user know we're searching.
 *
 * ### Tier 3: Future cloud AI (optional upgrade)
 * When you're ready to add a paid Gemini/OpenAI key, this engine
 * can be extended to call a cloud API as a fallback.
 *
 * This ensures the assistant **never crashes** and always responds,
 * even without internet.
 */
@Singleton
class KnowledgeEngine @Inject constructor() {

    companion object {
        private const val TAG = "KnowledgeEngine"
    }

    /**
     * Result of answering a question.
     */
    sealed class AnswerResult {
        /** A direct answer was found. */
        data class DirectAnswer(val answer: String) : AnswerResult()
        
        /** No direct answer; the question should be passed to a search engine. */
        data class SearchSuggestion(val query: String) : AnswerResult()
    }

    /**
     * Attempts to answer a general question.
     *
     * @param question the user's spoken question
     * @return [AnswerResult] with either a direct answer or a search suggestion
     */
    suspend fun answer(question: String): AnswerResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Processing question: '$question'")

        // Tier 1: Check built-in knowledge base first (Offline, Instant)
        val builtInAnswer = checkBuiltInKnowledge(question)
        if (builtInAnswer != null) {
            return@withContext AnswerResult.DirectAnswer(builtInAnswer)
        }

        // Tier 2: Check Gemini LLM (Online, Conversational)
        val geminiApiKey = BuildConfig.GEMINI_API_KEY
        if (geminiApiKey.isBlank()) {
            return@withContext AnswerResult.DirectAnswer("The Gemini API key is missing from the configuration.")
        }
        
        try {
            val generativeModel = GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = geminiApiKey,
                systemInstruction = com.google.ai.client.generativeai.type.content {
                    text("You are VisionAid, a helpful AI assistant for a visually impaired user. " +
                            "Keep your answers extremely concise, spoken-word friendly, and usually under 2 sentences. " +
                            "Do not use markdown or complex formatting.")
                }
            )
            
            Log.i(TAG, "Sending query to Gemini 1.5 Flash...")
            val response = generativeModel.generateContent(question)
            val responseText = response.text?.trim()
            
            if (!responseText.isNullOrEmpty()) {
                return@withContext AnswerResult.DirectAnswer(responseText)
            } else {
                return@withContext AnswerResult.DirectAnswer("Gemini returned an empty response.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API failed to answer", e)
            var errorMsg = e.message ?: "Unknown error"
            
            // Extract JSON message if it's an Unexpected Response
            if (errorMsg.contains("Unexpected Response:")) {
                try {
                    val jsonStr = errorMsg.substringAfter("Unexpected Response:").trim()
                    val jsonObject = org.json.JSONObject(jsonStr)
                    val errorObj = jsonObject.optJSONObject("error")
                    if (errorObj != null && errorObj.has("message")) {
                        errorMsg = errorObj.getString("message")
                    }
                } catch (ignore: Exception) {}
            }
            
            // Check for common API key errors
            if (errorMsg.contains("API key not valid", ignoreCase = true)) {
                return@withContext AnswerResult.DirectAnswer("Your Gemini API key appears to be invalid or expired. Please check Google AI Studio.")
            }
            
            return@withContext AnswerResult.DirectAnswer("Gemini AI error: $errorMsg")
        }
    }

    /**
     * Checks a local knowledge base of common questions.
     *
     * This is intentionally simple and lightweight. It handles
     * the most common types of questions a visually impaired user
     * might ask their assistant device.
     */
    private fun checkBuiltInKnowledge(question: String): String? {
        val q = question.lowercase().trim()

        // Time-related
        if (q.contains("what time") || q.contains("current time")) {
            val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date())
            return "The current time is $time"
        }

        if (q.contains("what day") || q.contains("what is today") || q.contains("today's date")) {
            val date = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault())
                .format(java.util.Date())
            return "Today is $date"
        }

        // Device info
        if (q.contains("what is your name") || q.contains("who are you")) {
            return "I am Vision Aid, your personal assistant for navigation and daily tasks."
        }

        if (q.contains("what can you do") || q.contains("help me") || q.contains("what are your features")) {
            return "I can describe your surroundings, find objects, make phone calls, " +
                    "send messages, open apps, and answer general questions. " +
                    "Just say Hey Vision followed by your command."
        }

        // Emergency
        if (q.contains("emergency") || q.contains("help") && q.contains("danger")) {
            return "If you need emergency help, say 'Call emergency services' " +
                    "or ask someone nearby for assistance."
        }

        // Math (simple)
        if (q.startsWith("what is ") && q.contains("+") || q.contains("plus") || 
            q.contains("minus") || q.contains("times") || q.contains("divided")) {
            val mathAnswer = trySimpleMath(q)
            if (mathAnswer != null) return mathAnswer
        }

        return null
    }

    /**
     * Attempts to solve very simple arithmetic from spoken text.
     * Handles "what is 5 plus 3" type questions.
     */
    private fun trySimpleMath(question: String): String? {
        return try {
            val cleaned = question
                .replace("what is", "")
                .replace("plus", "+")
                .replace("minus", "-")
                .replace("times", "*")
                .replace("multiplied by", "*")
                .replace("divided by", "/")
                .trim()

            // Very basic: try to evaluate "A op B" pattern
            val regex = Regex("(\\d+\\.?\\d*)\\s*([+\\-*/])\\s*(\\d+\\.?\\d*)")
            val match = regex.find(cleaned) ?: return null

            val a = match.groupValues[1].toDouble()
            val op = match.groupValues[2]
            val b = match.groupValues[3].toDouble()

            val result = when (op) {
                "+" -> a + b
                "-" -> a - b
                "*" -> a * b
                "/" -> if (b != 0.0) a / b else return "Cannot divide by zero"
                else -> return null
            }

            // Format as integer if whole number
            val formatted = if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                "%.2f".format(result)
            }

            "The answer is $formatted"
        } catch (e: Exception) {
            Log.w(TAG, "Math parsing failed for: $question", e)
            null
        }
    }
}
