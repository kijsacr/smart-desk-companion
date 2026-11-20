package com.smartdesk.companion.shared

import com.google.firebase.database.DataSnapshot
import java.time.LocalTime
import kotlin.math.abs

/**
 * V7 Section 2: Night Schedule configuration (`config/nightSchedule`).
 * `{ "enabled": true, "start": "23:00", "end": "06:30", "brightnessPct": 5 }`
 */
data class NightScheduleConfig(
    val enabled: Boolean = AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_ENABLED,
    val start: String = AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_START,
    val end: String = AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_END,
    val brightnessPct: Int = AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_BRIGHTNESS_PCT
) {
    val clampedBrightnessPct: Int
        get() = brightnessPct.coerceIn(
            AppConfigConstants.NIGHT_SCHEDULE_MIN_BRIGHTNESS_PCT,
            AppConfigConstants.NIGHT_SCHEDULE_MAX_BRIGHTNESS_PCT
        )

    val brightnessFloat: Float
        get() = clampedBrightnessPct.toFloat() / 100f

    /**
     * Evaluates whether [now] falls within `[start, end)`.
     * Handles windows that cross midnight (e.g. `23:00` to `06:30`).
     */
    fun isActiveAt(now: LocalTime): Boolean {
        if (!enabled) return false
        val startTime = parseHourMinute(start) ?: LocalTime.of(23, 0)
        val endTime = parseHourMinute(end) ?: LocalTime.of(6, 30)

        return if (startTime == endTime) {
            true
        } else if (startTime.isBefore(endTime)) {
            // Same-day window (e.g. 01:00 to 06:00)
            !now.isBefore(startTime) && now.isBefore(endTime)
        } else {
            // Overnight window crossing midnight (e.g. 23:00 to 06:30)
            !now.isBefore(startTime) || now.isBefore(endTime)
        }
    }

    companion object {
        fun parseHourMinute(value: String?): LocalTime? {
            if (value.isNullOrBlank()) return null
            val parts = value.trim().split(":")
            if (parts.size < 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return LocalTime.of(h, m)
        }

        fun formatHourMinute(hour: Int, minute: Int): String =
            String.format(java.util.Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

        fun fromSnapshot(snapshot: DataSnapshot): NightScheduleConfig {
            if (!snapshot.exists()) return NightScheduleConfig()
            val enabled = snapshot.child("enabled").getValue(Boolean::class.java)
                ?: AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_ENABLED
            val start = snapshot.child("start").getValue(String::class.java)?.trim()
                ?.takeIf { parseHourMinute(it) != null }
                ?: AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_START
            val end = snapshot.child("end").getValue(String::class.java)?.trim()
                ?.takeIf { parseHourMinute(it) != null }
                ?: AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_END
            val brightness = (
                snapshot.child("brightnessPct").getValue(Int::class.java)
                    ?: snapshot.child("brightnessPct").getValue(Long::class.java)?.toInt()
                    ?: AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_BRIGHTNESS_PCT
                ).coerceIn(
                AppConfigConstants.NIGHT_SCHEDULE_MIN_BRIGHTNESS_PCT,
                AppConfigConstants.NIGHT_SCHEDULE_MAX_BRIGHTNESS_PCT
            )
            return NightScheduleConfig(
                enabled = enabled,
                start = start,
                end = end,
                brightnessPct = brightness
            )
        }
    }
}

/**
 * V7 Section 3: Pixel Shift (`config/pixelShift`).
 * Cycles through 9 positions on a ring every 3 minutes:
 * `(0,0), (4,0), (4,4), (0,4), (-4,4), (-4,0), (-4,-4), (0,-4), (4,-4)` dp.
 */
object PixelShiftRing {
    val OFFSETS_DP: List<Pair<Int, Int>> = listOf(
        0 to 0,
        4 to 0,
        4 to 4,
        0 to 4,
        -4 to 4,
        -4 to 0,
        -4 to -4,
        0 to -4,
        4 to -4
    )

    fun offsetForIndex(index: Int, enabled: Boolean): Pair<Int, Int> {
        if (!enabled) return 0 to 0
        val safeIdx = (index % OFFSETS_DP.size + OFFSETS_DP.size) % OFFSETS_DP.size
        return OFFSETS_DP[safeIdx]
    }

    fun parseEnabled(snapshot: DataSnapshot): Boolean {
        if (!snapshot.exists()) return AppConfigConstants.PIXEL_SHIFT_DEFAULT_ENABLED
        snapshot.getValue(Boolean::class.java)?.let { return it }
        return snapshot.child("enabled").getValue(Boolean::class.java)
            ?: AppConfigConstants.PIXEL_SHIFT_DEFAULT_ENABLED
    }
}

/**
 * V7 Section 5: Phone Battery payload (`phone/battery`).
 * `{ "level": 82, "charging": false, "updatedAt": { ".sv": "timestamp" } }`
 */
data class PhoneBatteryPayload(
    val level: Int,
    val charging: Boolean,
    val updatedAt: Long
) {
    fun isStale(
        estimatedServerNowMs: Long,
        staleThresholdMs: Long = AppConfigConstants.PHONE_BATTERY_STALE_MS
    ): Boolean {
        if (updatedAt <= 0L) return true
        return abs(estimatedServerNowMs - updatedAt) > staleThresholdMs
    }

    companion object {
        fun fromSnapshot(snapshot: DataSnapshot): PhoneBatteryPayload? {
            if (!snapshot.exists()) return null
            val level = (
                snapshot.child("level").getValue(Int::class.java)
                    ?: snapshot.child("level").getValue(Long::class.java)?.toInt()
                )?.coerceIn(0, 100) ?: return null
            val charging = snapshot.child("charging").getValue(Boolean::class.java) ?: false
            val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
            return PhoneBatteryPayload(
                level = level,
                charging = charging,
                updatedAt = updatedAt
            )
        }
    }
}

/**
 * V7 Section 6: Proximity Screen-Off configuration (`config/proximity`).
 * `{ "enabled": true, "offDelayMin": 5 }`
 */
data class ProximityConfig(
    val enabled: Boolean = AppConfigConstants.PROXIMITY_DEFAULT_ENABLED,
    val offDelayMin: Int = AppConfigConstants.PROXIMITY_DEFAULT_OFF_DELAY_MIN
) {
    val clampedDelayMin: Int
        get() = offDelayMin.coerceIn(
            AppConfigConstants.PROXIMITY_MIN_DELAY_MIN,
            AppConfigConstants.PROXIMITY_MAX_DELAY_MIN
        )

    val clampedOffDelayMin: Int
        get() = clampedDelayMin

    val delayMillis: Long
        get() = clampedDelayMin * 60_000L

    companion object {
        fun fromSnapshot(snapshot: DataSnapshot): ProximityConfig {
            if (!snapshot.exists()) return ProximityConfig()
            val enabled = snapshot.child("enabled").getValue(Boolean::class.java)
                ?: AppConfigConstants.PROXIMITY_DEFAULT_ENABLED
            val delayMin = (
                snapshot.child("offDelayMin").getValue(Int::class.java)
                    ?: snapshot.child("offDelayMin").getValue(Long::class.java)?.toInt()
                    ?: AppConfigConstants.PROXIMITY_DEFAULT_OFF_DELAY_MIN
                ).coerceIn(
                AppConfigConstants.PROXIMITY_MIN_DELAY_MIN,
                AppConfigConstants.PROXIMITY_MAX_DELAY_MIN
            )
            return ProximityConfig(
                enabled = enabled,
                offDelayMin = delayMin
            )
        }
    }
}

/**
 * V7 Section 6.4: Live Tablet Screen status published by the tablet (`status/tabletScreen`)
 * so the phone Settings screen can display `Tablet: on / waiting to switch off / off`.
 */
data class TabletScreenStatus(
    val state: String = STATE_ON,
    val updatedAt: Long = 0L
) {
    companion object {
        const val STATE_ON = "on"
        const val STATE_WAITING = "waiting to switch off"
        const val STATE_OFF = "off"

        fun fromSnapshot(snapshot: DataSnapshot): TabletScreenStatus {
            if (!snapshot.exists()) return TabletScreenStatus()
            val state = snapshot.child("state").getValue(String::class.java)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: STATE_ON
            val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
            return TabletScreenStatus(state = state, updatedAt = updatedAt)
        }
    }
}

/**
 * V7 Section 7: Phone DND state (`phone/dnd`) and Music & Clock Mode config (`config/musicMode`).
 */
data class PhoneDndPayload(
    val active: Boolean = false,
    val updatedAt: Long = 0L
) {
    companion object {
        fun fromSnapshot(snapshot: DataSnapshot): PhoneDndPayload {
            if (!snapshot.exists()) return PhoneDndPayload()
            val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
            val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
            return PhoneDndPayload(active = active, updatedAt = updatedAt)
        }
    }
}

object MusicModeConfigParser {
    fun parseEnabled(snapshot: DataSnapshot): Boolean {
        if (!snapshot.exists()) return AppConfigConstants.MUSIC_MODE_DEFAULT_ENABLED
        snapshot.getValue(Boolean::class.java)?.let { return it }
        return snapshot.child("enabled").getValue(Boolean::class.java)
            ?: AppConfigConstants.MUSIC_MODE_DEFAULT_ENABLED
    }
}

/**
 * V8 Section 6: Voice override for Music & Clock Mode (`config/musicModeOverride`).
 * `{ "mode": "on" | "off" | "auto", "updatedAt": <server timestamp> }`
 *
 * Effective Music & Clock Mode rule:
 * 1. If an override exists and its `updatedAt` is newer than `phone/dnd/updatedAt`,
 *    then `"on"` forces the mode on, `"off"` forces it off, and `"auto"` follows the phone.
 * 2. Otherwise follow the phone exactly as in V7 (`phoneDnd.active && musicModeEnabled`).
 */
data class MusicModeOverridePayload(
    val mode: String = MODE_AUTO,
    val updatedAt: Long = 0L
) {
    companion object {
        const val MODE_ON = "on"
        const val MODE_OFF = "off"
        const val MODE_AUTO = "auto"

        fun fromSnapshot(snapshot: DataSnapshot): MusicModeOverridePayload {
            if (!snapshot.exists()) return MusicModeOverridePayload()
            val rawMode = snapshot.child("mode").getValue(String::class.java)?.trim()?.lowercase()
                ?: snapshot.getValue(String::class.java)?.trim()?.lowercase()
                ?: MODE_AUTO
            val mode = when (rawMode) {
                MODE_ON -> MODE_ON
                MODE_OFF -> MODE_OFF
                else -> MODE_AUTO
            }
            val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
            return MusicModeOverridePayload(mode = mode, updatedAt = updatedAt)
        }

        fun resolveEffectiveMusicClockMode(
            phoneDnd: PhoneDndPayload,
            musicModeEnabled: Boolean,
            overridePayload: MusicModeOverridePayload
        ): Boolean {
            val phoneFollowResult = phoneDnd.active && musicModeEnabled
            if (overridePayload.updatedAt > 0L && overridePayload.updatedAt > phoneDnd.updatedAt) {
                return when (overridePayload.mode.lowercase()) {
                    MODE_ON -> true
                    MODE_OFF -> false
                    else -> phoneFollowResult
                }
            }
            return phoneFollowResult
        }
    }
}

/**
 * V8 Section 6 & Section 7: Live Alexa skill status (`status/alexa`).
 * `{ "lastIntent": "ShowTimetableIntent", "lastAt": { ".sv": "timestamp" }, "ok": true, "error": null }`
 */
data class AlexaStatusPayload(
    val lastIntent: String = "",
    val lastAt: Long = 0L,
    val ok: Boolean = true,
    val error: String? = null
) {
    fun formatStatusLine(estimatedServerNowMs: Long): String {
        if (lastIntent.isBlank() && lastAt <= 0L) {
            return "No Alexa commands yet"
        }
        if (!ok) {
            val errReason = error?.trim()?.takeIf { it.isNotEmpty() } ?: "could not reach Firebase"
            return "Last Alexa command failed: $errReason"
        }
        val friendlyIntent = friendlyIntentLabel(lastIntent)
        val ageText = if (lastAt <= 0L) {
            "just now"
        } else {
            val elapsedMs = (estimatedServerNowMs - lastAt).coerceAtLeast(0L)
            val mins = elapsedMs / 60_000L
            when {
                mins <= 0L -> "just now"
                mins < 60L -> "$mins min ago"
                else -> "${mins / 60L} hr ago"
            }
        }
        return "Last Alexa command: $friendlyIntent, $ageText"
    }

    companion object {
        fun friendlyIntentLabel(intentName: String): String = when (intentName.trim()) {
            "ShowTimetableIntent" -> "Show timetable"
            "AddTaskIntent" -> "Add task"
            "WhatsDueIntent" -> "What's due"
            "SetThemeIntent" -> "Set clock theme"
            "PhoneBatteryIntent" -> "Phone battery"
            "PcStatsIntent" -> "PC stats"
            "MusicModeIntent" -> "Music mode"
            "ClearNotificationsIntent" -> "Clear notifications"
            "LaunchRequest" -> "Open Smart Desk"
            else -> intentName.removeSuffix("Intent").ifBlank { "Voice command" }
        }

        fun fromSnapshot(snapshot: DataSnapshot): AlexaStatusPayload {
            if (!snapshot.exists()) return AlexaStatusPayload()
            val lastIntent = snapshot.child("lastIntent").getValue(String::class.java)?.trim().orEmpty()
            val lastAt = snapshot.child("lastAt").getValue(Long::class.java) ?: 0L
            val ok = snapshot.child("ok").getValue(Boolean::class.java) ?: true
            val error = snapshot.child("error").getValue(String::class.java)?.trim()
                ?.takeIf { it.isNotEmpty() }
            return AlexaStatusPayload(
                lastIntent = lastIntent,
                lastAt = lastAt,
                ok = ok,
                error = error
            )
        }
    }
}
