package com.smartdesk.companion.phone.widget

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
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.smartdesk.companion.phone.MainActivity
import com.smartdesk.companion.phone.R
import com.smartdesk.companion.phone.config.PhoneSyncPreferences
import com.smartdesk.companion.phone.service.PhonePerformanceForegroundService
import com.smartdesk.companion.phone.ui.PerformanceEndConfirmDialogActivity
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.shared.ClockThemeCatalog
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager

/**
 * V9 Section 2: Home-screen widget ("Performance Mode") on the phone.
 * Toggle widget in the same style as the PC monitor widget, reflecting ON / OFF state.
 *
 * Behavior:
 * - Pressed while OFF: turns mode ON, starts PhonePerformanceForegroundService,
 *   and opens MainActivity to the "Select or create session" screen.
 * - Pressed while ON (from home screen, Wing q, or anywhere): launches
 *   [PerformanceEndConfirmDialogActivity] (small transparent dialog-style activity).
 */
class PerformanceModeWidgetProvider : AppWidgetProvider() {

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
                options = options
            )
        }
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
            options = newOptions
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_PERFORMANCE_MODE -> {
                handleWidgetTap(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                updateAllWidgets(context)
            }
        }
    }

    private fun handleWidgetTap(context: Context) {
        val appContext = context.applicationContext
        val isCurrentlyEnabled = PerformanceStorageAndCacheManager.isPerformanceModeEnabled(appContext)

        if (!isCurrentlyEnabled) {
            // V9 Section 2: Widget pressed while mode is OFF -> start mode and open Select or Create Session
            PhonePerformanceForegroundService.startPerformanceMode(appContext)
            val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("extra_open_performance_screen", true)
            }
            appContext.startActivity(launchIntent)
            updateAllWidgets(appContext)
        } else {
            // V9 Section 2: Widget pressed while mode is ON -> show confirm dialog over whatever app is showing
            val dialogIntent = Intent(appContext, PerformanceEndConfirmDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            appContext.startActivity(dialogIntent)
        }
    }

    companion object {
        const val ACTION_TOGGLE_PERFORMANCE_MODE =
            "com.smartdesk.companion.phone.action.TOGGLE_PERFORMANCE_MODE"

        fun updateAllWidgets(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, PerformanceModeWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                updateSingleWidget(
                    context = appContext,
                    appWidgetManager = manager,
                    appWidgetId = id,
                    options = options
                )
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            options: Bundle?
        ) {
            val prefs = PhoneSyncPreferences(context)
            val theme = ClockThemeCatalog.findById(prefs.getSelectedClockThemeId())
            val isModeActive = PerformanceStorageAndCacheManager.isPerformanceModeEnabled(context)
            val captionText = if (isModeActive) "Active • ON" else "Tap to start"

            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150)
                ?.takeIf { it > 0 } ?: 150
            val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72)
                ?.takeIf { it > 0 } ?: 72

            val widthPx = (minWidthDp * density).toInt().coerceIn(260, 960)
            val heightPx = (minHeightDp * density).toInt().coerceIn(130, 480)

            val bitmap = renderPerformanceWidgetBitmap(
                widthPx = widthPx,
                heightPx = heightPx,
                density = density,
                theme = theme,
                caption = captionText,
                isActive = isModeActive
            )

            val views = RemoteViews(context.packageName, R.layout.widget_performance_mode)
            views.setImageViewBitmap(R.id.widget_performance_mode_canvas, bitmap)

            val tapIntent = Intent(context, PerformanceModeWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_PERFORMANCE_MODE
            }
            val pendingTap = PendingIntent.getBroadcast(
                context,
                3001,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_performance_mode_root, pendingTap)
            views.setOnClickPendingIntent(R.id.widget_performance_mode_canvas, pendingTap)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun renderPerformanceWidgetBitmap(
            widthPx: Int,
            heightPx: Int,
            density: Float,
            theme: ClockTheme,
            caption: String,
            isActive: Boolean
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val w = widthPx.toFloat()
            val h = heightPx.toFloat()
            val cornerRadius = (26f * density).coerceAtMost(h * 0.46f)
            val inset = 1.5f * density
            val cardRect = RectF(inset, inset, w - inset, h - inset)

            // Performance mode violet / purple accents
            val purplePrimary = Color.rgb(171, 71, 188) // #AB47BC
            val purpleGlow = Color.rgb(225, 190, 231) // #E1BEE7
            val activeGreen = Color.rgb(0, 230, 118)

            // 1. Frosted dark glass base
            val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = if (isActive) Color.argb(200, 0x23, 0x11, 0x36) else Color.argb(162, 0x1C, 0x1C, 0x22)
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, basePaint)

            // 2. Translucent sheen
            val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(Color.argb(38, 255, 255, 255), Color.argb(5, 255, 255, 255)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, sheenPaint)

            // 3. Rim border
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f * density
                color = if (isActive) Color.argb(210, 171, 71, 188) else Color.argb(50, 255, 255, 255)
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

            // 4. Music note glyph
            val glyphSize = (h * 0.46f).coerceIn(32f * density, 60f * density)
            val glyphLeft = cardRect.left + (16f * density)
            val glyphTop = cardRect.top + (h - glyphSize) / 2f

            drawMusicGlyph(canvas, glyphLeft, glyphTop, glyphSize, if (isActive) purpleGlow else Color.WHITE)

            // 5. Texts
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (h * 0.22f).coerceIn(14f * density, 20f * density)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isActive) activeGreen else Color.rgb(170, 174, 185)
                textSize = (h * 0.16f).coerceIn(11f * density, 15f * density)
                typeface = Typeface.create(Typeface.SANS_SERIF, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            }

            val textLeft = glyphLeft + glyphSize + (14f * density)
            val textBaselineY = cardRect.top + h * 0.44f
            canvas.drawText("Performance Mode", textLeft, textBaselineY, titlePaint)
            canvas.drawText(caption, textLeft, textBaselineY + (h * 0.26f), captionPaint)

            return bitmap
        }

        private fun drawMusicGlyph(
            canvas: Canvas,
            left: Float,
            top: Float,
            size: Float,
            tintColor: Int
        ) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.FILL
            }
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                style = Paint.Style.STROKE
                strokeWidth = size * 0.12f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            // Draw double note with connecting beam
            val note1X = left + size * 0.28f
            val note1Y = top + size * 0.72f
            val note2X = left + size * 0.76f
            val note2Y = top + size * 0.58f
            val headRadius = size * 0.16f

            // Note heads
            canvas.drawCircle(note1X, note1Y, headRadius, paint)
            canvas.drawCircle(note2X, note2Y, headRadius, paint)

            // Vertical stems
            val stem1Top = top + size * 0.18f
            val stem2Top = top + size * 0.10f
            canvas.drawLine(note1X + headRadius * 0.7f, note1Y, note1X + headRadius * 0.7f, stem1Top, strokePaint)
            canvas.drawLine(note2X + headRadius * 0.7f, note2Y, note2X + headRadius * 0.7f, stem2Top, strokePaint)

            // Top beam
            canvas.drawLine(note1X + headRadius * 0.7f, stem1Top, note2X + headRadius * 0.7f, stem2Top, strokePaint)
        }
    }
}
