package com.smartdesk.companion.shared

import com.google.firebase.database.DataSnapshot
import kotlin.math.abs

/**
 * V6 Section 1.3 & 1.4:
 * Desktop System Resource Monitor payload written by `pc_monitor.py` to `/pc/stats`.
 */
data class PcStatsPayload(
    val cpu: Int = 0,
    val ram: Int = 0,
    val temp: Int? = null,
    val tempSource: String? = null,
    val host: String = "",
    val updatedAt: Long = 0L
) {
    fun isOffline(estimatedServerNowMs: Long): Boolean {
        if (updatedAt <= 0L) return true
        val ageMs = abs(estimatedServerNowMs - updatedAt)
        return ageMs > AppConfigConstants.PC_STATS_STALE_MS
    }

    fun secondsAgo(estimatedServerNowMs: Long): Long {
        if (updatedAt <= 0L) return -1L
        return ((estimatedServerNowMs - updatedAt).coerceAtLeast(0L)) / 1000L
    }

    companion object {
        fun fromSnapshot(snapshot: DataSnapshot): PcStatsPayload? {
            if (!snapshot.exists()) return null
            val cpu = snapshot.child("cpu").getValue(Int::class.java)
                ?: snapshot.child("cpu").getValue(Double::class.java)?.toInt()
                ?: return null
            val ram = snapshot.child("ram").getValue(Int::class.java)
                ?: snapshot.child("ram").getValue(Double::class.java)?.toInt()
                ?: return null
            val temp = snapshot.child("temp").getValue(Int::class.java)
                ?: snapshot.child("temp").getValue(Double::class.java)?.toInt()
            val tempSource = snapshot.child("tempSource").getValue(String::class.java)
            val host = snapshot.child("host").getValue(String::class.java).orEmpty()
            val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
            return PcStatsPayload(
                cpu = cpu.coerceIn(0, 100),
                ram = ram.coerceIn(0, 100),
                temp = temp,
                tempSource = tempSource,
                host = host,
                updatedAt = updatedAt
            )
        }
    }
}

/**
 * V6 Section 2.3:
 * Configuration stored at `/config/batteryAutomation` (written by Phone, read by Tablet).
 */
data class BatteryAutomationConfig(
    val enabled: Boolean = false,
    val cutAt: Int = AppConfigConstants.BATTERY_DEFAULT_CUT_AT,
    val resumeAt: Int = AppConfigConstants.BATTERY_DEFAULT_RESUME_AT,
    val offUrl: String = "",
    val onUrl: String = "",
    val method: String = "GET"
) {
    val normalizedMethod: String
        get() = if (method.trim().equals("POST", ignoreCase = true)) "POST" else "GET"

    companion object {
        fun fromSnapshot(snapshot: DataSnapshot): BatteryAutomationConfig {
            if (!snapshot.exists()) return BatteryAutomationConfig()
            val enabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
            val rawCutAt = snapshot.child("cutAt").getValue(Int::class.java)
                ?: AppConfigConstants.BATTERY_DEFAULT_CUT_AT
            val rawResumeAt = snapshot.child("resumeAt").getValue(Int::class.java)
                ?: AppConfigConstants.BATTERY_DEFAULT_RESUME_AT
            val offUrl = snapshot.child("offUrl").getValue(String::class.java)?.trim().orEmpty()
            val onUrl = snapshot.child("onUrl").getValue(String::class.java)?.trim().orEmpty()
            val method = snapshot.child("method").getValue(String::class.java)?.trim() ?: "GET"

            val (validCut, validResume) = BatteryProtectionStateMachine.sanitizeThresholds(
                rawCutAt,
                rawResumeAt
            )
            return BatteryAutomationConfig(
                enabled = enabled,
                cutAt = validCut,
                resumeAt = validResume,
                offUrl = offUrl,
                onUrl = onUrl,
                method = if (method.equals("POST", ignoreCase = true)) "POST" else "GET"
            )
        }
    }
}

/**
 * V6 Section 2.3:
 * Live status stored at `/status/batteryAutomation` (written by Tablet, read by Phone & Tablet UI).
 * - `lastCommand`: "off" | "on" | "unknown"
 * - `state`: "idle" | "holding" | "charging" | "error"
 */
data class BatteryAutomationStatus(
    val lastCommand: String = COMMAND_UNKNOWN,
    val lastCommandAt: Long = 0L,
    val state: String = STATE_IDLE,
    val error: String? = null,
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false
) {
    val isHolding: Boolean
        get() = state.equals(STATE_HOLDING, ignoreCase = true)

    val isError: Boolean
        get() = state.equals(STATE_ERROR, ignoreCase = true) || !error.isNullOrBlank()

    companion object {
        const val COMMAND_OFF = "off"
        const val COMMAND_ON = "on"
        const val COMMAND_UNKNOWN = "unknown"

        const val STATE_IDLE = "idle"
        const val STATE_HOLDING = "holding"
        const val STATE_CHARGING = "charging"
        const val STATE_ERROR = "error"

        fun fromSnapshot(snapshot: DataSnapshot): BatteryAutomationStatus {
            if (!snapshot.exists()) return BatteryAutomationStatus()
            return BatteryAutomationStatus(
                lastCommand = snapshot.child("lastCommand").getValue(String::class.java)
                    ?: COMMAND_UNKNOWN,
                lastCommandAt = snapshot.child("lastCommandAt").getValue(Long::class.java) ?: 0L,
                state = snapshot.child("state").getValue(String::class.java) ?: STATE_IDLE,
                error = snapshot.child("error").getValue(String::class.java),
                batteryLevel = snapshot.child("batteryLevel").getValue(Int::class.java) ?: -1,
                isCharging = snapshot.child("isCharging").getValue(Boolean::class.java) ?: false
            )
        }
    }
}

/**
 * Pure decision output from the hysteresis state machine (Section 2.2).
 */
enum class BatteryWebhookAction {
    NONE,
    SEND_OFF,
    SEND_ON
}

data class BatteryStateEvaluation(
    val action: BatteryWebhookAction,
    val nextLastCommand: String,
    val nextState: String
)

/**
 * V6 Section 2.2:
 * Pure hysteresis state machine for Smart Battery Protection (80% cut / 25% restore).
 */
object BatteryProtectionStateMachine {

    /**
     * Enforces:
     * - `cutAt` in 50..95
     * - `resumeAt` in 10..50
     * - `resumeAt <= cutAt - 20`
     */
    fun sanitizeThresholds(rawCutAt: Int, rawResumeAt: Int): Pair<Int, Int> {
        val cut = rawCutAt.coerceIn(
            AppConfigConstants.BATTERY_MIN_CUT_AT,
            AppConfigConstants.BATTERY_MAX_CUT_AT
        )
        val maxAllowedResume = (cut - AppConfigConstants.BATTERY_MIN_HYSTERESIS_GAP)
            .coerceAtMost(AppConfigConstants.BATTERY_MAX_RESUME_AT)
        val resume = rawResumeAt.coerceIn(
            AppConfigConstants.BATTERY_MIN_RESUME_AT,
            maxAllowedResume
        )
        return cut to resume
    }

    /**
     * Evaluates a battery reading against the hysteresis thresholds and previous command state:
     * - If disabled:
     *   - Failsafe: if `previousLastCommand == "off"`, immediately send ON (`SEND_ON`) so the
     *     tablet does not drain to 0% with the charger cut.
     *   - Otherwise do nothing (`NONE`, `STATE_IDLE`).
     * - If enabled:
     *   - `level >= cutAt && isCharging`: if `previousLastCommand != "off"`, trigger `SEND_OFF`
     *     and enter `STATE_HOLDING`.
     *   - `level <= resumeAt && !isCharging`: if `previousLastCommand != "on"`, trigger `SEND_ON`
     *     and enter `STATE_CHARGING`.
     *   - Anything between: `NONE` (do not spam webhooks on every battery tick).
     */
    fun evaluate(
        config: BatteryAutomationConfig,
        batteryLevel: Int,
        isCharging: Boolean,
        previousLastCommand: String,
        previousState: String
    ): BatteryStateEvaluation {
        if (!config.enabled) {
            return if (previousLastCommand == BatteryAutomationStatus.COMMAND_OFF) {
                BatteryStateEvaluation(
                    action = BatteryWebhookAction.SEND_ON,
                    nextLastCommand = BatteryAutomationStatus.COMMAND_ON,
                    nextState = BatteryAutomationStatus.STATE_IDLE
                )
            } else {
                BatteryStateEvaluation(
                    action = BatteryWebhookAction.NONE,
                    nextLastCommand = BatteryAutomationStatus.COMMAND_UNKNOWN,
                    nextState = BatteryAutomationStatus.STATE_IDLE
                )
            }
        }

        if (batteryLevel !in 0..100) {
            return BatteryStateEvaluation(
                action = BatteryWebhookAction.NONE,
                nextLastCommand = previousLastCommand,
                nextState = previousState
            )
        }

        val (cutAt, resumeAt) = sanitizeThresholds(config.cutAt, config.resumeAt)

        // Condition 1: Level >= cutAt AND tablet is charging/plugged in -> Cut power (OFF)
        if (batteryLevel >= cutAt && isCharging) {
            return if (previousLastCommand != BatteryAutomationStatus.COMMAND_OFF) {
                BatteryStateEvaluation(
                    action = BatteryWebhookAction.SEND_OFF,
                    nextLastCommand = BatteryAutomationStatus.COMMAND_OFF,
                    nextState = BatteryAutomationStatus.STATE_HOLDING
                )
            } else {
                BatteryStateEvaluation(
                    action = BatteryWebhookAction.NONE,
                    nextLastCommand = BatteryAutomationStatus.COMMAND_OFF,
                    nextState = BatteryAutomationStatus.STATE_HOLDING
                )
            }
        }

        // Condition 2: Level <= resumeAt AND tablet is not charging -> Restore power (ON)
        if (batteryLevel <= resumeAt && !isCharging) {
            return if (previousLastCommand != BatteryAutomationStatus.COMMAND_ON) {
                BatteryStateEvaluation(
                    action = BatteryWebhookAction.SEND_ON,
                    nextLastCommand = BatteryAutomationStatus.COMMAND_ON,
                    nextState = BatteryAutomationStatus.STATE_CHARGING
                )
            } else {
                BatteryStateEvaluation(
                    action = BatteryWebhookAction.NONE,
                    nextLastCommand = BatteryAutomationStatus.COMMAND_ON,
                    nextState = BatteryAutomationStatus.STATE_CHARGING
                )
            }
        }

        // Condition 3: Anything between -> Do nothing (preserve current transition state)
        val steadyState = when {
            previousLastCommand == BatteryAutomationStatus.COMMAND_OFF ->
                BatteryAutomationStatus.STATE_HOLDING
            previousLastCommand == BatteryAutomationStatus.COMMAND_ON || isCharging ->
                BatteryAutomationStatus.STATE_CHARGING
            else -> BatteryAutomationStatus.STATE_IDLE
        }

        return BatteryStateEvaluation(
            action = BatteryWebhookAction.NONE,
            nextLastCommand = previousLastCommand,
            nextState = steadyState
        )
    }
}
