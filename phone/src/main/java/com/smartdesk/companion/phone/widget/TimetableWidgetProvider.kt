package com.smartdesk.companion.phone.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import com.smartdesk.companion.phone.R
import com.smartdesk.companion.phone.config.PhoneSyncPreferences
import com.smartdesk.companion.phone.sync.TimetableCommandSender
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.shared.ClockThemeCatalog
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import com.smartdesk.companion.shared.TimetableData
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * V5 Addendum (Section 12 & Section C):
 * Home-screen widget ("Smart Desk Timetable") with a frosted-glass card,
 * theme-tinted Smart Desk Widget Glyph, "Timetable" title, and live mode caption
 * ("Today", "Tomorrow", or "Full week").
 *
 * Tapping the widget sends `/commands/timetable` directly to Firebase without opening the app.
 */
class TimetableWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            val options = appWidgetManager.getAppWidgetOptions(widgetId)
            updateSingleWidget(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetId = widgetId,
                options = options,
                overrideCaption = null,
                isFeedbackAccent = false
            )
        }
        scheduleNextBoundaryRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateSingleWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            options = newOptions,
            overrideCaption = null,
            isFeedbackAccent = false
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TRIGGER_TIMETABLE -> {
                handleWidgetTap(context)
            }

            ACTION_BOUNDARY_REFRESH,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                updateAllWidgets(context)
                scheduleNextBoundaryRefresh(context)
            }
        }
    }

    private fun handleWidgetTap(context: Context) {
        val appContext = context.applicationContext
        // V9 Section 4: Pause timetable function & widget data updates while Performance Mode is on
        if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(appContext)) {
            return
        }
        val pendingResult = goAsync()

        // Immediate tactile visual state on widget ("Sending...")
        updateAllWidgets(
            context = appContext,
            overrideCaption = "Sending…",
            isFeedbackAccent = true
        )

        TimetableCommandSender.sendShowTimetableCommand(appContext) { success, _, _ ->
            try {
                if (success) {
                    updateAllWidgets(
                        context = appContext,
                        overrideCaption = "Sent ✓",
                        isFeedbackAccent = true
                    )
                } else {
                    updateAllWidgets(
                        context = appContext,
                        overrideCaption = "Not connected",
                        isFeedbackAccent = false
                    )
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            appContext,
                            "Smart Desk: Phone is offline",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                // Restore standard mode caption ("Today" / "Tomorrow" / "Full week") after 1.8s
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        updateAllWidgets(appContext)
                    }
                    runCatching { pendingResult.finish() }
                }, 1800L)
            } catch (_: Exception) {
                runCatching { pendingResult.finish() }
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER_TIMETABLE =
            "com.smartdesk.companion.phone.action.TRIGGER_TIMETABLE"
        const val ACTION_BOUNDARY_REFRESH =
            "com.smartdesk.companion.phone.action.TIMETABLE_BOUNDARY_REFRESH"

        fun updateAllWidgets(
            context: Context,
            overrideCaption: String? = null,
            isFeedbackAccent: Boolean = false
        ) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, TimetableWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                updateSingleWidget(
                    context = appContext,
                    appWidgetManager = manager,
                    appWidgetId = id,
                    options = options,
                    overrideCaption = overrideCaption,
                    isFeedbackAccent = isFeedbackAccent
                )
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            options: Bundle?,
            overrideCaption: String?,
            isFeedbackAccent: Boolean
        ) {
            val prefs = PhoneSyncPreferences(context)
            val theme = ClockThemeCatalog.findById(prefs.getSelectedClockThemeId())
            val decision = TimetableData.resolveHighlight(LocalDateTime.now())
            val captionText = overrideCaption ?: decision.toWidgetCaption()

            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150)
                ?.takeIf { it > 0 } ?: 150
            val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72)
                ?.takeIf { it > 0 } ?: 72

            val widthPx = (minWidthDp * density).toInt().coerceIn(260, 960)
            val heightPx = (minHeightDp * density).toInt().coerceIn(130, 480)

            val bitmap = renderFrostedGlassWidgetBitmap(
                widthPx = widthPx,
                heightPx = heightPx,
                density = density,
                theme = theme,
                caption = captionText,
                isFeedbackAccent = isFeedbackAccent
            )

            val views = RemoteViews(context.packageName, R.layout.widget_timetable)
            views.setImageViewBitmap(R.id.widget_canvas_image, bitmap)

            val tapIntent = Intent(context, TimetableWidgetProvider::class.java).apply {
                action = ACTION_TRIGGER_TIMETABLE
            }
            val pendingTap = PendingIntent.getBroadcast(
                context,
                0,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingTap)
            views.setOnClickPendingIntent(R.id.widget_canvas_image, pendingTap)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Renders the frosted-glass widget card with:
         * - Translucent dark glass base (`#1C1C22` at ~62% alpha)
         * - Top-to-bottom subtle white specular sheen gradient (`14% -> 2%`)
         * - Theme-tinted ambient radial glow behind the glyph
         * - 1dp translucent white rim border (`20%` alpha)
         * - Custom Smart Desk Widget Glyph tinted with `theme.digitColors[0..2]`
         * - Primary title "Timetable" + secondary live caption ("Today" / "Tomorrow" / "Full week")
         */
        private fun renderFrostedGlassWidgetBitmap(
            widthPx: Int,
            heightPx: Int,
            density: Float,
            theme: ClockTheme,
            caption: String,
            isFeedbackAccent: Boolean
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val w = widthPx.toFloat()
            val h = heightPx.toFloat()
            val cornerRadius = (26f * density).coerceAtMost(h * 0.46f)
            val inset = 1.5f * density
            val cardRect = RectF(inset, inset, w - inset, h - inset)

            val c0 = theme.digitColors.getOrElse(0) { androidx.compose.ui.graphics.Color(0xFF5CB8FF) }.toArgb()
            val c1 = theme.digitColors.getOrElse(1) { androidx.compose.ui.graphics.Color(0xFFFFB067) }.toArgb()
            val c2 = theme.digitColors.getOrElse(2) { androidx.compose.ui.graphics.Color(0xFF1E78E6) }.toArgb()

            // 1. Frosted dark glass base (#1C1C22 at ~62% alpha = 0x9E)
            val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(162, 0x1C, 0x1C, 0x22)
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, basePaint)

            // 2. Top-to-bottom subtle white glass sheen gradient
            val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f,
                    cardRect.top,
                    0f,
                    cardRect.bottom,
                    intArrayOf(
                        Color.argb(36, 255, 255, 255),
                        Color.argb(12, 255, 255, 255),
                        Color.argb(4, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, sheenPaint)

            // 3. Subtle theme accent tint along the left edge
            val accentOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    cardRect.left,
                    cardRect.top,
                    cardRect.left + w * 0.65f,
                    cardRect.bottom,
                    intArrayOf(
                        withAlpha(c0, if (isFeedbackAccent) 58 else 34),
                        Color.TRANSPARENT
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, accentOverlayPaint)

            // 4. 1dp translucent white glass border (highlighted when tapped)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f * density
                color = if (isFeedbackAccent) {
                    withAlpha(c0, 170)
                } else {
                    Color.argb(48, 255, 255, 255)
                }
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

            // 5. Left: Custom Smart Desk Widget Glyph (theme-tinted)
            val horizontalPad = (14f * density).coerceAtMost(w * 0.09f)
            val glyphSize = (h * 0.60f).coerceIn(32f * density, 54f * density)
            val glyphLeft = cardRect.left + horizontalPad
            val glyphTop = (h - glyphSize) / 2f
            val glyphRect = RectF(glyphLeft, glyphTop, glyphLeft + glyphSize, glyphTop + glyphSize)

            drawSmartDeskGlyph(
                canvas = canvas,
                rect = glyphRect,
                c0 = c0,
                c1 = c1,
                c2 = c2,
                density = density
            )

            // 6. Right: "Timetable" primary title + secondary mode caption ("Today" / "Tomorrow" / "Full week")
            val textStartX = glyphRect.right + (12f * density)
            val availableTextWidth = (cardRect.right - horizontalPad - textStartX).coerceAtLeast(40f)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = (15.5f * density).coerceAtMost(h * 0.25f)
            }
            // Shrink title slightly if widget is very narrow
            while (titlePaint.measureText("Timetable") > availableTextWidth && titlePaint.textSize > 10f * density) {
                titlePaint.textSize -= 0.8f * density
            }

            val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isFeedbackAccent) c1 else Color.argb(190, 235, 235, 245)
                typeface = Typeface.create(
                    Typeface.DEFAULT,
                    if (isFeedbackAccent) Typeface.BOLD else Typeface.NORMAL
                )
                textSize = (12.5f * density).coerceAtMost(h * 0.20f)
            }

            val titleMetrics = titlePaint.fontMetrics
            val captionMetrics = captionPaint.fontMetrics
            val titleHeight = titleMetrics.descent - titleMetrics.ascent
            val captionHeight = captionMetrics.descent - captionMetrics.ascent
            val lineGap = 2.5f * density
            val totalTextBlockHeight = titleHeight + lineGap + captionHeight

            val blockTop = (h - totalTextBlockHeight) / 2f
            val titleBaseline = blockTop - titleMetrics.ascent
            val captionBaseline = blockTop + titleHeight + lineGap - captionMetrics.ascent

            canvas.drawText("Timetable", textStartX, titleBaseline, titlePaint)
            canvas.drawText(caption, textStartX, captionBaseline, captionPaint)

            return bitmap
        }

        /**
         * Draws the custom Smart Desk Widget Glyph inside [rect] using the active Clock Theme's
         * three primary colors ([c0], [c1], [c2]):
         * - Dark squircle container with subtle border
         * - Mini timetable rows with a glowing highlighted row in [c1] / [c0]
         * - Accent pills in [c0], [c1], and [c2]
         */
        private fun drawSmartDeskGlyph(
            canvas: Canvas,
            rect: RectF,
            c0: Int,
            c1: Int,
            c2: Int,
            density: Float
        ) {
            val gw = rect.width()
            val gh = rect.height()
            val badgeRadius = gw * 0.28f

            // Squircle badge fill
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(215, 0x14, 0x16, 0x1C)
            }
            canvas.drawRoundRect(rect, badgeRadius, badgeRadius, badgeBgPaint)

            // Squircle badge border in c0
            val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.1f * density
                color = withAlpha(c0, 110)
            }
            canvas.drawRoundRect(rect, badgeRadius, badgeRadius, badgeBorderPaint)

            val padX = gw * 0.17f
            val padY = gh * 0.19f
            val innerLeft = rect.left + padX
            val innerRight = rect.right - padX
            val innerWidth = innerRight - innerLeft

            // Top header bar (two mini pills in c0 and c2)
            val topBarY = rect.top + padY
            val topBarH = gh * 0.12f
            val pillRadius = topBarH / 2f

            val paintC0 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = c0
            }
            val paintC1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = c1
            }
            val paintC2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = c2
            }
            val paintDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(95, 255, 255, 255)
            }

            // Top row: two period cells (c0 + c2)
            canvas.drawRoundRect(
                RectF(innerLeft, topBarY, innerLeft + innerWidth * 0.44f, topBarY + topBarH),
                pillRadius,
                pillRadius,
                paintC0
            )
            canvas.drawRoundRect(
                RectF(innerLeft + innerWidth * 0.52f, topBarY, innerRight, topBarY + topBarH),
                pillRadius,
                pillRadius,
                paintC2
            )

            // Middle row (HIGHLIGHTED DAY ROW — slightly wider & taller, tinted in c1)
            val midRowY = rect.centerY() - gh * 0.04f
            val midRowH = gh * 0.19f
            val midRect = RectF(
                innerLeft - gw * 0.03f,
                midRowY,
                innerRight + gw * 0.03f,
                midRowY + midRowH
            )
            val midGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = withAlpha(c1, 55)
            }
            canvas.drawRoundRect(
                RectF(
                    midRect.left - 1.5f * density,
                    midRect.top - 1.5f * density,
                    midRect.right + 1.5f * density,
                    midRect.bottom + 1.5f * density
                ),
                midRowH / 2f,
                midRowH / 2f,
                midGlowPaint
            )
            canvas.drawRoundRect(midRect, midRowH / 2f, midRowH / 2f, paintC1)

            // Bottom row: muted schedule cells
            val botRowY = rect.bottom - padY - topBarH
            canvas.drawRoundRect(
                RectF(innerLeft, botRowY, innerLeft + innerWidth * 0.60f, botRowY + topBarH),
                pillRadius,
                pillRadius,
                paintDim
            )
            canvas.drawRoundRect(
                RectF(innerLeft + innerWidth * 0.68f, botRowY, innerRight, botRowY + topBarH),
                pillRadius,
                pillRadius,
                paintC0
            )
        }

        private fun withAlpha(argb: Int, alpha: Int): Int {
            return Color.argb(
                alpha.coerceIn(0, 255),
                Color.red(argb),
                Color.green(argb),
                Color.blue(argb)
            )
        }

        /**
         * Schedules a non-wakeup RTC alarm for the next 16:00 or 00:00 boundary so the
         * widget caption transitions automatically between "Today", "Tomorrow", and "Full week".
         */
        fun scheduleNextBoundaryRefresh(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return@runCatching

                val now = LocalDateTime.now()
                val todayFourPm = LocalDateTime.of(now.toLocalDate(), LocalTime.of(16, 0, 2))
                val nextMidnight = LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.of(0, 0, 2))

                val nextBoundary = if (now.isBefore(todayFourPm)) todayFourPm else nextMidnight
                val triggerAtMillis = nextBoundary
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

                val intent = Intent(appContext, TimetableWidgetProvider::class.java).apply {
                    action = ACTION_BOUNDARY_REFRESH
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    1001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                alarmManager.set(AlarmManager.RTC, triggerAtMillis, pendingIntent)
            }
        }
    }
}
