package com.visionaid.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.visionaid.app.assistant.VisionAidSession
import com.visionaid.app.service.VisionAidService
import com.visionaid.app.settings.SettingsRepository
import com.visionaid.app.ui.gesture.VisionGesture
import com.visionaid.app.ui.screens.GesturePadScreen
import com.visionaid.app.ui.screens.HistoryScreen
import com.visionaid.app.ui.screens.SettingsScreen
import com.visionaid.app.ui.theme.VisionAidTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main entry point for VisionAid AI.
 *
 * Responsibilities:
 * - Goes fullscreen (hides system bars) for the gesture pad
 * - Requests runtime permissions (Bluetooth, Audio, Notifications)
 * - Starts and binds to [VisionAidService] foreground service
 * - Hosts the Compose UI (gesture pad + future navigation)
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    companion object {
        private const val TAG = "MainActivity"
    }

    /** Tracks whether the foreground service is currently bound. */
    private var serviceBound by mutableStateOf(false)

    /** Tracks if we were launched by the Assistant and need to trigger voice when bound. */
    private var pendingAssistTrigger = false

    /** Reference to the bound service for state observation. */
    private var visionAidService: VisionAidService? = null

    /** Service connection callback for binding to VisionAidService. */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VisionAidService.LocalBinder
            visionAidService = localBinder.getService()
            serviceBound = true
            Log.i(TAG, "Bound to VisionAidService")

            if (pendingAssistTrigger) {
                pendingAssistTrigger = false
                Log.i(TAG, "Firing pending Assist trigger now that service is bound")
                visionAidService?.handleGesture(VisionGesture.DoubleTap)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            visionAidService = null
            serviceBound = false
            Log.w(TAG, "Disconnected from VisionAidService")
        }
    }

    /** Permission launcher for runtime permissions. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.i(TAG, "All permissions granted")
            startVisionAidService()
        } else {
            Log.w(TAG, "Some permissions denied: ${permissions.filter { !it.value }.keys}")
            // Still start the service — it will operate in degraded mode
            startVisionAidService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkAssistTrigger(intent)

        // Go fullscreen: hide system bars for the gesture pad
        enableEdgeToEdge()
        goFullscreen()

        // Request permissions and start service
        requestRequiredPermissions()

        // Set up Compose UI
        setContent {
            VisionAidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val navController = rememberNavController()
                    
                    NavHost(navController = navController, startDestination = "gesture_pad") {
                        composable("gesture_pad") {
                            GesturePadScreen(
                                serviceState = visionAidService?.serviceState,
                                onGestureAction = { gesture ->
                                    visionAidService?.handleGesture(gesture)
                                },
                                onNavigateSettings = {
                                    navController.navigate("settings")
                                },
                                onNavigateHistory = {
                                    navController.navigate("history")
                                }
                            )
                        }
                        
                        composable("settings") {
                            SettingsScreen(
                                settingsRepository = settingsRepository,
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        
                        composable("history") {
                            HistoryScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkAssistTrigger(intent)
    }

    private fun checkAssistTrigger(intent: Intent?) {
        if (intent?.getBooleanExtra(VisionAidSession.EXTRA_ASSIST_TRIGGER, false) == true) {
            Log.i(TAG, "Launched via Assistant (Power Button)")
            if (serviceBound && visionAidService != null) {
                // Already bound, fire immediately
                visionAidService?.handleGesture(VisionGesture.DoubleTap)
            } else {
                // Wait for binding
                pendingAssistTrigger = true
            }
            // Remove the extra so it doesn't fire again on rotation
            intent.removeExtra(VisionAidSession.EXTRA_ASSIST_TRIGGER)
        }
    }

    override fun onStart() {
        super.onStart()
        bindToService()
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    /**
     * Hides system bars (status bar + navigation bar) for a fully
     * immersive gesture pad experience. The user interacts with the
     * entire screen surface.
     */
    private fun goFullscreen() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Requests all runtime permissions needed by VisionAid AI.
     *
     * Permissions:
     * - BLUETOOTH_CONNECT: Communicate with Pi wearable
     * - BLUETOOTH_SCAN: Discover Pi wearable
     * - RECORD_AUDIO: Offline voice commands
     * - POST_NOTIFICATIONS: Foreground service notification (Android 13+)
     * - CALL_PHONE: Make outgoing calls
     * - READ_CONTACTS: Resolve spoken names to phone numbers
     * - SEND_SMS: Send text messages
     * - READ_PHONE_STATE: Detect incoming calls
     * - ANSWER_PHONE_CALLS: Accept/reject incoming calls via voice
     */
    private fun requestRequiredPermissions() {
        val permissions = buildList {
            // Bluetooth (Android 12+)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)

            // Voice recognition
            add(Manifest.permission.RECORD_AUDIO)

            // Notification (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }

            // Telephony
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }

            // Contacts
            add(Manifest.permission.READ_CONTACTS)

            // SMS
            add(Manifest.permission.SEND_SMS)
        }

        val ungrantedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungrantedPermissions.isNotEmpty()) {
            permissionLauncher.launch(ungrantedPermissions.toTypedArray())
        } else {
            startVisionAidService()
        }
    }

    /**
     * Starts the foreground service so the app stays alive in the user's pocket.
     * The service runs independently of the activity lifecycle.
     */
    private fun startVisionAidService() {
        val serviceIntent = Intent(this, VisionAidService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        Log.i(TAG, "Started VisionAidService")
    }

    /**
     * Binds to the running service to observe its [VisionAidService.serviceState].
     */
    private fun bindToService() {
        val intent = Intent(this, VisionAidService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
}
