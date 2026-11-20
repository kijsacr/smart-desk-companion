package com.smartdesk.companion.tablet

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.MusicModeOverridePayload
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import com.smartdesk.companion.tablet.media.SpotifyMediaControllerManager
import com.smartdesk.companion.tablet.service.TabletBatteryProtectionService
import com.smartdesk.companion.tablet.service.TabletPerformanceForegroundService
import com.smartdesk.companion.tablet.sync.FirebaseNotificationSyncManager
import com.smartdesk.companion.tablet.ui.DeskCompanionScreen
import com.smartdesk.companion.tablet.ui.TabletPerformanceModeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

class MainActivity : ComponentActivity() {

    private lateinit var syncManager: FirebaseNotificationSyncManager
    private lateinit var mediaControllerManager: SpotifyMediaControllerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // V7 Section 6.4: Show above lock screen and turn screen on when woken
        configureLockScreenWakeAttributes()

        // Section 2 & Roadmap Step 1: Prevent sleep states while docked on the desk
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        configureAmbientImmersiveWindow()

        syncManager = FirebaseNotificationSyncManager(applicationContext)
        TabletBatteryProtectionService.start(applicationContext)
        TabletPerformanceForegroundService.ensureIdleStateListener(applicationContext)
        mediaControllerManager = SpotifyMediaControllerManager(
            context = applicationContext,
            scope = lifecycleScope
        )

        // V7 Section 1.2: 100ms ticker for OverlayManager countdowns (OTP, Timetable pause/resume, Phone Battery)
        lifecycleScope.launch {
            var lastRealtimeMs = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(100L)
                val nowRealtimeMs = SystemClock.elapsedRealtime()
                val deltaMs = (nowRealtimeMs - lastRealtimeMs).coerceIn(0L, 1_000L)
                lastRealtimeMs = nowRealtimeMs
                syncManager.tickOverlaysAndBatteryState(deltaMs)
            }
        }

        setContent {
            val notifications by syncManager.notifications.collectAsState()
            val tasks by syncManager.tasks.collectAsState()
            val clockTheme by syncManager.clockTheme.collectAsState()
            val activeOverlay by syncManager.activeOverlay.collectAsState()
            val pcStats by syncManager.pcStats.collectAsState()
            val pcMonitorEnabled by syncManager.pcMonitorEnabled.collectAsState()
            val pcFullScreenEnabled by syncManager.pcFullScreenEnabled.collectAsState()
            val phoneBattery by syncManager.phoneBattery.collectAsState()
            val phoneDnd by syncManager.phoneDnd.collectAsState()
            val musicModeEnabled by syncManager.musicModeEnabled.collectAsState()
            val musicModeOverride by syncManager.musicModeOverride.collectAsState()
            val nightScheduleConfig by syncManager.nightScheduleConfig.collectAsState()
            val pixelShiftEnabled by syncManager.pixelShiftEnabled.collectAsState()
            val batteryAutomationStatus by syncManager.batteryAutomationStatus.collectAsState()
            val serverTimeOffsetMs by syncManager.serverTimeOffset.collectAsState()
            val syncStatus by syncManager.connectionStatus.collectAsState()
            val syncStatusMessage by syncManager.statusMessage.collectAsState()
            val mediaState by mediaControllerManager.playbackState.collectAsState()
            val isScreenOffByProximity by TabletBatteryProtectionService.isScreenOffByProximity.collectAsState()

            val perfState by TabletPerformanceForegroundService.stateSnapshot.collectAsState()
            val perfSession by TabletPerformanceForegroundService.activeSession.collectAsState()
            val perfLinkStatus by TabletPerformanceForegroundService.linkStatus.collectAsState()

            var nightTickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(15_000L)
                    nightTickMs = System.currentTimeMillis()
                }
            }

            val isNightScheduleActive = remember(
                nightScheduleConfig.enabled,
                nightScheduleConfig.start,
                nightScheduleConfig.end,
                nightTickMs
            ) {
                nightScheduleConfig.isActiveAt(LocalTime.now())
            }

            val requiresTempNightBoost = activeOverlay.requiresTemporaryNightBrightnessBoost
            // V8 Section 6: Voice override wins if its updatedAt > phoneDnd.updatedAt;
            // otherwise follow the phone's DND state and musicMode switch as in V7.
            val isDndMusicClockMode = MusicModeOverridePayload.resolveEffectiveMusicClockMode(
                phoneDnd = phoneDnd,
                musicModeEnabled = musicModeEnabled,
                overridePayload = musicModeOverride
            )

            // V7 Section 1.4, Section 2.2 & Section 6.3/6.4:
            // Manage window brightness and FLAG_KEEP_SCREEN_ON dynamically.
            LaunchedEffect(
                perfState.enabled,
                isNightScheduleActive,
                nightScheduleConfig.brightnessPct,
                requiresTempNightBoost,
                isScreenOffByProximity
            ) {
                if (!perfState.enabled) {
                    applyWindowScreenAndBrightnessPolicy(
                        isNightScheduleActive = isNightScheduleActive,
                        nightBrightnessFloat = nightScheduleConfig.brightnessFloat,
                        requiresTempNightBoost = requiresTempNightBoost,
                        isScreenOffByProximity = isScreenOffByProximity
                    )
                }
            }

            if (perfState.enabled) {
                // V9 Section 3: Keep screen on for the whole time Performance Mode is active.
                // Fixed brightness behaviour, no auto-dimming.
                LaunchedEffect(Unit) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val lp = window.attributes
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = lp
                }

                TabletPerformanceModeScreen(
                    session = perfSession,
                    stateSnapshot = perfState,
                    linkStatus = perfLinkStatus
                )
            } else {
                DeskCompanionScreen(
                    notifications = notifications,
                    tasks = tasks,
                    clockTheme = clockTheme,
                    activeOverlay = activeOverlay,
                    phoneBattery = phoneBattery,
                    pcStats = pcStats,
                    pcMonitorEnabled = pcMonitorEnabled,
                    pcFullScreenEnabled = pcFullScreenEnabled,
                    batteryAutomationStatus = batteryAutomationStatus,
                    isNightScheduleActive = isNightScheduleActive,
                    pixelShiftEnabled = pixelShiftEnabled,
                    isDndMusicClockMode = isDndMusicClockMode,
                    isScreenOffByProximity = isScreenOffByProximity,
                    serverTimeOffsetMs = serverTimeOffsetMs,
                    syncStatus = syncStatus,
                    syncStatusMessage = syncStatusMessage,
                    mediaState = mediaState,
                    initialDatabaseUrl = syncManager.getDatabaseUrl(),
                    initialNodePath = syncManager.getNodePath(),
                    onDismissAllNotifications = { syncManager.dismissAllNotifications() },
                    onPushTestNotification = { syncManager.pushTestNotification() },
                    onSaveFirebaseConfig = { url, node ->
                        syncManager.updateConfiguration(url, node)
                        TabletBatteryProtectionService.notifyFirebaseConfigChanged(applicationContext)
                    },
                    onPlayPause = { mediaControllerManager.togglePlayPause() },
                    onSkipNext = { mediaControllerManager.skipNext() },
                    onSkipPrevious = { mediaControllerManager.skipPrevious() },
                    onOpenSpotify = { mediaControllerManager.openSpotifyApp() },
                    onOpenNotificationAccess = { mediaControllerManager.openNotificationListenerSettings() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(TabletBatteryProtectionService.EXTRA_PROXIMITY_WAKE, false)) {
            configureLockScreenWakeAttributes()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            configureAmbientImmersiveWindow()
        }
    }

    override fun onResume() {
        super.onResume()
        if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(applicationContext)) {
            TabletPerformanceForegroundService.start(applicationContext)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        configureAmbientImmersiveWindow()
        mediaControllerManager.refreshSessions()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureAmbientImmersiveWindow()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::syncManager.isInitialized) {
            syncManager.release()
        }
        if (::mediaControllerManager.isInitialized) {
            mediaControllerManager.release()
        }
    }

    private fun configureLockScreenWakeAttributes() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            val km = getSystemService(KeyguardManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                km?.requestDismissKeyguard(this, null)
            }
        }
    }

    private fun applyWindowScreenAndBrightnessPolicy(
        isNightScheduleActive: Boolean,
        nightBrightnessFloat: Float,
        requiresTempNightBoost: Boolean,
        isScreenOffByProximity: Boolean
    ) {
        if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(applicationContext)) {
            // V9 Section 3: In Performance Mode, keep fixed brightness and never dim
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            return
        }
        runCatching {
            if (isScreenOffByProximity) {
                // V7 Section 6.3 Method B (Fallback): clear FLAG_KEEP_SCREEN_ON so display sleeps
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = window.attributes
                lp.screenBrightness = 0.0f
                window.attributes = lp
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val targetBrightness = when {
                    isNightScheduleActive && requiresTempNightBoost -> {
                        AppConfigConstants.NIGHT_SCHEDULE_TEMP_BOOST_BRIGHTNESS
                    }
                    isNightScheduleActive -> {
                        nightBrightnessFloat
                    }
                    else -> {
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
                val lp = window.attributes
                if (lp.screenBrightness != targetBrightness) {
                    lp.screenBrightness = targetBrightness
                    window.attributes = lp
                }
            }
        }
    }

    private fun configureAmbientImmersiveWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        )
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }
}
