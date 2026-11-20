package com.smartdesk.companion.tablet.model

import androidx.compose.runtime.Immutable
import com.google.firebase.database.IgnoreExtraProperties

/**
 * Matches the Module B & V7 Section 4.4 payload schema:
 * {
 *   "packageName": "com.google.android.apps.messaging",
 *   "title": "Swiggy",
 *   "message": "482913",
 *   "timestamp": "1:15 PM",
 *   "isOtp": true,
 *   "source": "Swiggy",
 *   "otp": "482913",
 *   "sentAt": { ".sv": "timestamp" }
 * }
 *
 * Includes a no-arg constructor for Firebase Realtime Database deserialization
 * and a deterministic/unique [id] for Compose LazyColumn key stability.
 */
@Immutable
@IgnoreExtraProperties
data class NotificationPayload(
    val id: String = "",
    val packageName: String = "",
    val title: String = "",
    val message: String = "",
    val timestamp: String = "",
    val epochMillis: Long = 0L,
    val isOtp: Boolean = false,
    val source: String = "",
    val otp: String = "",
    val sentAt: Long = 0L
) {
    fun appDisplayName(): String {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("calendar") -> "Calendar"
            pkg.contains("gm") || pkg.contains("gmail") -> "Gmail"
            pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms") -> "Messages"
            pkg.contains("dialer") || pkg.contains("incallui") || pkg.contains("telecom") || pkg.contains("phone") -> "Phone Call"
            else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }
}
