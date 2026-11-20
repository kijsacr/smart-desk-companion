package com.smartdesk.companion.tablet.sync

import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.HighlightDecision
import com.smartdesk.companion.shared.OtpDetector
import com.smartdesk.companion.shared.PhoneBatteryPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ActiveTabletOverlay {
    val requiresTemporaryNightBrightnessBoost: Boolean

    data object None : ActiveTabletOverlay {
        override val requiresTemporaryNightBrightnessBoost: Boolean = false
    }

    /** Priority 1 (Highest): 20s Full-Screen OTP Takeover. */
    data class Otp(
        val id: String,
        val source: String,
        val rawCode: String,
        val formattedCode: String,
        val remainingMs: Long,
        val totalDurationMs: Long = AppConfigConstants.OTP_OVERLAY_DURATION_MS
    ) : ActiveTabletOverlay {
        override val requiresTemporaryNightBrightnessBoost: Boolean = true
        val remainingFraction: Float
            get() = (remainingMs.toFloat() / totalDurationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    }

    /** Priority 2: 20s Full-Screen Timetable Takeover (resumes remaining time if interrupted by OTP). */
    data class Timetable(
        val requestId: String,
        val decision: HighlightDecision,
        val remainingMs: Long,
        val totalDurationMs: Long = AppConfigConstants.TIMETABLE_DISPLAY_DURATION_MS
    ) : ActiveTabletOverlay {
        override val requiresTemporaryNightBrightnessBoost: Boolean = false
        val remainingFraction: Float
            get() = (remainingMs.toFloat() / totalDurationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    }

    /** Priority 3: Persistent Phone Battery Critical (<= 10% and not charging). */
    data class PhoneBatteryCritical(
        val level: Int
    ) : ActiveTabletOverlay {
        override val requiresTemporaryNightBrightnessBoost: Boolean = true
    }

    /** Priority 4: 10s Phone Battery Low (<= 20% and not charging, once per discharge cycle). */
    data class PhoneBatteryLow(
        val level: Int,
        val remainingMs: Long,
        val totalDurationMs: Long = AppConfigConstants.PHONE_BATTERY_LOW_OVERLAY_DURATION_MS
    ) : ActiveTabletOverlay {
        override val requiresTemporaryNightBrightnessBoost: Boolean = true
        val remainingFraction: Float
            get() = (remainingMs.toFloat() / totalDurationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * V7 Section 1: Shared Overlay Manager on the tablet.
 *
 * Owns all full-screen overlays (OTP, Timetable, Phone Battery Critical, Phone Battery Low)
 * so they never fight:
 * - Priority 1: OTP (20 s)
 * - Priority 2: Timetable (20 s, pauses and resumes with remaining time if interrupted by OTP)
 * - Priority 3: Phone battery critical (<= 10%, persists until charging or stale > 10m)
 * - Priority 4: Phone battery low (<= 20%, 10 s, once per discharge cycle, does not resume if interrupted)
 */
class OverlayManager {

    private val lock = Any()

    private var otpOverlay: ActiveTabletOverlay.Otp? = null
    private var timetableOverlay: ActiveTabletOverlay.Timetable? = null
    private var criticalBatteryLevel: Int? = null
    private var lowBatteryOverlay: ActiveTabletOverlay.PhoneBatteryLow? = null

    private var lowAlertShownInCurrentCycle: Boolean = false

    private val _activeOverlay = MutableStateFlow<ActiveTabletOverlay>(ActiveTabletOverlay.None)
    val activeOverlay: StateFlow<ActiveTabletOverlay> = _activeOverlay.asStateFlow()

    /**
     * Triggers or replaces the Priority 1 OTP overlay (20 seconds).
     */
    fun triggerOtpOverlay(
        id: String,
        source: String,
        rawCode: String,
        durationMs: Long = AppConfigConstants.OTP_OVERLAY_DURATION_MS
    ) {
        synchronized(lock) {
            otpOverlay = ActiveTabletOverlay.Otp(
                id = id,
                source = source.ifBlank { "Unknown" },
                rawCode = rawCode,
                formattedCode = OtpDetector.formatOtpForOverlay(rawCode),
                remainingMs = durationMs,
                totalDurationMs = durationMs
            )
            // Section 1: "The 10-second low-battery alert does not resume."
            lowBatteryOverlay = null
            publishWinnerLocked()
        }
    }

    /**
     * Triggers or replaces the Priority 2 Timetable overlay (20 seconds).
     */
    fun triggerTimetableOverlay(
        requestId: String,
        decision: HighlightDecision,
        durationMs: Long = AppConfigConstants.TIMETABLE_DISPLAY_DURATION_MS
    ) {
        synchronized(lock) {
            timetableOverlay = ActiveTabletOverlay.Timetable(
                requestId = requestId,
                decision = decision,
                remainingMs = durationMs,
                totalDurationMs = durationMs
            )
            // Interrupting low-battery alert cancels it without resume
            lowBatteryOverlay = null
            publishWinnerLocked()
        }
    }

    /**
     * Evaluates incoming or ticked Phone Battery state against Section 5.2 rules:
     * - Stale (> 10 min): clear critical & low overlays.
     * - Charging or > 30%: re-arm low battery alert for next discharge cycle, clear critical & low.
     * - <= 10% and not charging: activate critical overlay persistently.
     * - <= 20% and not charging: trigger 10s low battery overlay once per discharge cycle.
     */
    fun updatePhoneBatteryState(
        payload: PhoneBatteryPayload?,
        estimatedServerNowMs: Long
    ) {
        synchronized(lock) {
            if (payload == null || payload.isStale(estimatedServerNowMs)) {
                criticalBatteryLevel = null
                lowBatteryOverlay = null
                publishWinnerLocked()
                return
            }

            if (payload.charging || payload.level > AppConfigConstants.PHONE_BATTERY_REARM_THRESHOLD) {
                lowAlertShownInCurrentCycle = false
            }

            if (payload.charging) {
                criticalBatteryLevel = null
                lowBatteryOverlay = null
                publishWinnerLocked()
                return
            }

            when {
                payload.level <= AppConfigConstants.PHONE_BATTERY_CRITICAL_THRESHOLD -> {
                    criticalBatteryLevel = payload.level
                    lowBatteryOverlay = null
                    lowAlertShownInCurrentCycle = true
                }

                payload.level <= AppConfigConstants.PHONE_BATTERY_LOW_THRESHOLD -> {
                    criticalBatteryLevel = null
                    if (!lowAlertShownInCurrentCycle) {
                        lowAlertShownInCurrentCycle = true
                        // Only start the 10s low-battery overlay if no higher-priority overlay is currently active
                        if (otpOverlay == null && timetableOverlay == null) {
                            lowBatteryOverlay = ActiveTabletOverlay.PhoneBatteryLow(
                                level = payload.level,
                                remainingMs = AppConfigConstants.PHONE_BATTERY_LOW_OVERLAY_DURATION_MS
                            )
                        }
                    } else {
                        lowBatteryOverlay = lowBatteryOverlay?.copy(level = payload.level)
                    }
                }

                else -> {
                    criticalBatteryLevel = null
                    lowBatteryOverlay = null
                }
            }

            publishWinnerLocked()
        }
    }

    /**
     * Advances the timer of ONLY the currently visible timed overlay by [deltaMs].
     * If Timetable (Priority 2) is interrupted by OTP (Priority 1), Timetable's remaining
     * time stays frozen until OTP finishes, then resumes automatically.
     */
    fun onTick(deltaMs: Long) {
        if (deltaMs <= 0L) return
        synchronized(lock) {
            val currentOtp = otpOverlay
            if (currentOtp != null) {
                val nextRemaining = currentOtp.remainingMs - deltaMs
                otpOverlay = if (nextRemaining <= 0L) {
                    null
                } else {
                    currentOtp.copy(remainingMs = nextRemaining)
                }
                publishWinnerLocked()
                return
            }

            val currentTt = timetableOverlay
            if (currentTt != null) {
                val nextRemaining = currentTt.remainingMs - deltaMs
                timetableOverlay = if (nextRemaining <= 0L) {
                    null
                } else {
                    currentTt.copy(remainingMs = nextRemaining)
                }
                publishWinnerLocked()
                return
            }

            if (criticalBatteryLevel != null) {
                publishWinnerLocked()
                return
            }

            val currentLow = lowBatteryOverlay
            if (currentLow != null) {
                val nextRemaining = currentLow.remainingMs - deltaMs
                lowBatteryOverlay = if (nextRemaining <= 0L) {
                    null
                } else {
                    currentLow.copy(remainingMs = nextRemaining)
                }
                publishWinnerLocked()
            }
        }
    }

    private fun publishWinnerLocked() {
        val winner: ActiveTabletOverlay = when {
            otpOverlay != null -> otpOverlay!!
            timetableOverlay != null -> timetableOverlay!!
            criticalBatteryLevel != null -> ActiveTabletOverlay.PhoneBatteryCritical(criticalBatteryLevel!!)
            lowBatteryOverlay != null -> lowBatteryOverlay!!
            else -> ActiveTabletOverlay.None
        }
        _activeOverlay.value = winner
    }
}
