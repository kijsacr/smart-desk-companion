package com.smartdesk.companion.tablet.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.BatteryAutomationStatus
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.tablet.media.MediaPlaybackState
import com.smartdesk.companion.tablet.sync.SyncConnectionStatus
import com.smartdesk.companion.tablet.ui.touch.ghostTouchSafeClickable

/**
 * V7 Section 7.2: DND Music & Clock Mode (Tablet Base Layer).
 * - Clean 50/50 landscape split:
 *   - Left half: Top-left Gear + TabletBatteryIndicator, centered Float clock + Date + Sync dot.
 *   - Right half: Vertically centered full Music Player with a spinning vinyl album disc
 *     (1 turn per 20s via GPU `graphicsLayer` lambda without recomposition), song title,
 *     artist subtitle, oversized theme-accented controls, and thin progress line.
 */
@Composable
fun TabletDndMusicClockContent(
    clockTheme: ClockTheme,
    batteryAutomationStatus: BatteryAutomationStatus,
    syncStatus: SyncConnectionStatus,
    mediaState: MediaPlaybackState,
    isNightScheduleActive: Boolean,
    isScreenOffByProximity: Boolean,
    onOpenSettingsDialog: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onTrackAreaClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // LEFT HALF (50%): Top-left Gear & Battery + Centered Float Clock & Date/Sync Dot
        Column(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
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

            DndLeftClockPane(
                clockTheme = clockTheme,
                syncStatus = syncStatus,
                onSyncDotClick = onOpenSettingsDialog,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        // RIGHT HALF (50%): Full Vinyl Disc Music Player vertically centered
        DndRightMusicPlayerPane(
            clockTheme = clockTheme,
            mediaState = mediaState,
            isNightScheduleActive = isNightScheduleActive,
            isScreenOffByProximity = isScreenOffByProximity,
            onPlayPause = onPlayPause,
            onSkipPrevious = onSkipPrevious,
            onSkipNext = onSkipNext,
            onTrackAreaClick = onTrackAreaClick,
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun DndLeftClockPane(
    clockTheme: ClockTheme,
    syncStatus: SyncConnectionStatus,
    onSyncDotClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatClockAndDateSectionShared(
        clockTheme = clockTheme,
        syncStatus = syncStatus,
        onSyncDotClick = onSyncDotClick,
        enableInternalPixelShift = false,
        modifier = modifier
    )
}

@Composable
private fun DndRightMusicPlayerPane(
    clockTheme: ClockTheme,
    mediaState: MediaPlaybackState,
    isNightScheduleActive: Boolean,
    isScreenOffByProximity: Boolean,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onTrackAreaClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }

    val shouldSpin = animationsEnabled &&
        mediaState.isSessionActive &&
        mediaState.isPlaying &&
        !isNightScheduleActive &&
        !isScreenOffByProximity

    val rotationAnim = remember { Animatable(0f) }
    LaunchedEffect(shouldSpin) {
        if (shouldSpin) {
            while (true) {
                val startAngle = rotationAnim.value % 360f
                rotationAnim.snapTo(startAngle)
                rotationAnim.animateTo(
                    targetValue = startAngle + 360f,
                    animationSpec = tween(
                        durationMillis = AppConfigConstants.DISC_ROTATION_DURATION_MS,
                        easing = LinearEasing
                    )
                )
            }
        }
    }

    val primaryAccent = clockTheme.digitColors.getOrElse(0) { Color(0xFF5CB8FF) }
    val secondaryAccent = clockTheme.digitColors.getOrElse(1) { Color(0xFFFFB067) }
    val isNightDim = clockTheme.id == "night_dim"

    val songTitle = when {
        !mediaState.hasNotificationPermission -> "Tap to Link Spotify"
        mediaState.isSessionActive && mediaState.trackTitle.isNotBlank() -> mediaState.trackTitle
        else -> "Nothing playing"
    }

    val artistSubtitle = when {
        !mediaState.hasNotificationPermission -> "Grant Notification Access on Tablet"
        mediaState.isSessionActive && mediaState.artistName.isNotBlank() -> mediaState.artistName
        else -> "Spotify"
    }

    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 1. Spinning Vinyl Disc (GPU layer rotation, no recomposition loop)
        VinylAlbumDisc(
            mediaState = mediaState,
            clockTheme = clockTheme,
            rotationProvider = { rotationAnim.value },
            onClick = onTrackAreaClick
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 2. Song Name + Artist Subtitle
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .ghostTouchSafeClickable(onClick = onTrackAreaClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = songTitle,
                color = if (isNightDim) primaryAccent else Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Text(
                text = artistSubtitle,
                color = if (isNightDim) Color(0xFF9E4747) else Color(0xFF9AA0A6),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 3. Oversized Theme-Accented Controls (Previous, Play/Pause larger, Next)
        DndMusicControlsRow(
            clockTheme = clockTheme,
            isPlaying = mediaState.isPlaying,
            onSkipPrevious = onSkipPrevious,
            onPlayPause = onPlayPause,
            onSkipNext = onSkipNext
        )

        Spacer(modifier = Modifier.height(18.dp))

        // 4. Very thin read-only progress / accent line under the controls
        Box(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .height(2.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1A1C22))
        ) {
            val fillWidth = if (mediaState.isSessionActive && mediaState.isPlaying) 1f else 0.35f
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fillWidth)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(primaryAccent, secondaryAccent)
                        )
                    )
            )
        }
    }
}

@Composable
private fun VinylAlbumDisc(
    mediaState: MediaPlaybackState,
    clockTheme: ClockTheme,
    rotationProvider: () -> Float,
    onClick: () -> Unit
) {
    val primaryAccent = clockTheme.digitColors.getOrElse(0) { Color(0xFF5CB8FF) }
    val secondaryAccent = clockTheme.digitColors.getOrElse(1) { Color(0xFFFFB067) }
    val isPlayingActive = mediaState.isSessionActive && mediaState.albumArt != null

    Box(
        modifier = Modifier
            .size(216.dp)
            .ghostTouchSafeClickable(onClick = onClick)
            .graphicsLayer {
                rotationZ = rotationProvider()
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer vinyl dark ring + subtle groove rings
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Base vinyl disc
            drawCircle(
                color = Color(0xFF121318),
                radius = radius,
                center = center
            )

            // Theme accent outer rim
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        primaryAccent.copy(alpha = 0.85f),
                        secondaryAccent.copy(alpha = 0.85f),
                        primaryAccent.copy(alpha = 0.85f)
                    ),
                    center = center
                ),
                radius = radius - 1.5.dp.toPx(),
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )

            // Vinyl grooves when no album art is loaded
            if (!isPlayingActive) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.06f),
                    radius = radius * 0.78f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.05f),
                    radius = radius * 0.58f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawCircle(
                    color = primaryAccent.copy(alpha = 0.25f),
                    radius = radius * 0.36f,
                    center = center
                )
            }
        }

        // Cropped circular album art inside the vinyl rim
        val art = mediaState.albumArt
        if (art != null && mediaState.isSessionActive) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = "Album Art Disc",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(198.dp)
                    .clip(CircleShape)
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = primaryAccent,
                modifier = Modifier.size(44.dp)
            )
        }

        // Vinyl center spindle hole + dark inner ring
        if (art != null && mediaState.isSessionActive) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF14161C).copy(alpha = 0.88f))
                    .border(2.dp, primaryAccent.copy(alpha = 0.65f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(1.dp, Color(0xFF3A3D46), CircleShape)
                )
            }
        }
    }
}

@Composable
private fun DndMusicControlsRow(
    clockTheme: ClockTheme,
    isPlaying: Boolean,
    onSkipPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit
) {
    val primaryAccent = clockTheme.digitColors.getOrElse(0) { Color(0xFF5CB8FF) }
    val isNightDim = clockTheme.id == "night_dim"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Previous button (oversized touch target)
        Box(
            modifier = Modifier
                .size(width = 86.dp, height = 74.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF16181E))
                .border(1.dp, primaryAccent.copy(alpha = 0.40f), RoundedCornerShape(20.dp))
                .ghostTouchSafeClickable(onClick = onSkipPrevious),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.FastRewind,
                contentDescription = "Previous Track",
                tint = primaryAccent,
                modifier = Modifier.size(42.dp)
            )
        }

        // Play / Pause button (larger center button in theme accent)
        Box(
            modifier = Modifier
                .size(width = 104.dp, height = 84.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(primaryAccent.copy(alpha = if (isNightDim) 0.22f else 0.28f))
                .border(1.8.dp, primaryAccent, RoundedCornerShape(24.dp))
                .ghostTouchSafeClickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = if (isNightDim) primaryAccent else Color.White,
                modifier = Modifier.size(52.dp)
            )
        }

        // Next button (oversized touch target)
        Box(
            modifier = Modifier
                .size(width = 86.dp, height = 74.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF16181E))
                .border(1.dp, primaryAccent.copy(alpha = 0.40f), RoundedCornerShape(20.dp))
                .ghostTouchSafeClickable(onClick = onSkipNext),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.FastForward,
                contentDescription = "Next Track",
                tint = primaryAccent,
                modifier = Modifier.size(42.dp)
            )
        }
    }
}
