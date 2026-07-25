package com.visionaid.app.ui.screens

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionaid.app.R
import com.visionaid.app.service.ServiceState
import com.visionaid.app.ui.gesture.GestureOverlay
import com.visionaid.app.ui.gesture.VisionGesture
import com.visionaid.app.ui.gesture.visionGestureDetector
import com.visionaid.app.ui.theme.neoPressed
import com.visionaid.app.ui.theme.neoRaised
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun GesturePadScreen(
    serviceState: StateFlow<ServiceState>?,
    onGestureAction: ((VisionGesture) -> Unit)? = null,
    onFindObject: ((String) -> Unit)? = null,
    onStopCamera: (() -> Unit)? = null,
    onNavigateSettings: () -> Unit = {},
    onNavigateHistory: () -> Unit = {}
) {
    val state by serviceState?.collectAsState()
        ?: return GesturePadContent(
            state = ServiceState.Idle,
            onGestureAction = onGestureAction,
            onFindObject = onFindObject,
            onStopCamera = onStopCamera,
            onNavigateSettings = onNavigateSettings,
            onNavigateHistory = onNavigateHistory
        )

    GesturePadContent(
        state = state,
        onGestureAction = onGestureAction,
        onFindObject = onFindObject,
        onStopCamera = onStopCamera,
        onNavigateSettings = onNavigateSettings,
        onNavigateHistory = onNavigateHistory
    )
}

@Composable
private fun GesturePadContent(
    state: ServiceState,
    onGestureAction: ((VisionGesture) -> Unit)?,
    onFindObject: ((String) -> Unit)?,
    onStopCamera: (() -> Unit)?,
    onNavigateSettings: () -> Unit,
    onNavigateHistory: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    var lastGesture by remember { mutableStateOf<VisionGesture?>(null) }

    val statusText = when (state) {
        is ServiceState.Idle -> stringResource(R.string.status_idle)
        is ServiceState.WaitingForPi -> stringResource(R.string.status_waiting)
        is ServiceState.Connecting -> stringResource(R.string.status_connecting)
        is ServiceState.Connected -> "VisionAid Active"
        is ServiceState.Reconnecting -> stringResource(R.string.status_reconnecting)
        is ServiceState.Error -> stringResource(R.string.status_error, state.message)
    }

    val transportText = when (state) {
        is ServiceState.Connected -> "Ready to assist."
        else -> ""
    }

    val gestureDoubleTapAnnounce = stringResource(R.string.gesture_announce_double_tap)
    val gestureLongPressAnnounce = stringResource(R.string.gesture_announce_long_press)
    val gestureVolumeUpAnnounce = stringResource(R.string.gesture_announce_volume_up)
    val gestureVolumeDownAnnounce = stringResource(R.string.gesture_announce_volume_down)
    val gestureSettingsAnnounce = "Opening settings"

    val actionVoiceCommand = stringResource(R.string.action_voice_command)
    val actionDescribeScene = stringResource(R.string.action_describe_scene)
    val actionVolumeUp = stringResource(R.string.action_volume_up)
    val actionVolumeDown = stringResource(R.string.action_volume_down)
    val actionSettings = "Open Settings"

    val fullDescription = stringResource(R.string.gesture_pad_description, statusText)

    fun handleGesture(gesture: VisionGesture) {
        lastGesture = gesture
        val announcement = when (gesture) {
            VisionGesture.DoubleTap -> gestureDoubleTapAnnounce
            VisionGesture.LongPress -> gestureLongPressAnnounce
            VisionGesture.TwoFingerSwipeUp -> gestureVolumeUpAnnounce
            VisionGesture.TwoFingerSwipeDown -> gestureVolumeDownAnnounce
            VisionGesture.ThreeFingerSwipeDown -> gestureSettingsAnnounce
        }
        view.announceForAccessibility(announcement)
        if (gesture == VisionGesture.ThreeFingerSwipeDown) {
            onNavigateSettings()
        } else {
            onGestureAction?.invoke(gesture)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics {
                contentDescription = fullDescription
                stateDescription = statusText
                customActions = listOf(
                    CustomAccessibilityAction(actionVoiceCommand) { handleGesture(VisionGesture.DoubleTap); true },
                    CustomAccessibilityAction(actionDescribeScene) { handleGesture(VisionGesture.LongPress); true },
                    CustomAccessibilityAction(actionVolumeUp) { handleGesture(VisionGesture.TwoFingerSwipeUp); true },
                    CustomAccessibilityAction(actionVolumeDown) { handleGesture(VisionGesture.TwoFingerSwipeDown); true },
                    CustomAccessibilityAction(actionSettings) { handleGesture(VisionGesture.ThreeFingerSwipeDown); true }
                )
            }
            .visionGestureDetector { gesture -> handleGesture(gesture) }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // TopAppBar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .neoRaised(cornerRadius = 0.dp, blurRadius = 12.dp, offset = 6.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "VisionAid",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Main Canvas (Scrollable)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                // Central Interactive Scanner Button
                Box(
                    modifier = Modifier
                        .size(192.dp) // 48 * 4 = 192dp (w-48 h-48 in tailwind)
                        .neoRaised(cornerRadius = 96.dp, blurRadius = 24.dp, offset = 12.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            handleGesture(VisionGesture.DoubleTap)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Voice Command",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = statusText,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                if (transportText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = transportText,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Demo Finder Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    DemoFindButton("Person", onClick = { onFindObject?.invoke("person") })
                    DemoFindButton("Laptop", onClick = { onFindObject?.invoke("laptop") })
                    DemoFindButton("Bottle", onClick = { onFindObject?.invoke("bottle") })
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                DemoFindButton("Stop Camera", onClick = { onStopCamera?.invoke() })

                Spacer(modifier = Modifier.height(24.dp))

                // Instructions Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neoRaised(cornerRadius = 24.dp, blurRadius = 16.dp, offset = 8.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                        .padding(24.dp)
                ) {
                    Text(
                        text = "QUICK COMMANDS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    QuickCommandRow(Icons.Default.PlayArrow, "Double-tap", "Voice command")
                    Spacer(Modifier.height(16.dp))
                    QuickCommandRow(Icons.Default.Info, "Long press", "Describe scene")
                    Spacer(Modifier.height(16.dp))
                    QuickCommandRow(Icons.Default.CheckCircle, "Two-finger swipe", "Adjust Volume")
                }
            }

            // BottomNavBar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .neoRaised(cornerRadius = 0.dp, blurRadius = 12.dp, offset = (-6).dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                BottomNavItem(Icons.Default.Search, "Explore", isActive = true, onClick = {})
                BottomNavItem(Icons.Default.Info, "History", onClick = { onNavigateHistory() })
                BottomNavItem(Icons.Default.Build, "Settings", onClick = { onNavigateSettings() })
            }
        }

        // Visual feedback overlay
        GestureOverlay(
            lastGesture = lastGesture,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun QuickCommandRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .neoPressed(cornerRadius = 12.dp, blurRadius = 8.dp, offset = 4.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .neoRaised(cornerRadius = 20.dp, blurRadius = 8.dp, offset = 4.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DemoFindButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .neoRaised(cornerRadius = 12.dp, blurRadius = 6.dp, offset = 3.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Find $label",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    label: String, 
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val mod = if (isActive) {
        Modifier
            .neoPressed(cornerRadius = 12.dp, blurRadius = 8.dp, offset = 4.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
    } else {
        Modifier
            .clip(RoundedCornerShape(12.dp))
    }

    Column(
        modifier = mod
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
