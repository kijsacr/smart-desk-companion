package com.smartdesk.companion.tablet.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight NotificationListenerService running on the Lenovo Yoga Tab 11.
 *
 * Android's [android.media.session.MediaSessionManager.getActiveSessions] requires a registered
 * NotificationListenerService ComponentName to inspect and control active media sessions from
 * apps like Spotify (`com.spotify.music`) without requiring Spotify Developer OAuth keys.
 */
class TabletMediaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        _sessionRefreshTrigger.value = System.currentTimeMillis()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == SPOTIFY_PACKAGE || sbn?.notification?.extras?.containsKey("android.mediaSession") == true) {
            _sessionRefreshTrigger.value = System.currentTimeMillis()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == SPOTIFY_PACKAGE) {
            _sessionRefreshTrigger.value = System.currentTimeMillis()
        }
    }

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _sessionRefreshTrigger = MutableStateFlow(0L)
        val sessionRefreshTrigger: StateFlow<Long> = _sessionRefreshTrigger.asStateFlow()

        fun componentName(context: Context): ComponentName =
            ComponentName(context, TabletMediaNotificationListenerService::class.java)

        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = componentName(context).flattenToString()
            return flat.split(":").any { it.equals(target, ignoreCase = true) }
        }
    }
}
