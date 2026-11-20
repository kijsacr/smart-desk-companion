package com.smartdesk.companion.phone.service

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
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.smartdesk.companion.phone.MainActivity
import com.smartdesk.companion.phone.R
import com.smartdesk.companion.phone.widget.PcMonitorWidgetProvider
import com.smartdesk.companion.phone.widget.PerformanceModeWidgetProvider
import com.smartdesk.companion.phone.widget.TimetableWidgetProvider
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.PerfFirebase
import com.smartdesk.companion.shared.PerformanceControlMessage
import com.smartdesk.companion.shared.PerformanceLinkStatus
import com.smartdesk.companion.shared.PerformanceSession
import com.smartdesk.companion.shared.PerformanceStateSnapshot
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import com.smartdesk.companion.shared.PhonePerformanceTransportController
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
 * V9 Sections 2, 3, 4 & 11:
 * Phone-side persistent Foreground Service for Performance Mode.
 *
 * - Keeps the BLE GATT connection, Firebase listeners, and live session/song/scroll/zoom/pan
 *   state alive even when the user switches to the Wing q app or locks/unlocks the screen.
 * - Zero vibration / zero sound on its notification channel.
 * - Restores automatically from saved state (`START_STICKY`) if the process is killed.
 */
class PhonePerformanceForegroundService : Service() {

    private var transportController: PhonePerformanceTransportController? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var linkStatusJob: Job? = null

    private val db by lazy { PerfFirebase.database() }
    private val sessionsRef by lazy { db.getReference(AppConfigConstants.FIREBASE_PERF_SESSIONS_PATH) }
    private val stateRef by lazy { db.getReference(AppConfigConstants.FIREBASE_PERF_STATE_PATH) }
    private val tabletCacheRef by lazy { db.getReference(AppConfigConstants.FIREBASE_PERF_TABLET_CACHE_PATH) }

    private val sessionsListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val loaded = mutableListOf<PerformanceSession>()
            for (child in snapshot.children) {
                PerformanceSession.fromSnapshot(child)?.let { sess ->
                    loaded.add(sess)
                    PerformanceStorageAndCacheManager.saveSessionToLocalCache(applicationContext, sess)
                }
            }
            _sessions.value = PerformanceStorageAndCacheManager.sortSessionsUpcomingFirst(loaded)
        }

        override fun onCancelled(error: DatabaseError) {}
    }

    private val remoteStateListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            if (!snapshot.exists()) return
            val remote = PerformanceStateSnapshot.fromSnapshot(snapshot)
            val current = _stateSnapshot.value
            // Accept remote state if we just started or if remote has a newer epoch/seq
            if (current.epoch == 0L || remote.epoch > current.epoch ||
                (remote.epoch == current.epoch && remote.seq > current.seq)
            ) {
                _stateSnapshot.value = remote
                PerformanceStorageAndCacheManager.saveLocalStateSnapshot(applicationContext, remote)
                PerformanceModeWidgetProvider.updateAllWidgets(applicationContext)
                if (!remote.enabled) {
                    stopSelf()
                }
            }
        }

        override fun onCancelled(error: DatabaseError) {}
    }

    private val tabletCacheListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val readyMap = HashMap<String, Boolean>()
            for (child in snapshot.children) {
                val sId = child.key.orEmpty()
                val ready = child.child("ready").getValue(Boolean::class.java) ?: false
                if (sId.isNotBlank()) readyMap[sId] = ready
            }
            _tabletCacheReadyBySession.value = readyMap
        }

        override fun onCancelled(error: DatabaseError) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureSilentChannel()
        // Restore local state snapshot immediately
        val restored = PerformanceStorageAndCacheManager.loadLocalStateSnapshot(applicationContext)
        _stateSnapshot.value = restored
        if (_sessions.value.isEmpty()) {
            _sessions.value = PerformanceStorageAndCacheManager.loadAllLocallyCachedSessions(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureSilentChannel()
        startAsForegroundSafely()

        // If started after process death with no intent, check persisted flag
        val localSnap = PerformanceStorageAndCacheManager.loadLocalStateSnapshot(applicationContext)
        if (!localSnap.enabled && intent?.action != ACTION_START_PERFORMANCE_MODE) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (transportController == null) {
            val ctrl = PhonePerformanceTransportController(applicationContext)
            transportController = ctrl
            activeTransportController = ctrl
            linkStatusJob?.cancel()
            linkStatusJob = serviceScope.launch {
                ctrl.linkStatus.collect { status ->
                    _linkStatus.value = status
                }
            }
            ctrl.start()
            sessionsRef.addValueEventListener(sessionsListener)
            stateRef.addValueEventListener(remoteStateListener)
            tabletCacheRef.addValueEventListener(tabletCacheListener)
            PerformanceStorageAndCacheManager.retryPendingDeletesOnAppStart(applicationContext)
        }

        PerformanceModeWidgetProvider.updateAllWidgets(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        runCatching { sessionsRef.removeEventListener(sessionsListener) }
        runCatching { stateRef.removeEventListener(remoteStateListener) }
        runCatching { tabletCacheRef.removeEventListener(tabletCacheListener) }
        linkStatusJob?.cancel()
        linkStatusJob = null
        serviceScope.cancel()
        transportController?.stop()
        transportController = null
        activeTransportController = null
        _linkStatus.value = PerformanceLinkStatus()
        PerformanceModeWidgetProvider.updateAllWidgets(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureSilentChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart Desk Performance Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps live Bluetooth & Firebase chord control active during performances"
                setSound(null, null)
                enableVibration(false)
                vibrationPattern = longArrayOf(0L)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val snap = _stateSnapshot.value
        val sessName = _sessions.value.firstOrNull { it.sessionId == snap.sessionId }?.name
        val subtitle = if (!sessName.isNullOrBlank()) {
            "Session: $sessName • Live touchpad active"
        } else {
            "Select a session • Paused notifications, timetable & PC stats"
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            9010,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Performance Mode Active")
            .setContentText(subtitle)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setSilent(true)
            .setVibrate(longArrayOf(0L))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startAsForegroundSafely() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Try CONNECTED_DEVICE | DATA_SYNC first; fall back to DATA_SYNC if BLE runtime permission not yet granted
            val startedConnected = runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                true
            }.getOrDefault(false)

            if (!startedConnected) {
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

    fun refreshNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        private const val CHANNEL_ID = "smart_desk_perf_mode_silent_v9"
        private const val NOTIFICATION_ID = 9091
        const val ACTION_START_PERFORMANCE_MODE = "com.smartdesk.companion.phone.action.START_PERF_MODE"

        @Volatile
        private var instance: PhonePerformanceForegroundService? = null

        @Volatile
        private var activeTransportController: PhonePerformanceTransportController? = null

        private val _stateSnapshot = MutableStateFlow(PerformanceStateSnapshot())
        val stateSnapshot: StateFlow<PerformanceStateSnapshot> = _stateSnapshot.asStateFlow()

        private val _sessions = MutableStateFlow<List<PerformanceSession>>(emptyList())
        val sessions: StateFlow<List<PerformanceSession>> = _sessions.asStateFlow()

        private val _tabletCacheReadyBySession = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        val tabletCacheReadyBySession: StateFlow<Map<String, Boolean>> = _tabletCacheReadyBySession.asStateFlow()

        private val _linkStatus = MutableStateFlow(PerformanceLinkStatus())
        val linkStatus: StateFlow<PerformanceLinkStatus> = _linkStatus.asStateFlow()

        fun initializeFromDiskIfNeeded(context: Context) {
            val appCtx = context.applicationContext
            val snap = PerformanceStorageAndCacheManager.loadLocalStateSnapshot(appCtx)
            if (_stateSnapshot.value.epoch == 0L && snap.epoch > 0L) {
                _stateSnapshot.value = snap
            } else if (snap.enabled != _stateSnapshot.value.enabled) {
                _stateSnapshot.value = _stateSnapshot.value.copy(enabled = snap.enabled)
            }
            if (_sessions.value.isEmpty()) {
                _sessions.value = PerformanceStorageAndCacheManager.loadAllLocallyCachedSessions(appCtx)
            }
            if (snap.enabled && instance == null) {
                startServiceCompat(appCtx)
            }
        }

        private fun startServiceCompat(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, PhonePerformanceForegroundService::class.java).apply {
                action = ACTION_START_PERFORMANCE_MODE
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            }
        }

        /**
         * V9 Section 2: Widget pressed while mode is OFF -> start Performance Mode!
         * Opens the Select or create session screen on the phone, and sets tablet to
         * "Please select session" (`enabled = true`, `sessionId = ""`).
         */
        fun startPerformanceMode(context: Context) {
            val appCtx = context.applicationContext
            val prev = PerformanceStorageAndCacheManager.loadLocalStateSnapshot(appCtx)
            val newSnap = PerformanceStateSnapshot(
                enabled = true,
                sessionId = "",
                songId = "",
                sectionIndex = 0,
                scrollY = 0f,
                zoom = 1f,
                panX = 0f,
                panLocked = false,
                darkTheme = prev.darkTheme,
                epoch = System.currentTimeMillis(),
                seq = 1L,
                updatedAt = System.currentTimeMillis()
            )
            _stateSnapshot.value = newSnap
            PerformanceStorageAndCacheManager.saveLocalStateSnapshot(appCtx, newSnap)
            startServiceCompat(appCtx)

            val ctrl = activeTransportController
            if (ctrl != null) {
                ctrl.sendDiscreteCommand(PerformanceControlMessage.TYPE_MODE, newSnap)
            } else {
                PerfFirebase.database()
                    .getReference(AppConfigConstants.FIREBASE_PERF_STATE_PATH)
                    .setValue(newSnap.toMap())
            }
            PerformanceModeWidgetProvider.updateAllWidgets(appCtx)
            instance?.refreshNotification()
        }

        /**
         * V9 Section 2: Confirmed "End Performance Mode?" from the widget confirm dialog.
         * Ends Performance Mode on both devices, resumes paused features, and stops the foreground service.
         */
        fun endPerformanceMode(context: Context) {
            val appCtx = context.applicationContext
            val current = _stateSnapshot.value
            val endedSnap = current.copy(
                enabled = false,
                sessionId = "",
                songId = "",
                sectionIndex = 0,
                scrollY = 0f,
                zoom = 1f,
                panX = 0f,
                panLocked = false,
                updatedAt = System.currentTimeMillis()
            )
            _stateSnapshot.value = endedSnap
            PerformanceStorageAndCacheManager.saveLocalStateSnapshot(appCtx, endedSnap)

            val ctrl = activeTransportController
            if (ctrl != null) {
                ctrl.sendDiscreteCommand(PerformanceControlMessage.TYPE_MODE, endedSnap)
            } else {
                PerfFirebase.database()
                    .getReference(AppConfigConstants.FIREBASE_PERF_STATE_PATH)
                    .setValue(endedSnap.toMap())
                PerfFirebase.database()
                    .getReference(AppConfigConstants.FIREBASE_PERF_LIVE_PATH)
                    .setValue(
                        PerformanceControlMessage(
                            epoch = System.currentTimeMillis(),
                            seq = endedSnap.seq + 1,
                            type = PerformanceControlMessage.TYPE_MODE,
                            modeEnabled = false,
                            sessionId = "",
                            songId = "",
                            sectionIndex = 0,
                            scrollY = 0f,
                            zoom = 1f,
                            panX = 0f,
                            panLocked = false,
                            darkTheme = endedSnap.darkTheme,
                            requiresAck = true
                        ).toFirebaseMap()
                    )
            }

            runCatching {
                appCtx.stopService(Intent(appCtx, PhonePerformanceForegroundService::class.java))
            }

            // V9 Section 4: Resume paused features & refresh widgets
            PerformanceModeWidgetProvider.updateAllWidgets(appCtx)
            TimetableWidgetProvider.updateAllWidgets(appCtx)
            PcMonitorWidgetProvider.updateAllWidgets(appCtx)
        }

        /**
         * V9 Section 2 & 5: Locks both devices into the selected [session] and its first song.
         */
        fun selectSession(context: Context, session: PerformanceSession) {
            val appCtx = context.applicationContext
            val firstSong = session.orderedSongs.firstOrNull()

            val current = _stateSnapshot.value
            val nextSnap = current.copy(
                enabled = true,
                sessionId = session.sessionId,
                songId = firstSong?.songId.orEmpty(),
                sectionIndex = 0,
                scrollY = 0f,
                zoom = 1f,
                panX = 0f,
                panLocked = false,
                updatedAt = System.currentTimeMillis()
            )
            _stateSnapshot.value = nextSnap
            sendDiscreteState(appCtx, PerformanceControlMessage.TYPE_SONG, nextSnap)
            instance?.refreshNotification()
        }

        fun updateDiscreteState(
            context: Context,
            type: String,
            transform: (PerformanceStateSnapshot) -> PerformanceStateSnapshot
        ) {
            val appCtx = context.applicationContext
            val nextSnap = transform(_stateSnapshot.value).copy(updatedAt = System.currentTimeMillis())
            _stateSnapshot.value = nextSnap
            sendDiscreteState(appCtx, type, nextSnap)
        }

        fun updateContinuousState(
            context: Context,
            type: String,
            transform: (PerformanceStateSnapshot) -> PerformanceStateSnapshot
        ) {
            val appCtx = context.applicationContext
            val nextSnap = transform(_stateSnapshot.value).copy(updatedAt = System.currentTimeMillis())
            _stateSnapshot.value = nextSnap
            val ctrl = activeTransportController
            if (ctrl != null) {
                ctrl.sendContinuousUpdate(type, nextSnap)
            } else {
                PerformanceStorageAndCacheManager.saveLocalStateSnapshot(appCtx, nextSnap)
                PerfFirebase.database()
                    .getReference(AppConfigConstants.FIREBASE_PERF_STATE_PATH)
                    .setValue(nextSnap.toMap())
            }
        }

        fun refreshLocalSessionInMemory(session: PerformanceSession) {
            val currentList = _sessions.value.toMutableList()
            val idx = currentList.indexOfFirst { it.sessionId == session.sessionId }
            if (idx >= 0) {
                currentList[idx] = session
            } else {
                currentList.add(session)
            }
            _sessions.value = PerformanceStorageAndCacheManager.sortSessionsUpcomingFirst(currentList)
        }

        fun removeLocalSessionInMemory(sessionId: String) {
            _sessions.value = _sessions.value.filterNot { it.sessionId == sessionId }
        }

        private fun sendDiscreteState(
            context: Context,
            type: String,
            snap: PerformanceStateSnapshot
        ) {
            val ctrl = activeTransportController
            if (ctrl != null) {
                ctrl.sendDiscreteCommand(type, snap)
            } else {
                PerformanceStorageAndCacheManager.saveLocalStateSnapshot(context, snap)
                PerfFirebase.database()
                    .getReference(AppConfigConstants.FIREBASE_PERF_STATE_PATH)
                    .setValue(snap.toMap())
            }
        }
    }
}
