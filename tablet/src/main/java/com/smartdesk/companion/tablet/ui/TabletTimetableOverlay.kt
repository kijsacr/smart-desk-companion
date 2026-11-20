package com.smartdesk.companion.tablet.ui

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.shared.DayScheduleRow
import com.smartdesk.companion.shared.SubjectCatalog
import com.smartdesk.companion.shared.TimetableData
import com.smartdesk.companion.shared.TimetableMode
import com.smartdesk.companion.shared.TimetableSlot
import com.smartdesk.companion.tablet.sync.ActiveTabletOverlay
import com.smartdesk.companion.tablet.ui.touch.GhostTouchConfig
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * V5 Addendum (Section 14 & Section G):
 * Full-screen 20-second Timetable overlay on the Lenovo Yoga Tab 11.
 * - Renders on top of the existing dashboard without tearing down background state.
 * - Zero touch interaction (blocks/ignores all touches).
 * - Scales up the highlighted day row (`MON`..`SAT`) with `zIndex(2f)` and theme-colored
 *   accent border/glow while dimming non-highlighted rows to `0.55f`.
 * - In `FULL_WEEK` mode, all 6 rows render at normal scale and 100% opacity.
 * - Displays a thin shrinking progress bar along the bottom edge over the 20-second window.
 */
@Composable
fun TabletTimetableOverlay(
    overlayState: ActiveTabletOverlay.Timetable,
    clockTheme: ClockTheme
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

    val remainingFraction = overlayState.remainingFraction
    val remainingSeconds = overlayState.remainingMs / 1000f

    val primaryAccent = clockTheme.digitColors.getOrElse(0) { Color(0xFF5CB8FF) }
    val secondaryAccent = clockTheme.digitColors.getOrElse(1) { Color(0xFFFFB067) }
    val isNightDim = clockTheme.id == "night_dim"

    val decision = overlayState.decision
    val isFullWeek = decision.mode == TimetableMode.FULL_WEEK || decision.highlightDay == null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            // Absorb all pointer input so the overlay is strictly non-interactive
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = GhostTouchConfig.EDGE_SAFE_INSET,
                    end = GhostTouchConfig.EDGE_SAFE_INSET,
                    top = 20.dp,
                    bottom = 26.dp
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Top Header Bar: Mode Caption ("Today, Fri" / "Tomorrow, Sat" / "This week") + Semester Title + Countdown
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Theme-tinted mode pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(primaryAccent.copy(alpha = if (isNightDim) 0.14f else 0.18f))
                            .border(
                                width = 1.dp,
                                color = primaryAccent.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(secondaryAccent)
                        )
                        Text(
                            text = decision.toTabletOverlayTitle(),
                            color = primaryAccent,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.3.sp
                        )
                    }

                    Text(
                        text = TimetableData.SEMESTER_TITLE,
                        color = if (isNightDim) Color(0xFF9E4747) else Color(0xFF9AA0A6),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                }

                // Subtle countdown indicator on the top-right
                Text(
                    text = "${ceil(remainingSeconds).toInt().coerceAtLeast(1)}s",
                    color = if (isNightDim) Color(0xFF8C3B3B) else Color(0xFF70757A),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 2. Period Header Row (P1..P4, slim Lunch column, P5..P6)
            TimetablePeriodHeaderRow(isNightDim = isNightDim)

            // 3. Weekly Grid Rows (MON..SAT)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimetableData.weeklySchedule.forEach { dayRow ->
                    val isHighlighted = !isFullWeek && dayRow.dayOfWeek == decision.highlightDay

                    val targetScaleY = if (isHighlighted) AppConfigConstants.TIMETABLE_HIGHLIGHT_SCALE else 1.0f
                    val targetScaleX = if (isHighlighted) 1.025f else 1.0f
                    val targetAlpha = when {
                        isFullWeek -> 1.0f
                        isHighlighted -> 1.0f
                        else -> AppConfigConstants.TIMETABLE_DIM_ALPHA
                    }

                    val animatedScaleY by animateFloatAsState(
                        targetValue = targetScaleY,
                        animationSpec = if (animationsEnabled) {
                            tween(durationMillis = 200, easing = FastOutSlowInEasing)
                        } else {
                            tween(durationMillis = 0)
                        },
                        label = "rowScaleY"
                    )
                    val animatedScaleX by animateFloatAsState(
                        targetValue = targetScaleX,
                        animationSpec = if (animationsEnabled) {
                            tween(durationMillis = 200, easing = FastOutSlowInEasing)
                        } else {
                            tween(durationMillis = 0)
                        },
                        label = "rowScaleX"
                    )

                    TimetableDayRowItem(
                        dayRow = dayRow,
                        isHighlighted = isHighlighted,
                        isNightDim = isNightDim,
                        primaryAccent = primaryAccent,
                        secondaryAccent = secondaryAccent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .zIndex(if (isHighlighted) 2f else 0f)
                            .graphicsLayer {
                                scaleX = animatedScaleX
                                scaleY = animatedScaleY
                                alpha = targetAlpha
                            }
                    )
                }
            }
        }

        // 4. Thin shrinking progress line along the bottom edge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = GhostTouchConfig.EDGE_SAFE_INSET, vertical = 8.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1E2025))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(remainingFraction)
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
private fun TimetablePeriodHeaderRow(isNightDim: Boolean) {
    val labelColor = if (isNightDim) Color(0xFFB85C5C) else Color(0xFFBDC1C6)
    val timeColor = if (isNightDim) Color(0xFF7D3838) else Color(0xFF70757A)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Day column header spacer
        Box(
            modifier = Modifier.width(64.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "DAY",
                color = timeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        // Morning periods P1..P4
        Row(
            modifier = Modifier.weight(4f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimetableData.periodHeaders.take(4).forEach { header ->
                PeriodHeaderCell(
                    code = header.code,
                    timeRange = header.timeRange,
                    labelColor = labelColor,
                    timeColor = timeColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Slim Lunch break column header (13:00–14:00)
        Box(
            modifier = Modifier.width(34.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "13–14",
                color = timeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        // Afternoon periods P5..P6
        Row(
            modifier = Modifier.weight(2f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimetableData.periodHeaders.takeLast(2).forEach { header ->
                PeriodHeaderCell(
                    code = header.code,
                    timeRange = header.timeRange,
                    labelColor = labelColor,
                    timeColor = timeColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PeriodHeaderCell(
    code: String,
    timeRange: String,
    labelColor: Color,
    timeColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF121317), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF23252C), RoundedCornerShape(10.dp))
            .padding(vertical = 4.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = code,
            color = labelColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = timeRange,
            color = timeColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun TimetableDayRowItem(
    dayRow: DayScheduleRow,
    isHighlighted: Boolean,
    isNightDim: Boolean,
    primaryAccent: Color,
    secondaryAccent: Color,
    modifier: Modifier = Modifier
) {
    val rowShape = RoundedCornerShape(16.dp)
    val rowFillColor = when {
        isHighlighted -> primaryAccent.copy(alpha = if (isNightDim) 0.12f else 0.15f)
        else -> Color(0xFF0E1014)
    }
    val rowBorderColor = when {
        isHighlighted -> primaryAccent.copy(alpha = 0.78f)
        else -> Color(0xFF1F2229)
    }
    val rowBorderWidth = if (isHighlighted) 1.6.dp else 1.dp

    Row(
        modifier = modifier
            .then(
                if (isHighlighted) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = primaryAccent.copy(alpha = if (isNightDim) 0.14f else 0.22f),
                            cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                            size = size.copy(
                                width = size.width + 6.dp.toPx(),
                                height = size.height + 6.dp.toPx()
                            ),
                            topLeft = androidx.compose.ui.geometry.Offset(-3.dp.toPx(), -3.dp.toPx())
                        )
                    }
                } else {
                    Modifier
                }
            )
            .clip(rowShape)
            .background(rowFillColor)
            .border(rowBorderWidth, rowBorderColor, rowShape)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left Day Code Badge ("MON".."SAT")
        Box(
            modifier = Modifier
                .width(64.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isHighlighted) {
                        primaryAccent.copy(alpha = 0.22f)
                    } else {
                        Color(0xFF17191F)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (isHighlighted) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(secondaryAccent)
                    )
                }
                Text(
                    text = dayRow.shortCode,
                    color = when {
                        isHighlighted -> secondaryAccent
                        isNightDim -> Color(0xFFD96B6B)
                        else -> Color(0xFFE8EAED)
                    },
                    fontSize = if (isHighlighted) 16.sp else 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.6.sp
                )
            }
        }

        // Morning Slots (P1..P4, total weight = 4f)
        Row(
            modifier = Modifier
                .weight(4f)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            dayRow.morningSlots.forEach { slot ->
                TimetableSlotCard(
                    slot = slot,
                    isRowHighlighted = isHighlighted,
                    isNightDim = isNightDim,
                    primaryAccent = primaryAccent,
                    secondaryAccent = secondaryAccent,
                    modifier = Modifier
                        .weight(slot.periodSpan.toFloat())
                        .fillMaxHeight()
                )
            }
        }

        // Slim Lunch Break Divider Column (13:00–14:00)
        Box(
            modifier = Modifier
                .width(34.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF13151A)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight(0.55f)
                    .background(
                        if (isHighlighted) primaryAccent.copy(alpha = 0.45f) else Color(0xFF282B33),
                        RoundedCornerShape(1.dp)
                    )
            )
        }

        // Afternoon Slots (P5..P6, total weight = 2f)
        Row(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            dayRow.afternoonSlots.forEach { slot ->
                TimetableSlotCard(
                    slot = slot,
                    isRowHighlighted = isHighlighted,
                    isNightDim = isNightDim,
                    primaryAccent = primaryAccent,
                    secondaryAccent = secondaryAccent,
                    modifier = Modifier
                        .weight(slot.periodSpan.toFloat())
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun TimetableSlotCard(
    slot: TimetableSlot,
    isRowHighlighted: Boolean,
    isNightDim: Boolean,
    primaryAccent: Color,
    secondaryAccent: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val title = slot.title

    if (title == null) {
        // Empty slot (Saturday P5, P6)
        Box(
            modifier = modifier
                .clip(shape)
                .background(Color(0xFF0B0C0F).copy(alpha = 0.45f))
                .border(1.dp, Color(0xFF1B1D24).copy(alpha = 0.6f), shape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "—",
                color = Color(0xFF3C4043),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        return
    }

    val subject = remember(slot.subjectId) {
        slot.subjectId?.let { SubjectCatalog.findById(it) }
    }

    val cellFill = when {
        isRowHighlighted && slot.isLab -> primaryAccent.copy(alpha = 0.24f)
        isRowHighlighted -> Color(0xFF1D222B).copy(alpha = 0.92f)
        slot.isLab -> Color(0xFF1A1E26)
        else -> Color(0xFF171920)
    }

    val cellBorder = when {
        isRowHighlighted && slot.isLab -> secondaryAccent.copy(alpha = 0.72f)
        isRowHighlighted -> primaryAccent.copy(alpha = 0.48f)
        slot.isLab -> Color(0xFF333846)
        else -> Color(0xFF262932)
    }

    val textColor = when {
        isNightDim && isRowHighlighted -> Color(0xFFFFB4B4)
        isNightDim -> Color(0xFFD97777)
        else -> Color.White
    }

    val iconTint = when {
        isRowHighlighted -> secondaryAccent
        isNightDim -> Color(0xFFB85C5C)
        else -> primaryAccent.copy(alpha = 0.85f)
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(cellFill)
            .border(1.dp, cellBorder, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (slot.periodSpan >= 2) {
            Arrangement.Center
        } else {
            Arrangement.Start
        }
    ) {
        if (subject != null) {
            Icon(
                imageVector = subject.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(if (isRowHighlighted) 18.dp else 16.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
        }

        Text(
            text = title,
            color = textColor,
            fontSize = if (isRowHighlighted) 14.sp else 13.sp,
            fontWeight = if (isRowHighlighted || slot.isLab) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
            textAlign = if (slot.periodSpan >= 2) TextAlign.Center else TextAlign.Start
        )
    }
}
