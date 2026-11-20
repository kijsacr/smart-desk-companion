package com.smartdesk.companion.phone.service

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.smartdesk.companion.phone.config.ForwardedAlertItem
import com.smartdesk.companion.phone.config.PhoneSyncPreferences
import com.smartdesk.companion.phone.sync.PhoneTaskAndThemeManager
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.BatteryAutomationStatus
import com.smartdesk.companion.shared.OtpChannel
import com.smartdesk.companion.shared.OtpDetector
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Module B, Roadmap Step 4 & V7 Sections 4 & 7:
 * Phone Side Companion service utilizing Android's [NotificationListenerService]
 * to:
 * 1. Detect OTPs from SMS, WhatsApp, and Gmail (replacing the ordinary notification and
 *    deduplicating with `SmsOtpBroadcastReceiver` within 2 minutes).
 * 2. Capture incoming alerts (WhatsApp, Instagram, Gmail, Calendar, Calls, SMS) and push them
 *    to Firebase Realtime Database.
 * 3. Detect Do Not Disturb (DND / Flip to Shhh) via `onInterruptionFilterChanged` and publish
 *    `phone/dnd` so the tablet enters Music & Clock Mode.
 */
class PhoneNotificationListenerService : NotificationListenerService() {

    private lateinit var syncPreferences: PhoneSyncPreferences
    private var lastSignature: String = ""
    private var lastSignatureTimeMs: Long = 0L
    private var batteryStatusRef: DatabaseReference? = null
    private var batteryStatusListener: ValueEventListener? = null
    private var lastAlertedBatteryCommandAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        syncPreferences = PhoneSyncPreferences(applicationContext)
        PhonePresenceAndBatteryService.start(applicationContext)
        attachBatteryAlertListener()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        PhonePresenceAndBatteryService.start(applicationContext)
        runCatching {
            val currentFilter = currentInterruptionFilter
            publishPhoneDndFilter(currentFilter)
        }
    }

    /**
     * V7 Section 7.1:
     * Overrides `onInterruptionFilterChanged(int interruptionFilter)` to detect Moto G67
     * "Flip for DND" / Do Not Disturb and write `phone/dnd` to Firebase.
     */
    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        super.onInterruptionFilterChanged(interruptionFilter)
        publishPhoneDndFilter(interruptionFilter)
    }

    private fun publishPhoneDndFilter(interruptionFilter: Int) {
        runCatching {
            val isDndActive = interruptionFilter != INTERRUPTION_FILTER_ALL &&
                interruptionFilter != INTERRUPTION_FILTER_UNKNOWN
            val payload = mapOf(
                "active" to isDndActive,
                "updatedAt" to ServerValue.TIMESTAMP
            )
            syncPreferences.resolvePhoneDndReference().setValue(payload)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        batteryStatusListener?.let { l -> runCatching { batteryStatusRef?.removeEventListener(l) } }
        batteryStatusRef = null
        batteryStatusListener = null
    }

    private fun attachBatteryAlertListener() {
        runCatching {
            val prefs = getSharedPreferences("smart_desk_phone_battery_alert_prefs", Context.MODE_PRIVATE)
            lastAlertedBatteryCommandAt = prefs.getLong("last_alerted_cmd_at", 0L)

            val ref = syncPreferences.resolveBatteryStatusReference()
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val status = BatteryAutomationStatus.fromSnapshot(snapshot)
                    val cmdAt = status.lastCommandAt
                    if (cmdAt <= 0L || cmdAt == lastAlertedBatteryCommandAt) return

                    // Ignore stale events older than 2 minutes when service first starts
                    val ageMs = kotlin.math.abs(System.currentTimeMillis() - cmdAt)
                    lastAlertedBatteryCommandAt = cmdAt
                    prefs.edit().putLong("last_alerted_cmd_at", cmdAt).apply()
                    if (ageMs > 120_000L) return

                    val pctText = if (status.batteryLevel in 0..100) "${status.batteryLevel}%" else ""
                    when {
                        status.isError -> {
                            PhoneTaskAndThemeManager.postLocalPhoneBatteryNotification(
                                context = applicationContext,
                                title = "Tablet Battery Protection Error",
                                body = "Error: ${status.error ?: "webhook failed"}"
                            )
                        }
                        status.lastCommand == BatteryAutomationStatus.COMMAND_OFF && status.isHolding -> {
                            PhoneTaskAndThemeManager.postLocalPhoneBatteryNotification(
                                context = applicationContext,
                                title = "Tablet Battery Reached $pctText",
                                body = "Holding at charge limit — unplug tablet charger (or keep Lenovo Battery Protection on)."
                            )
                        }
                        status.lastCommand == BatteryAutomationStatus.COMMAND_ON &&
                            status.state == BatteryAutomationStatus.STATE_CHARGING -> {
                            PhoneTaskAndThemeManager.postLocalPhoneBatteryNotification(
                                context = applicationContext,
                                title = "Tablet Battery Low ($pctText)",
                                body = "Battery dropped to restore threshold — please plug in tablet charger."
                            )
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            ref.addValueEventListener(listener)
            batteryStatusRef = ref
            batteryStatusListener = listener
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // V9 Section 4: Pause notification syncing while Performance Mode is on
        if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(applicationContext)) {
            return
        }
        val notification = sbn.notification ?: return

        // Ignore group summary stub notifications (e.g. "3 new messages" header)
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        val packageName = sbn.packageName ?: return
        val pkgLower = packageName.lowercase(Locale.US)
        val extras = notification.extras ?: return

        // Never forward media player notifications (Spotify, media transport sessions, etc.)
        if (notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        ) {
            return
        }

        // Ignore ongoing background service notifications unless it is an incoming/ongoing call
        val isCallCategory = notification.category == Notification.CATEGORY_CALL ||
            pkgLower.contains("dialer") ||
            pkgLower.contains("incallui") ||
            pkgLower.contains("telecom")

        if (sbn.isOngoing && !isCallCategory) {
            return
        }

        val rawConversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val rawBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val rawTextLines = runCatching {
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n") { it.toString() }
                .orEmpty()
        }.getOrDefault("")

        // V7 Section 4.1: OTP detection runs BEFORE the normal App Filters check
        if (syncPreferences.isOtpAlertsEnabled()) {
            val otpChannel: OtpChannel? = when {
                syncPreferences.isSmsPackage(applicationContext, pkgLower, packageName) &&
                    syncPreferences.isOtpSmsEnabled() -> OtpChannel.SMS
                (pkgLower == "com.whatsapp" || pkgLower == "com.whatsapp.w4b") &&
                    syncPreferences.isOtpWhatsappEnabled() -> OtpChannel.WHATSAPP
                (pkgLower == "com.google.android.gm" || pkgLower.contains("gmail")) &&
                    syncPreferences.isOtpGmailEnabled() -> OtpChannel.GMAIL
                else -> null
            }

            if (otpChannel != null) {
                val combinedTextForCheck = listOf(rawBigText, rawText, rawTextLines, rawSubText)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                val headerForOtp = rawConversationTitle.ifBlank { rawTitle }

                if (OtpDetector.isRedactedSensitivePlaceholder(rawTitle) ||
                    OtpDetector.isRedactedSensitivePlaceholder(rawText) ||
                    OtpDetector.isRedactedSensitivePlaceholder(rawBigText)
                ) {
                    syncPreferences.markSensitiveRedactionDetected(true)
                } else {
                    val otpDetection = OtpDetector.detect(
                        channel = otpChannel,
                        messageText = combinedTextForCheck,
                        senderOrTitle = headerForOtp,
                        subjectText = rawTitle.takeIf { it != headerForOtp }
                    )
                    if (otpDetection != null) {
                        val nowMs = System.currentTimeMillis()
                        val timestamp = SimpleDateFormat("h:mm a", Locale.getDefault())
                            .format(Date(sbn.postTime.takeIf { it > 0L } ?: nowMs))
                        pushOtpPayloadToFirebase(
                            context = applicationContext,
                            packageName = packageName,
                            source = otpDetection.source,
                            otp = otpDetection.code,
                            timestamp = timestamp,
                            epochMillis = nowMs
                        )
                        // Section 4.4: Replace ordinary notification instead of duplicating
                        return
                    }
                }
            }
        }

        // Standard App Filter check for non-OTP notifications
        if (!syncPreferences.shouldForwardPackage(packageName)) {
            return
        }

        val isInstagram = pkgLower.contains("instagram")
        val (resolvedTitle, resolvedMessage) = if (isInstagram) {
            extractInstagramSenderAndMessage(extras)
        } else {
            val titleOut = rawConversationTitle.takeIf { it.isNotBlank() } ?: rawTitle
            val messageOut = rawBigText.takeIf { it.isNotBlank() }
                ?: rawText.takeIf { it.isNotBlank() }
                ?: rawSubText
            titleOut to messageOut
        }

        if (resolvedTitle.isBlank() && resolvedMessage.isBlank()) {
            return
        }

        val nowMs = System.currentTimeMillis()
        val signature = "$packageName|$resolvedTitle|$resolvedMessage"
        if (signature == lastSignature && (nowMs - lastSignatureTimeMs) < 3_000L) {
            return
        }
        lastSignature = signature
        lastSignatureTimeMs = nowMs

        val timestamp = SimpleDateFormat("h:mm a", Locale.getDefault())
            .format(Date(sbn.postTime.takeIf { it > 0L } ?: nowMs))

        pushPayloadToFirebase(
            context = applicationContext,
            packageName = packageName,
            title = resolvedTitle,
            message = resolvedMessage,
            timestamp = timestamp,
            epochMillis = nowMs
        )
    }

    /**
     * Extracts the actual DM sender's name and message body for Instagram notifications,
     * stripping out the recipient account handle that Instagram places in
     * `EXTRA_CONVERSATION_TITLE`, `EXTRA_TITLE`, or `(account_name):` prefixes.
     */
    private fun extractInstagramSenderAndMessage(extras: Bundle): Pair<String, String> {
        val accountBracketPrefix = Regex("""^(?:\([^)]+\)|\[[^\]]+\])\s*:?\s*""")
        val actionVerbRegex = Regex(
            """^([A-Za-z0-9._]+)\s+(sent|liked|reacted|mentioned|shared|replied|tagged|started)\b(.*)$""",
            RegexOption.IGNORE_CASE
        )

        // 1. Check MessagingStyle EXTRA_MESSAGES for the latest message's direct sender & text
        var msgStyleSender: String? = null
        var msgStyleText: String? = null
        runCatching {
            @Suppress("DEPRECATION")
            val parcelables = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            val lastBundle = parcelables?.lastOrNull() as? Bundle
            if (lastBundle != null) {
                val personName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    (lastBundle.getParcelable("sender_person") as? Person)?.name?.toString()
                } else {
                    null
                }
                msgStyleSender = (personName ?: lastBundle.getCharSequence("sender")?.toString())
                    ?.replace(accountBracketPrefix, "")
                    ?.trim()
                msgStyleText = lastBundle.getCharSequence("text")?.toString()
                    ?.replace(accountBracketPrefix, "")
                    ?.trim()
            }
        }

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            .replace(accountBracketPrefix, "")
            .trim()
        val rawBody = (
            msgStyleText?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ).orEmpty()
            .replace(accountBracketPrefix, "")
            .trim()

        // 2. If the body itself is formatted as "sender_name: message", split out the sender!
        val colonIdx = rawBody.indexOf(": ")
        if (colonIdx in 1..40) {
            val candidateSender = rawBody.substring(0, colonIdx)
                .replace(accountBracketPrefix, "")
                .trim()
            val remainderMsg = rawBody.substring(colonIdx + 2).trim()
            if (candidateSender.isNotEmpty() && !candidateSender.contains("http", ignoreCase = true)) {
                return candidateSender to remainderMsg
            }
        }

        // 3. If the body is an Instagram action like "sender_username sent a reel..."
        val actionMatch = actionVerbRegex.find(rawBody)
        if (actionMatch != null) {
            val actor = actionMatch.groupValues[1].trim()
            val verbAndRest = (actionMatch.groupValues[2] + actionMatch.groupValues[3]).trim()
            return actor to verbAndRest
        }

        // 4. Prefer MessagingStyle sender (the person who sent the message) over EXTRA_CONVERSATION_TITLE
        val bestSender = msgStyleSender?.takeIf {
            it.isNotBlank() && !it.equals("Instagram", ignoreCase = true)
        } ?: rawTitle.takeIf {
            it.isNotBlank() && !it.equals("Instagram", ignoreCase = true)
        }.orEmpty()

        return bestSender to rawBody
    }

    companion object {
        private const val MAX_FIREBASE_RING_SIZE = 5
        private val recentOtpDedupLock = Any()
        private val recentOtpCache = mutableMapOf<String, Long>()

        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = ComponentName(context, PhoneNotificationListenerService::class.java)
                .flattenToString()
            return flat.split(":").any { it.equals(target, ignoreCase = true) }
        }

        /**
         * V7 Section 4.4:
         * Pushes an OTP notification payload to `notifications/<pushId>` with:
         * - `isOtp = true`
         * - `source = source`
         * - `otp = otp`
         * - `title = source`
         * - `message = otp` (only the OTP code, never the rest of the SMS/email text)
         * - `sentAt = ServerValue.TIMESTAMP`
         * Deduplicates identical `(source, otp)` within 2 minutes unless `bypassDeduplication = true`.
         */
        fun pushOtpPayloadToFirebase(
            context: Context,
            packageName: String,
            source: String,
            otp: String,
            timestamp: String,
            epochMillis: Long = System.currentTimeMillis(),
            bypassDeduplication: Boolean = false
        ) {
            if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(context)) {
                return
            }
            val normalizedSource = source.trim().ifEmpty { "Unknown" }
            val normalizedOtp = otp.trim()
            if (normalizedOtp.isEmpty()) return

            if (!bypassDeduplication) {
                val dedupKey = "${normalizedSource.lowercase(Locale.US)}|$normalizedOtp"
                synchronized(recentOtpDedupLock) {
                    val now = System.currentTimeMillis()
                    val iterator = recentOtpCache.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (now - entry.value > AppConfigConstants.OTP_DEDUP_WINDOW_MS) {
                            iterator.remove()
                        }
                    }
                    val previousAt = recentOtpCache[dedupKey]
                    if (previousAt != null && (now - previousAt) < AppConfigConstants.OTP_DEDUP_WINDOW_MS) {
                        return
                    }
                    recentOtpCache[dedupKey] = now
                }
            }

            val prefs = PhoneSyncPreferences(context)
            try {
                val ref = prefs.resolveDatabaseReference()
                val key = ref.push().key ?: "otp_$epochMillis"
                val payload = mapOf(
                    "packageName" to packageName,
                    "title" to normalizedSource,
                    "message" to normalizedOtp,
                    "timestamp" to timestamp,
                    "epochMillis" to epochMillis,
                    "isOtp" to true,
                    "source" to normalizedSource,
                    "otp" to normalizedOtp,
                    "sentAt" to ServerValue.TIMESTAMP
                )

                ref.child(key).setValue(payload)
                    .addOnSuccessListener {
                        PhoneSyncPreferences.recordForwarded(
                            item = ForwardedAlertItem(
                                packageName = "$packageName (OTP)",
                                title = normalizedSource,
                                message = normalizedOtp,
                                timestamp = timestamp
                            ),
                            statusText = "OTP ($normalizedSource: $normalizedOtp) synced at $timestamp"
                        )
                        pruneOldNotifications(ref)
                    }
                    .addOnFailureListener { err ->
                        PhoneSyncPreferences.updateStatus(
                            "OTP push failed: ${err.localizedMessage}"
                        )
                    }
            } catch (e: Exception) {
                PhoneSyncPreferences.updateStatus(
                    "Firebase error: ${e.localizedMessage ?: "Check Database URL"}"
                )
            }
        }

        /**
         * V7 Section 4.7:
         * Pushes a sample OTP (`source = "Swiggy"`, `code = "482913"`) so the user can verify
         * the 20s full-screen takeover and card replacement without waiting for a real SMS.
         */
        fun sendSampleTestOtp(context: Context) {
            val nowMs = System.currentTimeMillis()
            val timestamp = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(nowMs))
            pushOtpPayloadToFirebase(
                context = context.applicationContext,
                packageName = "com.google.android.apps.messaging",
                source = "Swiggy",
                otp = "482913",
                timestamp = timestamp,
                epochMillis = nowMs,
                bypassDeduplication = true
            )
        }

        fun pushPayloadToFirebase(
            context: Context,
            packageName: String,
            title: String,
            message: String,
            timestamp: String,
            epochMillis: Long = System.currentTimeMillis()
        ) {
            if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(context)) {
                return
            }
            val prefs = PhoneSyncPreferences(context)
            try {
                val ref = prefs.resolveDatabaseReference()
                val key = ref.push().key ?: "notif_$epochMillis"
                val payload = mapOf(
                    "packageName" to packageName,
                    "title" to title,
                    "message" to message,
                    "timestamp" to timestamp,
                    "epochMillis" to epochMillis
                )

                ref.child(key).setValue(payload)
                    .addOnSuccessListener {
                        PhoneSyncPreferences.recordForwarded(
                            item = ForwardedAlertItem(
                                packageName = packageName,
                                title = title,
                                message = message,
                                timestamp = timestamp
                            ),
                            statusText = "Synced to Firebase at $timestamp"
                        )
                        pruneOldNotifications(ref)
                    }
                    .addOnFailureListener { err ->
                        PhoneSyncPreferences.updateStatus(
                            "Firebase push failed: ${err.localizedMessage}"
                        )
                    }
            } catch (e: Exception) {
                PhoneSyncPreferences.updateStatus(
                    "Firebase error: ${e.localizedMessage ?: "Check Database URL"}"
                )
            }
        }

        private fun pruneOldNotifications(ref: com.google.firebase.database.DatabaseReference) {
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val children = snapshot.children.toList()
                    if (children.size <= MAX_FIREBASE_RING_SIZE) return

                    val sorted = children.sortedByDescending { child ->
                        child.child("epochMillis").getValue(Long::class.java) ?: 0L
                    }
                    sorted.drop(MAX_FIREBASE_RING_SIZE).forEach { stale ->
                        stale.ref.removeValue()
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            })
        }
    }
}
