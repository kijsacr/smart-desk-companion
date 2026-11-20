package com.smartdesk.companion.tablet.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import com.smartdesk.companion.tablet.MainActivity

/**
 * V9 Section 3:
 * Tablet Boot & Watchdog Receiver.
 *
 * - Auto-starts after reboot.
 * - Periodic AlarmManager watchdog pings ensure that if the app is killed or crashes,
 *   it relaunches into Performance Mode automatically without requiring any touch on the tablet.
 */
class TabletBootAndWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext

        val isModeEnabled = PerformanceStorageAndCacheManager.isPerformanceModeEnabled(appContext)

        if (action == Intent.ACTION_BOOT_COMPLETED || action == TabletPerformanceForegroundService.ACTION_WATCHDOG_PING) {
            if (isModeEnabled) {
                TabletPerformanceForegroundService.start(appContext)
                val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                appContext.startActivity(launchIntent)
            }
        }
    }
}
