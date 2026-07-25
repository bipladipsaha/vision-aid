package com.visionaid.app.ui.gesture

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * VisionAid gesture configuration constants.
 *
 * Tuned for accessibility:
 * - Generous timing windows (blind users may be slower)
 * - Large movement thresholds (avoid false triggers)
 * - Clear distinction between gesture types
 */
object GestureConfig {
    /** Max time between two taps to count as double-tap (ms). */
    const val DOUBLE_TAP_TIMEOUT_MS = 400L

    /** Min duration for a long press to register (ms). */
    const val LONG_PRESS_DURATION_MS = 800L

    /** Min vertical distance for a two-finger swipe to register (px). */
    const val TWO_FINGER_SWIPE_THRESHOLD_PX = 80f

    /** Max horizontal drift allowed during a vertical swipe (px). */
    const val SWIPE_HORIZONTAL_TOLERANCE_PX = 150f
}

/**
 * All gestures the Invisible Command Center recognizes.
 *
 * Each gesture maps to a specific VisionAid action:
 * - [DoubleTap] → Wake microphone for voice command
 * - [LongPress] → Request scene description from Pi
 * - [TwoFingerSwipeUp] → Increase earbud volume
 * - [TwoFingerSwipeDown] → Decrease earbud volume
 */
sealed class VisionGesture {
    /** Double-tap anywhere on the pad. */
    data object DoubleTap : VisionGesture()

    /** Long press (hold) anywhere on the pad. */
    data object LongPress : VisionGesture()

    /** Two fingers swiped upward. */
    data object TwoFingerSwipeUp : VisionGesture()

    /** Two fingers swiped downward. */
    data object TwoFingerSwipeDown : VisionGesture()

    /** Three fingers swiped downward to open settings. */
    data object ThreeFingerSwipeDown : VisionGesture()
}

/**
 * Compose [Modifier] that attaches the full VisionAid gesture detector
 * to the gesture pad surface.
 *
 * Handles:
 * 1. **Single-finger double-tap** → [VisionGesture.DoubleTap]
 * 2. **Single-finger long press** → [VisionGesture.LongPress]
 * 3. **Two-finger vertical swipe** → [VisionGesture.TwoFingerSwipeUp] / [TwoFingerSwipeDown]
 *
 * When TalkBack is active, standard gestures are intercepted by the
 * screen reader. In that case, the gesture pad registers accessibility
 * actions (handled separately in the Compose semantics block).
 *
 * @param onGesture Callback invoked when a gesture is recognized
 */
fun Modifier.visionGestureDetector(
    onGesture: (VisionGesture) -> Unit
): Modifier = this
    .pointerInput(Unit) {
        detectDoubleTapAndLongPress(onGesture)
    }
    .pointerInput(Unit) {
        detectMultiFingerSwipe(onGesture)
    }

/**
 * Detects single-finger double-tap and long press gestures.
 *
 * State machine:
 * - IDLE → first tap down → start long-press timer
 * - If released quickly → WAITING_SECOND_TAP
 * - If held past threshold → LONG_PRESS detected
 * - WAITING_SECOND_TAP → second tap within timeout → DOUBLE_TAP detected
 * - WAITING_SECOND_TAP → timeout expires → single tap (ignored)
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDoubleTapAndLongPress(
    onGesture: (VisionGesture) -> Unit
) {
    detectTapGestures(
        onDoubleTap = { onGesture(VisionGesture.DoubleTap) },
        onLongPress = { onGesture(VisionGesture.LongPress) }
    )
}

/**
 * Detects multi-finger vertical swipe gestures.
 * Handles 2-finger (volume) and 3-finger (settings) swipes.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectMultiFingerSwipe(
    onGesture: (VisionGesture) -> Unit
) {
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        var startY = firstDown.position.y
        var currentY = startY
        var maxFingers = 1

        while (true) {
            val event = awaitPointerEvent()
            val activePointers = event.changes.filter { it.pressed }

            if (activePointers.size > maxFingers) {
                maxFingers = activePointers.size
                // Reset start position when a new finger joins to avoid jumping
                startY = activePointers.map { it.position.y }.average().toFloat()
            }

            if (activePointers.isNotEmpty()) {
                currentY = activePointers.map { it.position.y }.average().toFloat()
            }

            if (activePointers.isEmpty()) {
                if (maxFingers >= 2) {
                    val deltaY = currentY - startY
                    if (kotlin.math.abs(deltaY) > GestureConfig.TWO_FINGER_SWIPE_THRESHOLD_PX) {
                        if (deltaY < 0) {
                            // Swiped UP
                            if (maxFingers == 2) onGesture(VisionGesture.TwoFingerSwipeUp)
                        } else {
                            // Swiped DOWN
                            if (maxFingers == 2) onGesture(VisionGesture.TwoFingerSwipeDown)
                            if (maxFingers >= 3) onGesture(VisionGesture.ThreeFingerSwipeDown)
                        }
                    }
                }
                break
            }
        }
    }
}
