package com.smartdesk.companion.tablet.sync

import android.content.Context
import android.os.SystemClock
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.BatteryAutomationStatus
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.shared.ClockThemeCatalog
import com.smartdesk.companion.shared.HighlightDecision
import com.smartdesk.companion.shared.MusicModeConfigParser
import com.smartdesk.companion.shared.MusicModeOverridePayload
import com.smartdesk.companion.shared.NightScheduleConfig
import com.smartdesk.companion.shared.PcStatsPayload
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import com.smartdesk.companion.shared.PhoneBatteryPayload
import com.smartdesk.companion.shared.PhoneDndPayload
import com.smartdesk.companion.shared.PixelShiftRing
import com.smartdesk.companion.shared.TaskItem
import com.smartdesk.companion.shared.TaskScheduleLogic
import com.smartdesk.companion.shared.TimetableData
import com.smartdesk.companion.shared.TimetableMode
import com.smartdesk.companion.tablet.model.NotificationPayload
import com.smartdesk.companion.tablet.util.NotificationRingBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class SyncConnectionStatus {
    CONNECTED,
    CONNECTING,
    UNCONFIGURED_PLACEHOLDER,
    ERROR
}

/**
 * Legacy session model retained for compatibility with [TabletTimetableOverlay].
 */
data class ActiveTimetableSession(
    val requestId: String,
    val decision: HighlightDecision,
    val durationMs: Long = AppConfigConstants.TIMETABLE_DISPLAY_DURATION_MS,
    val startedAtElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
)

/**
 * Roadmap Step 3, Module I, V5, V6 & V7 Addendum (Tablet Side):
 * Connects to Firebase Realtime Database and attaches read listeners for:
 * 1. `/notifications` (bounded 5-item ring buffer + V7 OTP full-screen takeover trigger)
 * 2. `/tasks` (read-only Task Viewer — tablet never writes to `/tasks`)
 * 3. `/config/clockTheme` (live Clock Theme selection from the phone, cached locally)
 * 4. `/commands/timetable` (routed through V7 [OverlayManager] with 15s staleness guard)
 * 5. `/pc/stats`, `/config/pcMonitor/enabled`, `/config/pcMonitor/fullScreen` (V6 PC Monitor)
 * 6. `/status/batteryAutomation` (V6 Smart Battery Protection indicator glyph state)
 * 7. `/phone/battery` (V7 Phone battery in top status strip + 20% Low / 10% Critical overlays)
 * 8. `/phone/dnd` & `/config/musicMode` (V7 DND Music & Clock Mode)
 * 9. `/config/nightSchedule` & `/config/pixelShift` (V7 Night Schedule & Pixel Shift)
 */
class FirebaseNotificationSyncManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val ringBuffer = NotificationRingBuffer(maxCapacity = 5)
    val overlayManager = OverlayManager()

    private val _notifications = MutableStateFlow<List<NotificationPayload>>(emptyList())
    val notifications: StateFlow<List<NotificationPayload>> = _notifications.asStateFlow()

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    private val _clockTheme = MutableStateFlow(
        ClockThemeCatalog.findById(
            prefs.getString(KEY_CACHED_CLOCK_THEME_ID, AppConfigConstants.DEFAULT_CLOCK_THEME_ID)
        )
    )
    val clockTheme: StateFlow<ClockTheme> = _clockTheme.asStateFlow()

    val activeOverlay: StateFlow<ActiveTabletOverlay> = overlayManager.activeOverlay

    private val _pcStats = MutableStateFlow<PcStatsPayload?>(null)
    val pcStats: StateFlow<PcStatsPayload?> = _pcStats.asStateFlow()

    private val _pcMonitorEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CACHED_PC_MONITOR_ENABLED, true)
    )
    val pcMonitorEnabled: StateFlow<Boolean> = _pcMonitorEnabled.asStateFlow()

    private val _pcFullScreenEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CACHED_PC_FULLSCREEN_ENABLED, false)
    )
    val pcFullScreenEnabled: StateFlow<Boolean> = _pcFullScreenEnabled.asStateFlow()

    private val _batteryAutomationStatus = MutableStateFlow(BatteryAutomationStatus())
    val batteryAutomationStatus: StateFlow<BatteryAutomationStatus> = _batteryAutomationStatus.asStateFlow()

    private val _phoneBattery = MutableStateFlow<PhoneBatteryPayload?>(null)
    val phoneBattery: StateFlow<PhoneBatteryPayload?> = _phoneBattery.asStateFlow()

    private val _phoneDnd = MutableStateFlow(PhoneDndPayload())
    val phoneDnd: StateFlow<PhoneDndPayload> = _phoneDnd.asStateFlow()

    private val _musicModeEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CACHED_MUSIC_MODE_ENABLED, AppConfigConstants.MUSIC_MODE_DEFAULT_ENABLED)
    )
    val musicModeEnabled: StateFlow<Boolean> = _musicModeEnabled.asStateFlow()

    // V8 Section 6: Voice override for Music & Clock Mode (/config/musicModeOverride)
    private val _musicModeOverride = MutableStateFlow(MusicModeOverridePayload())
    val musicModeOverride: StateFlow<MusicModeOverridePayload> = _musicModeOverride.asStateFlow()

    private val _nightScheduleConfig = MutableStateFlow(loadCachedNightSchedule())
    val nightScheduleConfig: StateFlow<NightScheduleConfig> = _nightScheduleConfig.asStateFlow()

    private val _pixelShiftEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CACHED_PIXEL_SHIFT_ENABLED, AppConfigConstants.PIXEL_SHIFT_DEFAULT_ENABLED)
    )
    val pixelShiftEnabled: StateFlow<Boolean> = _pixelShiftEnabled.asStateFlow()

    private val _otpOverlayEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CACHED_OTP_OVERLAY_ENABLED, true)
    )
    val otpOverlayEnabled: StateFlow<Boolean> = _otpOverlayEnabled.asStateFlow()

    private val _serverTimeOffset = MutableStateFlow(0L)
    val serverTimeOffset: StateFlow<Long> = _serverTimeOffset.asStateFlow()

    private val _connectionStatus = MutableStateFlow(SyncConnectionStatus.CONNECTING)
    val connectionStatus: StateFlow<SyncConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow("Initializing Firebase listener...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    @Volatile
    private var serverTimeOffsetMs: Long = 0L

    @Volatile
    private var lastProcessedTimetableRequestId: String =
        prefs.getString(KEY_LAST_TIMETABLE_REQUEST_ID, "").orEmpty()

    @Volatile
    private var lastProcessedOtpNotificationId: String =
        prefs.getString(KEY_LAST_OTP_NOTIFICATION_ID, "").orEmpty()

    private var activeRef: DatabaseReference? = null
    private var activeListener: ValueEventListener? = null
    private var tasksRef: DatabaseReference? = null
    private var tasksListener: ValueEventListener? = null
    private var themeRef: DatabaseReference? = null
    private var themeListener: ValueEventListener? = null
    private var timetableCommandRef: DatabaseReference? = null
    private var timetableCommandListener: ValueEventListener? = null
    private var pcStatsRef: DatabaseReference? = null
    private var pcStatsListener: ValueEventListener? = null
    private var pcMonitorEnabledRef: DatabaseReference? = null
    private var pcMonitorEnabledListener: ValueEventListener? = null
    private var pcFullScreenRef: DatabaseReference? = null
    private var pcFullScreenListener: ValueEventListener? = null
    private var batteryStatusRef: DatabaseReference? = null
    private var batteryStatusListener: ValueEventListener? = null
    private var phoneBatteryRef: DatabaseReference? = null
    private var phoneBatteryListener: ValueEventListener? = null
    private var phoneDndRef: DatabaseReference? = null
    private var phoneDndListener: ValueEventListener? = null
    private var musicModeRef: DatabaseReference? = null
    private var musicModeListener: ValueEventListener? = null
    private var musicModeOverrideRef: DatabaseReference? = null
    private var musicModeOverrideListener: ValueEventListener? = null
    private var nightScheduleRef: DatabaseReference? = null
    private var nightScheduleListener: ValueEventListener? = null
    private var pixelShiftRef: DatabaseReference? = null
    private var pixelShiftListener: ValueEventListener? = null
    private var otpConfigRef: DatabaseReference? = null
    private var otpConfigListener: ValueEventListener? = null
    private var serverOffsetRef: DatabaseReference? = null
    private var serverOffsetListener: ValueEventListener? = null
    private var connectedInfoRef: DatabaseReference? = null
    private var connectedInfoListener: ValueEventListener? = null

    init {
        connectToDatabase()
    }

    private fun loadCachedNightSchedule(): NightScheduleConfig {
        return NightScheduleConfig(
            enabled = prefs.getBoolean(
                KEY_CACHED_NIGHT_ENABLED,
                AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_ENABLED
            ),
            start = prefs.getString(
                KEY_CACHED_NIGHT_START,
                AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_START
            ) ?: AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_START,
            end = prefs.getString(
                KEY_CACHED_NIGHT_END,
                AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_END
            ) ?: AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_END,
            brightnessPct = prefs.getInt(
                KEY_CACHED_NIGHT_BRIGHTNESS,
                AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_BRIGHTNESS_PCT
            )
        )
    }

    private fun saveCachedNightSchedule(cfg: NightScheduleConfig) {
        prefs.edit()
            .putBoolean(KEY_CACHED_NIGHT_ENABLED, cfg.enabled)
            .putString(KEY_CACHED_NIGHT_START, cfg.start)
            .putString(KEY_CACHED_NIGHT_END, cfg.end)
            .putInt(KEY_CACHED_NIGHT_BRIGHTNESS, cfg.clampedBrightnessPct)
            .apply()
    }

    fun getDatabaseUrl(): String =
        prefs.getString(KEY_CUSTOM_DB_URL, "")?.trim().orEmpty()

    fun getNodePath(): String =
        prefs.getString(KEY_NODE_PATH, DEFAULT_NODE_PATH)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_NODE_PATH

    fun updateConfiguration(customDatabaseUrl: String, nodePath: String) {
        val cleanedUrl = customDatabaseUrl.trim()
        val cleanedNode = nodePath.trim().ifEmpty { DEFAULT_NODE_PATH }
        prefs.edit()
            .putString(KEY_CUSTOM_DB_URL, cleanedUrl)
            .putString(KEY_NODE_PATH, cleanedNode)
            .apply()
        connectToDatabase()
    }

    fun selectClockThemeLocally(theme: ClockTheme) {
        _clockTheme.value = theme
        prefs.edit().putString(KEY_CACHED_CLOCK_THEME_ID, theme.id).apply()
    }

    fun tickOverlaysAndBatteryState(deltaMs: Long) {
        if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(appContext)) return
        overlayManager.onTick(deltaMs)
        val estimatedServerNow = System.currentTimeMillis() + serverTimeOffsetMs
        overlayManager.updatePhoneBatteryState(_phoneBattery.value, estimatedServerNow)
    }

    fun connectToDatabase() {
        detachCurrentListener()

        val customUrl = getDatabaseUrl()
        val nodePath = getNodePath()

        try {
            val database = if (customUrl.isNotEmpty()) {
                FirebaseDatabase.getInstance(customUrl)
            } else {
                FirebaseDatabase.getInstance()
            }

            val configuredUrl = runCatching { database.reference.toString() }.getOrDefault("")
            val isPlaceholder = customUrl.isEmpty() &&
                configuredUrl.contains("smart-desk-companion-default-rtdb")

            if (isPlaceholder) {
                _connectionStatus.value = SyncConnectionStatus.UNCONFIGURED_PLACEHOLDER
                _statusMessage.value = "Set your Firebase Database URL in Settings (or replace google-services.json)"
            } else {
                _connectionStatus.value = SyncConnectionStatus.CONNECTING
                _statusMessage.value = "Listening on /$nodePath"
            }

            val infoRef = database.getReference(".info/connected")
            val infoListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = runCatching { snapshot.getValue(Boolean::class.java) }.getOrNull() ?: false
                    if (isPlaceholder) {
                        _connectionStatus.value = SyncConnectionStatus.UNCONFIGURED_PLACEHOLDER
                    } else if (connected) {
                        _connectionStatus.value = SyncConnectionStatus.CONNECTED
                        _statusMessage.value = "Live Sync Active (/$nodePath)"
                    } else {
                        _connectionStatus.value = SyncConnectionStatus.ERROR
                        _statusMessage.value = "Firebase Sync Disconnected (/$nodePath)"
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            infoRef.addValueEventListener(infoListener)
            connectedInfoRef = infoRef
            connectedInfoListener = infoListener

            val offsetRef = database.getReference(".info/serverTimeOffset")
            val offsetListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val offset = runCatching { snapshot.getValue(Long::class.java) }.getOrNull() ?: 0L
                    serverTimeOffsetMs = offset
                    _serverTimeOffset.value = offset
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            offsetRef.addValueEventListener(offsetListener)
            serverOffsetRef = offsetRef
            serverOffsetListener = offsetListener

            // 1. Notifications listener (+ V7 OTP full-screen takeover detection)
            val ref = database.getReference(nodePath)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(appContext)) return
                    val parsed = parseSnapshot(snapshot)
                    val sorted = ringBuffer.replaceAll(parsed)
                    _notifications.value = sorted
                    checkAndTriggerOtpOverlayIfFresh(sorted)
                    if (!isPlaceholder) {
                        _connectionStatus.value = SyncConnectionStatus.CONNECTED
                        _statusMessage.value = "Live Sync Active (/$nodePath)"
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    _connectionStatus.value = SyncConnectionStatus.ERROR
                    _statusMessage.value = "Firebase Error: ${error.message}"
                }
            }
            ref.addValueEventListener(listener)
            activeRef = ref
            activeListener = listener

            // 2. Read-only Tasks listener (/tasks)
            val tRef = database.getReference(AppConfigConstants.FIREBASE_TASKS_NODE)
            val tListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _tasks.value = TaskScheduleLogic.parseTasksSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            tRef.addValueEventListener(tListener)
            tasksRef = tRef
            tasksListener = tListener

            // 3. Read-only Clock Theme listener (/config/clockTheme)
            val cRef = database.getReference(AppConfigConstants.FIREBASE_CLOCK_THEME_PATH)
            val cListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val remoteThemeId = runCatching { snapshot.getValue(String::class.java)?.trim() }.getOrNull()
                    if (!remoteThemeId.isNullOrEmpty()) {
                        val resolved = ClockThemeCatalog.findById(remoteThemeId)
                        _clockTheme.value = resolved
                        prefs.edit().putString(KEY_CACHED_CLOCK_THEME_ID, resolved.id).apply()
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            cRef.addValueEventListener(cListener)
            themeRef = cRef
            themeListener = cListener

            // 4. V5 & V7: Read-only Timetable Command listener (/commands/timetable)
            val cmdRef = database.getReference(AppConfigConstants.FIREBASE_TIMETABLE_COMMAND_PATH)
            val cmdListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(appContext)) return
                    handleTimetableCommandSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            cmdRef.addValueEventListener(cmdListener)
            timetableCommandRef = cmdRef
            timetableCommandListener = cmdListener

            // 5. V6: Read-only PC Stats listener (/pc/stats), (/config/pcMonitor/enabled), (/config/pcMonitor/fullScreen)
            val pRef = database.getReference(AppConfigConstants.FIREBASE_PC_STATS_PATH)
            val pListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(appContext)) return
                    _pcStats.value = PcStatsPayload.fromSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            pRef.addValueEventListener(pListener)
            pcStatsRef = pRef
            pcStatsListener = pListener

            val pmRef = database.getReference(AppConfigConstants.FIREBASE_PC_MONITOR_ENABLED_PATH)
            val pmListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val enabled = runCatching { snapshot.getValue(Boolean::class.java) }.getOrNull() ?: true
                    _pcMonitorEnabled.value = enabled
                    prefs.edit().putBoolean(KEY_CACHED_PC_MONITOR_ENABLED, enabled).apply()
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            pmRef.addValueEventListener(pmListener)
            pcMonitorEnabledRef = pmRef
            pcMonitorEnabledListener = pmListener

            val pfsRef = database.getReference(AppConfigConstants.FIREBASE_PC_FULLSCREEN_PATH)
            val pfsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val fullScreen = runCatching { snapshot.getValue(Boolean::class.java) }.getOrNull() ?: false
                    _pcFullScreenEnabled.value = fullScreen
                    prefs.edit().putBoolean(KEY_CACHED_PC_FULLSCREEN_ENABLED, fullScreen).apply()
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            pfsRef.addValueEventListener(pfsListener)
            pcFullScreenRef = pfsRef
            pcFullScreenListener = pfsListener

            // 6. V6: Read-only Battery Automation Status listener (/status/batteryAutomation)
            val bsRef = database.getReference(AppConfigConstants.FIREBASE_BATTERY_STATUS_PATH)
            val bsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _batteryAutomationStatus.value = BatteryAutomationStatus.fromSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            bsRef.addValueEventListener(bsListener)
            batteryStatusRef = bsRef
            batteryStatusListener = bsListener

            // 7. V7 Section 5: Read-only Phone Battery listener (/phone/battery)
            val pbRef = database.getReference(AppConfigConstants.FIREBASE_PHONE_BATTERY_PATH)
            val pbListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val payload = PhoneBatteryPayload.fromSnapshot(snapshot)
                    _phoneBattery.value = payload
                    val estimatedServerNow = System.currentTimeMillis() + serverTimeOffsetMs
                    overlayManager.updatePhoneBatteryState(payload, estimatedServerNow)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            pbRef.addValueEventListener(pbListener)
            phoneBatteryRef = pbRef
            phoneBatteryListener = pbListener

            // 8. V7 Section 7: Read-only Phone DND (/phone/dnd) & Music Mode config (/config/musicMode)
            val dndRef = database.getReference(AppConfigConstants.FIREBASE_PHONE_DND_PATH)
            val dndListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _phoneDnd.value = PhoneDndPayload.fromSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            dndRef.addValueEventListener(dndListener)
            phoneDndRef = dndRef
            phoneDndListener = dndListener

            val mmRef = database.getReference(AppConfigConstants.FIREBASE_MUSIC_MODE_CONFIG_PATH)
            val mmListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val enabled = MusicModeConfigParser.parseEnabled(snapshot)
                    _musicModeEnabled.value = enabled
                    prefs.edit().putBoolean(KEY_CACHED_MUSIC_MODE_ENABLED, enabled).apply()
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            mmRef.addValueEventListener(mmListener)
            musicModeRef = mmRef
            musicModeListener = mmListener

            // V8 Section 6: Read-only Music & Clock voice override listener (/config/musicModeOverride)
            val mmoRef = database.getReference(AppConfigConstants.FIREBASE_MUSIC_MODE_OVERRIDE_PATH)
            val mmoListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _musicModeOverride.value = MusicModeOverridePayload.fromSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            mmoRef.addValueEventListener(mmoListener)
            musicModeOverrideRef = mmoRef
            musicModeOverrideListener = mmoListener

            // 9. V7 Section 2 & 3: Night Schedule (/config/nightSchedule) & Pixel Shift (/config/pixelShift)
            val nsRef = database.getReference(AppConfigConstants.FIREBASE_NIGHT_SCHEDULE_PATH)
            val nsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val cfg = NightScheduleConfig.fromSnapshot(snapshot)
                    _nightScheduleConfig.value = cfg
                    saveCachedNightSchedule(cfg)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            nsRef.addValueEventListener(nsListener)
            nightScheduleRef = nsRef
            nightScheduleListener = nsListener

            val psRef = database.getReference(AppConfigConstants.FIREBASE_PIXEL_SHIFT_PATH)
            val psListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val enabled = PixelShiftRing.parseEnabled(snapshot)
                    _pixelShiftEnabled.value = enabled
                    prefs.edit().putBoolean(KEY_CACHED_PIXEL_SHIFT_ENABLED, enabled).apply()
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            psRef.addValueEventListener(psListener)
            pixelShiftRef = psRef
            pixelShiftListener = psListener

            // 10. V7 Section 4.5: OTP full-screen alerts master switch (/config/otpAlerts/enabled)
            val otpRef = database.getReference(AppConfigConstants.FIREBASE_OTP_CONFIG_PATH)
            val otpListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val enabled = if (!snapshot.exists()) {
                        true
                    } else {
                        snapshot.child("enabled").getValue(Boolean::class.java) ?: true
                    }
                    _otpOverlayEnabled.value = enabled
                    prefs.edit().putBoolean(KEY_CACHED_OTP_OVERLAY_ENABLED, enabled).apply()
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            otpRef.addValueEventListener(otpListener)
            otpConfigRef = otpRef
            otpConfigListener = otpListener
        } catch (e: Exception) {
            _connectionStatus.value = SyncConnectionStatus.ERROR
            _statusMessage.value = "Invalid Database URL: ${e.localizedMessage ?: "Check settings"}"
        }
    }

    /**
     * V7 Section 4.4 & Section 1:
     * Shows the OTP overlay (Priority 1) when the tablet receives a new `isOtp` item
     * whose `sentAt` is under 15 seconds old.
     */
    private fun checkAndTriggerOtpOverlayIfFresh(items: List<NotificationPayload>) {
        if (!_otpOverlayEnabled.value) return
        val newestOtp = items.firstOrNull { it.isOtp && it.otp.isNotBlank() } ?: return
        if (newestOtp.id.isEmpty() || newestOtp.id == lastProcessedOtpNotificationId) {
            return
        }

        val sentTimestamp = if (newestOtp.sentAt > 0L) newestOtp.sentAt else newestOtp.epochMillis
        val estimatedServerNow = System.currentTimeMillis() + serverTimeOffsetMs
        val ageMs = abs(estimatedServerNow - sentTimestamp)

        // Always record the ID so we never re-trigger the same notification on reconnect
        lastProcessedOtpNotificationId = newestOtp.id
        prefs.edit().putString(KEY_LAST_OTP_NOTIFICATION_ID, newestOtp.id).apply()

        if (sentTimestamp <= 0L || ageMs > AppConfigConstants.COMMAND_STALE_MS) {
            return
        }

        overlayManager.triggerOtpOverlay(
            id = newestOtp.id,
            source = newestOtp.source.ifBlank { newestOtp.title },
            rawCode = newestOtp.otp.ifBlank { newestOtp.message },
            durationMs = AppConfigConstants.OTP_OVERLAY_DURATION_MS
        )
    }

    private fun handleTimetableCommandSnapshot(snapshot: DataSnapshot) {
        if (!snapshot.exists()) return

        val requestId = snapshot.child("requestId").getValue(String::class.java)?.trim().orEmpty()
        if (requestId.isEmpty() || requestId == lastProcessedTimetableRequestId) {
            return
        }

        val requestedAt = snapshot.child("requestedAt").getValue(Long::class.java)
            ?: snapshot.child("clientTimestampMs").getValue(Long::class.java)
            ?: 0L

        val estimatedServerNow = System.currentTimeMillis() + serverTimeOffsetMs
        val ageMs = abs(estimatedServerNow - requestedAt)
        lastProcessedTimetableRequestId = requestId
        prefs.edit().putString(KEY_LAST_TIMETABLE_REQUEST_ID, requestId).apply()

        // V7 Section 1: Ignore any command older than 15 seconds when received
        if (requestedAt <= 0L || ageMs > AppConfigConstants.COMMAND_STALE_MS) {
            return
        }

        val rawMode = snapshot.child("mode").getValue(String::class.java)
        val rawHighlightDay = snapshot.child("highlightDay").getValue(String::class.java)
            ?: snapshot.child("day").getValue(String::class.java)
        val durationSec = snapshot.child("durationSec").getValue(Int::class.java)
            ?: AppConfigConstants.TIMETABLE_DISPLAY_DURATION_SEC

        val parsedMode = TimetableMode.fromWireValue(rawMode)
        val parsedDay = TimetableData.parseDayOfWeek(rawHighlightDay)

        val decision = if (parsedMode == TimetableMode.FULL_WEEK || parsedDay == null) {
            HighlightDecision(mode = TimetableMode.FULL_WEEK, highlightDay = null)
        } else {
            HighlightDecision(mode = parsedMode, highlightDay = parsedDay)
        }

        overlayManager.triggerTimetableOverlay(
            requestId = requestId,
            decision = decision,
            durationMs = (durationSec.coerceIn(5, 60)) * 1000L
        )
    }

    private fun parseSnapshot(snapshot: DataSnapshot): List<NotificationPayload> {
        if (!snapshot.exists()) return emptyList()

        if (snapshot.hasChild("packageName") && snapshot.hasChild("title")) {
            val single = snapshotToPayload(snapshot, fallbackIndex = 0L)
            return if (single != null) listOf(single) else emptyList()
        }

        val list = mutableListOf<NotificationPayload>()
        var idx = 0L
        for (child in snapshot.children) {
            idx++
            val item = snapshotToPayload(child, fallbackIndex = idx)
            if (item != null) {
                list.add(item)
            }
        }
        return list
    }

    private fun snapshotToPayload(snapshot: DataSnapshot, fallbackIndex: Long): NotificationPayload? {
        val packageName = snapshot.child("packageName").getValue(String::class.java).orEmpty()
        if (packageName.lowercase().contains("spotify")) return null

        val isOtp = snapshot.child("isOtp").getValue(Boolean::class.java) ?: false
        val source = snapshot.child("source").getValue(String::class.java).orEmpty()
        val otp = snapshot.child("otp").getValue(String::class.java).orEmpty()
        val sentAt = snapshot.child("sentAt").getValue(Long::class.java) ?: 0L

        val rawTitle = snapshot.child("title").getValue(String::class.java).orEmpty()
        val rawMessage = snapshot.child("message").getValue(String::class.java).orEmpty()
        val timestamp = snapshot.child("timestamp").getValue(String::class.java).orEmpty()
        val epochMillis = snapshot.child("epochMillis").getValue(Long::class.java)
            ?: sentAt.takeIf { it > 0L }
            ?: fallbackIndex

        val title = if (isOtp) source.ifBlank { rawTitle } else rawTitle
        val message = if (isOtp) otp.ifBlank { rawMessage } else rawMessage

        if (title.isBlank() && message.isBlank()) return null

        val key = snapshot.key ?: "${packageName}_${title}_${timestamp}_$fallbackIndex"
        return NotificationPayload(
            id = key,
            packageName = packageName.ifBlank { "android.system" },
            title = title.ifBlank { "Notification" },
            message = message,
            timestamp = timestamp,
            epochMillis = epochMillis,
            isOtp = isOtp,
            source = source.ifBlank { title },
            otp = otp.ifBlank { if (isOtp) message else "" },
            sentAt = sentAt
        )
    }

    fun dismissAllNotifications() {
        _notifications.value = ringBuffer.clear()
        runCatching {
            activeRef?.removeValue()
        }
    }

    fun pushTestNotification() {
        val formattedTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val nowMs = System.currentTimeMillis()
        val gmailSample = NotificationPayload(
            id = "test_gmail_$nowMs",
            packageName = "com.google.android.gm",
            title = "Meeting Update",
            message = "Project Sync",
            timestamp = formattedTime,
            epochMillis = nowMs - 1000L
        )
        val whatsappSample = NotificationPayload(
            id = "test_wa_$nowMs",
            packageName = "com.whatsapp",
            title = "Sarah",
            message = "See you at lunch!",
            timestamp = formattedTime,
            epochMillis = nowMs
        )
        ringBuffer.push(gmailSample)
        _notifications.value = ringBuffer.push(whatsappSample)

        runCatching {
            val ref = activeRef ?: return@runCatching
            for (sample in listOf(gmailSample, whatsappSample)) {
                val payloadMap = mapOf(
                    "packageName" to sample.packageName,
                    "title" to sample.title,
                    "message" to sample.message,
                    "timestamp" to sample.timestamp,
                    "epochMillis" to sample.epochMillis,
                    "sentAt" to ServerValue.TIMESTAMP
                )
                ref.child(sample.id).setValue(payloadMap)
            }
        }
    }

    fun release() {
        detachCurrentListener()
    }

    private fun detachCurrentListener() {
        activeListener?.let { l -> runCatching { activeRef?.removeEventListener(l) } }
        activeRef = null
        activeListener = null

        tasksListener?.let { l -> runCatching { tasksRef?.removeEventListener(l) } }
        tasksRef = null
        tasksListener = null

        themeListener?.let { l -> runCatching { themeRef?.removeEventListener(l) } }
        themeRef = null
        themeListener = null

        timetableCommandListener?.let { l -> runCatching { timetableCommandRef?.removeEventListener(l) } }
        timetableCommandRef = null
        timetableCommandListener = null

        pcStatsListener?.let { l -> runCatching { pcStatsRef?.removeEventListener(l) } }
        pcStatsRef = null
        pcStatsListener = null

        pcMonitorEnabledListener?.let { l -> runCatching { pcMonitorEnabledRef?.removeEventListener(l) } }
        pcMonitorEnabledRef = null
        pcMonitorEnabledListener = null

        pcFullScreenListener?.let { l -> runCatching { pcFullScreenRef?.removeEventListener(l) } }
        pcFullScreenRef = null
        pcFullScreenListener = null

        batteryStatusListener?.let { l -> runCatching { batteryStatusRef?.removeEventListener(l) } }
        batteryStatusRef = null
        batteryStatusListener = null

        phoneBatteryListener?.let { l -> runCatching { phoneBatteryRef?.removeEventListener(l) } }
        phoneBatteryRef = null
        phoneBatteryListener = null

        phoneDndListener?.let { l -> runCatching { phoneDndRef?.removeEventListener(l) } }
        phoneDndRef = null
        phoneDndListener = null

        musicModeListener?.let { l -> runCatching { musicModeRef?.removeEventListener(l) } }
        musicModeRef = null
        musicModeListener = null

        musicModeOverrideListener?.let { l -> runCatching { musicModeOverrideRef?.removeEventListener(l) } }
        musicModeOverrideRef = null
        musicModeOverrideListener = null

        nightScheduleListener?.let { l -> runCatching { nightScheduleRef?.removeEventListener(l) } }
        nightScheduleRef = null
        nightScheduleListener = null

        pixelShiftListener?.let { l -> runCatching { pixelShiftRef?.removeEventListener(l) } }
        pixelShiftRef = null
        pixelShiftListener = null

        otpConfigListener?.let { l -> runCatching { otpConfigRef?.removeEventListener(l) } }
        otpConfigRef = null
        otpConfigListener = null

        serverOffsetListener?.let { l -> runCatching { serverOffsetRef?.removeEventListener(l) } }
        serverOffsetRef = null
        serverOffsetListener = null

        connectedInfoListener?.let { l -> runCatching { connectedInfoRef?.removeEventListener(l) } }
        connectedInfoRef = null
        connectedInfoListener = null
    }

    companion object {
        const val PREFS_NAME = "smart_desk_tablet_sync_prefs"
        const val KEY_CUSTOM_DB_URL = "custom_firebase_db_url"
        const val KEY_NODE_PATH = "firebase_node_path"
        private const val KEY_CACHED_CLOCK_THEME_ID = "cached_clock_theme_id"
        private const val KEY_CACHED_PC_MONITOR_ENABLED = "cached_pc_monitor_enabled"
        private const val KEY_CACHED_PC_FULLSCREEN_ENABLED = "cached_pc_fullscreen_enabled"
        private const val KEY_CACHED_MUSIC_MODE_ENABLED = "cached_music_mode_enabled"
        private const val KEY_CACHED_NIGHT_ENABLED = "cached_night_schedule_enabled"
        private const val KEY_CACHED_NIGHT_START = "cached_night_schedule_start"
        private const val KEY_CACHED_NIGHT_END = "cached_night_schedule_end"
        private const val KEY_CACHED_NIGHT_BRIGHTNESS = "cached_night_schedule_brightness"
        private const val KEY_CACHED_PIXEL_SHIFT_ENABLED = "cached_pixel_shift_enabled"
        private const val KEY_CACHED_OTP_OVERLAY_ENABLED = "cached_otp_overlay_enabled"
        private const val KEY_LAST_TIMETABLE_REQUEST_ID = "last_timetable_request_id"
        private const val KEY_LAST_OTP_NOTIFICATION_ID = "last_otp_notification_id"
        const val DEFAULT_NODE_PATH = AppConfigConstants.FIREBASE_NOTIFICATIONS_NODE
    }
}
