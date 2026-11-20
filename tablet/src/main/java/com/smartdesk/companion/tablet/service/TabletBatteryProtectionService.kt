package com.smartdesk.companion.tablet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.BatteryAutomationConfig
import com.smartdesk.companion.shared.BatteryAutomationStatus
import com.smartdesk.companion.shared.BatteryProtectionStateMachine
import com.smartdesk.companion.shared.BatteryWebhookAction
import com.smartdesk.companion.shared.ProximityConfig
import com.smartdesk.companion.shared.TabletScreenStatus
import com.smartdesk.companion.tablet.MainActivity
import com.smartdesk.companion.tablet.receiver.TabletDeviceAdminReceiver
import com.smartdesk.companion.tablet.sync.FirebaseNotificationSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V6 Section 2.2 & V7 Section 6:
 * Foreground service running on the tablet that:
 * 1. Monitors `ACTION_BATTERY_CHANGED` and `/config/batteryAutomation`, keeping the tablet's
 *    lithium battery between 25% and 80%.
 * 2. Implements V7 Section 6 **Proximity Screen-Off** by combining validated network loss
 *    (`ConnectivityManager` default network callback) and `phone/presence/lastSeen` staleness
 *    (`> 2 min`), turning the screen off after `offDelayMin` via Device Admin `lockNow()`
 *    (Method A) or clearing `FLAG_KEEP_SCREEN_ON` (Method B), and waking the screen immediately
 *    when the phone returns.
 */
class TabletBatteryProtectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var prefs: android.content.SharedPreferences
    private var configRef: DatabaseReference? = null
    private var configListener: ValueEventListener? = null
    private var statusRef: DatabaseReference? = null
    private var notificationsRef: DatabaseReference? = null

    // V7 Section 6: Proximity Firebase refs & listeners
    private var proximityConfigRef: DatabaseReference? = null
    private var proximityConfigListener: ValueEventListener? = null
    private var phonePresenceRef: DatabaseReference? = null
    private var phonePresenceListener: ValueEventListener? = null
    private var serverOffsetRef: DatabaseReference? = null
    private var serverOffsetListener: ValueEventListener? = null
    private var tabletScreenStatusRef: DatabaseReference? = null

    private var currentConfig = BatteryAutomationConfig()
    private var currentBatteryLevel: Int = -1
    private var currentIsCharging: Boolean = false

    private var activeWebhookJob: Job? = null
    private var verificationJob: Job? = null
    private var proximityTickerJob: Job? = null

    // V7 Section 6: Proximity runtime state
    private var proximityConfig = ProximityConfig()
    private var phoneLastSeenMs: Long = 0L
    private var serverTimeOffsetMs: Long = 0L
    private var hasValidatedNetwork: Boolean = true
    private var awaySinceElapsedMs: Long? = null
    private var hasTriggeredScreenOffForCurrentAway: Boolean = false
    private var lastPublishedScreenState: String = ""

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered: Boolean = false
    private var awayPartialWakeLock: PowerManager.WakeLock? = null
    private var awayWifiLock: WifiManager.WifiLock? = null

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshValidatedNetworkState()
            evaluateProximityState()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val validated =
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasValidatedNetwork != validated) {
                hasValidatedNetwork = validated
                evaluateProximityState()
            }
        }

        override fun onLost(network: Network) {
            refreshValidatedNetworkState()
            evaluateProximityState()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val (level, charging) = parseBatteryIntent(intent)
            val changed = (level != currentBatteryLevel) || (charging != currentIsCharging)
            currentBatteryLevel = level
            currentIsCharging = charging
            if (changed) {
                onBatteryOrConfigUpdated()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadCachedProximityConfig()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        // Read initial sticky battery broadcast
        val sticky = runCatching {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        if (sticky != null) {
            val (level, charging) = parseBatteryIntent(sticky)
            currentBatteryLevel = level
            currentIsCharging = charging
        }

        registerProximityNetworkCallback()
        attachFirebaseListeners()
        startProximityEvaluationLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        if (intent?.action == ACTION_RECONNECT_FIREBASE) {
            attachFirebaseListeners()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(batteryReceiver) }
        unregisterProximityNetworkCallback()
        releaseAwayLocks()
        detachFirebaseListeners()
        serviceScope.cancel()
    }

    private fun loadCachedProximityConfig() {
        val enabled = prefs.getBoolean(
            KEY_PROXIMITY_ENABLED,
            AppConfigConstants.PROXIMITY_DEFAULT_ENABLED
        )
        val delayMin = prefs.getInt(
            KEY_PROXIMITY_OFF_DELAY_MIN,
            AppConfigConstants.PROXIMITY_DEFAULT_OFF_DELAY_MIN
        )
        proximityConfig = ProximityConfig(
            enabled = enabled,
            offDelayMin = delayMin
        )
    }

    private fun saveCachedProximityConfig(cfg: ProximityConfig) {
        prefs.edit()
            .putBoolean(KEY_PROXIMITY_ENABLED, cfg.enabled)
            .putInt(KEY_PROXIMITY_OFF_DELAY_MIN, cfg.clampedOffDelayMin)
            .apply()
    }

    private fun registerProximityNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching
            connectivityManager = cm
            refreshValidatedNetworkState()
            cm.registerDefaultNetworkCallback(defaultNetworkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterProximityNetworkCallback() {
        if (networkCallbackRegistered) {
            runCatching {
                connectivityManager?.unregisterNetworkCallback(defaultNetworkCallback)
            }
            networkCallbackRegistered = false
        }
    }

    private fun refreshValidatedNetworkState() {
        runCatching {
            val cm = connectivityManager
                ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            if (cm == null) {
                hasValidatedNetwork = false
                return@runCatching
            }
            val activeNet = cm.activeNetwork
            val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
            hasValidatedNetwork = caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.onFailure {
            hasValidatedNetwork = false
        }
    }

    private fun startProximityEvaluationLoop() {
        proximityTickerJob?.cancel()
        proximityTickerJob = serviceScope.launch {
            while (isActive) {
                refreshValidatedNetworkState()
                evaluateProximityState()
                delay(3_000L)
            }
        }
    }

    /**
     * V7 Section 6.2 – 6.4:
     * Evaluates whether the phone is away (network lost OR `phone/presence/lastSeen` older than 2 min),
     * manages partial wake + Wi-Fi locks while away, locks/turns off the screen after `offDelayMin`,
     * and wakes the screen immediately when the phone returns.
     */
    private fun evaluateProximityState() {
        if (!proximityConfig.enabled) {
            awaySinceElapsedMs = null
            if (_isScreenOffByProximity.value || hasTriggeredScreenOffForCurrentAway) {
                _isScreenOffByProximity.value = false
                hasTriggeredScreenOffForCurrentAway = false
                wakeTabletScreenNow()
            }
            releaseAwayLocks()
            publishTabletScreenStatusIfNeeded(TabletScreenStatus.STATE_ON)
            return
        }

        val nowWallMs = System.currentTimeMillis()
        val estimatedServerNowMs = nowWallMs + serverTimeOffsetMs

        val isHeartbeatStale = phoneLastSeenMs > 0L &&
            (estimatedServerNowMs - phoneLastSeenMs) > AppConfigConstants.PHONE_PRESENCE_STALE_MS

        val isAway = !hasValidatedNetwork || isHeartbeatStale

        if (isAway) {
            acquireAwayLocksIfNeeded()
            val awayStart = awaySinceElapsedMs ?: nowWallMs.also { awaySinceElapsedMs = it }
            val awayElapsedMs = (nowWallMs - awayStart).coerceAtLeast(0L)
            val offThresholdMs = proximityConfig.clampedOffDelayMin * 60_000L

            if (awayElapsedMs >= offThresholdMs) {
                _isScreenOffByProximity.value = true
                if (!hasTriggeredScreenOffForCurrentAway) {
                    hasTriggeredScreenOffForCurrentAway = true
                    turnTabletScreenOffNow()
                }
                publishTabletScreenStatusIfNeeded(TabletScreenStatus.STATE_OFF)
            } else {
                publishTabletScreenStatusIfNeeded(TabletScreenStatus.STATE_WAITING)
            }
        } else {
            val wasOffOrWaiting = awaySinceElapsedMs != null ||
                _isScreenOffByProximity.value ||
                hasTriggeredScreenOffForCurrentAway
            awaySinceElapsedMs = null
            _isScreenOffByProximity.value = false

            if (wasOffOrWaiting) {
                val needWake = hasTriggeredScreenOffForCurrentAway || !isScreenInteractive()
                hasTriggeredScreenOffForCurrentAway = false
                if (needWake) {
                    wakeTabletScreenNow()
                }
            }
            releaseAwayLocks()
            publishTabletScreenStatusIfNeeded(TabletScreenStatus.STATE_ON)
        }
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    /**
     * V7 Section 6.3:
     * Method A (Recommended): `DevicePolicyManager.lockNow()` if Device Admin is active.
     * Method B (Fallback): `_isScreenOffByProximity` is observed by `MainActivity`, which clears
     * `FLAG_KEEP_SCREEN_ON` and dims window brightness to `0.0f`.
     */
    private fun turnTabletScreenOffNow() {
        runCatching {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = TabletDeviceAdminReceiver.getComponentName(this)
            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
            }
        }
    }

    /**
     * V7 Section 6.4:
     * When connectivity returns and `phone/presence/lastSeen` is fresh, wake the screen immediately
     * via `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` and bring `MainActivity` to the front.
     */
    private fun wakeTabletScreenNow() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "SmartDeskCompanion:ProximityReturnWakeLock"
                )
                wl.acquire(5_000L)
            }

            val wakeIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_PROXIMITY_WAKE, true)
            }
            startActivity(wakeIntent)
        }
    }

    /**
     * V7 Section 6.4:
     * While away, the tablet service holds a partial wake lock and Wi-Fi lock so return detection
     * works while the tablet is idle.
     */
    private fun acquireAwayLocksIfNeeded() {
        runCatching {
            if (awayPartialWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                awayPartialWakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SmartDeskCompanion:ProximityAwayWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (awayPartialWakeLock?.isHeld == false) {
                awayPartialWakeLock?.acquire(60L * 60L * 1000L) // 1h safety cap, re-acquired while away
            }

            if (awayWifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                awayWifiLock = wm?.createWifiLock(
                    mode,
                    "SmartDeskCompanion:ProximityAwayWifiLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (awayWifiLock?.isHeld == false) {
                awayWifiLock?.acquire()
            }
        }
    }

    private fun releaseAwayLocks() {
        runCatching {
            if (awayPartialWakeLock?.isHeld == true) {
                awayPartialWakeLock?.release()
            }
        }
        runCatching {
            if (awayWifiLock?.isHeld == true) {
                awayWifiLock?.release()
            }
        }
    }

    private fun publishTabletScreenStatusIfNeeded(newState: String) {
        if (newState == lastPublishedScreenState) return
        lastPublishedScreenState = newState
        runCatching {
            val ref = tabletScreenStatusRef ?: return@runCatching
            val payload = mapOf(
                "state" to newState,
                "updatedAt" to ServerValue.TIMESTAMP
            )
            ref.setValue(payload)
        }
    }

    private fun attachFirebaseListeners() {
        detachFirebaseListeners()
        runCatching {
            val syncPrefs = getSharedPreferences(
                FirebaseNotificationSyncManager.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val customUrl = syncPrefs.getString(
                FirebaseNotificationSyncManager.KEY_CUSTOM_DB_URL,
                ""
            )?.trim().orEmpty()
            val notifNode = syncPrefs.getString(
                FirebaseNotificationSyncManager.KEY_NODE_PATH,
                AppConfigConstants.FIREBASE_NOTIFICATIONS_NODE
            )?.trim()?.ifEmpty { AppConfigConstants.FIREBASE_NOTIFICATIONS_NODE }
                ?: AppConfigConstants.FIREBASE_NOTIFICATIONS_NODE

            val database = if (customUrl.isNotEmpty()) {
                FirebaseDatabase.getInstance(customUrl)
            } else {
                FirebaseDatabase.getInstance()
            }

            statusRef = database.getReference(AppConfigConstants.FIREBASE_BATTERY_STATUS_PATH)
            notificationsRef = database.getReference(notifNode)
            tabletScreenStatusRef = database.getReference(AppConfigConstants.FIREBASE_TABLET_SCREEN_STATUS_PATH)

            val offRef = database.getReference(".info/serverTimeOffset")
            val offListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    serverTimeOffsetMs = snapshot.getValue(Long::class.java) ?: 0L
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            offRef.addValueEventListener(offListener)
            serverOffsetRef = offRef
            serverOffsetListener = offListener

            val proxRef = database.getReference(AppConfigConstants.FIREBASE_PROXIMITY_CONFIG_PATH)
            val proxListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val parsed = ProximityConfig.fromSnapshot(snapshot)
                        proximityConfig = parsed
                        saveCachedProximityConfig(parsed)
                        evaluateProximityState()
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            proxRef.addValueEventListener(proxListener)
            proximityConfigRef = proxRef
            proximityConfigListener = proxListener

            val presRef = database.getReference(AppConfigConstants.FIREBASE_PHONE_PRESENCE_LAST_SEEN_PATH)
            val presListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val ts = snapshot.getValue(Long::class.java) ?: 0L
                    if (ts > 0L) {
                        phoneLastSeenMs = ts
                        evaluateProximityState()
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            presRef.addValueEventListener(presListener)
            phonePresenceRef = presRef
            phonePresenceListener = presListener

            val cRef = database.getReference(AppConfigConstants.FIREBASE_BATTERY_CONFIG_PATH)
            val cListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val newConfig = BatteryAutomationConfig.fromSnapshot(snapshot)
                    val previousConfig = currentConfig
                    currentConfig = newConfig

                    // Failsafe: if URLs were cleared while lastCommand was OFF, restore power
                    val lastCmd = getSavedLastCommand()
                    if (newConfig.enabled &&
                        lastCmd == BatteryAutomationStatus.COMMAND_OFF &&
                        previousConfig.offUrl.isNotBlank() &&
                        newConfig.offUrl.isBlank() &&
                        previousConfig.onUrl.isNotBlank()
                    ) {
                        triggerWebhookWithRetries(
                            action = BatteryWebhookAction.SEND_ON,
                            url = previousConfig.onUrl,
                            method = previousConfig.normalizedMethod,
                            targetLastCommand = BatteryAutomationStatus.COMMAND_ON,
                            targetState = BatteryAutomationStatus.STATE_IDLE
                        )
                        return
                    }

                    onBatteryOrConfigUpdated()
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            cRef.addValueEventListener(cListener)
            configRef = cRef
            configListener = cListener

            lastPublishedScreenState = ""
            evaluateProximityState()
        }
    }

    private fun detachFirebaseListeners() {
        configListener?.let { l -> runCatching { configRef?.removeEventListener(l) } }
        configRef = null
        configListener = null

        proximityConfigListener?.let { l -> runCatching { proximityConfigRef?.removeEventListener(l) } }
        proximityConfigRef = null
        proximityConfigListener = null

        phonePresenceListener?.let { l -> runCatching { phonePresenceRef?.removeEventListener(l) } }
        phonePresenceRef = null
        phonePresenceListener = null

        serverOffsetListener?.let { l -> runCatching { serverOffsetRef?.removeEventListener(l) } }
        serverOffsetRef = null
        serverOffsetListener = null
    }

    private fun onBatteryOrConfigUpdated() {
        val lastCommand = getSavedLastCommand()
        val lastState = getSavedState()

        // If charging state changed in the direction a pending verification was waiting for, cancel verification!
        if (lastCommand == BatteryAutomationStatus.COMMAND_OFF && !currentIsCharging) {
            verificationJob?.cancel()
            if (lastState == BatteryAutomationStatus.STATE_ERROR) {
                saveStateAndPushToFirebase(
                    lastCommand = BatteryAutomationStatus.COMMAND_OFF,
                    state = BatteryAutomationStatus.STATE_HOLDING,
                    error = null,
                    updateTimestamp = false
                )
            }
        } else if (lastCommand == BatteryAutomationStatus.COMMAND_ON && currentIsCharging) {
            verificationJob?.cancel()
            if (lastState == BatteryAutomationStatus.STATE_ERROR) {
                saveStateAndPushToFirebase(
                    lastCommand = BatteryAutomationStatus.COMMAND_ON,
                    state = BatteryAutomationStatus.STATE_CHARGING,
                    error = null,
                    updateTimestamp = false
                )
            }
        }

        val evaluation = BatteryProtectionStateMachine.evaluate(
            config = currentConfig,
            batteryLevel = currentBatteryLevel,
            isCharging = currentIsCharging,
            previousLastCommand = lastCommand,
            previousState = lastState
        )

        when (evaluation.action) {
            BatteryWebhookAction.SEND_OFF -> {
                val url = currentConfig.offUrl.trim()
                if (url.isNotEmpty()) {
                    triggerWebhookWithRetries(
                        action = BatteryWebhookAction.SEND_OFF,
                        url = url,
                        method = currentConfig.normalizedMethod,
                        targetLastCommand = evaluation.nextLastCommand,
                        targetState = evaluation.nextState
                    )
                } else {
                    // Phone Alert / Software Notification Mode (no smart plug URL needed)
                    verificationJob?.cancel()
                    activeWebhookJob?.cancel()
                    saveStateAndPushToFirebase(
                        lastCommand = evaluation.nextLastCommand,
                        state = evaluation.nextState,
                        error = null,
                        updateTimestamp = true
                    )
                    pushBatteryThresholdAlertToHub(
                        title = "Battery Protection (${currentBatteryLevel}%)",
                        message = "Tablet reached ${currentConfig.cutAt}% — unplug charger or keep Lenovo Battery Protection on."
                    )
                }
            }

            BatteryWebhookAction.SEND_ON -> {
                val url = currentConfig.onUrl.trim()
                if (url.isNotEmpty()) {
                    triggerWebhookWithRetries(
                        action = BatteryWebhookAction.SEND_ON,
                        url = url,
                        method = currentConfig.normalizedMethod,
                        targetLastCommand = evaluation.nextLastCommand,
                        targetState = evaluation.nextState
                    )
                } else {
                    // Phone Alert / Software Notification Mode (no smart plug URL needed)
                    verificationJob?.cancel()
                    activeWebhookJob?.cancel()
                    saveStateAndPushToFirebase(
                        lastCommand = evaluation.nextLastCommand,
                        state = evaluation.nextState,
                        error = null,
                        updateTimestamp = true
                    )
                    if (currentConfig.enabled) {
                        pushBatteryThresholdAlertToHub(
                            title = "Battery Low (${currentBatteryLevel}%)",
                            message = "Tablet reached ${currentConfig.resumeAt}% — please plug in tablet charger."
                        )
                    }
                }
            }

            BatteryWebhookAction.NONE -> {
                if (evaluation.nextState != lastState || evaluation.nextLastCommand != lastCommand) {
                    saveStateAndPushToFirebase(
                        lastCommand = evaluation.nextLastCommand,
                        state = evaluation.nextState,
                        error = null,
                        updateTimestamp = false
                    )
                }
            }
        }
    }

    /**
     * Section 2.2:
     * Executes the webhook with HTTP failure backoff (30s, 1m, 2m, 5m, capped at 15m)
     * and schedules 90s charging-state verification (retrying up to 3 times 2m apart, then every 15m).
     */
    private fun triggerWebhookWithRetries(
        action: BatteryWebhookAction,
        url: String,
        method: String,
        targetLastCommand: String,
        targetState: String
    ) {
        activeWebhookJob?.cancel()
        verificationJob?.cancel()

        activeWebhookJob = serviceScope.launch {
            val httpBackoffSec = longArrayOf(30L, 60L, 120L, 300L, 900L)
            var attempt = 0

            while (true) {
                val (ok, errText) = executeWebhookRequest(url, method)
                if (ok) {
                    saveStateAndPushToFirebase(
                        lastCommand = targetLastCommand,
                        state = targetState,
                        error = null,
                        updateTimestamp = true
                    )
                    if (currentConfig.enabled) {
                        scheduleChargingStateVerification(
                            action = action,
                            url = url,
                            method = method,
                            targetLastCommand = targetLastCommand,
                            targetState = targetState
                        )
                    }
                    break
                } else {
                    saveStateAndPushToFirebase(
                        lastCommand = targetLastCommand,
                        state = BatteryAutomationStatus.STATE_ERROR,
                        error = errText ?: "webhook failed",
                        updateTimestamp = true
                    )
                    val waitSec = httpBackoffSec[attempt.coerceAtMost(httpBackoffSec.lastIndex)]
                    attempt++
                    delay(waitSec * 1000L)
                }
            }
        }
    }

    /**
     * Section 2.2:
     * Verify each command: after sending OFF, the charging state should change to discharging
     * within about 90 seconds; after sending ON it should change to charging.
     * If not, retry up to 3 times, 2 minutes apart, then mark the state `error` and keep
     * retrying every 15 minutes.
     */
    private fun scheduleChargingStateVerification(
        action: BatteryWebhookAction,
        url: String,
        method: String,
        targetLastCommand: String,
        targetState: String
    ) {
        verificationJob?.cancel()
        verificationJob = serviceScope.launch {
            delay(90_000L) // Wait 90 seconds for smart plug & charger state to settle

            var retryIndex = 0
            while (currentConfig.enabled) {
                val stateMatchesExpected = when (action) {
                    BatteryWebhookAction.SEND_OFF -> !currentIsCharging
                    BatteryWebhookAction.SEND_ON -> currentIsCharging
                    BatteryWebhookAction.NONE -> true
                }

                if (stateMatchesExpected) {
                    saveStateAndPushToFirebase(
                        lastCommand = targetLastCommand,
                        state = targetState,
                        error = null,
                        updateTimestamp = false
                    )
                    break
                }

                retryIndex++
                if (retryIndex <= 3) {
                    executeWebhookRequest(url, method)
                    delay(120_000L) // 2 minutes apart for first 3 verification retries
                } else {
                    saveStateAndPushToFirebase(
                        lastCommand = targetLastCommand,
                        state = BatteryAutomationStatus.STATE_ERROR,
                        error = if (action == BatteryWebhookAction.SEND_OFF) {
                            "Still charging after OFF webhook"
                        } else {
                            "Not charging after ON webhook"
                        },
                        updateTimestamp = false
                    )
                    executeWebhookRequest(url, method)
                    delay(15L * 60L * 1000L) // Keep retrying every 15 minutes
                }
            }
        }
    }

    private fun pushBatteryThresholdAlertToHub(title: String, message: String) {
        runCatching {
            val ref = notificationsRef ?: return@runCatching
            val nowMs = System.currentTimeMillis()
            val formattedTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(nowMs))
            val id = "battery_guard_$nowMs"
            val payload = mapOf(
                "packageName" to "android.system.battery",
                "title" to title,
                "message" to message,
                "timestamp" to formattedTime,
                "epochMillis" to nowMs
            )
            ref.child(id).setValue(payload)
        }
    }

    private fun getSavedLastCommand(): String =
        prefs.getString(KEY_LAST_COMMAND, BatteryAutomationStatus.COMMAND_UNKNOWN)
            ?: BatteryAutomationStatus.COMMAND_UNKNOWN

    private fun getSavedState(): String =
        prefs.getString(KEY_STATE, BatteryAutomationStatus.STATE_IDLE)
            ?: BatteryAutomationStatus.STATE_IDLE

    private fun saveStateAndPushToFirebase(
        lastCommand: String,
        state: String,
        error: String?,
        updateTimestamp: Boolean
    ) {
        prefs.edit()
            .putString(KEY_LAST_COMMAND, lastCommand)
            .putString(KEY_STATE, state)
            .apply()

        runCatching {
            val sRef = statusRef ?: return@runCatching
            val updates = mutableMapOf<String, Any?>(
                "lastCommand" to lastCommand,
                "state" to state,
                "error" to error,
                "batteryLevel" to currentBatteryLevel,
                "isCharging" to currentIsCharging
            )
            if (updateTimestamp) {
                updates["lastCommandAt"] = ServerValue.TIMESTAMP
            }
            sRef.updateChildren(updates)
        }
    }

    private fun parseBatteryIntent(intent: Intent): Pair<Int, Boolean> {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) {
            ((level.toFloat() / scale.toFloat()) * 100f).toInt().coerceIn(0, 100)
        } else {
            100
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
        return pct to charging
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart Desk Battery & Proximity Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors tablet battery (25%–80%) and phone proximity screen-off"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Smart Desk Companion Service")
            .setContentText("Monitoring battery (25% – 80%) & phone proximity")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .build()
    }

    companion object {
        private const val PREFS_NAME = "smart_desk_battery_protection_prefs"
        private const val KEY_LAST_COMMAND = "last_command"
        private const val KEY_STATE = "automation_state"
        private const val KEY_PROXIMITY_ENABLED = "proximity_enabled"
        private const val KEY_PROXIMITY_OFF_DELAY_MIN = "proximity_off_delay_min"
        private const val CHANNEL_ID = "smart_desk_battery_protection_channel"
        private const val NOTIFICATION_ID = 6021
        const val ACTION_RECONNECT_FIREBASE =
            "com.smartdesk.companion.tablet.action.RECONNECT_BATTERY_FIREBASE"
        const val EXTRA_PROXIMITY_WAKE = "extra_proximity_wake"

        private val _isScreenOffByProximity = MutableStateFlow(false)
        val isScreenOffByProximity: StateFlow<Boolean> = _isScreenOffByProximity.asStateFlow()

        fun start(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, TabletBatteryProtectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun notifyFirebaseConfigChanged(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, TabletBatteryProtectionService::class.java).apply {
                    action = ACTION_RECONNECT_FIREBASE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        /**
         * Executes an HTTP GET or POST (empty body) with a 10s timeout.
         * Never logs the webhook URL (since it may contain a secret key).
         */
        suspend fun executeWebhookRequest(
            rawUrl: String,
            method: String
        ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
            val cleaned = rawUrl.trim()
            if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
                return@withContext false to "Invalid URL scheme"
            }

            var connection: HttpURLConnection? = null
            try {
                val urlObj = URL(cleaned)
                connection = (urlObj.openConnection() as HttpURLConnection).apply {
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
                if (connection.requestMethod == "POST") {
                    connection.outputStream.use { /* empty body */ }
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    true to null
                } else {
                    false to "HTTP $code"
                }
            } catch (e: Exception) {
                false to (e.javaClass.simpleName ?: "Network error")
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }
}
