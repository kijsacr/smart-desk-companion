package com.smartdesk.companion.tablet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.smartdesk.companion.shared.PerformanceLinkStatus

/**
 * V9 Section 11 & Section 10: Connection status indicators.
 * - Blue dot = Bluetooth LE direct link
 * - Green dot = Firebase cloud sync fallback
 * - Solid dot = connected and healthy
 * - Hollow outline = degraded / reconnecting / disconnected
 * - The route currently carrying live control is drawn slightly larger with a soft glow.
 * - Tiny dim copy on tablet.
 */
@Composable
fun PerformanceConnectionDots(
    status: PerformanceLinkStatus,
    modifier: Modifier = Modifier,
    isTabletDimCopy: Boolean = true
) {
    val alphaScale = if (isTabletDimCopy) 0.52f else 1.0f
    val boxSize = if (isTabletDimCopy) 12.dp else 18.dp
    val spacing = if (isTabletDimCopy) 4.dp else 6.dp

    val btColor = Color(0xFF2979FF)
    val netColor = Color(0xFF00E676)

    val isBleActiveRoute = status.activeRoute == PerformanceLinkStatus.ActiveControlRoute.BLE && status.bleHealthy
    val isFirebaseActiveRoute =
        status.activeRoute == PerformanceLinkStatus.ActiveControlRoute.FIREBASE && status.firebaseHealthy

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Blue Bluetooth Dot
        Canvas(modifier = Modifier.size(boxSize)) {
            val center = this.center
            val baseRadius = size.minDimension * 0.25f
            val activeRadius = size.minDimension * 0.33f
            if (isBleActiveRoute) {
                drawCircle(
                    color = btColor.copy(alpha = 0.28f * alphaScale),
                    radius = size.minDimension * 0.48f,
                    center = center
                )
            }
            if (status.bleHealthy) {
                drawCircle(
                    color = btColor.copy(alpha = 0.95f * alphaScale),
                    radius = if (isBleActiveRoute) activeRadius else baseRadius,
                    center = center
                )
            } else {
                drawCircle(
                    color = btColor.copy(alpha = 0.30f * alphaScale),
                    radius = baseRadius * 0.9f,
                    center = center,
                    style = Stroke(width = if (isTabletDimCopy) 1.2f else 2.0f)
                )
            }
        }

        // Green Internet (Firebase) Dot
        Canvas(modifier = Modifier.size(boxSize)) {
            val center = this.center
            val baseRadius = size.minDimension * 0.25f
            val activeRadius = size.minDimension * 0.33f
            if (isFirebaseActiveRoute) {
                drawCircle(
                    color = netColor.copy(alpha = 0.28f * alphaScale),
                    radius = size.minDimension * 0.48f,
                    center = center
                )
            }
            if (status.firebaseHealthy) {
                drawCircle(
                    color = netColor.copy(alpha = 0.95f * alphaScale),
                    radius = if (isFirebaseActiveRoute) activeRadius else baseRadius,
                    center = center
                )
            } else {
                drawCircle(
                    color = netColor.copy(alpha = 0.30f * alphaScale),
                    radius = baseRadius * 0.9f,
                    center = center,
                    style = Stroke(width = if (isTabletDimCopy) 1.2f else 2.0f)
                )
            }
        }
    }
}
