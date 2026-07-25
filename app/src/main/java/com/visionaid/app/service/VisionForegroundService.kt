package com.visionaid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.visionaid.app.data.repository.PiRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground Service to ensure the WebSocket connection to the Pi
 * remains active even when the app is in the background or screen is off.
 */
@AndroidEntryPoint
class VisionForegroundService : Service() {

    @Inject
    lateinit var piRepository: PiRepository

    companion object {
        private const val CHANNEL_ID = "VisionAidForegroundChannel"
        private const val NOTIFICATION_ID = 101

        fun start(context: Context) {
            val intent = Intent(context, VisionForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VisionForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // Start connection automatically when service starts
        piRepository.connect()
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        piRepository.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VisionAid Connection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the connection to the Raspberry Pi alive."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VisionAid Active")
            .setContentText("Connected to Raspberry Pi")
            .setSmallIcon(android.R.drawable.ic_menu_camera) // Replace with app icon later
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
