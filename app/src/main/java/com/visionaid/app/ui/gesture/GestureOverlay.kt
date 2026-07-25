package com.visionaid.app.ui.gesture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.visionaid.app.ui.theme.AccentCyan
import com.visionaid.app.ui.theme.DangerRed
import com.visionaid.app.ui.theme.PiConnectedBlue
import com.visionaid.app.ui.theme.SafeGreen

/**
 * Visual feedback overlay for gesture recognition.
 *
 * Displays a subtle expanding ring animation when a gesture is detected,
 * giving low-vision users visual confirmation that their gesture was
 * recognized. The animation is:
 * - **Cyan ring** → Double-tap (voice command)
 * - **Green ring** → Long press (describe scene)
 * - **Blue pulse up** → Two-finger swipe up (volume up)
 * - **Blue pulse down** → Two-finger swipe down (volume down)
 *
 * For fully blind users, this overlay is invisible — feedback comes
 * through haptics and TalkBack announcements instead.
 */
@Composable
fun GestureOverlay(
    lastGesture: VisionGesture?,
    modifier: Modifier = Modifier
) {
    if (lastGesture == null) return

    val alpha = remember(lastGesture) { Animatable(0.6f) }
    val radius = remember(lastGesture) { Animatable(50f) }

    val color = when (lastGesture) {
        VisionGesture.DoubleTap -> AccentCyan
        VisionGesture.LongPress -> SafeGreen
        VisionGesture.TwoFingerSwipeUp -> PiConnectedBlue
        VisionGesture.TwoFingerSwipeDown -> PiConnectedBlue
        VisionGesture.ThreeFingerSwipeDown -> Color.Gray
    }

    // Animate: expand ring while fading out
    LaunchedEffect(lastGesture) {
        launch {
            radius.animateTo(
                targetValue = 400f,
                animationSpec = tween(durationMillis = 500)
            )
        }
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 500)
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)

        // Draw expanding ring
        drawCircle(
            color = color.copy(alpha = alpha.value),
            radius = radius.value,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 4f
            )
        )

        // Draw center dot
        drawCircle(
            color = color.copy(alpha = alpha.value * 0.5f),
            radius = 12f,
            center = center
        )
    }
}
