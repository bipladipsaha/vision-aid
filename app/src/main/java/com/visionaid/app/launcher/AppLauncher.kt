package com.visionaid.app.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launches installed applications by spoken name.
 *
 * Supports two resolution strategies:
 * 1. **Known aliases**: Common names like "messages", "phone", "camera", "maps"
 *    are mapped to their standard Android package names for instant resolution.
 * 2. **Package Manager query**: For other apps, queries the system for installed
 *    launchable apps and performs fuzzy name matching.
 *
 * Priority apps (Messages, Phone/Dialer, WhatsApp, Maps) are given special
 * treatment with hardcoded aliases to ensure reliable voice-driven access.
 */
@Singleton
class AppLauncher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AppLauncher"

        /**
         * Known app aliases → package names.
         * These are the "most important" apps as specified by the user.
         * Multiple spoken names can map to the same package.
         */
        private val KNOWN_APPS: Map<String, String> = mapOf(
            // Messaging
            "messages" to "com.google.android.apps.messaging",
            "message" to "com.google.android.apps.messaging",
            "messaging" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "texts" to "com.google.android.apps.messaging",

            // Phone / Dialer
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer",
            "caller" to "com.google.android.dialer",
            "call" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",

            // WhatsApp
            "whatsapp" to "com.whatsapp",
            "whats app" to "com.whatsapp",

            // Maps / Navigation
            "maps" to "com.google.android.apps.maps",
            "map" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "navigation" to "com.google.android.apps.maps",

            // Camera
            "camera" to "com.android.camera",
            "camera" to "com.google.android.GoogleCamera",

            // YouTube
            "youtube" to "com.google.android.youtube",
            "you tube" to "com.google.android.youtube",

            // Chrome / Browser
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "google chrome" to "com.android.chrome",

            // Google
            "google" to "com.google.android.googlequicksearchbox",

            // Settings
            "settings" to "com.android.settings",
            "setting" to "com.android.settings",

            // Calculator
            "calculator" to "com.google.android.calculator",

            // Clock / Alarm
            "clock" to "com.google.android.deskclock",
            "alarm" to "com.google.android.deskclock",
            "timer" to "com.google.android.deskclock",

            // Gallery / Photos
            "gallery" to "com.google.android.apps.photos",
            "photos" to "com.google.android.apps.photos",
            "photo" to "com.google.android.apps.photos",

            // Play Store
            "play store" to "com.android.vending",
            "store" to "com.android.vending",
            "app store" to "com.android.vending",

            // Instagram
            "instagram" to "com.instagram.android",

            // Facebook
            "facebook" to "com.facebook.katana",

            // Spotify
            "spotify" to "com.spotify.music",
            "music" to "com.spotify.music",

        )
    }

    /**
     * Result of attempting to launch an app.
     */
    sealed class LaunchResult {
        data class Success(val appName: String) : LaunchResult()
        data object AppNotFound : LaunchResult()
        data class Error(val message: String) : LaunchResult()
    }

    /**
     * Launches an app by its spoken name.
     *
     * @param spokenName the name spoken by the user (e.g., "messages", "youtube")
     * @return [LaunchResult] indicating outcome
     */
    fun launchApp(spokenName: String): LaunchResult {
        val normalizedName = spokenName.lowercase().trim()
        Log.i(TAG, "Attempting to launch app: '$normalizedName'")

        // Strategy 0: Self-launch
        if (normalizedName in listOf("vision aid", "visionaid", "your app", "vision aid ai", "this app")) {
            val result = launchByPackage(context.packageName, "VisionAid")
            if (result is LaunchResult.Success) return result
        }

        // Strategy 1: Check known aliases
        val knownPackage = KNOWN_APPS[normalizedName]
        if (knownPackage != null) {
            val result = launchByPackage(knownPackage, normalizedName)
            if (result is LaunchResult.Success) return result
            // If the known package isn't installed, fall through to fuzzy search
        }

        // Strategy 2: Query all launchable apps and fuzzy-match
        return launchByFuzzySearch(normalizedName)
    }

    /**
     * Launches an app by its package name.
     */
    private fun launchByPackage(packageName: String, displayName: String): LaunchResult {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (packageName == context.packageName) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launchIntent)
                Log.i(TAG, "Launched $displayName ($packageName)")
                LaunchResult.Success(displayName)
            } else {
                Log.w(TAG, "No launch intent for $packageName")
                LaunchResult.AppNotFound
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching $packageName", e)
            LaunchResult.Error("Could not open $displayName: ${e.message}")
        }
    }

    /**
     * Searches all installed launchable apps by display name.
     * Uses contains-matching for fuzzy resolution.
     */
    private fun launchByFuzzySearch(spokenName: String): LaunchResult {
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val apps: List<ResolveInfo> = context.packageManager.queryIntentActivities(
                mainIntent, PackageManager.MATCH_ALL
            )

            // Find exact match first
            val exactMatch = apps.find {
                it.loadLabel(context.packageManager).toString().lowercase() == spokenName
            }
            if (exactMatch != null) {
                val packageName = exactMatch.activityInfo.packageName
                val label = exactMatch.loadLabel(context.packageManager).toString()
                return launchByPackage(packageName, label)
            }

            // Then try contains match
            val containsMatch = apps.find {
                val label = it.loadLabel(context.packageManager).toString().lowercase()
                label.contains(spokenName) || spokenName.contains(label)
            }
            if (containsMatch != null) {
                val packageName = containsMatch.activityInfo.packageName
                val label = containsMatch.loadLabel(context.packageManager).toString()
                return launchByPackage(packageName, label)
            }

            Log.w(TAG, "No app found matching '$spokenName'")
            return LaunchResult.AppNotFound
        } catch (e: Exception) {
            Log.e(TAG, "Error searching for apps", e)
            return LaunchResult.Error("Could not search for apps: ${e.message}")
        }
    }
}
