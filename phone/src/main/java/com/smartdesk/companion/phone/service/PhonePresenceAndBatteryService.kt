package com.smartdesk.companion.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.database.ServerValue
import com.smartdesk.companion.phone.MainActivity
import com.smartdesk.companion.phone.config.PhoneSyncPreferences
import com.smartdesk.companion.shared.AppConfigConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * V7 Section 5.1, Section 6.2 & Section 10:
 * Foreground service running on the phone (Moto G67) that:
 * 1. Publishes `phone/battery` (`level`, `charging`, `updatedAt`) on every 1% change,
 *    on plug/unplug, and on a 5-minute heartbeat.
 * 2. Publishes `phone/presence/lastSeen` (server timestamp) every 60 seconds and immediately
 *    when network connectivity becomes available so the tablet's Proximity Screen-Off knows
 *    the phone is nearby.
 */
class PhonePresenceAndBatteryService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var syncPreferences: PhoneSyncPreferences
    private var currentBatteryLevel: Int = -1
    private var currentIsCharging: Boolean = false
    private var lastBatteryPublishedElapsedMs: Long = 0L

    private var heartbeatJob: Job? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered: Boolean = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publishPresenceHeartbeatNow()
            publishPhoneBatteryNow()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val (level, charging) = readCurrentBatteryState(intent)
            val changed = (level != currentBatteryLevel) || (charging != currentIsCharging)
            currentBatteryLevel = level
            currentIsCharging = charging
            if (changed) {
                publishPhoneBatteryNow()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        syncPreferences = PhoneSyncPreferences(applicationContext)
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val sticky = runCatching { registerReceiver(batteryReceiver, filter) }.getOrNull()
        if (sticky != null) {
            val (level, charging) = readCurrentBatteryState(sticky)
            currentBatteryLevel = level
            currentIsCharging = charging
        }

        registerNetworkCallback()
        publishPresenceHeartbeatNow()
        publishPhoneBatteryNow()
        startHeartbeatLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        publishPresenceHeartbeatNow()
        publishPhoneBatteryNow()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(batteryReceiver) }
        if (networkCallbackRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        serviceScope.cancel()
    }

    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching
            connectivityManager = cm
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(AppConfigConstants.PHONE_PRESENCE_HEARTBEAT_INTERVAL_MS)
                publishPresenceHeartbeatNow()

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastBatteryPublishedElapsedMs >= AppConfigConstants.PHONE_BATTERY_HEARTBEAT_INTERVAL_MS) {
                    refreshBatteryFromSystemAndPublish()
                }
            }
        }
    }

    private fun refreshBatteryFromSystemAndPublish() {
        val sticky = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        if (sticky != null) {
            val (level, charging) = readCurrentBatteryState(sticky)
            currentBatteryLevel = level
            currentIsCharging = charging
        }
        publishPhoneBatteryNow()
    }

    private fun publishPresenceHeartbeatNow() {
        runCatching {
            syncPreferences.resolvePhonePresenceLastSeenReference()
                .setValue(ServerValue.TIMESTAMP)
        }
    }

    private fun publishPhoneBatteryNow() {
        if (currentBatteryLevel !in 0..100) return
        lastBatteryPublishedElapsedMs = System.currentTimeMillis()
        runCatching {
            val payload = mapOf(
                "level" to currentBatteryLevel,
                "charging" to currentIsCharging,
                "updatedAt" to ServerValue.TIMESTAMP
            )
            syncPreferences.resolvePhoneBatteryReference().setValue(payload)
        }
    }

    private fun readCurrentBatteryState(intent: Intent): Pair<Int, Boolean> {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) {
            ((level.toFloat() / scale.toFloat()) * 100f).toInt().coerceIn(0, 100)
        } else {
            currentBatteryLevel.takeIf { it in 0..100 } ?: 100
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0 ||
            intent.action == Intent.ACTION_POWER_CONNECTED
        return pct to charging
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart Desk Phone Link",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Relays phone battery & presence heartbeat to your desk tablet"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Smart Desk Phone Link Active")
            .setContentText("Syncing battery & proximity presence with your tablet")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "smart_desk_phone_presence_channel"
        private const val NOTIFICATION_ID = 7201

        fun start(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, PhonePresenceAndBatteryService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }
    }
}
