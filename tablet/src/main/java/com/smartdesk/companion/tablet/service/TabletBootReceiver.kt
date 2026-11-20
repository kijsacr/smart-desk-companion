package com.smartdesk.companion.tablet.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * V6 Section 2.2:
 * Starts [TabletBatteryProtectionService] automatically when the Lenovo Yoga Tab 11 boots.
 */
class TabletBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            TabletBatteryProtectionService.start(context)
        }
    }
}
