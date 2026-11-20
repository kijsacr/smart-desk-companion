package com.smartdesk.companion.tablet.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * V7 Section 6.3 (Method A — Recommended):
 * DeviceAdminReceiver with `USES_POLICY_FORCE_LOCK` so `TabletBatteryProtectionService`
 * can call `DevicePolicyManager.lockNow()` when the phone hotspot/presence goes away
 * for `offDelayMin` minutes.
 *
 * One-time setup via ADB:
 * `adb shell dpm set-active-admin com.smartdesk.companion.tablet/.receiver.TabletDeviceAdminReceiver`
 */
class TabletDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }

    companion object {
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, TabletDeviceAdminReceiver::class.java)
    }
}
