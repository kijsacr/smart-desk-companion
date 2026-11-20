package com.smartdesk.companion.shared

/**
 * Section 9 & V7 Section 12: Centralized configuration constants.
 * Change any threshold or node name here and both :tablet and :phone update automatically.
 */
object AppConfigConstants {

    /** Section 9.4: Maximum number of task cards displayed on the non-scrollable tablet viewer. */
    const val MAX_TABLET_VISIBLE_TASKS: Int = 4

    /**
     * Section 9.2: Red urgency threshold in days until deadline.
     * 1L = Tomorrow triggers red urgency.
     */
    const val URGENCY_THRESHOLD_DAYS: Long = 1L

    /**
     * Section 9.2: When true, tasks due Today (0 days) and Overdue (< 0 days) also stay red.
     * Set to false if red should apply on the day before (Tomorrow) only.
     */
    const val URGENCY_KEEP_RED_FOR_TODAY_AND_OVERDUE: Boolean = true

    /**
     * Section 9.3: Optional auto-purge threshold in days for completed tasks in Firebase.
     */
    const val COMPLETED_TASK_AUTO_PURGE_DAYS: Long = 30L

    /** Module I: Firebase Realtime Database node paths. */
    const val FIREBASE_NOTIFICATIONS_NODE: String = "notifications"
    const val FIREBASE_TASKS_NODE: String = "tasks"
    const val FIREBASE_CONFIG_NODE: String = "config"
    const val FIREBASE_CLOCK_THEME_KEY: String = "clockTheme"
    const val FIREBASE_CLOCK_THEME_PATH: String = "config/clockTheme"
    const val FIREBASE_TIMETABLE_COMMAND_PATH: String = "commands/timetable"

    /** Default Clock Theme ID (Theme 2: Sunset Float — the existing Blue & Orange look). */
    const val DEFAULT_CLOCK_THEME_ID: String = "sunset_float"
    const val NIGHT_DIM_CLOCK_THEME_ID: String = "night_dim"

    /** Section 4.1: Standard Google Calendar package name. */
    const val GOOGLE_CALENDAR_PACKAGE: String = "com.google.android.calendar"

    /** V5 & V7 Section 1: Overlay durations & 15-second command staleness guard. */
    const val COMMAND_STALE_MS: Long = 15_000L
    const val TIMETABLE_DISPLAY_DURATION_SEC: Int = 20
    const val TIMETABLE_DISPLAY_DURATION_MS: Long = 20_000L
    const val TIMETABLE_COMMAND_STALE_MS: Long = 15_000L
    const val TIMETABLE_HIGHLIGHT_SCALE: Float = 1.10f
    const val TIMETABLE_DIM_ALPHA: Float = 0.55f

    const val OTP_OVERLAY_DURATION_MS: Long = 20_000L
    const val PHONE_BATTERY_LOW_OVERLAY_DURATION_MS: Long = 10_000L
    const val NIGHT_OVERLAY_TEMP_BRIGHTNESS_PCT: Int = 40
    const val NIGHT_SCHEDULE_TEMP_BOOST_BRIGHTNESS: Float = 0.40f

    /** V6 Addendum: Firebase Realtime Database paths for PC Monitor & Smart Battery Protection. */
    const val FIREBASE_PC_STATS_PATH: String = "pc/stats"
    const val FIREBASE_PC_MONITOR_ENABLED_PATH: String = "config/pcMonitor/enabled"
    const val FIREBASE_PC_FULLSCREEN_PATH: String = "config/pcMonitor/fullScreen"
    const val FIREBASE_BATTERY_CONFIG_PATH: String = "config/batteryAutomation"
    const val FIREBASE_BATTERY_STATUS_PATH: String = "status/batteryAutomation"

    /** V6 Section 1.5 & V7 Section 5.4: PC Resource Monitor thresholds. */
    const val PC_STATS_STALE_MS: Long = 45_000L
    const val PC_CPU_AMBER_THRESHOLD: Int = 90
    const val PC_RAM_AMBER_THRESHOLD: Int = 90
    const val PC_TEMP_AMBER_THRESHOLD: Int = 80

    /** V6 Section 2.2: Smart Battery Protection defaults and limits. */
    const val BATTERY_DEFAULT_CUT_AT: Int = 80
    const val BATTERY_DEFAULT_RESUME_AT: Int = 25
    const val BATTERY_MIN_CUT_AT: Int = 50
    const val BATTERY_MAX_CUT_AT: Int = 95
    const val BATTERY_MIN_RESUME_AT: Int = 10
    const val BATTERY_MAX_RESUME_AT: Int = 50
    const val BATTERY_MIN_HYSTERESIS_GAP: Int = 20

    /** V7 Section 9: Firebase Realtime Database paths (V7 additions). */
    const val FIREBASE_PHONE_BATTERY_PATH: String = "phone/battery"
    const val FIREBASE_PHONE_PRESENCE_PATH: String = "phone/presence/lastSeen"
    const val FIREBASE_PHONE_PRESENCE_LAST_SEEN_PATH: String = "phone/presence/lastSeen"
    const val FIREBASE_PHONE_DND_PATH: String = "phone/dnd"
    const val FIREBASE_NIGHT_SCHEDULE_PATH: String = "config/nightSchedule"
    const val FIREBASE_PIXEL_SHIFT_PATH: String = "config/pixelShift"
    const val FIREBASE_PROXIMITY_CONFIG_PATH: String = "config/proximity"
    const val FIREBASE_MUSIC_MODE_CONFIG_PATH: String = "config/musicMode"
    const val FIREBASE_MUSIC_MODE_PATH: String = "config/musicMode"
    const val FIREBASE_OTP_CONFIG_PATH: String = "config/otpAlerts"
    const val FIREBASE_OTP_ALERTS_PATH: String = "config/otpAlerts"
    const val FIREBASE_TABLET_SCREEN_STATUS_PATH: String = "status/tabletScreen"

    /** V7 Section 2: Night Schedule defaults and bounds. */
    const val NIGHT_SCHEDULE_DEFAULT_ENABLED: Boolean = true
    const val NIGHT_SCHEDULE_DEFAULT_START: String = "23:00"
    const val NIGHT_SCHEDULE_DEFAULT_END: String = "06:30"
    const val NIGHT_SCHEDULE_DEFAULT_BRIGHTNESS_PCT: Int = 5
    const val NIGHT_SCHEDULE_MIN_BRIGHTNESS_PCT: Int = 1
    const val NIGHT_SCHEDULE_MAX_BRIGHTNESS_PCT: Int = 40

    /** V7 Section 3: Pixel Shift defaults and step interval. */
    const val PIXEL_SHIFT_DEFAULT_ENABLED: Boolean = true
    const val PIXEL_SHIFT_INTERVAL_MS: Long = 180_000L // 3 minutes
    const val PIXEL_SHIFT_EXTRA_PADDING_DP: Int = 4

    /** V7 Section 4: OTP Detection constants. */
    const val OTP_DEDUP_WINDOW_MS: Long = 120_000L // 2 minutes
    const val SAMPLE_TEST_OTP_SOURCE: String = "Swiggy"
    const val SAMPLE_TEST_OTP_CODE: String = "482913"

    /** V7 Section 5: Phone Battery Monitoring & Alerts constants. */
    const val PHONE_BATTERY_LOW_THRESHOLD: Int = 20
    const val PHONE_BATTERY_CRITICAL_THRESHOLD: Int = 10
    const val PHONE_BATTERY_REARM_THRESHOLD: Int = 30
    const val PHONE_BATTERY_STALE_MS: Long = 600_000L // 10 minutes
    const val PHONE_BATTERY_HEARTBEAT_MS: Long = 300_000L // 5 minutes
    const val PHONE_BATTERY_HEARTBEAT_INTERVAL_MS: Long = 300_000L // 5 minutes

    /** V7 Section 6: Proximity Screen-Off constants. */
    const val PROXIMITY_DEFAULT_ENABLED: Boolean = true
    const val PROXIMITY_DEFAULT_OFF_DELAY_MIN: Int = 5
    const val PROXIMITY_MIN_DELAY_MIN: Int = 1
    const val PROXIMITY_MAX_DELAY_MIN: Int = 30
    const val PROXIMITY_MIN_OFF_DELAY_MIN: Int = 1
    const val PROXIMITY_MAX_OFF_DELAY_MIN: Int = 30
    const val PHONE_PRESENCE_HEARTBEAT_MS: Long = 60_000L // 60 seconds
    const val PHONE_PRESENCE_HEARTBEAT_INTERVAL_MS: Long = 60_000L // 60 seconds
    const val PHONE_PRESENCE_STALE_MS: Long = 120_000L // 2 minutes

    /** V7 Section 7: DND Music & Clock Mode constants. */
    const val MUSIC_MODE_DEFAULT_ENABLED: Boolean = true
    const val DISC_ROTATION_DURATION_MS: Int = 20_000 // 1 turn per 20 seconds

    /** V8 Addendum: Alexa Voice Control Firebase paths and defaults. */
    const val FIREBASE_ALEXA_ENABLED_PATH: String = "config/alexa/enabled"
    const val FIREBASE_ALEXA_STATUS_PATH: String = "status/alexa"
    const val FIREBASE_MUSIC_MODE_OVERRIDE_PATH: String = "config/musicModeOverride"
    const val ALEXA_DEFAULT_ENABLED: Boolean = true

    /** V9 Addendum: Performance Mode Firebase Realtime Database paths, BLE UUIDs & constants. */
    const val FIREBASE_PERFORMANCE_ROOT_NODE: String = "performance"
    const val FIREBASE_PERFORMANCE_STATE_PATH: String = "performance/state"
    const val FIREBASE_PERF_STATE_PATH: String = "performance/state"
    const val FIREBASE_PERFORMANCE_LIVE_PATH: String = "performance/live"
    const val FIREBASE_PERF_LIVE_PATH: String = "performance/live"
    const val FIREBASE_PERFORMANCE_ACK_PATH: String = "performance/ack"
    const val FIREBASE_PERF_ACK_PATH: String = "performance/ack"
    const val FIREBASE_PERFORMANCE_SECRET_PATH: String = "performance/bleSecret"
    const val FIREBASE_PERF_BLE_SECRET_PATH: String = "performance/bleSecret"
    const val FIREBASE_PERFORMANCE_SESSIONS_PATH: String = "performance/sessions"
    const val FIREBASE_PERF_SESSIONS_PATH: String = "performance/sessions"
    const val FIREBASE_PERFORMANCE_CHUNKS_PATH: String = "performance/chunks"
    const val FIREBASE_PERF_CHUNKS_PATH: String = "performance/chunks"
    const val FIREBASE_PERFORMANCE_TABLET_CACHE_PATH: String = "performance/tabletCache"
    const val FIREBASE_PERF_TABLET_CACHE_PATH: String = "performance/tabletCache"
    const val FIREBASE_PERF_PENDING_DELETES_PATH: String = "performance/pendingDeletes"
    const val FIREBASE_TASKS_PATH: String = "tasks"

    /** V9 Section 11 & 13.2: Fixed generated BLE GATT UUIDs for Performance Mode. */
    const val PERF_BLE_SERVICE_UUID: String = "7a9e0001-4c8d-4f21-b863-59d4e21a9001"
    const val PERF_BLE_CONTROL_CHAR_UUID: String = "7a9e0002-4c8d-4f21-b863-59d4e21a9001"
    const val PERF_BLE_ACK_CHAR_UUID: String = "7a9e0003-4c8d-4f21-b863-59d4e21a9001"
    const val PERF_BLE_CCCD_UUID: String = "00002902-0000-1000-8000-00805f9b34fb"

    /** V9 Section 6 & 11: Strip rendering and live transport limits. */
    const val PERF_STRIP_TARGET_WIDTH_PX: Int = 2200
    const val PERF_STRIP_RENDER_WIDTH_PX: Int = 2200
    const val PERF_STRIP_MAX_TILE_HEIGHT_PX: Int = 3600
    const val PERF_RTDB_CHUNK_CHAR_SIZE: Int = 85_000
    const val PERF_BLE_THROTTLE_MS: Long = 16L // ~60 messages/sec over BLE
    const val PERF_BLE_CONTINUOUS_THROTTLE_MS: Long = 16L
    const val PERF_FIREBASE_LIVE_THROTTLE_MS: Long = 75L // ~13 messages/sec over Firebase
    const val PERF_SNAPSHOT_SAVE_INTERVAL_MS: Long = 2_000L // Every 2s during continuous movement
    const val PERF_STATE_SNAPSHOT_INTERVAL_MS: Long = 2_000L
    const val PERF_BLE_HEARTBEAT_INTERVAL_MS: Long = 500L // Heartbeat every 500ms
    const val PERF_BLE_MISSED_HEARTBEATS_LIMIT: Int = 2
    const val PERF_BLE_MISSED_HEARTBEAT_TIMEOUT_MS: Long = 1_100L
    const val PERF_DISCRETE_ACK_TIMEOUT_MS: Long = 300L // 300ms discrete ack failover
    const val PERF_BLE_DISCRETE_ACK_TIMEOUT_MS: Long = 300L
    const val PERF_BLE_STABLE_RECOVERY_MS: Long = 4_000L // Stable for 4s before switching back
    const val PERF_BLE_STABILITY_WINDOW_MS: Long = 4_000L
}
