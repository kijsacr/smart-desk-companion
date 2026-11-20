package com.smartdesk.companion.tablet.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.BatteryAutomationStatus
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.shared.PcStatsPayload
import com.smartdesk.companion.tablet.ui.touch.GhostTouchConfig
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class PcMonitorThemeSpec(
    val hostTitle: String,
    val currentTime: String,
    val isOffline: Boolean,
    val isNightDim: Boolean,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val tertiaryAccent: Color,
    val batteryAutomationStatus: BatteryAutomationStatus
)

private class PcMetricCardSpec(
    val title: String,
    val subtitle: String,
    val isOffline: Boolean,
    val clampedPercent: Int,
    val isAmberWarning: Boolean,
    val targetFraction: Float,
    val statusLabel: String,
    val primaryColor: Color,
    val badgeAccent: Color,
    val activeMainColor: Color,
    val activeEndColor: Color,
    val isNightDim: Boolean,
    val animationsEnabled: Boolean
) {
    companion object {
        fun create(
            title: String,
            subtitle: String,
            percentValue: Int?,
            amberThreshold: Int,
            primaryColor: Color,
            secondaryColor: Color,
            badgeAccent: Color,
            isNightDim: Boolean,
            animationsEnabled: Boolean
        ): PcMetricCardSpec {
            val isOffline = percentValue == null
            val clampedPercent = (percentValue ?: 0).coerceIn(0, 100)
            val isAmberWarning = !isOffline && clampedPercent >= amberThreshold
            val amberColor = Color(0xFFFFB300)

            val activeMainColor = when {
                isOffline -> Color(0xFF5F6368)
                isAmberWarning -> amberColor
                else -> primaryColor
            }
            val activeEndColor = when {
                isOffline -> Color(0xFF3C4043)
                isAmberWarning -> Color(0xFFFF6D00)
                else -> secondaryColor
            }
            val targetFraction = if (isOffline) 0f else (clampedPercent / 100f).coerceIn(0f, 1f)
            val statusLabel = when {
                isOffline -> "OFFLINE"
                isAmberWarning -> "HIGH LOAD"
                clampedPercent >= 65 -> "MODERATE"
                else -> "OPTIMAL"
            }

            return PcMetricCardSpec(
                title = title,
                subtitle = subtitle,
                isOffline = isOffline,
                clampedPercent = clampedPercent,
                isAmberWarning = isAmberWarning,
                targetFraction = targetFraction,
                statusLabel = statusLabel,
                primaryColor = primaryColor,
                badgeAccent = badgeAccent,
                activeMainColor = activeMainColor,
                activeEndColor = activeEndColor,
                isNightDim = isNightDim,
                animationsEnabled = animationsEnabled
            )
        }
    }
}

/**
 * Full-screen PC Resource Monitor overlay on the Lenovo Yoga Tab 11.
 * - Triggered / toggled ON or OFF from the Phone's "Smart Desk PC Monitor" home-screen widget
 *   (or Phone Settings).
 * - Follows the exact same UI rules as [TabletTimetableOverlay]:
 *   - Pure `#000000` black background, 32dp edge-safe perimeter padding, non-interactive
 *   - All accents, borders, glows, and gauges dynamically adapt to the active [clockTheme]
 *   - Displays giant CPU and RAM circular gauges + horizontal bars with tabular (`tnum`) numerals
 */
@Composable
fun TabletPcResourceOverlay(
    pcStats: PcStatsPayload?,
    clockTheme: ClockTheme,
    batteryAutomationStatus: BatteryAutomationStatus,
    serverTimeOffsetMs: Long
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

    val timeFormatter = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    var currentTime by remember { mutableStateOf(timeFormatter.format(Date())) }
    var tickNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var burnInShift by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            currentTime = timeFormatter.format(now)
            tickNowMs = now.time
            burnInShift = ((now.time / 60_000L) % 5L).toInt() - 2
            delay(1_000L)
        }
    }

    val estimatedServerNow = tickNowMs + serverTimeOffsetMs
    val isOffline = pcStats == null || pcStats.isOffline(estimatedServerNow)

    val primaryAccent = clockTheme.digitColors.getOrElse(0) { Color(0xFF5CB8FF) }
    val secondaryAccent = clockTheme.digitColors.getOrElse(1) { Color(0xFFFFB067) }
    val tertiaryAccent = clockTheme.digitColors.getOrElse(2) { Color(0xFF1E78E6) }
    val quaternaryAccent = clockTheme.digitColors.getOrElse(3) { Color(0xFFFF7A30) }
    val isNightDim = clockTheme.id == "night_dim"

    val hostTitle = when {
        isOffline || pcStats == null -> "PC OFFLINE"
        pcStats.host.isNotBlank() -> "${pcStats.host.uppercase(Locale.US)} • LIVE"
        else -> "LAPTOP • LIVE"
    }

    val themeSpec = PcMonitorThemeSpec(
        hostTitle = hostTitle,
        currentTime = currentTime,
        isOffline = isOffline,
        isNightDim = isNightDim,
        primaryAccent = primaryAccent,
        secondaryAccent = secondaryAccent,
        tertiaryAccent = tertiaryAccent,
        batteryAutomationStatus = batteryAutomationStatus
    )

    val cpuSpec = PcMetricCardSpec.create(
        title = "CPU",
        subtitle = "PROCESSOR LOAD",
        percentValue = if (isOffline || pcStats == null) null else pcStats.cpu,
        amberThreshold = AppConfigConstants.PC_CPU_AMBER_THRESHOLD,
        primaryColor = primaryAccent,
        secondaryColor = tertiaryAccent,
        badgeAccent = secondaryAccent,
        isNightDim = isNightDim,
        animationsEnabled = animationsEnabled
    )

    val ramSpec = PcMetricCardSpec.create(
        title = "RAM",
        subtitle = "MEMORY USAGE",
        percentValue = if (isOffline || pcStats == null) null else pcStats.ram,
        amberThreshold = AppConfigConstants.PC_RAM_AMBER_THRESHOLD,
        primaryColor = secondaryAccent,
        secondaryColor = quaternaryAccent,
        badgeAccent = primaryAccent,
        isNightDim = isNightDim,
        animationsEnabled = animationsEnabled
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
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
                .offset { IntOffset(x = burnInShift * 2, y = burnInShift) }
                .padding(
                    start = GhostTouchConfig.EDGE_SAFE_INSET,
                    end = GhostTouchConfig.EDGE_SAFE_INSET,
                    top = 20.dp,
                    bottom = 28.dp
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PcMonitorHeaderRow(spec = themeSpec)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                PcMetricGaugeCard(
                    spec = cpuSpec,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                PcMetricGaugeCard(
                    spec = ramSpec,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        PcMonitorBottomAccentBar(
            spec = themeSpec,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

@Composable
private fun PcMonitorHeaderRow(spec: PcMonitorThemeSpec) {
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
            val pillAlpha = if (spec.isNightDim) 0.14f else 0.18f
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(spec.primaryAccent.copy(alpha = pillAlpha))
                    .border(
                        width = 1.dp,
                        color = spec.primaryAccent.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val dotColor = if (spec.isOffline) Color(0xFF70757A) else spec.secondaryAccent
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = "PC RESOURCE MONITOR",
                    color = spec.primaryAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp
                )
            }

            val hostColor = when {
                spec.isOffline -> Color(0xFF70757A)
                spec.isNightDim -> Color(0xFF9E4747)
                else -> Color(0xFF9AA0A6)
            }
            Text(
                text = spec.hostTitle,
                color = hostColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val timeColor = if (spec.isNightDim) Color(0xFFD96B6B) else Color(0xFFE8EAED)
            Text(
                text = spec.currentTime,
                color = timeColor,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"
                )
            )
            TabletBatteryIndicator(automationStatus = spec.batteryAutomationStatus)
        }
    }
}

@Composable
private fun PcMonitorBottomAccentBar(
    spec: PcMonitorThemeSpec,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GhostTouchConfig.EDGE_SAFE_INSET, vertical = 8.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF1E2025))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(spec.primaryAccent, spec.secondaryAccent, spec.tertiaryAccent)
                    )
                )
        )
    }
}

@Composable
private fun PcMetricGaugeCard(
    spec: PcMetricCardSpec,
    modifier: Modifier = Modifier
) {
    val animatedFraction by animateFloatAsState(
        targetValue = spec.targetFraction,
        animationSpec = if (spec.animationsEnabled) {
            tween(durationMillis = 450, easing = FastOutSlowInEasing)
        } else {
            tween(durationMillis = 0)
        },
        label = "${spec.title}GaugeFraction"
    )

    val cardShape = RoundedCornerShape(24.dp)
    val cardFill = spec.primaryColor.copy(alpha = if (spec.isNightDim) 0.08f else 0.11f)
    val cardBorder = spec.activeMainColor.copy(alpha = if (spec.isOffline) 0.35f else 0.72f)
    val glowAlpha = if (spec.isNightDim) 0.10f else 0.16f

    Column(
        modifier = modifier
            .drawBehind {
                drawRoundRect(
                    color = spec.activeMainColor.copy(alpha = glowAlpha),
                    cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()),
                    size = size.copy(
                        width = size.width + 6.dp.toPx(),
                        height = size.height + 6.dp.toPx()
                    ),
                    topLeft = Offset(-3.dp.toPx(), -3.dp.toPx())
                )
            }
            .clip(cardShape)
            .background(Color(0xFF0E1014))
            .background(cardFill)
            .border(1.5.dp, cardBorder, cardShape)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PcMetricCardTopBar(spec = spec)

        PcMetricCenterGauge(
            spec = spec,
            animatedFraction = animatedFraction,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        PcMetricBottomProgressBar(
            spec = spec,
            animatedFraction = animatedFraction
        )
    }
}

@Composable
private fun PcMetricCardTopBar(spec: PcMetricCardSpec) {
    val amberColor = Color(0xFFFFB300)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(spec.activeMainColor.copy(alpha = 0.22f))
                    .border(1.dp, spec.activeMainColor.copy(alpha = 0.60f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = spec.title,
                    color = spec.activeMainColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }

            val subColor = if (spec.isNightDim) Color(0xFFB85C5C) else Color(0xFFBDC1C6)
            Text(
                text = spec.subtitle,
                color = subColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp
            )
        }

        val pillBorderColor = if (spec.isAmberWarning) amberColor.copy(alpha = 0.7f) else Color(0xFF282B34)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF171920))
                .border(1.dp, pillBorderColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val dotColor = when {
                spec.isOffline -> Color(0xFF5F6368)
                spec.isAmberWarning -> amberColor
                else -> spec.badgeAccent
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            val labelColor = when {
                spec.isOffline -> Color(0xFF70757A)
                spec.isAmberWarning -> amberColor
                spec.isNightDim -> Color(0xFFD97777)
                else -> Color(0xFFE8EAED)
            }
            Text(
                text = spec.statusLabel,
                color = labelColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.7.sp
            )
        }
    }
}

@Composable
private fun PcMetricCenterGauge(
    spec: PcMetricCardSpec,
    animatedFraction: Float,
    modifier: Modifier = Modifier
) {
    val amberColor = Color(0xFFFFB300)
    val glowAlpha = if (spec.isNightDim) 0.18f else 0.25f

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .fillMaxWidth(0.78f)
        ) {
            val diameter = size.minDimension
            if (diameter > 8f) {
                val strokeWidth = (diameter * 0.115f).coerceIn(14.dp.toPx(), 28.dp.toPx())
                val arcSize = (diameter - strokeWidth).coerceAtLeast(4f)
                val topLeft = Offset(
                    x = (size.width - arcSize) / 2f,
                    y = (size.height - arcSize) / 2f
                )
                val arcRectSize = Size(arcSize, arcSize)

                val startAngle = 135f
                val totalSweep = 270f

                drawArc(
                    color = Color(0xFF1A1D24),
                    startAngle = startAngle,
                    sweepAngle = totalSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcRectSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                if (animatedFraction > 0.005f) {
                    val activeSweep = (totalSweep * animatedFraction).coerceIn(2f, totalSweep)
                    drawArc(
                        color = spec.activeMainColor.copy(alpha = glowAlpha),
                        startAngle = startAngle,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcRectSize,
                        style = Stroke(width = strokeWidth * 1.45f, cap = StrokeCap.Round)
                    )

                    drawArc(
                        brush = Brush.linearGradient(
                            colors = listOf(spec.activeMainColor, spec.activeEndColor),
                            start = Offset(topLeft.x, topLeft.y + arcSize),
                            end = Offset(topLeft.x + arcSize, topLeft.y)
                        ),
                        startAngle = startAngle,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcRectSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }
        }

        PcMetricCenterReadout(spec = spec)
    }
}

@Composable
private fun PcMetricCenterReadout(spec: PcMetricCardSpec) {
    val amberColor = Color(0xFFFFB300)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val numberColor = when {
            spec.isOffline -> Color(0xFF5F6368)
            spec.isAmberWarning -> amberColor
            spec.isNightDim -> spec.primaryColor
            else -> Color.White
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (spec.isOffline) "—" else "${spec.clampedPercent}",
                color = numberColor,
                style = TextStyle(
                    fontSize = 74.sp,
                    fontWeight = FontWeight.Black,
                    fontFeatureSettings = "tnum",
                    letterSpacing = (-1.5).sp
                )
            )
            if (!spec.isOffline) {
                Text(
                    text = "%",
                    color = spec.activeMainColor,
                    style = TextStyle(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFeatureSettings = "tnum"
                    ),
                    modifier = Modifier.padding(bottom = 12.dp, start = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        val captionColor = if (spec.isNightDim) Color(0xFF9E4747) else Color(0xFF8E9299)
        Text(
            text = if (spec.isOffline) "WAITING FOR PC AGENT" else "${spec.title} UTILIZATION",
            color = captionColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp
        )
    }
}

@Composable
private fun PcMetricBottomProgressBar(
    spec: PcMetricCardSpec,
    animatedFraction: Float
) {
    val labelStyle = remember {
        TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = "tnum"
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "0%", color = Color(0xFF5F6368), style = labelStyle)
            Text(text = "50%", color = Color(0xFF5F6368), style = labelStyle)
            Text(text = "100%", color = Color(0xFF5F6368), style = labelStyle)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF181B22))
                .border(1.dp, Color(0xFF252832), RoundedCornerShape(5.dp))
        ) {
            if (animatedFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedFraction)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(spec.activeMainColor, spec.activeEndColor)
                            )
                        )
                )
            }
        }
    }
}
