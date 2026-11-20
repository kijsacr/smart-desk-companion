package com.smartdesk.companion.tablet.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.shared.BatteryAutomationStatus

@Immutable
private data class TabletBatteryStatus(
    val percentage: Int = 100,
    val isCharging: Boolean = false
)

/**
 * Module H & V6 Section 2.5: Tablet Battery Indicator
 * Placed immediately to the right of the top-left settings gear, vertically centered.
 * - Shows charging bolt when charging and red low-battery state at <= 15%.
 * - V6: Shows a tiny muted plug-off glyph next to the percentage when Smart Battery Protection
 *   is actively `holding` (power cut by automation), or in amber (`#FFB300`) when in `error`.
 */
@Composable
fun TabletBatteryIndicator(
    automationStatus: BatteryAutomationStatus = BatteryAutomationStatus(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var batteryStatus by remember {
        mutableStateOf(readStickyBatteryStatus(context))
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent != null) {
                    batteryStatus = parseBatteryIntent(intent)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val initialIntent = runCatching { context.registerReceiver(receiver, filter) }.getOrNull()
        if (initialIntent != null) {
            batteryStatus = parseBatteryIntent(initialIntent)
        }

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val isLowBattery = batteryStatus.percentage <= 15 && !batteryStatus.isCharging
    val tintColor = when {
        isLowBattery -> Color(0xFFFF5252)
        batteryStatus.isCharging -> Color(0xFF34D399)
        else -> Color(0xFF8E9299)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Compact battery glyph drawn cleanly on Canvas
        Canvas(modifier = Modifier.size(width = 22.dp, height = 12.dp)) {
            val strokeW = 1.4.dp.toPx()
            val nubWidth = 2.dp.toPx()
            val bodyWidth = size.width - nubWidth - strokeW
            val bodyHeight = size.height - strokeW

            // Outer battery casing
            drawRoundRect(
                color = tintColor,
                topLeft = Offset(strokeW / 2f, strokeW / 2f),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
                style = Stroke(width = strokeW)
            )

            // Positive terminal nub on the right
            val nubHeight = bodyHeight * 0.45f
            drawRoundRect(
                color = tintColor,
                topLeft = Offset(strokeW / 2f + bodyWidth + 0.8.dp.toPx(), (size.height - nubHeight) / 2f),
                size = Size(nubWidth, nubHeight),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
            )

            // Inner fill proportional to battery percentage
            val inset = 2.6.dp.toPx()
            val maxFillWidth = (bodyWidth - inset * 2f).coerceAtLeast(0f)
            val fillFraction = (batteryStatus.percentage / 100f).coerceIn(0.05f, 1f)
            drawRoundRect(
                color = tintColor,
                topLeft = Offset(strokeW / 2f + inset, strokeW / 2f + inset),
                size = Size(maxFillWidth * fillFraction, (bodyHeight - inset * 2f).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx())
            )
        }

        if (batteryStatus.isCharging) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = "Charging",
                tint = tintColor,
                modifier = Modifier.size(14.dp)
            )
        }

        Text(
            text = "${batteryStatus.percentage}%",
            color = tintColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )

        // V6 Section 2.5: Tiny plug-off glyph when holding (muted) or error (amber)
        if (automationStatus.isError || automationStatus.isHolding) {
            val plugOffTint = if (automationStatus.isError) {
                Color(0xFFFFB300) // Amber on error
            } else {
                Color(0xFF8E9299) // Muted while holding (power cut at 80%)
            }
            Icon(
                imageVector = Icons.Default.PowerOff,
                contentDescription = if (automationStatus.isError) {
                    "Battery protection error"
                } else {
                    "Battery protection holding"
                },
                tint = plugOffTint,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun readStickyBatteryStatus(context: Context): TabletBatteryStatus {
    val intent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    return if (intent != null) parseBatteryIntent(intent) else TabletBatteryStatus()
}

private fun parseBatteryIntent(intent: Intent): TabletBatteryStatus {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val pct = if (level >= 0 && scale > 0) {
        ((level.toFloat() / scale.toFloat()) * 100f).toInt().coerceIn(0, 100)
    } else {
        100
    }

    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL ||
        plugged != 0

    return TabletBatteryStatus(
        percentage = pct,
        isCharging = isCharging
    )
}
