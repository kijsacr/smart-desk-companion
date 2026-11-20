package com.smartdesk.companion.tablet.ui.touch

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Module E: Hardware Mitigation & Touch Sluggishness Architecture
 * Specifically tailored for the Lenovo Yoga Tab 11 (YT-X705X) digitizer behavior:
 * 1. Edge-safe inset centering (minimum 32.dp outer perimeter padding).
 * 2. Stuck-touch / ghost-hold cancellation (>1,500ms press timeout without release).
 * 3. Simultaneous phantom multi-touch point rejection.
 * 4. Tap debounce (350ms default) to prevent digitizer bounce double-clicks.
 */
object GhostTouchConfig {
    val EDGE_SAFE_INSET: Dp = 32.dp
    val MIN_OVERSIZED_TOUCH_TARGET: Dp = 80.dp
    const val STUCK_TOUCH_TIMEOUT_MS: Long = 1500L
    const val DEFAULT_DEBOUNCE_MS: Long = 350L
    const val MAX_STATIONARY_DRIFT_PX: Float = 48f
}

/**
 * Keeps all critical UI & interactive components padded inwards by at least 32.dp
 * away from the outer screen perimeter where the Yoga Tab 11 digitizer most
 * commonly registers ghost touches or phantom continuous presses.
 */
fun Modifier.edgeSafeInsetPadding(inset: Dp = GhostTouchConfig.EDGE_SAFE_INSET): Modifier =
    this.padding(inset)

/**
 * Root-level pointer filter applied to Compose root containers (Roadmap Step 7).
 * - Immediately consumes/drops events with simultaneous multi-touch points (>1 pointer),
 *   which on a desk clock dashboard are almost always phantom digitizer touches.
 * - Cancels pointers that remain held stationary longer than [stuckTouchTimeoutMs] (1.5s).
 */
fun Modifier.ghostTouchRootFilter(
    stuckTouchTimeoutMs: Long = GhostTouchConfig.STUCK_TOUCH_TIMEOUT_MS
): Modifier = this.pointerInput(stuckTouchTimeoutMs) {
    val pointerDownTimestamps = mutableMapOf<Long, Long>()
    val pointerDownOrigins = mutableMapOf<Long, Offset>()

    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val now = SystemClock.uptimeMillis()
            val activePointers = event.changes.filter { it.pressed }

            // Drop simultaneous multi-touch points caused by phantom edge/palm ghosting
            if (activePointers.size > 1) {
                event.changes.forEach { it.consume() }
                pointerDownTimestamps.clear()
                pointerDownOrigins.clear()
                continue
            }

            for (change in event.changes) {
                val id = change.id.value
                if (change.pressed) {
                    val startTime = pointerDownTimestamps.getOrPut(id) { now }
                    val origin = pointerDownOrigins.getOrPut(id) { change.position }
                    val drift = (change.position - origin).getDistance()

                    // If held stationary beyond 1.5s threshold, treat as stuck phantom touch
                    if (now - startTime > stuckTouchTimeoutMs &&
                        drift < GhostTouchConfig.MAX_STATIONARY_DRIFT_PX
                    ) {
                        change.consume()
                    }
                } else {
                    pointerDownTimestamps.remove(id)
                    pointerDownOrigins.remove(id)
                }
            }
        }
    }
}

/**
 * Ghost-touch-mitigated click modifier for oversized interactive buttons.
 * - Rejects multi-touch events.
 * - Rejects phantom held presses exceeding [maxPressDurationMs] (1.5s).
 * - Debounces rapid repeated triggers within [debounceMs] (350ms).
 */
@Composable
fun Modifier.ghostTouchSafeClickable(
    enabled: Boolean = true,
    debounceMs: Long = GhostTouchConfig.DEFAULT_DEBOUNCE_MS,
    maxPressDurationMs: Long = GhostTouchConfig.STUCK_TOUCH_TIMEOUT_MS,
    onClick: () -> Unit
): Modifier {
    val lastClickTimestamp = remember { LongArray(1) { 0L } }

    return this.pointerInput(enabled, debounceMs, maxPressDurationMs, onClick) {
        if (!enabled) return@pointerInput

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downTime = SystemClock.uptimeMillis()

            // Wait for pointer release within the 1.5s stuck-touch window
            val releasedCleanly = withTimeoutOrNull(maxPressDurationMs) {
                while (true) {
                    val event = awaitPointerEvent()
                    // Reject if a second phantom touch appears mid-press
                    if (event.changes.count { it.pressed } > 1) {
                        event.changes.forEach { it.consume() }
                        return@withTimeoutOrNull false
                    }
                    val matching = event.changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull false

                    if (!matching.pressed) {
                        matching.consume()
                        return@withTimeoutOrNull true
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } ?: false

            // If the pointer was held >1.5s (phantom held touch), wait until lifted and ignore it
            if (!releasedCleanly) {
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }

            val now = SystemClock.uptimeMillis()
            val holdDuration = now - downTime
            if (holdDuration in 15L..maxPressDurationMs &&
                now - lastClickTimestamp[0] >= debounceMs
            ) {
                lastClickTimestamp[0] = now
                onClick()
            }
        }
    }
}
