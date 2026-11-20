package com.smartdesk.companion.tablet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.tablet.sync.ActiveTabletOverlay
import com.smartdesk.companion.tablet.ui.touch.GhostTouchConfig

/**
 * V7 Section 4.1: 20-Second Full-Screen OTP Takeover Overlay (Priority 1).
 * - Shows the source name large at the top (e.g., `Swiggy`, `Microsoft`, `Google`)
 * - Shows the OTP in very large digits below it, grouped for readability (`482 913`),
 *   in the active clock theme accent colour on pure black.
 * - Nothing else: no message body, no timestamps.
 * - Ignores all touch input.
 */
@Composable
fun TabletOtpOverlay(
    otpOverlay: ActiveTabletOverlay.Otp,
    clockTheme: ClockTheme
) {
    val accentColor = clockTheme.digitColors.getOrElse(0) { Color(0xFF5CB8FF) }
    val secondaryAccent = clockTheme.digitColors.getOrElse(1) { Color(0xFFFFB067) }

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
            .padding(
                start = GhostTouchConfig.EDGE_SAFE_INSET,
                end = GhostTouchConfig.EDGE_SAFE_INSET,
                top = 32.dp,
                bottom = 32.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0E1014), RoundedCornerShape(32.dp))
                .border(2.dp, accentColor.copy(alpha = 0.65f), RoundedCornerShape(32.dp))
                .padding(horizontal = 40.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = otpOverlay.source,
                color = secondaryAccent,
                fontSize = 46.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = otpOverlay.formattedCode,
                color = accentColor,
                style = TextStyle(
                    fontSize = 116.sp,
                    fontWeight = FontWeight.Black,
                    fontFeatureSettings = "tnum",
                    letterSpacing = 6.sp,
                    textAlign = TextAlign.Center
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * V7 Section 5.2: Low (<= 20%, 10s) and Critical (<= 10%, persistent) Phone Battery Overlays.
 * - Low: amber (`#FFB300`), large battery glyph, percentage, and `Phone battery low`.
 * - Critical: red (`#FF3B30`), large battery glyph, percentage, `Phone battery critical`,
 *   and `Plug in your phone`. Subject to pixel shift to avoid burn-in.
 */
@Composable
fun TabletPhoneBatteryAlertOverlay(
    level: Int,
    isCritical: Boolean,
    pixelShiftOffsetDp: Pair<Int, Int>
) {
    val alertColor = if (isCritical) Color(0xFFFF3B30) else Color(0xFFFFB300)
    val clampedLevel = level.coerceIn(0, 100)
    val shiftX = if (isCritical) pixelShiftOffsetDp.first else 0
    val shiftY = if (isCritical) pixelShiftOffsetDp.second else 0

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
            .padding(
                start = GhostTouchConfig.EDGE_SAFE_INSET + 4.dp,
                end = GhostTouchConfig.EDGE_SAFE_INSET + 4.dp,
                top = 32.dp,
                bottom = 32.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .offset { IntOffset(x = shiftX.dp.roundToPx(), y = shiftY.dp.roundToPx()) }
                .fillMaxWidth(0.84f)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFF101115))
                .border(2.dp, alertColor.copy(alpha = 0.78f), RoundedCornerShape(32.dp))
                .padding(horizontal = 40.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                LargePhoneBatteryGlyph(
                    level = clampedLevel,
                    isCritical = isCritical
                )

                Text(
                    text = "${clampedLevel}%",
                    color = alertColor,
                    style = TextStyle(
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Black,
                        fontFeatureSettings = "tnum",
                        letterSpacing = (-2).sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = if (isCritical) "Phone battery critical" else "Phone battery low",
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            if (isCritical) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Plug in your phone",
                    color = alertColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun LargePhoneBatteryGlyph(
    level: Int,
    isCritical: Boolean
) {
    val strokeAndFillColor = if (isCritical) Color(0xFFFF3B30) else Color(0xFFFFB300)
    val fraction = (level.coerceIn(0, 100) / 100f).coerceIn(0.06f, 1f)

    Canvas(modifier = Modifier.size(width = 96.dp, height = 52.dp)) {
        val capWidth = 7.dp.toPx()
        val capHeight = 20.dp.toPx()
        val bodyWidth = size.width - capWidth - 4.dp.toPx()
        val bodyHeight = size.height
        val strokeW = 4.dp.toPx()
        val corner = 10.dp.toPx()

        // Outer battery body
        drawRoundRect(
            color = strokeAndFillColor,
            topLeft = Offset(0f, 0f),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = strokeW)
        )

        // Positive terminal nub on right
        drawRoundRect(
            color = strokeAndFillColor,
            topLeft = Offset(bodyWidth + 3.dp.toPx(), (bodyHeight - capHeight) / 2f),
            size = Size(capWidth, capHeight),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        )

        // Inner fill bar
        val inset = 7.dp.toPx()
        val maxFillWidth = (bodyWidth - inset * 2f).coerceAtLeast(4f)
        val fillWidth = (maxFillWidth * fraction).coerceAtLeast(4f)
        val fillHeight = (bodyHeight - inset * 2f).coerceAtLeast(4f)

        drawRoundRect(
            color = strokeAndFillColor,
            topLeft = Offset(inset, inset),
            size = Size(fillWidth, fillHeight),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
        )
    }
}
