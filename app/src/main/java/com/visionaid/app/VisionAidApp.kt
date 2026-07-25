package com.visionaid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * VisionAid AI Application class.
 *
 * Initializes core infrastructure on app startup:
 * - Notification channel for the persistent foreground service
 * - Hilt dependency injection graph
 */
@HiltAndroidApp
class VisionAidApp : Application() {

    companion object {
        const val TAG = "VisionAidApp"

        /** Notification channel for the always-alive foreground service. */
        const val SERVICE_CHANNEL_ID = "visionaid_service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "VisionAid AI initialized")
    }

    /**
     * Creates the notification channel for the foreground service.
     *
     * Uses IMPORTANCE_LOW: the notification is persistent and silent.
     * We communicate system state via haptics, not notification sounds.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
