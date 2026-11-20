package com.smartdesk.companion.tablet.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.shared.PerformanceLinkStatus
import com.smartdesk.companion.shared.PerformanceSession
import com.smartdesk.companion.shared.PerformanceSong
import com.smartdesk.companion.shared.PerformanceStateSnapshot
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import com.smartdesk.companion.shared.SongPinnedNote
import com.smartdesk.companion.shared.SongSectionRegion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * V9 Section 10: Tablet Display Screen.
 *
 * Requirements:
 * - Touch screen is dead: 100% display-only, driven remotely by Phone.
 * - No session selected: centered text "Please select session" (respecting dark/light theme).
 * - Session active: current song's stitched strip, full-screen: scroll, zoom, pan, section jumps, theme.
 * - Smooth motion: frame-interpolated animation so differences in message timing look buttery smooth.
 * - Dark/light mode: Light shows pages as rendered; Dark inverts colors (softened white #EBEBEB on #0A0A0C).
 * - Notes window: small, semi-transparent card on the right side of the screen. Shows note while
 *   its anchor line is inside the visible viewport; stacks multiple notes, fades when scrolled out.
 *   Fixed screen position independent of zoom/pan.
 * - Tiny dim version of the two connection dots in a corner.
 * - Subtle accent bar along the left edge of the current section's region.
 */
@Composable
fun TabletPerformanceModeScreen(
    session: PerformanceSession?,
    stateSnapshot: PerformanceStateSnapshot,
    linkStatus: PerformanceLinkStatus,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val darkTheme = stateSnapshot.darkTheme

    val screenBgColor = if (darkTheme) Color(0xFF0A0A0C) else Color(0xFFF7F7F8)
    val textPrimaryColor = if (darkTheme) Color(0xFFEBEBEB) else Color(0xFF1E1E24)
    val textSecondaryColor = if (darkTheme) Color(0xFF888899) else Color(0xFF6E6E7E)

    // Smooth 60fps frame-interpolated state targets (Section 10)
    val animatedScrollY by animateFloatAsState(
        targetValue = stateSnapshot.scrollY,
        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        label = "tabletScroll"
    )
    val animatedZoom by animateFloatAsState(
        targetValue = stateSnapshot.zoom,
        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        label = "tabletZoom"
    )
    val animatedPanX by animateFloatAsState(
        targetValue = stateSnapshot.panX,
        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        label = "tabletPan"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(screenBgColor)
    ) {
        if (session == null || stateSnapshot.sessionId.isBlank()) {
            // Section 10: "No session selected: the centered text 'Please select session'."
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Please select session",
                        color = textPrimaryColor,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Waiting for phone to select or create a performance session…",
                        color = textSecondaryColor,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            val song = remember(session, stateSnapshot.songId) {
                session.findSong(stateSnapshot.songId) ?: session.orderedSongs.firstOrNull()
            }

            if (song != null) {
                val activeEntry = song.activeStructure.getOrNull(stateSnapshot.sectionIndex)
                val slideKey = Triple(song.songId, stateSnapshot.sectionIndex, activeEntry?.regionId)
                AnimatedContent(
                    targetState = slideKey,
                    transitionSpec = {
                        val forward = targetState.first != initialState.first || targetState.second >= initialState.second
                        (slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { w ->
                            if (forward) (w * 0.22f).toInt() else -(w * 0.22f).toInt()
                        } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)) { w ->
                                if (forward) -(w * 0.22f).toInt() else (w * 0.22f).toInt()
                            } + fadeOut(tween(150)))
                    },
                    label = "tablet_slide_transition"
                ) { (targetSongId, targetSectionIdx, _) ->
                    val targetSong = remember(session, targetSongId) {
                        session.findSong(targetSongId) ?: song
                    }
                    TabletSongStripViewer(
                        sessionId = session.sessionId,
                        song = targetSong,
                        sectionIndex = targetSectionIdx,
                        scrollYNorm = animatedScrollY,
                        zoom = animatedZoom,
                        panXNorm = animatedPanX,
                        darkTheme = darkTheme,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Bottom bar showing Previous / Current / Next slide titles
                val struct = song.activeStructure
                if (struct.isNotEmpty()) {
                    val safeIdx = stateSnapshot.sectionIndex.coerceIn(0, struct.size - 1)
                    val prevTitle = if (safeIdx > 0) {
                        song.findRegion(struct[safeIdx - 1].regionId)?.displayLabel
                    } else null
                    val currRegion = song.findRegion(struct[safeIdx].regionId)
                    val currTitle = currRegion?.displayLabel ?: "Slide ${safeIdx + 1}"
                    val nextTitle = if (safeIdx < struct.lastIndex) {
                        song.findRegion(struct[safeIdx + 1].regionId)?.displayLabel
                    } else null

                    val pillBg = if (darkTheme) Color(0xCC121218) else Color(0xDDF4F4F8)
                    val pillBorder = if (darkTheme) Color(0xFF2D2D3A) else Color(0xFFD0D0DC)
                    val mutedText = if (darkTheme) Color(0xFF9AA0B0) else Color(0xFF5C6070)
                    val accent = currRegion?.category?.accentColor ?: Color(0xFFAB47BC)

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .background(pillBg, RoundedCornerShape(20.dp))
                            .border(1.dp, pillBorder, RoundedCornerShape(20.dp))
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = if (prevTitle != null) "◀ $prevTitle" else "◀ Start",
                            color = if (prevTitle != null) mutedText else mutedText.copy(alpha = 0.45f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp)
                        )
                        Text(
                            text = "•",
                            color = mutedText.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                        Text(
                            text = currTitle,
                            color = accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                        Text(
                            text = "•",
                            color = mutedText.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (nextTitle != null) "$nextTitle ▶" else "End ▶",
                            color = if (nextTitle != null) mutedText else mutedText.copy(alpha = 0.45f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp)
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "No songs in session", color = textSecondaryColor, fontSize = 24.sp)
                }
            }
        }

        // Tiny, dim version of the two connection dots in a corner (Section 10 & 11)
        PerformanceConnectionDots(
            status = linkStatus,
            isTabletDimCopy = true,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
        )
    }
}

/**
 * Full-screen stitched strip renderer with GPU-safe tile decoding, smooth zoom/pan/scroll,
 * inverted dark-mode ColorFilter, and fixed-position notes window on the right.
 */
@Composable
private fun TabletSongStripViewer(
    sessionId: String,
    song: PerformanceSong,
    sectionIndex: Int,
    scrollYNorm: Float,
    zoom: Float,
    panXNorm: Float,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tileHeights = song.tileHeightsPx
    val totalStripH = song.stripHeightPx.coerceAtLeast(100)
    val totalStripW = song.stripWidthPx.coerceAtLeast(100)

    // Current section region & accent bar
    val currentRegion = remember(song, sectionIndex) {
        val struct = song.activeStructure
        if (struct.isNotEmpty()) {
            val safeIdx = sectionIndex.coerceIn(0, struct.size - 1)
            song.findRegion(struct[safeIdx].regionId)
        } else {
            song.regions.firstOrNull()
        }
    }

    // Inverted color matrix for dark mode: inverts colors and softens white text to #EBEBEB
    val darkFilterPaint = remember {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            // Invert colors: R' = 235 - R, G' = 235 - G, B' = 235 - B
            val matrix = ColorMatrix(
                floatArrayOf(
                    -0.92f,  0f,     0f,     0f, 235f,
                     0f,    -0.92f,  0f,     0f, 235f,
                     0f,     0f,    -0.92f,  0f, 235f,
                     0f,     0f,     0f,     1f,   0f
                )
            )
            colorFilter = ColorMatrixColorFilter(matrix)
        }
    }

    val normalFilterPaint = remember {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()

        // Strip scaling: fit width by default, scaled by zoom
        val baseScale = screenW / totalStripW.toFloat()
        val effectiveScale = baseScale * zoom
        val scaledStripW = totalStripW * effectiveScale
        val scaledStripH = totalStripH * effectiveScale

        // Crop to the active slide's region bounds
        val regionStartNorm = currentRegion?.yStartNorm ?: 0f
        val regionEndNorm = (currentRegion?.yEndNorm ?: 1f).coerceAtLeast(regionStartNorm + 0.02f)
        val regionTopStripPx = regionStartNorm * totalStripH
        val regionBottomStripPx = regionEndNorm * totalStripH
        val regionStripH = max(1f, regionBottomStripPx - regionTopStripPx)
        val scaledRegionH = regionStripH * effectiveScale

        val maxSlideScrollPx = max(0f, scaledRegionH - screenH)
        val slideScrollPx = (scrollYNorm.coerceIn(0f, 1f) * maxSlideScrollPx)
        val regionScreenTop = if (scaledRegionH <= screenH) {
            (screenH - scaledRegionH) * 0.5f
        } else {
            -slideScrollPx
        }

        val panXPx = panXNorm * (screenW * 0.5f)
        val originX = (screenW - scaledStripW) * 0.5f + panXPx

        // Visible normalized range [yViewTopNorm, yViewBottomNorm] on strip for notes window
        val yViewTopNorm = (regionStartNorm + (max(0f, -regionScreenTop) / scaledStripH)).coerceIn(regionStartNorm, regionEndNorm)
        val yViewBottomNorm = (regionStartNorm + (min(scaledRegionH, screenH - regionScreenTop) / scaledStripH)).coerceIn(regionStartNorm, regionEndNorm)

        // Notes window: filter notes whose line is inside visible viewport
        val visibleNotes = remember(song.notes, yViewTopNorm, yViewBottomNorm, currentRegion) {
            song.notes.filter { note ->
                val reg = song.findRegion(note.regionId)
                if (reg != null) {
                    val line = reg.lines.getOrNull(note.lineIndexInSection)
                    val noteY = line?.yCenterNorm ?: reg.yStartNorm
                    noteY in (yViewTopNorm - 0.02f)..(yViewBottomNorm + 0.02f)
                } else false
            }
        }

        // Draw stitched strip tiles clipped to the active slide region
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { composeCanvas ->
                val canvas = composeCanvas.nativeCanvas
                val activePaint = if (darkTheme) darkFilterPaint else normalFilterPaint

                val clipLeft = max(0f, originX)
                val clipRight = min(screenW, originX + scaledStripW)
                val clipTop = max(0f, regionScreenTop)
                val clipBottom = min(screenH, regionScreenTop + scaledRegionH)

                if (clipRight > clipLeft && clipBottom > clipTop) {
                    canvas.save()
                    canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)

                    var tileTopPx = 0f
                    for (tileIdx in tileHeights.indices) {
                        val tileH = tileHeights[tileIdx].toFloat()
                        val dstTop = regionScreenTop + (tileTopPx - regionTopStripPx) * effectiveScale
                        val dstBottom = dstTop + tileH * effectiveScale

                        // Frustum culling: only decode & draw tiles visible within slide viewport
                        if (dstBottom >= clipTop && dstTop <= clipBottom) {
                            val bmp = PerformanceStorageAndCacheManager.loadTileBitmap(
                                context = context,
                                sessionId = sessionId,
                                songId = song.songId,
                                tileIndex = tileIdx
                            )
                            if (bmp != null && !bmp.isRecycled) {
                                val srcRect = android.graphics.Rect(0, 0, bmp.width, bmp.height)
                                val dstRect = android.graphics.RectF(
                                    originX,
                                    dstTop,
                                    originX + scaledStripW,
                                    dstBottom
                                )
                                canvas.drawBitmap(bmp, srcRect, dstRect, activePaint)
                            }
                        }
                        tileTopPx += tileH
                    }

                    canvas.restore()
                }

                // Section accent bar pinned to left edge of screen (scales in length only)
                if (currentRegion != null) {
                    val visibleBarTop = max(0f, regionScreenTop)
                    val visibleBarBottom = min(screenH, regionScreenTop + scaledRegionH)
                    if (visibleBarBottom > visibleBarTop) {
                        val barColor = currentRegion.category.accentHex.toInt()
                        val barPaint = Paint().apply {
                            color = barColor
                            alpha = if (darkTheme) 220 else 180
                            style = Paint.Style.FILL
                        }
                        val barLeft = 4f
                        val barWidth = (8f * zoom).coerceIn(6f, 14f)
                        val barRect = android.graphics.RectF(
                            barLeft,
                            visibleBarTop,
                            barLeft + barWidth,
                            visibleBarBottom
                        )
                        canvas.drawRoundRect(barRect, 4f, 4f, barPaint)
                    }
                }
            }
        }

        // Section 10: Notes Window on the right side of the screen
        // Fixed screen position independent of zoom/pan, stacks multiple visible notes, fades out when scrolled out
        AnimatedVisibility(
            visible = visibleNotes.isNotEmpty(),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                visibleNotes.forEach { note ->
                    val reg = song.findRegion(note.regionId)
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (darkTheme) Color(0xE625143A) else Color(0xF2F3E5F5)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            if (darkTheme) Color(0xFFAB47BC).copy(alpha = 0.85f) else Color(0xFF8E24AA)
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "NOTE • ${reg?.displayLabel ?: "Section"}",
                                color = if (darkTheme) Color(0xFFE1BEE7) else Color(0xFF6A1B9A),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = note.text,
                                color = if (darkTheme) Color.White else Color(0xFF1E1E24),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 21.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
