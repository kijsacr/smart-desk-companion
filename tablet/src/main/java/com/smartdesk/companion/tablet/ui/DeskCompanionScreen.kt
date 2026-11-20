package com.smartdesk.companion.tablet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.BatteryAutomationStatus
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.shared.ClockThemeCatalog
import com.smartdesk.companion.shared.PcStatsPayload
import com.smartdesk.companion.shared.PhoneBatteryPayload
import com.smartdesk.companion.shared.PixelShiftRing
import com.smartdesk.companion.shared.SubjectCatalog
import com.smartdesk.companion.shared.TaskItem
import com.smartdesk.companion.shared.TaskScheduleLogic
import com.smartdesk.companion.tablet.media.MediaPlaybackState
import com.smartdesk.companion.tablet.model.NotificationPayload
import com.smartdesk.companion.tablet.sync.ActiveTabletOverlay
import com.smartdesk.companion.tablet.sync.SyncConnectionStatus
import com.smartdesk.companion.tablet.ui.touch.GhostTouchConfig
import com.smartdesk.companion.tablet.ui.touch.ghostTouchRootFilter
import com.smartdesk.companion.tablet.ui.touch.ghostTouchSafeClickable
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

@Composable
fun DeskCompanionScreen(
    notifications: List<NotificationPayload>,
    tasks: List<TaskItem>,
    clockTheme: ClockTheme,
    activeOverlay: ActiveTabletOverlay,
    phoneBattery: PhoneBatteryPayload?,
    pcStats: PcStatsPayload?,
    pcMonitorEnabled: Boolean,
    pcFullScreenEnabled: Boolean,
    batteryAutomationStatus: BatteryAutomationStatus,
    isNightScheduleActive: Boolean,
    pixelShiftEnabled: Boolean,
    isDndMusicClockMode: Boolean,
    isScreenOffByProximity: Boolean,
    serverTimeOffsetMs: Long,
    syncStatus: SyncConnectionStatus,
    syncStatusMessage: String,
    mediaState: MediaPlaybackState,
    initialDatabaseUrl: String,
    initialNodePath: String,
    onDismissAllNotifications: () -> Unit,
    onPushTestNotification: () -> Unit,
    onSaveFirebaseConfig: (url: String, nodePath: String) -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenNotificationAccess: () -> Unit
) {
    var showSettingsDialog by remember { mutableStateOf(false) }

    // V7 Section 2: Temporarily apply Night Dim clock theme while Night Schedule is active
    val effectiveClockTheme = remember(clockTheme, isNightScheduleActive) {
        if (isNightScheduleActive) {
            ClockThemeCatalog.findById(AppConfigConstants.NIGHT_DIM_CLOCK_THEME_ID)
        } else {
            clockTheme
        }
    }

    // V7 Section 3: Pixel Shift ring (cycles through 9 positions every 3 minutes)
    var pixelShiftStepIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(pixelShiftEnabled) {
        if (!pixelShiftEnabled) {
            pixelShiftStepIndex = 0
        } else {
            while (true) {
                delay(AppConfigConstants.PIXEL_SHIFT_INTERVAL_MS)
                pixelShiftStepIndex = (pixelShiftStepIndex + 1) % PixelShiftRing.OFFSETS_DP.size
            }
        }
    }
    val pixelShiftOffsetDp = remember(pixelShiftStepIndex, pixelShiftEnabled) {
        PixelShiftRing.offsetForIndex(pixelShiftStepIndex, pixelShiftEnabled)
    }

    // Extra 4dp base padding so the shifted container never drops below 32dp from any edge
    val extraPad = AppConfigConstants.PIXEL_SHIFT_EXTRA_PADDING_DP.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Base Layer: Normal Dashboard or DND Music & Clock Mode (with 300ms crossfade & pixel shift)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .ghostTouchRootFilter()
                .padding(
                    start = GhostTouchConfig.EDGE_SAFE_INSET + extraPad,
                    end = GhostTouchConfig.EDGE_SAFE_INSET + extraPad,
                    top = 20.dp + extraPad,
                    bottom = 32.dp + extraPad
                )
                .offset {
                    IntOffset(
                        x = pixelShiftOffsetDp.first.dp.roundToPx(),
                        y = pixelShiftOffsetDp.second.dp.roundToPx()
                    )
                }
        ) {
            Crossfade(
                targetState = isDndMusicClockMode,
                animationSpec = tween(durationMillis = 300),
                label = "BaseLayerModeCrossfade"
            ) { dndMode ->
                if (dndMode) {
                    TabletDndMusicClockContent(
                        clockTheme = effectiveClockTheme,
                        batteryAutomationStatus = batteryAutomationStatus,
                        syncStatus = syncStatus,
                        mediaState = mediaState,
                        isNightScheduleActive = isNightScheduleActive,
                        isScreenOffByProximity = isScreenOffByProximity,
                        onOpenSettingsDialog = { showSettingsDialog = true },
                        onPlayPause = onPlayPause,
                        onSkipPrevious = onSkipPrevious,
                        onSkipNext = onSkipNext,
                        onTrackAreaClick = {
                            if (!mediaState.hasNotificationPermission) {
                                onOpenNotificationAccess()
                            } else {
                                onOpenSpotify()
                            }
                        }
                    )
                } else {
                    DashboardStandardContent(
                        notifications = notifications,
                        tasks = tasks,
                        clockTheme = effectiveClockTheme,
                        phoneBattery = phoneBattery,
                        pcStats = pcStats,
                        pcMonitorEnabled = pcMonitorEnabled,
                        batteryAutomationStatus = batteryAutomationStatus,
                        serverTimeOffsetMs = serverTimeOffsetMs,
                        syncStatus = syncStatus,
                        mediaState = mediaState,
                        onOpenSettingsDialog = { showSettingsDialog = true },
                        onDismissAllNotifications = onDismissAllNotifications,
                        onPlayPause = onPlayPause,
                        onSkipPrevious = onSkipPrevious,
                        onSkipNext = onSkipNext,
                        onTrackAreaClick = {
                            if (!mediaState.hasNotificationPermission) {
                                onOpenNotificationAccess()
                            } else {
                                onOpenSpotify()
                            }
                        }
                    )
                }
            }
        }

        // Full-Screen PC Resource Monitor (toggled ON/OFF from the phone widget; yields to Priority 1..4 overlays)
        AnimatedVisibility(
            visible = pcFullScreenEnabled && activeOverlay is ActiveTabletOverlay.None,
            enter = fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = fadeOut(animationSpec = tween(durationMillis = 250))
        ) {
            TabletPcResourceOverlay(
                pcStats = pcStats,
                clockTheme = effectiveClockTheme,
                batteryAutomationStatus = batteryAutomationStatus,
                serverTimeOffsetMs = serverTimeOffsetMs
            )
        }

        // V7 Section 1: Shared Overlay Manager rendering (Priority 1..4)
        when (val overlay = activeOverlay) {
            is ActiveTabletOverlay.Otp -> {
                TabletOtpOverlay(
                    otpOverlay = overlay,
                    clockTheme = effectiveClockTheme
                )
            }

            is ActiveTabletOverlay.Timetable -> {
                TabletTimetableOverlay(
                    overlayState = overlay,
                    clockTheme = effectiveClockTheme
                )
            }

            is ActiveTabletOverlay.PhoneBatteryCritical -> {
                TabletPhoneBatteryAlertOverlay(
                    level = overlay.level,
                    isCritical = true,
                    pixelShiftOffsetDp = pixelShiftOffsetDp
                )
            }

            is ActiveTabletOverlay.PhoneBatteryLow -> {
                TabletPhoneBatteryAlertOverlay(
                    level = overlay.level,
                    isCritical = false,
                    pixelShiftOffsetDp = 0 to 0
                )
            }

            ActiveTabletOverlay.None -> Unit
        }
    }

    if (showSettingsDialog && activeOverlay is ActiveTabletOverlay.None && !pcFullScreenEnabled) {
        SettingsAndTweaksDialog(
            initialDatabaseUrl = initialDatabaseUrl,
            initialNodePath = initialNodePath,
            statusMessage = syncStatusMessage,
            hasNotificationListenerPermission = mediaState.hasNotificationPermission,
            activeClockTheme = effectiveClockTheme,
            onSaveFirebaseConfig = onSaveFirebaseConfig,
            onOpenNotificationAccess = onOpenNotificationAccess,
            onPushTestNotification = onPushTestNotification,
            onDismissAllNotifications = onDismissAllNotifications,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
private fun DashboardStandardContent(
    notifications: List<NotificationPayload>,
    tasks: List<TaskItem>,
    clockTheme: ClockTheme,
    phoneBattery: PhoneBatteryPayload?,
    pcStats: PcStatsPayload?,
    pcMonitorEnabled: Boolean,
    batteryAutomationStatus: BatteryAutomationStatus,
    serverTimeOffsetMs: Long,
    syncStatus: SyncConnectionStatus,
    mediaState: MediaPlaybackState,
    onOpenSettingsDialog: () -> Unit,
    onDismissAllNotifications: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onTrackAreaClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // LEFT PANE (~64% width): Top-Left Gear + Battery Indicator + Giant "Float" Clock + Date/Sync Dot + Compact Spotify Strip
        Column(
            modifier = Modifier
                .weight(0.64f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // V7 Section 5.4: Top-left (gear and tablet battery indicator) is unchanged. Bottom-left is empty.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .ghostTouchSafeClickable(onClick = onOpenSettingsDialog),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings & Configuration",
                        tint = Color(0xFF70757A),
                        modifier = Modifier.size(32.dp)
                    )
                }

                TabletBatteryIndicator(automationStatus = batteryAutomationStatus)
            }

            // Module A + C: Giant Overlapping "Float" Clock (themed) + Date & Glowing Sync Dot Row
            FloatClockAndDateSectionShared(
                clockTheme = clockTheme,
                syncStatus = syncStatus,
                onSyncDotClick = onOpenSettingsDialog,
                enableInternalPixelShift = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Module D: Sleek, Compact Spotify Music Control Strip
            CompactSpotifyStrip(
                mediaState = mediaState,
                onPlayPause = onPlayPause,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onTrackAreaClick = onTrackAreaClick,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // RIGHT PANE (~36% width): Top-Right Status Strip + Upper Notification Hub + Lower Read-Only Task Viewer
        Column(
            modifier = Modifier
                .weight(0.36f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // V7 Section 5.4: Single combined status pill at the top-right corner
            // ([phone] 82% | CPU 14% | RAM 48% | 52°C) with top margin above notification cards
            TabletTopStatusStrip(
                phoneBattery = phoneBattery,
                pcStats = pcStats,
                pcMonitorEnabled = pcMonitorEnabled,
                serverTimeOffsetMs = serverTimeOffsetMs,
                modifier = Modifier.align(Alignment.End)
            )

            // Upper Part (Module B): Notification Hub Cards (3-5) with Dismiss All
            NotificationHubStack(
                notifications = notifications,
                onDismissAll = onDismissAllNotifications,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.46f)
            )

            // Lower Part (Module F): Read-Only Task Viewer (up to 4 cards + "+N more")
            TabletTaskViewerSection(
                allTasks = tasks,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.54f)
            )
        }
    }
}

/**
 * Module A + C (shared between standard Dashboard and DND Music & Clock Mode):
 * Giant iOS StandBy "Float" overlapping clock centered in the left pane,
 * with the current Date on the bottom-left and the glowing green/red Firebase Sync Dot on the bottom-right.
 */
@Composable
internal fun FloatClockAndDateSectionShared(
    clockTheme: ClockTheme,
    syncStatus: SyncConnectionStatus,
    onSyncDotClick: () -> Unit,
    enableInternalPixelShift: Boolean = false,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { SimpleDateFormat("hh:mm", Locale.getDefault()) }
    val dateFormatter = remember { SimpleDateFormat("EEE MMM d, yyyy", Locale.getDefault()) }

    var currentTime by remember { mutableStateOf(timeFormatter.format(Date())) }
    var currentDate by remember { mutableStateOf(dateFormatter.format(Date())) }
    var burnInShift by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            currentTime = timeFormatter.format(now)
            currentDate = dateFormatter.format(now)
            burnInShift = if (enableInternalPixelShift) {
                ((now.time / 60_000L) % 5L).toInt() - 2
            } else {
                0
            }
            delay(1000L)
        }
    }

    Column(
        modifier = modifier
            .offset { IntOffset(x = burnInShift * 2, y = burnInShift) },
        verticalArrangement = Arrangement.Center
    ) {
        FloatStyleClock(
            timeText = currentTime,
            theme = clockTheme,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 6.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentDate,
                color = if (clockTheme.id == "night_dim") Color(0xFFD96B6B) else Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            )

            SyncDotIndicator(
                syncStatus = syncStatus,
                onClick = onSyncDotClick
            )
        }
    }
}

@Composable
internal fun SyncDotIndicator(
    syncStatus: SyncConnectionStatus,
    onClick: () -> Unit
) {
    val dotColor = if (syncStatus == SyncConnectionStatus.CONNECTED) {
        Color(0xFF2AF568)
    } else {
        Color(0xFFFF3B30)
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .ghostTouchSafeClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        dotColor.copy(alpha = 0.58f),
                        dotColor.copy(alpha = 0.20f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension / 2f
                ),
                radius = size.minDimension / 2f,
                center = center
            )
            drawCircle(
                color = dotColor,
                radius = 6.5.dp.toPx(),
                center = center
            )
        }
    }
}

/**
 * Module D: Compact Spotify Remote Music Controls
 */
@Composable
private fun CompactSpotifyStrip(
    mediaState: MediaPlaybackState,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onTrackAreaClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(86.dp)
            .background(Color(0xFF1A1B1E), RoundedCornerShape(22.dp))
            .border(1.dp, Color(0xFF2A2B30), RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .ghostTouchSafeClickable(onClick = onTrackAreaClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF282A2E)),
                contentAlignment = Alignment.Center
            ) {
                val art = mediaState.albumArt
                if (art != null) {
                    Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFF9AA0A6),
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val primaryLine = when {
                    !mediaState.hasNotificationPermission -> "Tap to Link Spotify"
                    mediaState.isSessionActive -> {
                        if (mediaState.artistName.isNotBlank() &&
                            !mediaState.artistName.startsWith("Tap Play")
                        ) {
                            "${mediaState.artistName} - ${mediaState.trackTitle}"
                        } else {
                            mediaState.trackTitle
                        }
                    }
                    else -> "Artist - Track Title"
                }

                val secondaryLine = when {
                    !mediaState.hasNotificationPermission -> "Grant Notification Access on Tablet"
                    mediaState.isSessionActive -> {
                        if (mediaState.albumName.isNotBlank()) {
                            "${mediaState.artistName} • ${mediaState.albumName}"
                        } else {
                            mediaState.artistName
                        }
                    }
                    else -> "Open Spotify on Tablet to Play"
                }

                Text(
                    text = primaryLine,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = secondaryLine,
                    color = Color(0xFF8E9299),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 78.dp, height = 74.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .ghostTouchSafeClickable(onClick = onSkipPrevious),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FastRewind,
                    contentDescription = "Previous Track",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(width = 84.dp, height = 74.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .ghostTouchSafeClickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (mediaState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (mediaState.isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(width = 78.dp, height = 74.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .ghostTouchSafeClickable(onClick = onSkipNext),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = "Next Track",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }
}

/**
 * Module B: Right-Side Notification Hub Feed (Upper Half of Right Pane)
 */
@Composable
private fun NotificationHubStack(
    notifications: List<NotificationPayload>,
    onDismissAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = notifications,
                key = { it.id }
            ) { item ->
                FloatStyleNotificationCard(
                    item = item,
                    onClick = onDismissAll
                )
            }
        }

        if (notifications.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .background(Color(0xFF1A1B1E), RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF32343A), RoundedCornerShape(14.dp))
                    .ghostTouchSafeClickable(onClick = onDismissAll)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Dismiss All",
                    color = Color(0xFF9AA0A6),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Module F: Task Viewer (Tablet)
 * Purely read-only dashboard section directly below the notification section.
 */
@Composable
private fun TabletTaskViewerSection(
    allTasks: List<TaskItem>,
    modifier: Modifier = Modifier
) {
    var minuteTick by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            val nextTick = System.currentTimeMillis() / 60_000L
            if (nextTick != minuteTick) {
                minuteTick = nextTick
            }
        }
    }

    val today = remember(minuteTick) { LocalDate.now() }
    val pendingTasks = remember(allTasks, minuteTick) {
        TaskScheduleLogic.sortPendingTasks(allTasks)
    }

    val maxVisible = AppConfigConstants.MAX_TABLET_VISIBLE_TASKS
    val visibleTasks = remember(pendingTasks) { pendingTasks.take(maxVisible) }
    val overflowCount = (pendingTasks.size - visibleTasks.size).coerceAtLeast(0)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "TASKS",
            color = Color(0xFF70757A),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 4.dp)
        )

        if (visibleTasks.isEmpty()) {
            Text(
                text = "No pending tasks",
                color = Color(0xFF5F6368),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        } else {
            visibleTasks.forEach { task ->
                ReadOnlyTabletTaskCard(
                    task = task,
                    today = today
                )
            }

            if (overflowCount > 0) {
                Text(
                    text = "+$overflowCount more",
                    color = Color(0xFF8E9299),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp, top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ReadOnlyTabletTaskCard(
    task: TaskItem,
    today: LocalDate
) {
    val subject = remember(task.subjectId) { SubjectCatalog.findById(task.subjectId) }
    val isPerfSession = remember(task.subjectId) { SubjectCatalog.isPerformanceSession(task.subjectId) }
    val deadlineInfo = remember(task.deadline, today) {
        TaskScheduleLogic.evaluateDeadline(task.deadline, today)
    }

    val cardBgColor = when {
        isPerfSession -> Color(0xFF231136)
        deadlineInfo.isUrgentRed -> Color(0xFF2B1215)
        else -> Color(0xFF202124)
    }
    val cardBorderColor = when {
        isPerfSession -> Color(0xFFAB47BC).copy(alpha = 0.85f)
        deadlineInfo.isUrgentRed -> Color(0xFFFF5252).copy(alpha = 0.78f)
        else -> Color(0xFF2E3036)
    }
    val iconBoxBgColor = when {
        isPerfSession -> Color(0xFF3B1C5A)
        deadlineInfo.isUrgentRed -> Color(0xFF4A181D)
        else -> Color(0xFF2C2E33)
    }
    val iconTintColor = when {
        isPerfSession -> Color(0xFFE1BEE7)
        deadlineInfo.isUrgentRed -> Color(0xFFFF8A80)
        else -> Color(0xFF8AB4F8)
    }
    val deadlineLabelColor = when {
        isPerfSession -> Color(0xFFCE93D8)
        deadlineInfo.isUrgentRed -> Color(0xFFFF6B6B)
        else -> Color(0xFF9AA0A6)
    }
    val subtitleColor = when {
        isPerfSession -> Color(0xFFCE93D8)
        deadlineInfo.isUrgentRed -> Color(0xFFFFB4AB)
        else -> Color(0xFF9AA0A6)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBgColor, RoundedCornerShape(18.dp))
            .border(1.dp, cardBorderColor, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Subject Icon
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBoxBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = subject.icon,
                contentDescription = subject.name,
                tint = iconTintColor,
                modifier = Modifier.size(21.dp)
            )
        }

        // 2. Task Title + Subject Name
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = task.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subject.name,
                color = subtitleColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 3. Deadline Label on the right
        Text(
            text = deadlineInfo.label,
            color = deadlineLabelColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun FloatStyleNotificationCard(
    item: NotificationPayload,
    onClick: () -> Unit
) {
    val appTitle = if (item.isOtp) {
        item.source.ifBlank { item.title.ifBlank { item.appDisplayName() } }
    } else {
        item.appDisplayName()
    }

    val bodyLine = remember(item.isOtp, item.otp, item.title, item.message, appTitle) {
        if (item.isOtp) {
            // V7 Section 4.1: "stays in the notification list as a normal card whose title is the source name
            // and whose content is only the OTP. The original message text is never shown."
            item.otp.ifBlank { item.message }.trim()
        } else {
            val cleanTitle = item.title.trim()
            val cleanMsg = item.message.trim()
            when {
                cleanTitle.isNotEmpty() && cleanMsg.isNotEmpty() -> {
                    if (cleanTitle.equals(appTitle, ignoreCase = true) ||
                        cleanTitle.equals("Alert", ignoreCase = true) ||
                        cleanTitle.equals("Notification", ignoreCase = true) ||
                        cleanMsg.startsWith("$cleanTitle:", ignoreCase = true)
                    ) {
                        cleanMsg
                    } else {
                        "$cleanTitle: $cleanMsg"
                    }
                }
                cleanMsg.isNotEmpty() -> cleanMsg
                else -> cleanTitle
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF202124), RoundedCornerShape(20.dp))
            .border(1.dp, Color(0xFF2E3036), RoundedCornerShape(20.dp))
            .ghostTouchSafeClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppBadgeIcon(packageName = item.packageName)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = appTitle,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.timestamp.isNotBlank()) {
                    Text(
                        text = item.timestamp,
                        color = Color(0xFF8E9299),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (item.isOtp) {
                Text(
                    text = bodyLine,
                    color = Color(0xFF63B8FF),
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFeatureSettings = "tnum",
                        letterSpacing = 1.6.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = bodyLine,
                    color = Color(0xFFDADCE0),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun AppBadgeIcon(packageName: String) {
    val pkg = packageName.lowercase()
    when {
        pkg.contains("whatsapp") -> {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF25D366)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(26.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(
                        color = Color.White,
                        radius = size.minDimension * 0.42f,
                        center = center,
                        style = Stroke(width = 2.6.dp.toPx())
                    )
                }
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "WhatsApp",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        pkg.contains("instagram") -> {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF833AB4),
                                Color(0xFFE1306C),
                                Color(0xFFFCAF45)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(120f, 120f)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val strokeW = 2.2.dp.toPx()
                    drawRoundRect(
                        color = Color.White,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                        style = Stroke(width = strokeW)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = size.minDimension * 0.23f,
                        center = Offset(size.width / 2f, size.height / 2f),
                        style = Stroke(width = strokeW)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 1.6.dp.toPx(),
                        center = Offset(size.width * 0.76f, size.height * 0.24f)
                    )
                }
            }
        }

        pkg.contains("calendar") -> {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF4285F4)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = "Calendar",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        pkg.contains("gm") || pkg.contains("gmail") -> {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(26.dp)) {
                    val w = size.width
                    val h = size.height
                    val strokeW = 4.0.dp.toPx()

                    drawLine(
                        color = Color(0xFF4285F4),
                        start = Offset(w * 0.14f, h * 0.25f),
                        end = Offset(w * 0.14f, h * 0.78f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF34A853),
                        start = Offset(w * 0.86f, h * 0.25f),
                        end = Offset(w * 0.86f, h * 0.78f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    val mPath = Path().apply {
                        moveTo(w * 0.14f, h * 0.24f)
                        lineTo(w * 0.50f, h * 0.56f)
                        lineTo(w * 0.86f, h * 0.24f)
                    }
                    drawPath(
                        path = mPath,
                        color = Color(0xFFEA4335),
                        style = Stroke(
                            width = strokeW,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }

        pkg.contains("dialer") || pkg.contains("incallui") || pkg.contains("telecom") || pkg.contains("phone") -> {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF00C853)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        pkg.contains("messaging") || pkg.contains("sms") || pkg.contains("mms") -> {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A73E8)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChatBubble,
                    contentDescription = "Messages",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        else -> {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF3C4043)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = "Notification",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
