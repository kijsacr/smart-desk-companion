package com.smartdesk.companion.phone.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.smartdesk.companion.phone.config.PhoneSyncPreferences
import com.smartdesk.companion.phone.widget.PcMonitorWidgetProvider
import com.smartdesk.companion.phone.widget.TimetableWidgetProvider
import com.smartdesk.companion.shared.AlexaStatusPayload
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.BatteryAutomationConfig
import com.smartdesk.companion.shared.BatteryAutomationStatus
import com.smartdesk.companion.shared.BatteryProtectionStateMachine
import com.smartdesk.companion.shared.ClockThemeCatalog
import com.smartdesk.companion.shared.MusicModeConfigParser
import com.smartdesk.companion.shared.NightScheduleConfig
import com.smartdesk.companion.shared.PcStatsPayload
import com.smartdesk.companion.shared.PhoneDndPayload
import com.smartdesk.companion.shared.PixelShiftRing
import com.smartdesk.companion.shared.ProximityConfig
import com.smartdesk.companion.shared.TabletScreenStatus
import com.smartdesk.companion.shared.TaskItem
import com.smartdesk.companion.shared.TaskScheduleLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Module G, Module I, V6 Addendum & V7 Addendum (Phone Side):
 * Manages real-time Firebase sync for:
 * - `/tasks` (creating tasks, toggling completion, streaming pending/completed lists)
 * - `/config/clockTheme` (selecting the tablet's Float clock colour theme from the phone)
 * - `/pc/stats` & `/config/pcMonitor/enabled` (V6 PC Resource Monitor toggle & status line)
 * - `/config/batteryAutomation` & `/status/batteryAutomation` (V6 Smart Battery Protection)
 * - `/config/nightSchedule`, `/config/pixelShift`, `/config/otpAlerts`, `/config/proximity`,
 *   `/status/tabletScreen`, `/config/musicMode`, and `/phone/dnd` (V7 Addendum)
 */
class PhoneTaskAndThemeManager(
    private val syncPreferences: PhoneSyncPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    private val _selectedThemeId = MutableStateFlow(syncPreferences.getSelectedClockThemeId())
    val selectedThemeId: StateFlow<String> = _selectedThemeId.asStateFlow()

    private val _pcMonitorEnabled = MutableStateFlow(true)
    val pcMonitorEnabled: StateFlow<Boolean> = _pcMonitorEnabled.asStateFlow()

    private val _pcFullScreenEnabled = MutableStateFlow(syncPreferences.isPcFullScreenEnabled())
    val pcFullScreenEnabled: StateFlow<Boolean> = _pcFullScreenEnabled.asStateFlow()

    private val _pcStats = MutableStateFlow<PcStatsPayload?>(null)
    val pcStats: StateFlow<PcStatsPayload?> = _pcStats.asStateFlow()

    private val _batteryConfig = MutableStateFlow(BatteryAutomationConfig())
    val batteryConfig: StateFlow<BatteryAutomationConfig> = _batteryConfig.asStateFlow()

    private val _batteryStatus = MutableStateFlow(BatteryAutomationStatus())
    val batteryStatus: StateFlow<BatteryAutomationStatus> = _batteryStatus.asStateFlow()

    private val _serverTimeOffsetMs = MutableStateFlow(0L)
    val serverTimeOffsetMs: StateFlow<Long> = _serverTimeOffsetMs.asStateFlow()

    // V7 StateFlows
    private val _nightScheduleConfig = MutableStateFlow(NightScheduleConfig())
    val nightScheduleConfig: StateFlow<NightScheduleConfig> = _nightScheduleConfig.asStateFlow()

    private val _pixelShiftEnabled = MutableStateFlow(AppConfigConstants.PIXEL_SHIFT_DEFAULT_ENABLED)
    val pixelShiftEnabled: StateFlow<Boolean> = _pixelShiftEnabled.asStateFlow()

    private val _otpAlertsEnabled = MutableStateFlow(syncPreferences.isOtpAlertsEnabled())
    val otpAlertsEnabled: StateFlow<Boolean> = _otpAlertsEnabled.asStateFlow()

    private val _proximityConfig = MutableStateFlow(ProximityConfig())
    val proximityConfig: StateFlow<ProximityConfig> = _proximityConfig.asStateFlow()

    private val _tabletScreenStatus = MutableStateFlow(TabletScreenStatus())
    val tabletScreenStatus: StateFlow<TabletScreenStatus> = _tabletScreenStatus.asStateFlow()

    private val _musicModeEnabled = MutableStateFlow(AppConfigConstants.MUSIC_MODE_DEFAULT_ENABLED)
    val musicModeEnabled: StateFlow<Boolean> = _musicModeEnabled.asStateFlow()

    private val _phoneDnd = MutableStateFlow(PhoneDndPayload())
    val phoneDnd: StateFlow<PhoneDndPayload> = _phoneDnd.asStateFlow()

    // V8 Alexa Voice Control StateFlows
    private val _alexaEnabled = MutableStateFlow(AppConfigConstants.ALEXA_DEFAULT_ENABLED)
    val alexaEnabled: StateFlow<Boolean> = _alexaEnabled.asStateFlow()

    private val _alexaStatus = MutableStateFlow(AlexaStatusPayload())
    val alexaStatus: StateFlow<AlexaStatusPayload> = _alexaStatus.asStateFlow()

    private var tasksRef: DatabaseReference? = null
    private var tasksListener: ValueEventListener? = null
    private var themeRef: DatabaseReference? = null
    private var themeListener: ValueEventListener? = null
    private var pcEnabledRef: DatabaseReference? = null
    private var pcEnabledListener: ValueEventListener? = null
    private var pcFullScreenRef: DatabaseReference? = null
    private var pcFullScreenListener: ValueEventListener? = null
    private var pcStatsRef: DatabaseReference? = null
    private var pcStatsListener: ValueEventListener? = null
    private var batteryConfigRef: DatabaseReference? = null
    private var batteryConfigListener: ValueEventListener? = null
    private var batteryStatusRef: DatabaseReference? = null
    private var batteryStatusListener: ValueEventListener? = null
    private var serverOffsetRef: DatabaseReference? = null
    private var serverOffsetListener: ValueEventListener? = null

    // V7 Firebase references & listeners
    private var nightScheduleRef: DatabaseReference? = null
    private var nightScheduleListener: ValueEventListener? = null
    private var pixelShiftRef: DatabaseReference? = null
    private var pixelShiftListener: ValueEventListener? = null
    private var otpAlertsRef: DatabaseReference? = null
    private var otpAlertsListener: ValueEventListener? = null
    private var proximityConfigRef: DatabaseReference? = null
    private var proximityConfigListener: ValueEventListener? = null
    private var tabletScreenStatusRef: DatabaseReference? = null
    private var tabletScreenStatusListener: ValueEventListener? = null
    private var musicModeRef: DatabaseReference? = null
    private var musicModeListener: ValueEventListener? = null
    private var phoneDndRef: DatabaseReference? = null
    private var phoneDndListener: ValueEventListener? = null

    // V8 Alexa Voice Control references & listeners
    private var alexaEnabledRef: DatabaseReference? = null
    private var alexaEnabledListener: ValueEventListener? = null
    private var alexaStatusRef: DatabaseReference? = null
    private var alexaStatusListener: ValueEventListener? = null

    init {
        reconnect()
    }

    fun reconnect() {
        detachListeners()
        try {
            val offRef = syncPreferences.resolveServerTimeOffsetReference()
            val offListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _serverTimeOffsetMs.value = snapshot.getValue(Long::class.java) ?: 0L
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            offRef.addValueEventListener(offListener)
            serverOffsetRef = offRef
            serverOffsetListener = offListener

            val tRef = syncPreferences.resolveTasksReference()
            val tListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val parsed = TaskScheduleLogic.parseTasksSnapshot(snapshot)
                    _tasks.value = parsed
                    purgeExpiredCompletedTasksIfNeeded(tRef, parsed)
                }

                override fun onCancelled(error: DatabaseError) {
                    PhoneSyncPreferences.updateStatus("Tasks sync error: ${error.message}")
                }
            }
            tRef.addValueEventListener(tListener)
            tasksRef = tRef
            tasksListener = tListener

            val cRef = syncPreferences.resolveClockThemeReference()
            val cListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val remoteId = snapshot.getValue(String::class.java)?.trim()
                    if (!remoteId.isNullOrEmpty()) {
                        val validId = ClockThemeCatalog.findById(remoteId).id
                        _selectedThemeId.value = validId
                        syncPreferences.setSelectedClockThemeId(validId)
                        runCatching {
                            TimetableWidgetProvider.updateAllWidgets(syncPreferences.appContext)
                            PcMonitorWidgetProvider.updateAllWidgets(syncPreferences.appContext)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            cRef.addValueEventListener(cListener)
            themeRef = cRef
            themeListener = cListener

            // V6: PC Monitor enabled switch
            val pmRef = syncPreferences.resolvePcMonitorEnabledReference()
            val pmListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _pcMonitorEnabled.value = snapshot.getValue(Boolean::class.java) ?: true
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            pmRef.addValueEventListener(pmListener)
            pcEnabledRef = pmRef
            pcEnabledListener = pmListener

            // V6: PC Full-screen Monitor toggle switch (/config/pcMonitor/fullScreen)
            val pfsRef = syncPreferences.resolvePcFullScreenReference()
            val pfsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val fullScreen = snapshot.getValue(Boolean::class.java) ?: false
                    _pcFullScreenEnabled.value = fullScreen
                    syncPreferences.setPcFullScreenEnabled(fullScreen)
                    runCatching {
                        PcMonitorWidgetProvider.updateAllWidgets(syncPreferences.appContext)
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            pfsRef.addValueEventListener(pfsListener)
            pcFullScreenRef = pfsRef
            pcFullScreenListener = pfsListener

            // V6: PC Stats live listener (for Phone Settings status line)
            val psRef = syncPreferences.resolvePcStatsReference()
            val psListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _pcStats.value = PcStatsPayload.fromSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            psRef.addValueEventListener(psListener)
            pcStatsRef = psRef
            pcStatsListener = psListener

            // V6: Battery Protection config listener
            val bcRef = syncPreferences.resolveBatteryConfigReference()
            val bcListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _batteryConfig.value = BatteryAutomationConfig.fromSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            bcRef.addValueEventListener(bcListener)
            batteryConfigRef = bcRef
            batteryConfigListener = bcListener

            // V6: Battery Protection live status listener
            val bsRef = syncPreferences.resolveBatteryStatusReference()
            val bsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _batteryStatus.value = BatteryAutomationStatus.fromSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            bsRef.addValueEventListener(bsListener)
            batteryStatusRef = bsRef
            batteryStatusListener = bsListener

            // V7 Section 2: Night Schedule config listener
            val nsRef = syncPreferences.resolveNightScheduleReference()
            val nsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        _nightScheduleConfig.value = NightScheduleConfig.fromSnapshot(snapshot)
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            nsRef.addValueEventListener(nsListener)
            nightScheduleRef = nsRef
            nightScheduleListener = nsListener

            // V7 Section 3: Pixel Shift config listener
            val pxRef = syncPreferences.resolvePixelShiftReference()
            val pxListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        _pixelShiftEnabled.value = PixelShiftRing.parseEnabled(snapshot)
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            pxRef.addValueEventListener(pxListener)
            pixelShiftRef = pxRef
            pixelShiftListener = pxListener

            // V7 Section 4: OTP Alerts config listener
            val otpRef = syncPreferences.resolveOtpAlertsReference()
            val otpListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val enabled = snapshot.child("enabled").getValue(Boolean::class.java)
                            ?: snapshot.getValue(Boolean::class.java)
                            ?: true
                        _otpAlertsEnabled.value = enabled
                        syncPreferences.setOtpAlertsEnabled(enabled)
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            otpRef.addValueEventListener(otpListener)
            otpAlertsRef = otpRef
            otpAlertsListener = otpListener

            // V7 Section 6: Proximity config listener
            val proxRef = syncPreferences.resolveProximityConfigReference()
            val proxListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        _proximityConfig.value = ProximityConfig.fromSnapshot(snapshot)
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            proxRef.addValueEventListener(proxListener)
            proximityConfigRef = proxRef
            proximityConfigListener = proxListener

            // V7 Section 6: Tablet Screen Status listener (`status/tabletScreen`)
            val tsRef = syncPreferences.resolveTabletScreenStatusReference()
            val tsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _tabletScreenStatus.value = TabletScreenStatus.fromSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            tsRef.addValueEventListener(tsListener)
            tabletScreenStatusRef = tsRef
            tabletScreenStatusListener = tsListener

            // V7 Section 7: Music Mode config listener (`config/musicMode`)
            val mmRef = syncPreferences.resolveMusicModeReference()
            val mmListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        _musicModeEnabled.value = MusicModeConfigParser.parseEnabled(snapshot)
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            mmRef.addValueEventListener(mmListener)
            musicModeRef = mmRef
            musicModeListener = mmListener

            // V7 Section 7: Phone DND status listener (`phone/dnd`)
            val dndRef = syncPreferences.resolvePhoneDndReference()
            val dndListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _phoneDnd.value = PhoneDndPayload.fromSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            dndRef.addValueEventListener(dndListener)
            phoneDndRef = dndRef
            phoneDndListener = dndListener

            // V8 Section 7: Alexa Voice Control enabled listener (`config/alexa/enabled`)
            val aeRef = syncPreferences.resolveAlexaEnabledReference()
            val aeListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        _alexaEnabled.value = AppConfigConstants.ALEXA_DEFAULT_ENABLED
                    } else {
                        _alexaEnabled.value = snapshot.getValue(Boolean::class.java)
                            ?: snapshot.child("enabled").getValue(Boolean::class.java)
                            ?: AppConfigConstants.ALEXA_DEFAULT_ENABLED
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            aeRef.addValueEventListener(aeListener)
            alexaEnabledRef = aeRef
            alexaEnabledListener = aeListener

            // V8 Section 7: Alexa live status listener (`status/alexa`)
            val asRef = syncPreferences.resolveAlexaStatusReference()
            val asListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _alexaStatus.value = AlexaStatusPayload.fromSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            asRef.addValueEventListener(asListener)
            alexaStatusRef = asRef
            alexaStatusListener = asListener
        } catch (e: Exception) {
            PhoneSyncPreferences.updateStatus(
                "Firebase connection error: ${e.localizedMessage ?: "Check Database URL"}"
            )
        }
    }

    fun createTask(
        title: String,
        subjectId: String,
        deadlineIso: String,
        onComplete: (Boolean) -> Unit
    ) {
        val cleanedTitle = title.trim()
        if (cleanedTitle.isEmpty() || deadlineIso.isBlank()) {
            onComplete(false)
            return
        }

        try {
            val ref = tasksRef ?: syncPreferences.resolveTasksReference()
            val newId = ref.push().key ?: "task_${System.currentTimeMillis()}"
            val nowMs = System.currentTimeMillis()

            val payload = mapOf(
                "id" to newId,
                "title" to cleanedTitle,
                "subjectId" to subjectId,
                "deadline" to deadlineIso,
                "completed" to false,
                "createdAt" to nowMs,
                "completedAt" to null
            )

            val optimisticItem = TaskItem(
                id = newId,
                title = cleanedTitle,
                subjectId = subjectId,
                deadline = deadlineIso,
                completed = false,
                createdAt = nowMs,
                completedAt = null
            )
            _tasks.value = _tasks.value.filterNot { it.id == newId } + optimisticItem

            ref.child(newId).setValue(payload)
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus("Task added: $cleanedTitle")
                }
                .addOnFailureListener { err ->
                    PhoneSyncPreferences.updateStatus(
                        "Failed to sync task: ${err.localizedMessage}"
                    )
                }

            onComplete(true)
        } catch (e: Exception) {
            PhoneSyncPreferences.updateStatus("Task error: ${e.localizedMessage}")
            onComplete(false)
        }
    }

    fun setTaskCompleted(taskId: String, completed: Boolean) {
        val completedAtVal: Long? = if (completed) System.currentTimeMillis() else null

        _tasks.value = _tasks.value.map { existing ->
            if (existing.id == taskId) {
                existing.copy(completed = completed, completedAt = completedAtVal)
            } else {
                existing
            }
        }

        runCatching {
            val ref = (tasksRef ?: syncPreferences.resolveTasksReference()).child(taskId)
            val updates = mapOf<String, Any?>(
                "completed" to completed,
                "completedAt" to completedAtVal
            )
            ref.updateChildren(updates)
        }
    }

    fun selectClockTheme(themeId: String) {
        val validTheme = ClockThemeCatalog.findById(themeId)
        _selectedThemeId.value = validTheme.id
        syncPreferences.setSelectedClockThemeId(validTheme.id)
        runCatching {
            TimetableWidgetProvider.updateAllWidgets(syncPreferences.appContext)
            PcMonitorWidgetProvider.updateAllWidgets(syncPreferences.appContext)
        }

        runCatching {
            val ref = themeRef ?: syncPreferences.resolveClockThemeReference()
            ref.setValue(validTheme.id)
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus("Clock theme set to ${validTheme.name}")
                }
        }
    }

    fun setPcMonitorEnabled(enabled: Boolean) {
        _pcMonitorEnabled.value = enabled
        runCatching {
            val ref = pcEnabledRef ?: syncPreferences.resolvePcMonitorEnabledReference()
            ref.setValue(enabled)
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus(
                        if (enabled) "PC stats pill enabled on tablet" else "PC stats pill hidden on tablet"
                    )
                }
        }
    }

    fun setPcFullScreenEnabled(enabled: Boolean) {
        _pcFullScreenEnabled.value = enabled
        syncPreferences.setPcFullScreenEnabled(enabled)
        runCatching {
            PcMonitorWidgetProvider.updateAllWidgets(syncPreferences.appContext)
        }
        runCatching {
            val ref = pcFullScreenRef ?: syncPreferences.resolvePcFullScreenReference()
            ref.setValue(enabled)
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus(
                        if (enabled) {
                            "Full-screen PC Monitor enabled on tablet"
                        } else {
                            "Returned tablet to clock dashboard"
                        }
                    )
                }
        }
    }

    fun saveBatteryAutomationConfig(
        enabled: Boolean,
        rawCutAt: Int,
        rawResumeAt: Int,
        offUrl: String,
        onUrl: String,
        method: String
    ) {
        val (validCut, validResume) = BatteryProtectionStateMachine.sanitizeThresholds(
            rawCutAt,
            rawResumeAt
        )
        val normalizedMethod = if (method.trim().equals("POST", ignoreCase = true)) "POST" else "GET"
        val newCfg = BatteryAutomationConfig(
            enabled = enabled,
            cutAt = validCut,
            resumeAt = validResume,
            offUrl = offUrl.trim(),
            onUrl = onUrl.trim(),
            method = normalizedMethod
        )
        _batteryConfig.value = newCfg

        runCatching {
            val ref = batteryConfigRef ?: syncPreferences.resolveBatteryConfigReference()
            val payload = mapOf(
                "enabled" to newCfg.enabled,
                "cutAt" to newCfg.cutAt,
                "resumeAt" to newCfg.resumeAt,
                "offUrl" to newCfg.offUrl,
                "onUrl" to newCfg.onUrl,
                "method" to newCfg.method
            )
            ref.setValue(payload)
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus(
                        "Saved Battery Protection (${newCfg.resumeAt}% – ${newCfg.cutAt}%)"
                    )
                }
        }
    }

    // V7 Section 2: Save Night Schedule config
    fun saveNightScheduleConfig(
        enabled: Boolean,
        start: String,
        end: String,
        brightnessPct: Int
    ) {
        val clampedPct = brightnessPct.coerceIn(
            AppConfigConstants.NIGHT_SCHEDULE_MIN_BRIGHTNESS_PCT,
            AppConfigConstants.NIGHT_SCHEDULE_MAX_BRIGHTNESS_PCT
        )
        val newCfg = NightScheduleConfig(
            enabled = enabled,
            start = start.trim().ifEmpty { AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_START },
            end = end.trim().ifEmpty { AppConfigConstants.NIGHT_SCHEDULE_DEFAULT_END },
            brightnessPct = clampedPct
        )
        _nightScheduleConfig.value = newCfg

        runCatching {
            val ref = nightScheduleRef ?: syncPreferences.resolveNightScheduleReference()
            val payload = mapOf(
                "enabled" to newCfg.enabled,
                "start" to newCfg.start,
                "end" to newCfg.end,
                "brightnessPct" to newCfg.clampedBrightnessPct
            )
            ref.setValue(payload)
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus(
                        "Saved Night Schedule (${newCfg.start} – ${newCfg.end}, ${newCfg.clampedBrightnessPct}%)"
                    )
                }
        }
    }

    // V7 Section 3: Set Pixel Shift enabled
    fun setPixelShiftEnabled(enabled: Boolean) {
        _pixelShiftEnabled.value = enabled
        runCatching {
            val ref = pixelShiftRef ?: syncPreferences.resolvePixelShiftReference()
            ref.setValue(mapOf("enabled" to enabled))
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus(
                        if (enabled) "Pixel shift enabled on tablet" else "Pixel shift disabled on tablet"
                    )
                }
        }
    }

    // V7 Section 4: Set OTP Alerts enabled
    fun setOtpAlertsEnabled(enabled: Boolean) {
        _otpAlertsEnabled.value = enabled
        syncPreferences.setOtpAlertsEnabled(enabled)
        runCatching {
            val ref = otpAlertsRef ?: syncPreferences.resolveOtpAlertsReference()
            ref.setValue(mapOf("enabled" to enabled))
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus(
                        if (enabled) "OTP full-screen alerts enabled" else "OTP full-screen alerts disabled"
                    )
                }
        }
    }

    // V7 Section 6: Save Proximity Screen-Off config
    fun saveProximityConfig(
        enabled: Boolean,
        offDelayMin: Int
    ) {
        val clampedMin = offDelayMin.coerceIn(
            AppConfigConstants.PROXIMITY_MIN_OFF_DELAY_MIN,
            AppConfigConstants.PROXIMITY_MAX_OFF_DELAY_MIN
        )
        val newCfg = ProximityConfig(
            enabled = enabled,
            offDelayMin = clampedMin
        )
        _proximityConfig.value = newCfg

        runCatching {
            val ref = proximityConfigRef ?: syncPreferences.resolveProximityConfigReference()
            val payload = mapOf(
                "enabled" to newCfg.enabled,
                "offDelayMin" to newCfg.clampedOffDelayMin
            )
            ref.setValue(payload)
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus(
                        "Saved Proximity Screen-Off (${newCfg.clampedOffDelayMin} min)"
                    )
                }
        }
    }

    // V7 Section 7: Set DND Music & Clock Mode enabled
    fun setMusicModeEnabled(enabled: Boolean) {
        _musicModeEnabled.value = enabled
        runCatching {
            val ref = musicModeRef ?: syncPreferences.resolveMusicModeReference()
            ref.setValue(mapOf("enabled" to enabled))
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus(
                        if (enabled) {
                            "DND Music & Clock mode enabled"
                        } else {
                            "DND Music & Clock mode disabled"
                        }
                    )
                }
        }
    }

    // V8 Section 7: Set Alexa Voice Control enabled (`config/alexa/enabled`)
    fun setAlexaEnabled(enabled: Boolean) {
        _alexaEnabled.value = enabled
        runCatching {
            val ref = alexaEnabledRef ?: syncPreferences.resolveAlexaEnabledReference()
            ref.setValue(enabled)
                .addOnSuccessListener {
                    PhoneSyncPreferences.updateStatus(
                        if (enabled) {
                            "Alexa voice control enabled"
                        } else {
                            "Alexa voice control disabled"
                        }
                    )
                }
        }
    }

    /**
     * Section 2.4:
     * Calls the webhook URL directly from the phone with a 10s timeout so the user can confirm
     * it works before relying on it (or triggers a test phone battery alert if the URL is blank).
     */
    fun testWebhookOrPhoneAlert(
        label: String,
        rawUrl: String,
        method: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val cleaned = rawUrl.trim()
        if (cleaned.isEmpty()) {
            postLocalPhoneBatteryNotification(
                context = syncPreferences.appContext,
                title = "Smart Desk Battery Alert ($label Test)",
                body = if (label.equals("OFF", ignoreCase = true)) {
                    "Tablet reached 80% — unplug charger or use Lenovo Battery Protection."
                } else {
                    "Tablet reached 25% — plug in tablet charger."
                }
            )
            onResult(true, "Test $label phone alert sent (no webhook URL configured)")
            return
        }

        scope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
                    return@withContext false to "Invalid URL (must start with https:// or http://)"
                }
                var conn: HttpURLConnection? = null
                try {
                    val urlObj = URL(cleaned)
                    conn = (urlObj.openConnection() as HttpURLConnection).apply {
                        requestMethod = if (method.equals("POST", ignoreCase = true)) "POST" else "GET"
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        instanceFollowRedirects = true
                        useCaches = false
                        if (requestMethod == "POST") {
                            doOutput = true
                            setFixedLengthStreamingMode(0)
                        }
                    }
                    if (conn.requestMethod == "POST") {
                        conn.outputStream.use { /* empty body */ }
                    }
                    val code = conn.responseCode
                    if (code in 200..299) {
                        true to "Test $label succeeded (HTTP $code)"
                    } else {
                        false to "Test $label failed: HTTP $code"
                    }
                } catch (e: Exception) {
                    false to "Test $label error: ${e.javaClass.simpleName}"
                } finally {
                    runCatching { conn?.disconnect() }
                }
            }
            onResult(ok, msg)
        }
    }

    private fun purgeExpiredCompletedTasksIfNeeded(
        ref: DatabaseReference,
        items: List<TaskItem>
    ) {
        val cutoffMillis = System.currentTimeMillis() -
            (AppConfigConstants.COMPLETED_TASK_AUTO_PURGE_DAYS * 24L * 60L * 60L * 1000L)
        for (item in items) {
            val doneAt = item.completedAt
            if (item.completed && doneAt != null && doneAt > 0L && doneAt < cutoffMillis) {
                runCatching { ref.child(item.id).removeValue() }
            }
        }
    }

    fun release() {
        detachListeners()
        scope.cancel()
    }

    private fun detachListeners() {
        tasksListener?.let { l -> runCatching { tasksRef?.removeEventListener(l) } }
        tasksRef = null
        tasksListener = null

        themeListener?.let { l -> runCatching { themeRef?.removeEventListener(l) } }
        themeRef = null
        themeListener = null

        pcEnabledListener?.let { l -> runCatching { pcEnabledRef?.removeEventListener(l) } }
        pcEnabledRef = null
        pcEnabledListener = null

        pcFullScreenListener?.let { l -> runCatching { pcFullScreenRef?.removeEventListener(l) } }
        pcFullScreenRef = null
        pcFullScreenListener = null

        pcStatsListener?.let { l -> runCatching { pcStatsRef?.removeEventListener(l) } }
        pcStatsRef = null
        pcStatsListener = null

        batteryConfigListener?.let { l -> runCatching { batteryConfigRef?.removeEventListener(l) } }
        batteryConfigRef = null
        batteryConfigListener = null

        batteryStatusListener?.let { l -> runCatching { batteryStatusRef?.removeEventListener(l) } }
        batteryStatusRef = null
        batteryStatusListener = null

        serverOffsetListener?.let { l -> runCatching { serverOffsetRef?.removeEventListener(l) } }
        serverOffsetRef = null
        serverOffsetListener = null

        nightScheduleListener?.let { l -> runCatching { nightScheduleRef?.removeEventListener(l) } }
        nightScheduleRef = null
        nightScheduleListener = null

        pixelShiftListener?.let { l -> runCatching { pixelShiftRef?.removeEventListener(l) } }
        pixelShiftRef = null
        pixelShiftListener = null

        otpAlertsListener?.let { l -> runCatching { otpAlertsRef?.removeEventListener(l) } }
        otpAlertsRef = null
        otpAlertsListener = null

        proximityConfigListener?.let { l -> runCatching { proximityConfigRef?.removeEventListener(l) } }
        proximityConfigRef = null
        proximityConfigListener = null

        tabletScreenStatusListener?.let { l -> runCatching { tabletScreenStatusRef?.removeEventListener(l) } }
        tabletScreenStatusRef = null
        tabletScreenStatusListener = null

        musicModeListener?.let { l -> runCatching { musicModeRef?.removeEventListener(l) } }
        musicModeRef = null
        musicModeListener = null

        phoneDndListener?.let { l -> runCatching { phoneDndRef?.removeEventListener(l) } }
        phoneDndRef = null
        phoneDndListener = null

        alexaEnabledListener?.let { l -> runCatching { alexaEnabledRef?.removeEventListener(l) } }
        alexaEnabledRef = null
        alexaEnabledListener = null

        alexaStatusListener?.let { l -> runCatching { alexaStatusRef?.removeEventListener(l) } }
        alexaStatusRef = null
        alexaStatusListener = null
    }

    companion object {
        private const val BATTERY_ALERT_CHANNEL_ID = "smart_desk_phone_battery_alerts"
        private const val BATTERY_ALERT_NOTIFICATION_ID = 7105

        fun postLocalPhoneBatteryNotification(
            context: Context,
            title: String,
            body: String
        ) {
            runCatching {
                val appContext = context.applicationContext
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return@runCatching

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        BATTERY_ALERT_CHANNEL_ID,
                        "Tablet Battery Protection Alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Alerts when your Lenovo tablet reaches 80% or drops to 25%"
                    }
                    nm.createNotificationChannel(channel)
                }

                val notif = NotificationCompat.Builder(appContext, BATTERY_ALERT_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()

                nm.notify(BATTERY_ALERT_NOTIFICATION_ID, notif)
            }
        }
    }
}
