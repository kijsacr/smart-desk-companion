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
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import com.smartdesk.companion.phone.R
import com.smartdesk.companion.phone.config.PhoneSyncPreferences
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.shared.ClockThemeCatalog
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager

/**
 * Home-screen widget ("Smart Desk PC Monitor") with a frosted-glass card,
 * theme-tinted PC Resource Monitor Glyph, "PC Monitor" title, and live toggle state caption
 * ("Active • ON" when full-screen CPU/RAM monitor is open on the tablet, or "Tap to show" when off).
 *
 * Tapping the widget toggles `/config/pcMonitor/fullScreen` directly in Firebase without opening the app.
 */
class PcMonitorWidgetProvider : AppWidgetProvider() {

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
                overrideCaption = null
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
            options = newOptions,
            overrideCaption = null
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_PC_MONITOR -> {
                handleWidgetTap(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                updateAllWidgets(context)
            }
        }
    }

    private fun handleWidgetTap(context: Context) {
        val appContext = context.applicationContext
        // V9 Section 4: Pause PC stats function while Performance Mode is on
        if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(appContext)) {
            return
        }
        val pendingResult = goAsync()
        val prefs = PhoneSyncPreferences(appContext)

        if (!hasActiveNetwork(appContext)) {
            updateAllWidgets(
                context = appContext,
                overrideCaption = "Not connected"
            )
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    appContext,
                    "Smart Desk: Phone is offline",
                    Toast.LENGTH_SHORT
                ).show()
            }
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { updateAllWidgets(appContext) }
                runCatching { pendingResult.finish() }
            }, 1800L)
            return
        }

        val nextState = !prefs.isPcFullScreenEnabled()
        prefs.setPcFullScreenEnabled(nextState)

        // Update widget immediately so the toggle feels instant
        updateAllWidgets(appContext)

        try {
            val ref = prefs.resolvePcFullScreenReference()
            ref.setValue(nextState)
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        // Revert if write failed
                        prefs.setPcFullScreenEnabled(!nextState)
                        updateAllWidgets(
                            context = appContext,
                            overrideCaption = "Sync error"
                        )
                    } else {
                        PhoneSyncPreferences.updateStatus(
                            if (nextState) {
                                "Full-screen PC Monitor enabled on tablet"
                            } else {
                                "Returned tablet to clock dashboard"
                            }
                        )
                        updateAllWidgets(appContext)
                    }
                    runCatching { pendingResult.finish() }
                }
        } catch (_: Exception) {
            runCatching { pendingResult.finish() }
        }
    }

    private fun hasActiveNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        const val ACTION_TOGGLE_PC_MONITOR =
            "com.smartdesk.companion.phone.action.TOGGLE_PC_MONITOR"

        fun updateAllWidgets(
            context: Context,
            overrideCaption: String? = null
        ) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, PcMonitorWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                updateSingleWidget(
                    context = appContext,
                    appWidgetManager = manager,
                    appWidgetId = id,
                    options = options,
                    overrideCaption = overrideCaption
                )
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            options: Bundle?,
            overrideCaption: String?
        ) {
            val prefs = PhoneSyncPreferences(context)
            val theme = ClockThemeCatalog.findById(prefs.getSelectedClockThemeId())
            val isFullScreenActive = prefs.isPcFullScreenEnabled()
            val captionText = overrideCaption ?: if (isFullScreenActive) {
                "Active • ON"
            } else {
                "Tap to show"
            }

            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150)
                ?.takeIf { it > 0 } ?: 150
            val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72)
                ?.takeIf { it > 0 } ?: 72

            val widthPx = (minWidthDp * density).toInt().coerceIn(260, 960)
            val heightPx = (minHeightDp * density).toInt().coerceIn(130, 480)

            val bitmap = renderPcMonitorWidgetBitmap(
                widthPx = widthPx,
                heightPx = heightPx,
                density = density,
                theme = theme,
                caption = captionText,
                isActive = isFullScreenActive && overrideCaption == null
            )

            val views = RemoteViews(context.packageName, R.layout.widget_pc_monitor)
            views.setImageViewBitmap(R.id.widget_pc_monitor_canvas, bitmap)

            val tapIntent = Intent(context, PcMonitorWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_PC_MONITOR
            }
            val pendingTap = PendingIntent.getBroadcast(
                context,
                2001,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_pc_monitor_root, pendingTap)
            views.setOnClickPendingIntent(R.id.widget_pc_monitor_canvas, pendingTap)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun renderPcMonitorWidgetBitmap(
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

            val c0 = theme.digitColors.getOrElse(0) { androidx.compose.ui.graphics.Color(0xFF5CB8FF) }.toArgb()
            val c1 = theme.digitColors.getOrElse(1) { androidx.compose.ui.graphics.Color(0xFFFFB067) }.toArgb()
            val c2 = theme.digitColors.getOrElse(2) { androidx.compose.ui.graphics.Color(0xFF1E78E6) }.toArgb()

            // 1. Frosted dark glass base (#1C1C22 at ~62% alpha)
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

            // 3. Subtle theme accent tint along the left edge (stronger when active)
            val accentOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    cardRect.left,
                    cardRect.top,
                    cardRect.left + w * 0.65f,
                    cardRect.bottom,
                    intArrayOf(
                        withAlpha(c0, if (isActive) 64 else 34),
                        Color.TRANSPARENT
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, accentOverlayPaint)

            // 4. 1dp translucent glass border (highlighted in theme color when active)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (isActive) 1.5f * density else 1.2f * density
                color = if (isActive) {
                    withAlpha(c0, 185)
                } else {
                    Color.argb(48, 255, 255, 255)
                }
            }
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

            // 5. Left: Custom PC Monitor Glyph (theme-tinted CPU/RAM gauge badge)
            val horizontalPad = (14f * density).coerceAtMost(w * 0.09f)
            val glyphSize = (h * 0.60f).coerceIn(32f * density, 54f * density)
            val glyphLeft = cardRect.left + horizontalPad
            val glyphTop = (h - glyphSize) / 2f
            val glyphRect = RectF(glyphLeft, glyphTop, glyphLeft + glyphSize, glyphTop + glyphSize)

            drawPcMonitorGlyph(
                canvas = canvas,
                rect = glyphRect,
                c0 = c0,
                c1 = c1,
                c2 = c2,
                isActive = isActive,
                density = density
            )

            // 6. Right: "PC Monitor" primary title + secondary state caption ("Active • ON" / "Tap to show")
            val textStartX = glyphRect.right + (12f * density)
            val availableTextWidth = (cardRect.right - horizontalPad - textStartX).coerceAtLeast(40f)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = (15.5f * density).coerceAtMost(h * 0.25f)
            }
            while (titlePaint.measureText("PC Monitor") > availableTextWidth && titlePaint.textSize > 10f * density) {
                titlePaint.textSize -= 0.8f * density
            }

            val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isActive) c1 else Color.argb(190, 235, 235, 245)
                typeface = Typeface.create(
                    Typeface.DEFAULT,
                    if (isActive) Typeface.BOLD else Typeface.NORMAL
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

            canvas.drawText("PC Monitor", textStartX, titleBaseline, titlePaint)
            canvas.drawText(caption, textStartX, captionBaseline, captionPaint)

            return bitmap
        }

        /**
         * Draws the custom PC Resource Monitor Glyph inside [rect] using the active Clock Theme's
         * colors ([c0], [c1], [c2]):
         * - Dark squircle badge container with theme border
         * - Circular speedometer/arc gauge on top in [c0] & [c1]
         * - Twin CPU / RAM resource bars along the bottom in [c0] and [c1]
         */
        private fun drawPcMonitorGlyph(
            canvas: Canvas,
            rect: RectF,
            c0: Int,
            c1: Int,
            c2: Int,
            isActive: Boolean,
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
                color = withAlpha(if (isActive) c1 else c0, if (isActive) 150 else 110)
            }
            canvas.drawRoundRect(rect, badgeRadius, badgeRadius, badgeBorderPaint)

            // Upper circular gauge arc
            val arcRadius = gw * 0.24f
            val arcCenterX = rect.centerX()
            val arcCenterY = rect.top + gh * 0.43f
            val arcOval = RectF(
                arcCenterX - arcRadius,
                arcCenterY - arcRadius,
                arcCenterX + arcRadius,
                arcCenterY + arcRadius
            )
            val strokeW = (gw * 0.095f).coerceAtLeast(2.2f * density)

            val trackArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
                color = Color.argb(70, 255, 255, 255)
            }
            canvas.drawArc(arcOval, 145f, 250f, false, trackArcPaint)

            val activeArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
                shader = LinearGradient(
                    arcOval.left,
                    arcOval.bottom,
                    arcOval.right,
                    arcOval.top,
                    c0,
                    c1,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawArc(arcOval, 145f, if (isActive) 205f else 165f, false, activeArcPaint)

            // Center dot inside gauge
            val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = if (isActive) c1 else c0
            }
            canvas.drawCircle(arcCenterX, arcCenterY, strokeW * 0.48f, centerDotPaint)

            // Bottom twin CPU / RAM bars
            val padX = gw * 0.18f
            val barLeft = rect.left + padX
            val barRight = rect.right - padX
            val barWidth = barRight - barLeft
            val barH = (gh * 0.095f).coerceAtLeast(2f * density)
            val barRadius = barH / 2f

            val cpuBarY = rect.bottom - gh * 0.28f
            val ramBarY = rect.bottom - gh * 0.14f

            val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(60, 255, 255, 255)
            }
            val cpuFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = c0
            }
            val ramFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = c1
            }

            // CPU bar
            canvas.drawRoundRect(
                RectF(barLeft, cpuBarY, barRight, cpuBarY + barH),
                barRadius,
                barRadius,
                barTrackPaint
            )
            canvas.drawRoundRect(
                RectF(barLeft, cpuBarY, barLeft + barWidth * 0.68f, cpuBarY + barH),
                barRadius,
                barRadius,
                cpuFillPaint
            )

            // RAM bar
            canvas.drawRoundRect(
                RectF(barLeft, ramBarY, barRight, ramBarY + barH),
                barRadius,
                barRadius,
                barTrackPaint
            )
            canvas.drawRoundRect(
                RectF(barLeft, ramBarY, barLeft + barWidth * 0.84f, ramBarY + barH),
                barRadius,
                barRadius,
                ramFillPaint
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
    }
}
