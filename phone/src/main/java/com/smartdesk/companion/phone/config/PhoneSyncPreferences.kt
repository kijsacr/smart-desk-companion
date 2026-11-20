package com.smartdesk.companion.phone.config

import android.content.Context
import android.provider.Telephony
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.smartdesk.companion.shared.AppConfigConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ForwardedAlertItem(
    val packageName: String,
    val title: String,
    val message: String,
    val timestamp: String
)

data class AppFilterToggleSpec(
    val prefKey: String,
    val label: String,
    val defaultEnabled: Boolean = true,
    val matcher: (context: Context, pkgLower: String, rawPkg: String) -> Boolean
)

class PhoneSyncPreferences(private val context: Context) {

    val appContext: Context = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (prefs.getBoolean(KEY_OTP_REDACTION_DETECTED, false)) {
            _otpRedactionDetected.value = true
        }
    }

    /**
     * Section 4.1: Data-driven App Filter catalog so adding another app later is a one-line change.
     */
    val appFilterSpecs: List<AppFilterToggleSpec> = listOf(
        AppFilterToggleSpec(
            prefKey = KEY_ALLOW_WHATSAPP,
            label = "WhatsApp (com.whatsapp)",
            defaultEnabled = true,
            matcher = { _, pkg, _ -> pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b" }
        ),
        AppFilterToggleSpec(
            prefKey = KEY_ALLOW_INSTAGRAM,
            label = "Instagram (com.instagram.android)",
            defaultEnabled = true,
            matcher = { _, pkg, _ ->
                pkg == "com.instagram.android" || pkg == "com.instagram.lite" || pkg.contains("instagram")
            }
        ),
        AppFilterToggleSpec(
            prefKey = KEY_ALLOW_GMAIL,
            label = "Gmail (com.google.android.gm)",
            defaultEnabled = true,
            matcher = { _, pkg, _ -> pkg == "com.google.android.gm" || pkg.contains("gmail") }
        ),
        AppFilterToggleSpec(
            prefKey = KEY_ALLOW_CALENDAR,
            label = "Calendar (com.google.android.calendar)",
            defaultEnabled = true,
            matcher = { _, pkg, _ -> pkg == AppConfigConstants.GOOGLE_CALENDAR_PACKAGE }
        ),
        AppFilterToggleSpec(
            prefKey = KEY_ALLOW_CALLS,
            label = "Incoming & Missed Calls",
            defaultEnabled = true,
            matcher = { _, pkg, _ ->
                pkg.contains("dialer") ||
                    pkg.contains("incallui") ||
                    pkg.contains("telecom") ||
                    pkg == "com.android.phone" ||
                    pkg == "com.google.android.dialer" ||
                    pkg == "com.samsung.android.incallui"
            }
        ),
        AppFilterToggleSpec(
            prefKey = KEY_ALLOW_SMS,
            label = "SMS / Messages",
            defaultEnabled = true,
            matcher = { ctx, pkg, rawPkg ->
                isSmsPackage(ctx, pkg, rawPkg)
            }
        )
    )

    fun isSmsPackage(ctx: Context, pkgLower: String, rawPkg: String): Boolean {
        val defaultSmsPkg = runCatching {
            Telephony.Sms.getDefaultSmsPackage(ctx)
        }.getOrNull()
        return (defaultSmsPkg != null && rawPkg == defaultSmsPkg) ||
            pkgLower == "com.google.android.apps.messaging" ||
            pkgLower == "com.samsung.android.messaging" ||
            pkgLower == "com.android.mms" ||
            pkgLower.contains("mms") ||
            pkgLower.contains("messaging")
    }

    fun getDatabaseUrl(): String =
        prefs.getString(KEY_CUSTOM_DB_URL, "")?.trim().orEmpty()

    fun getNodePath(): String =
        prefs.getString(KEY_NODE_PATH, DEFAULT_NODE_PATH)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_NODE_PATH

    fun setFirebaseConfig(url: String, nodePath: String) {
        prefs.edit()
            .putString(KEY_CUSTOM_DB_URL, url.trim())
            .putString(KEY_NODE_PATH, nodePath.trim().ifEmpty { DEFAULT_NODE_PATH })
            .apply()
    }

    fun isFilterEnabled(prefKey: String, defaultEnabled: Boolean = true): Boolean =
        prefs.getBoolean(prefKey, defaultEnabled)

    fun setFilterEnabled(prefKey: String, enabled: Boolean) =
        prefs.edit().putBoolean(prefKey, enabled).apply()

    fun isAllowAllAppsEnabled(): Boolean = prefs.getBoolean(KEY_ALLOW_ALL_APPS, false)
    fun setAllowAllAppsEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_ALLOW_ALL_APPS, enabled).apply()

    fun getSelectedClockThemeId(): String =
        prefs.getString(KEY_SELECTED_CLOCK_THEME, AppConfigConstants.DEFAULT_CLOCK_THEME_ID)
            ?: AppConfigConstants.DEFAULT_CLOCK_THEME_ID

    fun setSelectedClockThemeId(themeId: String) =
        prefs.edit().putString(KEY_SELECTED_CLOCK_THEME, themeId).apply()

    fun isPcFullScreenEnabled(): Boolean =
        prefs.getBoolean(KEY_PC_FULLSCREEN_ENABLED, false)

    fun setPcFullScreenEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_PC_FULLSCREEN_ENABLED, enabled).apply()

    // V7 Section 4: OTP Alert local preferences
    fun isOtpAlertsEnabled(): Boolean =
        prefs.getBoolean(KEY_OTP_ALERTS_ENABLED, true)

    fun setOtpAlertsEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_OTP_ALERTS_ENABLED, enabled).apply()

    fun isOtpSmsEnabled(): Boolean =
        prefs.getBoolean(KEY_OTP_SMS_ENABLED, true)

    fun setOtpSmsEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_OTP_SMS_ENABLED, enabled).apply()

    fun isOtpWhatsappEnabled(): Boolean =
        prefs.getBoolean(KEY_OTP_WHATSAPP_ENABLED, true)

    fun setOtpWhatsappEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_OTP_WHATSAPP_ENABLED, enabled).apply()

    fun isOtpGmailEnabled(): Boolean =
        prefs.getBoolean(KEY_OTP_GMAIL_ENABLED, true)

    fun setOtpGmailEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_OTP_GMAIL_ENABLED, enabled).apply()

    fun markSensitiveRedactionDetected(detected: Boolean) {
        prefs.edit().putBoolean(KEY_OTP_REDACTION_DETECTED, detected).apply()
        _otpRedactionDetected.value = detected
    }

    /**
     * Checks whether a given [packageName] should be forwarded to the Lenovo tablet
     * based on the user's data-driven filter preferences.
     *
     * Strictly blocks Spotify (`com.spotify.music`, `com.spotify.lite`) so phone music
     * notifications never get pushed to Firebase or the tablet's notification list.
     */
    fun shouldForwardPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()

        // Always block Spotify, system UI, and our own companion apps—even if "Allow All Apps" is on
        if (packageName == context.packageName ||
            packageName == "com.smartdesk.companion.tablet" ||
            packageName == "android" ||
            packageName == "com.android.systemui" ||
            pkg.contains("spotify")
        ) {
            return false
        }

        // Explicit Calendar check: when Calendar toggle is OFF, block com.google.android.calendar
        // even if "Allow All Other Apps" is enabled.
        if (pkg == AppConfigConstants.GOOGLE_CALENDAR_PACKAGE) {
            return isFilterEnabled(KEY_ALLOW_CALENDAR, true)
        }

        for (spec in appFilterSpecs) {
            if (spec.matcher(context, pkg, packageName)) {
                return isFilterEnabled(spec.prefKey, spec.defaultEnabled)
            }
        }

        return isAllowAllAppsEnabled()
    }

    private fun resolveFirebaseDatabase(): FirebaseDatabase {
        val customUrl = getDatabaseUrl()
        return if (customUrl.isNotEmpty()) {
            FirebaseDatabase.getInstance(customUrl)
        } else {
            FirebaseDatabase.getInstance()
        }
    }

    fun resolveDatabaseReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(getNodePath())

    fun resolveTasksReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_TASKS_NODE)

    fun resolveClockThemeReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_CLOCK_THEME_PATH)

    fun resolveTimetableCommandReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_TIMETABLE_COMMAND_PATH)

    fun resolvePcStatsReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_PC_STATS_PATH)

    fun resolvePcMonitorEnabledReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_PC_MONITOR_ENABLED_PATH)

    fun resolvePcFullScreenReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_PC_FULLSCREEN_PATH)

    fun resolveConnectedInfoReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(".info/connected")

    fun resolveBatteryConfigReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_BATTERY_CONFIG_PATH)

    fun resolveBatteryStatusReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_BATTERY_STATUS_PATH)

    fun resolveServerTimeOffsetReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(".info/serverTimeOffset")

    // V7 Firebase references
    fun resolvePhoneBatteryReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_PHONE_BATTERY_PATH)

    fun resolvePhonePresenceLastSeenReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_PHONE_PRESENCE_LAST_SEEN_PATH)

    fun resolvePhoneDndReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_PHONE_DND_PATH)

    fun resolveNightScheduleReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_NIGHT_SCHEDULE_PATH)

    fun resolvePixelShiftReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_PIXEL_SHIFT_PATH)

    fun resolveOtpAlertsReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_OTP_ALERTS_PATH)

    fun resolveProximityConfigReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_PROXIMITY_CONFIG_PATH)

    fun resolveTabletScreenStatusReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_TABLET_SCREEN_STATUS_PATH)

    fun resolveMusicModeReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_MUSIC_MODE_PATH)

    // V8 Alexa Voice Control Firebase references
    fun resolveAlexaEnabledReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_ALEXA_ENABLED_PATH)

    fun resolveAlexaStatusReference(): DatabaseReference =
        resolveFirebaseDatabase().getReference(AppConfigConstants.FIREBASE_ALEXA_STATUS_PATH)

    companion object {
        private const val PREFS_NAME = "smart_desk_phone_sender_prefs"
        private const val KEY_CUSTOM_DB_URL = "custom_firebase_db_url"
        private const val KEY_NODE_PATH = "firebase_node_path"
        const val KEY_ALLOW_WHATSAPP = "allow_whatsapp"
        const val KEY_ALLOW_INSTAGRAM = "allow_instagram"
        const val KEY_ALLOW_GMAIL = "allow_gmail"
        const val KEY_ALLOW_CALENDAR = "allow_calendar"
        const val KEY_ALLOW_CALLS = "allow_calls"
        const val KEY_ALLOW_SMS = "allow_sms"
        private const val KEY_ALLOW_ALL_APPS = "allow_all_apps"
        private const val KEY_SELECTED_CLOCK_THEME = "selected_clock_theme_id"
        private const val KEY_PC_FULLSCREEN_ENABLED = "pc_fullscreen_enabled"

        const val KEY_OTP_ALERTS_ENABLED = "otp_alerts_enabled"
        const val KEY_OTP_SMS_ENABLED = "otp_sms_enabled"
        const val KEY_OTP_WHATSAPP_ENABLED = "otp_whatsapp_enabled"
        const val KEY_OTP_GMAIL_ENABLED = "otp_gmail_enabled"
        const val KEY_OTP_REDACTION_DETECTED = "otp_sensitive_redaction_detected"

        const val DEFAULT_NODE_PATH = AppConfigConstants.FIREBASE_NOTIFICATIONS_NODE

        private val _recentForwarded = MutableStateFlow<List<ForwardedAlertItem>>(emptyList())
        val recentForwarded: StateFlow<List<ForwardedAlertItem>> = _recentForwarded.asStateFlow()

        private val _lastSyncStatus = MutableStateFlow("Ready to relay phone notifications")
        val lastSyncStatus: StateFlow<String> = _lastSyncStatus.asStateFlow()

        private val _otpRedactionDetected = MutableStateFlow(false)
        val otpRedactionDetected: StateFlow<Boolean> = _otpRedactionDetected.asStateFlow()

        fun recordForwarded(item: ForwardedAlertItem, statusText: String) {
            val updated = (listOf(item) + _recentForwarded.value).take(5)
            _recentForwarded.value = updated
            _lastSyncStatus.value = statusText
        }

        fun updateStatus(statusText: String) {
            _lastSyncStatus.value = statusText
        }
    }
}
