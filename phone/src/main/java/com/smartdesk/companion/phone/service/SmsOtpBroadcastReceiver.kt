package com.smartdesk.companion.phone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.smartdesk.companion.phone.config.PhoneSyncPreferences
import com.smartdesk.companion.shared.OtpChannel
import com.smartdesk.companion.shared.OtpDetector
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V7 Section 4.1:
 * Primary SMS OTP path using `android.provider.Telephony.SMS_RECEIVED` (`RECEIVE_SMS` permission)
 * so the phone companion receives the full SMS body and sender ID directly, bypassing Android 15+
 * notification redaction ("Sensitive notification content hidden") for SMS messages.
 */
class SmsOtpBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val appContext = context.applicationContext
        // V9 Section 4: Pause notification/OTP syncing while Performance Mode is on
        if (PerformanceStorageAndCacheManager.isPerformanceModeEnabled(appContext)) {
            return
        }
        val syncPreferences = PhoneSyncPreferences(appContext)
        if (!syncPreferences.isOtpAlertsEnabled() || !syncPreferences.isOtpSmsEnabled()) {
            return
        }

        val messages = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrNull() ?: return

        if (messages.isEmpty()) return

        val senderAddress = messages.firstOrNull { !it.displayOriginatingAddress.isNullOrBlank() }
            ?.displayOriginatingAddress
            ?: messages.firstOrNull { !it.originatingAddress.isNullOrBlank() }
                ?.originatingAddress
            ?: ""

        val combinedBody = buildString {
            for (sms in messages) {
                val part = sms.displayMessageBody ?: sms.messageBody
                if (!part.isNullOrEmpty()) {
                    append(part)
                }
            }
        }.trim()

        if (combinedBody.isEmpty()) return

        val detection = OtpDetector.detect(
            channel = OtpChannel.SMS,
            senderOrHeader = senderAddress,
            bodyText = combinedBody
        ) ?: return

        val nowMs = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(nowMs))
        val defaultSmsPkg = runCatching {
            Telephony.Sms.getDefaultSmsPackage(appContext)
        }.getOrNull() ?: "com.google.android.apps.messaging"

        PhoneNotificationListenerService.pushOtpPayloadToFirebase(
            context = appContext,
            packageName = defaultSmsPkg,
            source = detection.source,
            otp = detection.code,
            timestamp = timestamp,
            epochMillis = nowMs
        )
    }
}
