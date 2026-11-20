package com.smartdesk.companion.tablet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.PcStatsPayload
import com.smartdesk.companion.shared.PhoneBatteryPayload
import kotlinx.coroutines.delay

/**
 * V7 Section 5.3 & 5.4: Combined Top Status Strip at the top-right corner.
 * Displays: `[phone] 82% | CPU 14% | RAM 48% | 52°C`
 * - Phone battery segment:
 *   - Small phone glyph + percentage, a bolt when charging, amber at <= 20% (not charging),
 *     red at <= 10% (not charging), and muted `--` when stale (> 10 minutes).
 * - PC segment (shown when [pcMonitorEnabled] is true):
 *   - `PC offline` when stale (> 45 seconds) or null
 *   - `CPU 14% | RAM 48%` (+ `| 52°C` when `temp != null`), with amber warnings at >= 90% CPU/RAM or >= 80°C.
 */
@Composable
fun TabletTopStatusStrip(
    phoneBattery: PhoneBatteryPayload?,
    pcStats: PcStatsPayload?,
    pcMonitorEnabled: Boolean,
    serverTimeOffsetMs: Long,
    modifier: Modifier = Modifier
) {
    var tickNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            tickNowMs = System.currentTimeMillis()
        }
    }

    val estimatedServerNow = tickNowMs + serverTimeOffsetMs
    val isPhoneStale = phoneBattery == null || phoneBattery.isStale(estimatedServerNow)
    val isPcOffline = pcStats == null || pcStats.isOffline(estimatedServerNow)

    val normalColor = Color.White.copy(alpha = 0.68f)
    val separatorColor = Color.White.copy(alpha = 0.28f)
    val amberWarningColor = Color(0xFFFFB300)
    val redCriticalColor = Color(0xFFFF3B30)
    val offlineColor = Color(0xFF70757A)

    val tabularStyle = remember {
        TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = "tnum"
        )
    }

    val phoneColor = when {
        isPhoneStale || phoneBattery == null -> offlineColor
        !phoneBattery.charging && phoneBattery.level <= AppConfigConstants.PHONE_BATTERY_CRITICAL_THRESHOLD -> redCriticalColor
        !phoneBattery.charging && phoneBattery.level <= AppConfigConstants.PHONE_BATTERY_LOW_THRESHOLD -> amberWarningColor
        else -> normalColor
    }

    val phoneText = if (isPhoneStale || phoneBattery == null) {
        "--"
    } else {
        "${phoneBattery.level}%"
    }

    Row(
        modifier = modifier
            .background(Color(0xFF18191E).copy(alpha = 0.90f), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF2B2D35), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Phone Battery Segment: [phone] 82% (+ charging bolt)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SmallPhoneGlyph(
                isStale = isPhoneStale,
                isCharging = !isPhoneStale && phoneBattery?.charging == true,
                level = phoneBattery?.level ?: 0
            )

            Text(
                text = phoneText,
                color = phoneColor,
                style = tabularStyle
            )
        }

        // 2. PC Stats Segment (hidden when pcMonitorEnabled is off)
        if (pcMonitorEnabled) {
            Text(
                text = "|",
                color = separatorColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )

            if (isPcOffline || pcStats == null) {
                Text(
                    text = "PC offline",
                    color = offlineColor,
                    style = tabularStyle
                )
            } else {
                val cpuColor = if (pcStats.cpu >= AppConfigConstants.PC_CPU_AMBER_THRESHOLD) {
                    amberWarningColor
                } else {
                    normalColor
                }
                val ramColor = if (pcStats.ram >= AppConfigConstants.PC_RAM_AMBER_THRESHOLD) {
                    amberWarningColor
                } else {
                    normalColor
                }

                Text(
                    text = "CPU ${pcStats.cpu}%",
                    color = cpuColor,
                    style = tabularStyle
                )

                Text(
                    text = "|",
                    color = separatorColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )

                Text(
                    text = "RAM ${pcStats.ram}%",
                    color = ramColor,
                    style = tabularStyle
                )

                val tempVal = pcStats.temp
                if (tempVal != null) {
                    val tempColor = if (tempVal >= AppConfigConstants.PC_TEMP_AMBER_THRESHOLD) {
                        amberWarningColor
                    } else {
                        normalColor
                    }

                    Text(
                        text = "|",
                        color = separatorColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )

                    Text(
                        text = "${tempVal}°C",
                        color = tempColor,
                        style = tabularStyle
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallPhoneGlyph(
    isStale: Boolean,
    isCharging: Boolean,
    level: Int
) {
    val glyphColor = when {
        isStale -> Color(0xFF70757A)
        !isCharging && level <= AppConfigConstants.PHONE_BATTERY_CRITICAL_THRESHOLD -> Color(0xFFFF3B30)
        !isCharging && level <= AppConfigConstants.PHONE_BATTERY_LOW_THRESHOLD -> Color(0xFFFFB300)
        else -> Color.White.copy(alpha = 0.72f)
    }

    val boltWidthDp = if (isCharging) 20.dp else 12.dp

    Canvas(modifier = Modifier.size(width = boltWidthDp, height = 16.dp)) {
        val phoneW = 9.5.dp.toPx()
        val phoneH = 15.dp.toPx()
        val topY = (size.height - phoneH) / 2f

        // Phone handset outline
        drawRoundRect(
            color = glyphColor,
            topLeft = Offset(0.5.dp.toPx(), topY),
            size = Size(phoneW, phoneH),
            cornerRadius = CornerRadius(2.2.dp.toPx(), 2.2.dp.toPx()),
            style = Stroke(width = 1.4.dp.toPx())
        )

        // Bottom home bar dot inside phone
        drawCircle(
            color = glyphColor,
            radius = 0.9.dp.toPx(),
            center = Offset(0.5.dp.toPx() + phoneW / 2f, topY + phoneH - 2.3.dp.toPx())
        )

        // Small charging bolt next to the phone glyph when charging
        if (isCharging) {
            val bx = phoneW + 2.5.dp.toPx()
            val by = topY + 1.5.dp.toPx()
            val bh = phoneH - 3.dp.toPx()
            val bw = 6.dp.toPx()
            val boltPath = Path().apply {
                moveTo(bx + bw * 0.62f, by)
                lineTo(bx + bw * 0.08f, by + bh * 0.56f)
                lineTo(bx + bw * 0.48f, by + bh * 0.56f)
                lineTo(bx + bw * 0.32f, by + bh)
                lineTo(bx + bw * 0.92f, by + bh * 0.44f)
                lineTo(bx + bw * 0.52f, by + bh * 0.44f)
                close()
            }
            drawPath(path = boltPath, color = Color(0xFF34D399))
        }
    }
}
