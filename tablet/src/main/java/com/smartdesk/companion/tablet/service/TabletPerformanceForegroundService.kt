package com.smartdesk.companion.tablet.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.PerfFirebase
import com.smartdesk.companion.shared.PerformanceControlMessage
import com.smartdesk.companion.shared.PerformanceLinkStatus
import com.smartdesk.companion.shared.PerformanceSession
import com.smartdesk.companion.shared.PerformanceStateSnapshot
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import com.smartdesk.companion.shared.TabletPerformanceTransportReceiver
import com.smartdesk.companion.tablet.MainActivity
import com.smartdesk.companion.tablet.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * V9 Section 3, 4, 10 & 11:
 * Tablet Performance Mode Foreground Service.
 *
 * Responsibilities:
 * - START_STICKY foreground service with AlarmManager watchdog to survive process death.
 * - Manages Do Not Disturb (DND): sets INTERRUPTION_FILTER_NONE so other app notifications
 *   never cover the chords; restores previous filter when mode ends (degrades gracefully if no DND permission).
 * - Runs TabletPerformanceTransportReceiver (BLE GATT server + Firebase live/state listener).
 * - Listens for active session updates on Firebase, caches tiles locally, and reports cache ready.
 * - Zero sounds, zero vibrations.
 */
class TabletPerformanceForegroundService : Service() {

    companion object {
        private const val TAG = "TabletPerfService"
        private const val CHANNEL_ID = "tablet_perf_silent_channel_v9"
        private const val NOTIFICATION_ID = 9092
        const val ACTION_START_TABLET_PERF = "com.smartdesk.companion.tablet.action.START_PERF"
        const val ACTION_WATCHDOG_PING = "com.smartdesk.companion.tablet.action.WATCHDOG_PING"

        @Volatile
        var instance: TabletPerformanceForegroundService? = null
            private set

        private val _stateSnapshot = MutableStateFlow(PerformanceStateSnapshot())
        val stateSnapshot: StateFlow<PerformanceStateSnapshot> = _stateSnapshot.asStateFlow()

        private val _activeSession = MutableStateFlow<PerformanceSession?>(null)
        val activeSession: StateFlow<PerformanceSession?> = _activeSession.asStateFlow()

        private val _linkStatus = MutableStateFlow(PerformanceLinkStatus())
        val linkStatus: StateFlow<PerformanceLinkStatus> = _linkStatus.asStateFlow()

        @Volatile
        private var idleListenerAttached = false

        /**
         * Keeps a lightweight listener on `/performance/state` even when Performance Mode is OFF
         * (and the foreground service is stopped), so the tablet wakes into Performance Mode
         * immediately when the phone turns it ON.
         */
        fun ensureIdleStateListener(context: Context) {
            val appCtx = context.applicationContext
            val localSnap = PerformanceStorageAndCacheManager.loadLocalStateSnapshot(appCtx)
            if (localSnap.enabled) {
                _stateSnapshot.value = localSnap
                start(appCtx)
            }
            if (idleListenerAttached) return
            idleListenerAttached = true
            runCatching {
                PerfFirebase.database()
                    .getReference(AppConfigConstants.FIREBASE_PERF_STATE_PATH)
                    .addValueEventListener(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (!snapshot.exists()) return
                            val remote = PerformanceStateSnapshot.fromSnapshot(snapshot)
                            val current = _stateSnapshot.value
                            val isNewer = remote.epoch > current.epoch ||
                                (remote.epoch == current.epoch && remote.seq >= current.seq)
                            if (!isNewer && current.epoch > 0L) return

                            PerformanceStorageAndCacheManager.saveLocalStateSnapshot(appCtx, remote)
                            _stateSnapshot.value = remote
                            if (remote.enabled) {
                                start(appCtx)
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            idleListenerAttached = false
                        }
                    })
            }.onFailure {
                idleListenerAttached = false
            }
        }

        fun start(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, TabletPerformanceForegroundService::class.java).apply {
                action = ACTION_START_TABLET_PERF
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, TabletPerformanceForegroundService::class.java))
            }
        }
    }

    private var transportReceiver: TabletPerformanceTransportReceiver? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var linkStatusJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var previousDndFilter: Int? = null

    private val db by lazy { PerfFirebase.database() }
    private var activeSessionRefListener: ValueEventListener? = null
    private var currentListeningSessionId = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureSilentChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartDesk:TabletPerfWakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // up to 12 hours

        // Restore local state snapshot immediately so tablet never starts blank
        val localSnap = PerformanceStorageAndCacheManager.loadLocalStateSnapshot(applicationContext)
        _stateSnapshot.value = localSnap
        if (localSnap.sessionId.isNotBlank()) {
            val localSess = PerformanceStorageAndCacheManager.loadSessionFromLocalCache(applicationContext, localSnap.sessionId)
            if (localSess != null) {
                _activeSession.value = localSess
            }
        }

        setupDoNotDisturb(enable = true)
        scheduleWatchdog()

        val receiver = TabletPerformanceTransportReceiver(
            context = applicationContext,
            onControlMessageAccepted = { msg ->
                val nextSnap = PerformanceStateSnapshot(
                    enabled = msg.modeEnabled,
                    sessionId = msg.sessionId,
                    songId = msg.songId,
                    sectionIndex = msg.sectionIndex,
                    scrollY = msg.scrollY,
                    zoom = msg.zoom,
                    panX = msg.panX,
                    panLocked = msg.panLocked,
                    darkTheme = msg.darkTheme,
                    epoch = msg.epoch,
                    seq = msg.seq,
                    updatedAt = System.currentTimeMillis()
                )
                _stateSnapshot.value = nextSnap
                if (!msg.modeEnabled) {
                    PerformanceStorageAndCacheManager.setLocalPerformanceModeEnabled(applicationContext, false)
                    setupDoNotDisturb(enable = false)
                    stopSelf()
                } else {
                    checkAndListenForSession(nextSnap.sessionId)
                }
            },
            onSnapshotRestored = { restoredSnap ->
                _stateSnapshot.value = restoredSnap
                if (!restoredSnap.enabled) {
                    PerformanceStorageAndCacheManager.setLocalPerformanceModeEnabled(applicationContext, false)
                    setupDoNotDisturb(enable = false)
                    stopSelf()
                } else {
                    checkAndListenForSession(restoredSnap.sessionId)
                }
            }
        )
        transportReceiver = receiver
        linkStatusJob?.cancel()
        linkStatusJob = serviceScope.launch {
            receiver.linkStatus.collect { status ->
                _linkStatus.value = status
            }
        }
        receiver.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureSilentChannel()
        startAsForegroundSafely()

        val localSnap = PerformanceStorageAndCacheManager.loadLocalStateSnapshot(applicationContext)
        if (!localSnap.enabled && intent?.action != ACTION_START_TABLET_PERF) {
            stopSelf()
            return START_NOT_STICKY
        }

        scheduleWatchdog()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        linkStatusJob?.cancel()
        linkStatusJob = null
        serviceScope.cancel()
        transportReceiver?.stop()
        transportReceiver = null
        _linkStatus.value = PerformanceLinkStatus()
        detachSessionListener()
        setupDoNotDisturb(enable = false)
        cancelWatchdog()

        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkAndListenForSession(sessionId: String) {
        if (sessionId.isBlank()) {
            _activeSession.value = null
            detachSessionListener()
            return
        }
        if (sessionId == currentListeningSessionId && _activeSession.value != null) return

        detachSessionListener()
        currentListeningSessionId = sessionId

        // Load local cache immediately
        val cached = PerformanceStorageAndCacheManager.loadSessionFromLocalCache(applicationContext, sessionId)
        if (cached != null) {
            _activeSession.value = cached
        }

        val ref = db.getReference(AppConfigConstants.FIREBASE_PERF_SESSIONS_PATH).child(sessionId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remoteSess = PerformanceSession.fromSnapshot(snapshot)
                if (remoteSess != null) {
                    _activeSession.value = remoteSess
                    // Download & cache all tiles locally so tablet never goes blank if offline
                    PerformanceStorageAndCacheManager.ensureSessionTilesCached(
                        context = applicationContext,
                        session = remoteSess,
                        reportTabletCacheStatus = true
                    )
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        activeSessionRefListener = listener
    }

    private fun detachSessionListener() {
        if (currentListeningSessionId.isNotBlank() && activeSessionRefListener != null) {
            runCatching {
                db.getReference(AppConfigConstants.FIREBASE_PERF_SESSIONS_PATH)
                    .child(currentListeningSessionId)
                    .removeEventListener(activeSessionRefListener!!)
            }
        }
        activeSessionRefListener = null
        currentListeningSessionId = ""
    }

    private fun setupDoNotDisturb(enable: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!nm.isNotificationPolicyAccessGranted) {
                Log.w(TAG, "Notification policy access not granted; skipping DND toggle")
                return
            }
            try {
                if (enable) {
                    previousDndFilter = nm.currentInterruptionFilter
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    Log.i(TAG, "DND enabled on tablet (INTERRUPTION_FILTER_NONE)")
                } else {
                    val prev = previousDndFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
                    nm.setInterruptionFilter(prev)
                    Log.i(TAG, "DND restored on tablet to filter=$prev")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to toggle DND: ${e.message}")
            }
        }
    }

    private fun scheduleWatchdog() {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = getWatchdogPendingIntent()
        val triggerAt = System.currentTimeMillis() + 60_000L // every 1 min
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelWatchdog() {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = getWatchdogPendingIntent()
        runCatching { am.cancel(pi) }
    }

    private fun getWatchdogPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, TabletBootAndWatchdogReceiver::class.java).apply {
            action = ACTION_WATCHDOG_PING
        }
        return PendingIntent.getBroadcast(
            applicationContext,
            8801,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureSilentChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart Desk Performance Display",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps live chord display active on Lenovo Yoga Tab 11"
                setSound(null, null)
                enableVibration(false)
                vibrationPattern = longArrayOf(0L)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun startAsForegroundSafely() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            8802,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Performance Mode Display Active")
            .setContentText("Displaying live chord charts from phone")
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setSilent(true)
            .setVibrate(longArrayOf(0L))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val started = runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                true
            }.getOrDefault(false)

            if (!started) {
                runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
