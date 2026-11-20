package com.smartdesk.companion.tablet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.shared.ClockThemeCatalog

/**
 * Module A & G:
 * Renders the iOS StandBy "Float"-style overlapping, chubby, pill-rounded digital clock.
 *
 * Layout, digit shape, size, blending, and positioning are unchanged from V3;
 * digit and colon colours come from the active [ClockTheme] (synced from the phone
 * via Firebase `config/clockTheme` and cached locally for offline startup).
 */
@Composable
fun FloatStyleClock(
    timeText: String, // e.g., "12:39" or "10:35" (4 digits, colon separated)
    theme: ClockTheme = ClockThemeCatalog.SUNSET_FLOAT,
    modifier: Modifier = Modifier
) {
    val cleanDigits = timeText.filter { it.isDigit() }.padStart(4, '0').take(4)

    val digitColors = theme.digitColors
    val overlapHighlightColors = theme.overlapHighlightColors

    Canvas(
        modifier = modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        val totalW = size.width
        val totalH = size.height
        if (totalW <= 0f || totalH <= 0f) return@Canvas

        // Constrain glyphHeight by BOTH available width and height so all 4 digits + stroke caps
        // fit comfortably inside the pane (~78% width max, ~74% height max) with zero clipping.
        val maxGlyphHeightByWidth = (totalW * 0.78f) / 2.21f
        val maxGlyphHeightByHeight = (totalH * 0.74f) / 1.21f
        val glyphHeight = minOf(maxGlyphHeightByWidth, maxGlyphHeightByHeight)

        val strokeWidth = glyphHeight * 0.21f
        val glyphWidth = glyphHeight * 0.54f

        val pairOverlapAdvance = glyphWidth * 0.85f
        val colonGapAdvance = glyphWidth * 1.00f

        val totalClusterWidth =
            pairOverlapAdvance + colonGapAdvance + pairOverlapAdvance + glyphWidth

        // Center horizontally and vertically inside the Canvas
        val startX = (totalW - totalClusterWidth) / 2f
        val glyphTop = (totalH - glyphHeight) / 2f

        val x0 = startX
        val x1 = x0 + pairOverlapAdvance
        val x2 = x1 + colonGapAdvance
        val x3 = x2 + pairOverlapAdvance
        val digitXPositions = listOf(x0, x1, x2, x3)

        val stroke = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        val paths = cleanDigits.mapIndexed { index, ch ->
            buildRoundedDigitPath(
                digit = ch,
                left = digitXPositions[index],
                top = glyphTop,
                width = glyphWidth,
                height = glyphHeight
            )
        }

        // Draw Digit 1 (Hours tens)
        drawPath(
            path = paths[0],
            color = digitColors[0 % digitColors.size],
            style = stroke
        )

        // Draw Digit 2 (Hours ones) with slightly translucent body + Screen blend for overlap
        drawPath(
            path = paths[1],
            color = digitColors[1 % digitColors.size].copy(alpha = theme.overlapAlpha),
            style = stroke
        )
        drawPath(
            path = paths[1],
            color = overlapHighlightColors[0 % overlapHighlightColors.size].copy(alpha = 0.32f),
            style = stroke,
            blendMode = BlendMode.Screen
        )

        // Draw Digit 3 (Minutes tens)
        drawPath(
            path = paths[2],
            color = digitColors[2 % digitColors.size],
            style = stroke
        )

        // Draw Digit 4 (Minutes ones) with translucent overlap highlight over Digit 3
        drawPath(
            path = paths[3],
            color = digitColors[3 % digitColors.size].copy(alpha = theme.overlapAlpha),
            style = stroke
        )
        drawPath(
            path = paths[3],
            color = overlapHighlightColors[1 % overlapHighlightColors.size].copy(alpha = 0.30f),
            style = stroke,
            blendMode = BlendMode.Screen
        )

        // Draw the two clean circular colon dots between Digit 2 and Digit 3
        val colonCenterX = (x1 + glyphWidth + x2) / 2f
        val dotRadius = strokeWidth * 0.48f
        val upperDotY = glyphTop + glyphHeight * 0.30f
        val lowerDotY = glyphTop + glyphHeight * 0.70f

        drawColonDot(
            center = Offset(colonCenterX, upperDotY),
            radius = dotRadius,
            color = theme.colonDotColor.copy(alpha = theme.colonAlpha)
        )
        drawColonDot(
            center = Offset(colonCenterX, lowerDotY),
            radius = dotRadius,
            color = theme.colonDotColor.copy(alpha = theme.colonAlpha)
        )
    }
}

private fun DrawScope.drawColonDot(center: Offset, radius: Float, color: Color) {
    drawCircle(
        color = color,
        radius = radius,
        center = center
    )
}

/**
 * Constructs smooth, pill-rounded centerline paths for digits '0'..'9'
 * matching the rounded geometric proportions of the iOS StandBy "Float" clock face.
 */
private fun buildRoundedDigitPath(
    digit: Char,
    left: Float,
    top: Float,
    width: Float,
    height: Float
): Path {
    val right = left + width
    val bottom = top + height
    val midY = top + height * 0.48f

    return Path().apply {
        when (digit) {
            '0' -> {
                addRoundRect(
                    RoundRect(
                        rect = Rect(left, top, right, bottom),
                        cornerRadius = CornerRadius(width * 0.5f, height * 0.45f)
                    )
                )
            }

            '1' -> {
                val stemX = left + width * 0.56f
                moveTo(left + width * 0.14f, top + height * 0.20f)
                lineTo(stemX, top)
                lineTo(stemX, bottom)
            }

            '2' -> {
                val archRect = Rect(left, top, right, top + height * 0.52f)
                arcTo(archRect, startAngleDegrees = 195f, sweepAngleDegrees = 215f, forceMoveTo = true)
                lineTo(left + width * 0.04f, bottom)
                lineTo(right, bottom)
            }

            '3' -> {
                val topLoop = Rect(left + width * 0.04f, top, right, midY)
                val bottomLoop = Rect(left, midY, right, bottom)
                arcTo(topLoop, startAngleDegrees = 195f, sweepAngleDegrees = 255f, forceMoveTo = true)
                arcTo(bottomLoop, startAngleDegrees = 270f, sweepAngleDegrees = 255f, forceMoveTo = false)
            }

            '4' -> {
                val stemX = left + width * 0.72f
                val crossY = top + height * 0.64f
                moveTo(stemX, bottom)
                lineTo(stemX, top)
                lineTo(left, crossY)
                lineTo(right, crossY)
            }

            '5' -> {
                val lowerBowlTop = top + height * 0.42f
                moveTo(right - width * 0.04f, top)
                lineTo(left + width * 0.10f, top)
                lineTo(left + width * 0.06f, top + height * 0.46f)
                val bowlRect = Rect(left, lowerBowlTop, right, bottom)
                arcTo(bowlRect, startAngleDegrees = 225f, sweepAngleDegrees = 275f, forceMoveTo = false)
            }

            '6' -> {
                val bowlTop = top + height * 0.42f
                moveTo(right - width * 0.14f, top + height * 0.04f)
                quadraticBezierTo(
                    left - width * 0.02f,
                    top + height * 0.30f,
                    left,
                    top + height * 0.70f
                )
                addOval(Rect(left, bowlTop, right, bottom))
            }

            '7' -> {
                moveTo(left, top)
                lineTo(right, top)
                lineTo(left + width * 0.28f, bottom)
            }

            '8' -> {
                val topOval = Rect(left + width * 0.05f, top, right - width * 0.05f, midY)
                val bottomOval = Rect(left, midY, right, bottom)
                addOval(topOval)
                addOval(bottomOval)
            }

            '9' -> {
                val bowlBottom = top + height * 0.58f
                addOval(Rect(left, top, right, bowlBottom))
                moveTo(right, top + height * 0.30f)
                quadraticBezierTo(
                    right + width * 0.02f,
                    top + height * 0.76f,
                    left + width * 0.16f,
                    bottom - height * 0.02f
                )
            }

            else -> {
                addOval(Rect(left, top, right, bottom))
            }
        }
    }
}
