package com.smartdesk.companion.phone.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.database.ServerValue
import com.smartdesk.companion.phone.config.PhoneSyncPreferences
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.HighlightDecision
import com.smartdesk.companion.shared.TimetableData
import java.time.LocalDateTime
import java.util.UUID

/**
 * V5 Addendum (Section 12 & Section E):
 * Resolves the current highlight mode (`TODAY`, `TOMORROW`, or `FULL_WEEK`) using the phone's
 * local time and writes the trigger command to `/commands/timetable` in Firebase Realtime Database.
 */
object TimetableCommandSender {

    fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun sendShowTimetableCommand(
        context: Context,
        onResult: (success: Boolean, decision: HighlightDecision, statusMessage: String) -> Unit = { _, _, _ -> }
    ) {
        val appContext = context.applicationContext
        val decision = TimetableData.resolveHighlight(LocalDateTime.now())

        if (!isNetworkConnected(appContext)) {
            val msg = "Not connected to internet"
            PhoneSyncPreferences.updateStatus(msg)
            onResult(false, decision, msg)
            return
        }

        try {
            val prefs = PhoneSyncPreferences(appContext)
            val cmdRef = prefs.resolveTimetableCommandReference()
            val requestId = UUID.randomUUID().toString()
            val nowMs = System.currentTimeMillis()

            val payload = mapOf<String, Any?>(
                "requestId" to requestId,
                "requestedAt" to ServerValue.TIMESTAMP,
                "clientTimestampMs" to nowMs,
                "mode" to decision.mode.name,
                "highlightDay" to decision.highlightDay?.name,
                "day" to decision.toShortDayCode(),
                "durationSec" to AppConfigConstants.TIMETABLE_DISPLAY_DURATION_SEC
            )

            cmdRef.setValue(payload)
                .addOnSuccessListener {
                    val caption = decision.toTabletOverlayTitle()
                    val msg = "Timetable sent to tablet ($caption)"
                    PhoneSyncPreferences.updateStatus(msg)
                    onResult(true, decision, msg)
                }
                .addOnFailureListener { err ->
                    val msg = "Failed to send timetable: ${err.localizedMessage ?: "Error"}"
                    PhoneSyncPreferences.updateStatus(msg)
                    onResult(false, decision, msg)
                }
        } catch (e: Exception) {
            val msg = "Timetable error: ${e.localizedMessage ?: "Check Firebase URL"}"
            PhoneSyncPreferences.updateStatus(msg)
            onResult(false, decision, msg)
        }
    }
}
