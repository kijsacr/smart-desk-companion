package com.smartdesk.companion.phone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * V7 Section 5.1 & Section 6.2:
 * Starts `PhonePresenceAndBatteryService` automatically when the phone boots.
 */
class PhoneBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PhonePresenceAndBatteryService.start(context)
        }
    }
}
