@file:Suppress("SpellCheckingInspection", "DEPRECATION", "ObsoleteSdkInt")

package com.smartdesk.companion.shared

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max

/**
 * V9 Section 11: Connectivity Status Model for Phone & Tablet.
 */
data class PerformanceLinkStatus(
    val bleHealthy: Boolean = false,
    val firebaseHealthy: Boolean = false,
    val activeRoute: ActiveControlRoute = ActiveControlRoute.FIREBASE,
    val btRttMs: Long? = null,
    val netRttMs: Long? = null,
    val roleDescription: String = "Initializing"
) {
    enum class ActiveControlRoute {
        BLE,
        FIREBASE,
        NONE
    }

    fun formatDiagnosticsFooter(): String {
        val btPart = btRttMs?.let { "BT $it ms" } ?: if (bleHealthy) "BT <45 ms" else "BT -- ms"
        val netPart = netRttMs?.let { "Net $it ms" } ?: if (firebaseHealthy) "Net ~140 ms" else "Net -- ms"
        return "$btPart / $netPart"
    }
}

/**
 * V9 Section 11: Phone-side Live Controller Transport (BLE First + Instant Firebase Fallback).
 *
 * Handles:
 * - Connecting as BLE GATT Client to Tablet's advertised service `7a9e0001-4c8d-4f21-b863-59d4e21a9001`
 *   (or automatically reversing roles to advertise as GATT Server if the tablet reports
 *   `bleRoleOverride = "phone_peripheral"` because it lacks multiple advertisement support).
 * - HMAC-SHA256 challenge/response authentication using `performance/bleSecret` so no pairing prompt
 *   is ever triggered on the untouchable tablet.
 * - Requesting `CONNECTION_PRIORITY_HIGH` and `MTU = 185`.
 * - 60 Hz continuous gesture throttling & coalescing on BLE; 12–15 Hz on `/performance/live`.
 * - 500 ms heartbeat with RTT tracking; instant failover to Firebase on write failure, disconnect,
 *   or 2 missed heartbeats; 300 ms discrete command ACK timeout; 4-second stability window before
 *   switching back to BLE.
 */
@SuppressLint("MissingPermission")
class PhonePerformanceTransportController(
    private val context: Context
) {
    companion object {
        private const val TAG = "PerfBlePhone"
        private const val ROLE_OVERRIDE_PATH = "performance/bleRoleOverride"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val db = PerfFirebase.database()
    private val stateRef = db.getReference(AppConfigConstants.FIREBASE_PERF_STATE_PATH)
    private val liveRef = db.getReference(AppConfigConstants.FIREBASE_PERF_LIVE_PATH)
    private val ackRef = db.getReference(AppConfigConstants.FIREBASE_PERF_ACK_PATH)
    private val connectedRef = db.getReference(".info/connected")
    private val roleOverrideRef = db.getReference(ROLE_OVERRIDE_PATH)

    private val _linkStatus = MutableStateFlow(PerformanceLinkStatus())
    val linkStatus: StateFlow<PerformanceLinkStatus> = _linkStatus.asStateFlow()

    private var isStarted = false
    private var sharedSecret = "sdc_ble_default_shared_secret_v9"
    private var epoch: Long = System.currentTimeMillis()
    private var sequenceNumber: Long = 0L

    // BLE State
    private var useReversedPeripheralRole = false
    private var bluetoothGatt: BluetoothGatt? = null
    private var gattControlChar: BluetoothGattCharacteristic? = null
    private var gattAckChar: BluetoothGattCharacteristic? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverConnectedClient: BluetoothDevice? = null
    private var serverControlChar: BluetoothGattCharacteristic? = null
    private var serverAckChar: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isScanning = false
    private var isAdvertising = false

    @Volatile
    private var bleAuthenticated = false
    @Volatile
    private var bleAuthenticatedSinceMs = 0L
    @Volatile
    private var lastBlePongAtMs = 0L
    @Volatile
    private var lastBlePingSentAtMs = 0L
    @Volatile
    private var missedHeartbeats = 0
    @Volatile
    private var bleBackoffMs = 1500L

    @Volatile
    private var firebaseConnected = false
    @Volatile
    private var lastNetPingSentAtMs = 0L

    // Throttling & Coalescing
    private var pendingContinuousMessage: PerformanceControlMessage? = null
    private var lastBleSendAtMs = 0L
    private var lastFirebaseLiveSendAtMs = 0L
    private var lastSnapshotSaveAtMs = 0L
    private var flushScheduled = false

    // Pending discrete command ACKs (seq -> runnable)
    private val pendingAckTimeouts = HashMap<Long, Runnable>()

    private val firebaseConnListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            firebaseConnected = snapshot.getValue(Boolean::class.java) ?: false
            refreshLinkStatus()
        }

        override fun onCancelled(error: DatabaseError) {
            firebaseConnected = false
            refreshLinkStatus()
        }
    }

    private val firebaseAckListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val ackSeq = snapshot.child("q").getValue(Long::class.java) ?: return
            val clientTs = snapshot.child("ts").getValue(Long::class.java) ?: 0L
            onDiscreteAckReceived(ackSeq)
            if (clientTs > 0L) {
                val rtt = (System.currentTimeMillis() - clientTs).coerceIn(5L, 5000L)
                _linkStatus.value = _linkStatus.value.copy(netRttMs = rtt)
            }
        }

        override fun onCancelled(error: DatabaseError) {}
    }

    private val roleOverrideListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val overrideVal = snapshot.getValue(String::class.java).orEmpty()
            val shouldReverse = overrideVal == "phone_peripheral"
            if (shouldReverse != useReversedPeripheralRole && isStarted) {
                Log.i(TAG, "BLE role override changed to '$overrideVal' (reversed=$shouldReverse)")
                useReversedPeripheralRole = shouldReverse
                restartBleLink()
            }
        }

        override fun onCancelled(error: DatabaseError) {}
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isStarted) return
            val now = System.currentTimeMillis()

            if (bleAuthenticated) {
                if (lastBlePingSentAtMs > lastBlePongAtMs && (now - lastBlePingSentAtMs) >= 480L) {
                    missedHeartbeats++
                    if (missedHeartbeats >= AppConfigConstants.PERF_BLE_MISSED_HEARTBEATS_LIMIT) {
                        Log.w(TAG, "Missed $missedHeartbeats BLE heartbeats — failing over to Firebase immediately")
                        onBleFailureDetected()
                    }
                }
                sendBlePing(now)
            }

            // Refresh status so 4s stability window can promote BLE when ready
            refreshLinkStatus()
            mainHandler.postDelayed(this, AppConfigConstants.PERF_BLE_HEARTBEAT_INTERVAL_MS)
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        epoch = max(System.currentTimeMillis(), epoch + 1)

        connectedRef.addValueEventListener(firebaseConnListener)
        ackRef.addValueEventListener(firebaseAckListener)
        roleOverrideRef.addValueEventListener(roleOverrideListener)

        PerformanceStorageAndCacheManager.getOrCreateBleSharedSecret(context) { secret ->
            sharedSecret = secret
            if (isStarted) {
                startBleRole()
            }
        }

        mainHandler.postDelayed(heartbeatRunnable, AppConfigConstants.PERF_BLE_HEARTBEAT_INTERVAL_MS)
        refreshLinkStatus()
    }

    fun stop() {
        isStarted = false
        mainHandler.removeCallbacksAndMessages(null)
        pendingAckTimeouts.clear()
        runCatching { connectedRef.removeEventListener(firebaseConnListener) }
        runCatching { ackRef.removeEventListener(firebaseAckListener) }
        runCatching { roleOverrideRef.removeEventListener(roleOverrideListener) }
        teardownBle()
    }

    /**
     * Sends a continuous gesture update (scroll, zoom, pan).
     * Coalesces rapid movements and throttles to ~60 msg/s over BLE or ~13 msg/s over Firebase,
     * plus updates the recovery state snapshot every 2 seconds.
     */
    fun sendContinuousUpdate(
        type: String,
        snapshot: PerformanceStateSnapshot
    ) {
        sequenceNumber++
        val now = System.currentTimeMillis()
        val updatedSnap = snapshot.copy(epoch = epoch, seq = sequenceNumber, updatedAt = now)
        PerformanceStorageAndCacheManager.saveLocalStateSnapshot(context, updatedSnap)

        pendingContinuousMessage = PerformanceControlMessage(
            epoch = epoch,
            seq = sequenceNumber,
            type = type,
            modeEnabled = updatedSnap.enabled,
            sessionId = updatedSnap.sessionId,
            songId = updatedSnap.songId,
            sectionIndex = updatedSnap.sectionIndex,
            scrollY = updatedSnap.scrollY,
            zoom = updatedSnap.zoom,
            panX = updatedSnap.panX,
            panLocked = updatedSnap.panLocked,
            darkTheme = updatedSnap.darkTheme,
            requiresAck = false,
            sentAtMs = now
        )

        flushContinuousIfDue()

        if (now - lastSnapshotSaveAtMs >= AppConfigConstants.PERF_STATE_SNAPSHOT_INTERVAL_MS) {
            lastSnapshotSaveAtMs = now
            stateRef.setValue(updatedSnap.toMap())
        }
    }

    /**
     * Sends a discrete command (section switch, song switch, theme change, mode change, pan-lock).
     * Persists immediately to local & Firebase `/performance/state` for recovery, sends over BLE
     * if active, and enforces the 300ms ACK failover rule.
     */
    fun sendDiscreteCommand(
        type: String,
        snapshot: PerformanceStateSnapshot
    ) {
        sequenceNumber++
        val now = System.currentTimeMillis()
        val updatedSnap = snapshot.copy(epoch = epoch, seq = sequenceNumber, updatedAt = now)
        PerformanceStorageAndCacheManager.saveLocalStateSnapshot(context, updatedSnap)

        val msg = PerformanceControlMessage(
            epoch = epoch,
            seq = sequenceNumber,
            type = type,
            modeEnabled = updatedSnap.enabled,
            sessionId = updatedSnap.sessionId,
            songId = updatedSnap.songId,
            sectionIndex = updatedSnap.sectionIndex,
            scrollY = updatedSnap.scrollY,
            zoom = updatedSnap.zoom,
            panX = updatedSnap.panX,
            panLocked = updatedSnap.panLocked,
            darkTheme = updatedSnap.darkTheme,
            requiresAck = true,
            sentAtMs = now
        )

        // Always update persistent recovery state snapshot on discrete change (Section 11)
        lastSnapshotSaveAtMs = now
        stateRef.setValue(updatedSnap.toMap())

        if (isBleCurrentlyCarryingControl()) {
            val wroteOk = writeMessageOverBle(msg)
            if (!wroteOk) {
                onBleFailureDetected()
                sendOverFirebaseLive(msg)
            } else {
                // Schedule 300ms ACK timeout (Section 11 Failover rule 2)
                val seqKey = msg.seq
                val timeoutRunnable = Runnable {
                    if (pendingAckTimeouts.remove(seqKey) != null) {
                        Log.w(TAG, "Discrete cmd seq=$seqKey not ACKed in 300ms over BLE; sending via Firebase")
                        sendOverFirebaseLive(msg)
                    }
                }
                pendingAckTimeouts[seqKey] = timeoutRunnable
                mainHandler.postDelayed(timeoutRunnable, AppConfigConstants.PERF_BLE_DISCRETE_ACK_TIMEOUT_MS)
            }
        } else {
            sendOverFirebaseLive(msg)
        }
    }

    private fun flushContinuousIfDue() {
        val msg = pendingContinuousMessage ?: return
        val now = System.currentTimeMillis()
        val usingBle = isBleCurrentlyCarryingControl()
        val throttleMs = if (usingBle) {
            AppConfigConstants.PERF_BLE_CONTINUOUS_THROTTLE_MS
        } else {
            AppConfigConstants.PERF_FIREBASE_LIVE_THROTTLE_MS
        }
        val lastSent = if (usingBle) lastBleSendAtMs else lastFirebaseLiveSendAtMs
        val elapsed = now - lastSent

        if (elapsed >= throttleMs) {
            pendingContinuousMessage = null
            if (usingBle) {
                lastBleSendAtMs = now
                if (!writeMessageOverBle(msg)) {
                    onBleFailureDetected()
                    lastFirebaseLiveSendAtMs = now
                    sendOverFirebaseLive(msg)
                }
            } else {
                lastFirebaseLiveSendAtMs = now
                sendOverFirebaseLive(msg)
            }
        } else if (!flushScheduled) {
            flushScheduled = true
            mainHandler.postDelayed({
                flushScheduled = false
                flushContinuousIfDue()
            }, (throttleMs - elapsed).coerceAtLeast(4L))
        }
    }

    private fun sendOverFirebaseLive(msg: PerformanceControlMessage) {
        lastNetPingSentAtMs = msg.sentAtMs
        liveRef.setValue(msg.toFirebaseMap())
    }

    private fun isBleCurrentlyCarryingControl(): Boolean {
        if (!bleAuthenticated) return false
        val stableDuration = System.currentTimeMillis() - bleAuthenticatedSinceMs
        return stableDuration >= AppConfigConstants.PERF_BLE_STABILITY_WINDOW_MS || !firebaseConnected
    }

    private fun refreshLinkStatus() {
        val bleReady = bleAuthenticated
        val route = when {
            isBleCurrentlyCarryingControl() -> PerformanceLinkStatus.ActiveControlRoute.BLE
            firebaseConnected -> PerformanceLinkStatus.ActiveControlRoute.FIREBASE
            bleReady -> PerformanceLinkStatus.ActiveControlRoute.BLE
            else -> PerformanceLinkStatus.ActiveControlRoute.NONE
        }
        _linkStatus.value = _linkStatus.value.copy(
            bleHealthy = bleReady,
            firebaseHealthy = firebaseConnected,
            activeRoute = route,
            roleDescription = if (useReversedPeripheralRole) "BLE Peripheral (Reversed)" else "BLE Central"
        )
    }

    private fun onDiscreteAckReceived(seq: Long) {
        pendingAckTimeouts.remove(seq)?.let { mainHandler.removeCallbacks(it) }
    }

    // =========================================================================
    // BLE Client (Default on Phone) & Reversed GATT Server Fallback
    // =========================================================================

    private fun startBleRole() {
        teardownBle()
        if (!isStarted) return
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter unavailable or disabled; running on Firebase only")
            refreshLinkStatus()
            scheduleBleReconnect()
            return
        }

        if (useReversedPeripheralRole) {
            if (!adapter.isMultipleAdvertisementSupported) {
                Log.w(TAG, "Neither tablet nor phone supports BLE advertising; running on Firebase only")
                refreshLinkStatus()
                return
            }
            startPhoneAsGattServer(btManager, adapter)
        } else {
            startPhoneAsGattClientScanner(adapter)
        }
    }

    private fun restartBleLink() {
        mainHandler.post { startBleRole() }
    }

    private fun onBleFailureDetected() {
        bleAuthenticated = false
        bleAuthenticatedSinceMs = 0L
        missedHeartbeats = 0
        refreshLinkStatus()
        scheduleBleReconnect()
    }

    private fun scheduleBleReconnect() {
        if (!isStarted) return
        val delay = bleBackoffMs
        bleBackoffMs = (bleBackoffMs * 2).coerceAtMost(8000L)
        mainHandler.postDelayed({
            if (isStarted && !bleAuthenticated) {
                startBleRole()
            }
        }, delay)
    }

    private fun teardownBle() {
        bleAuthenticated = false
        bleAuthenticatedSinceMs = 0L
        runCatching {
            if (isScanning) {
                scanner?.stopScan(scanCallback)
                isScanning = false
            }
        }
        runCatching {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
            }
        }
        runCatching {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
        gattControlChar = null
        gattAckChar = null
        runCatching {
            gattServer?.close()
        }
        gattServer = null
        serverConnectedClient = null
    }

    private fun startPhoneAsGattClientScanner(adapter: BluetoothAdapter) {
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            Log.w(TAG, "BluetoothLeScanner unavailable; retrying later")
            scheduleBleReconnect()
            return
        }
        scanner = leScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(AppConfigConstants.PERF_BLE_SERVICE_UUID)))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching {
            leScanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.i(TAG, "Started BLE scan for service ${AppConfigConstants.PERF_BLE_SERVICE_UUID}")
        }.onFailure { e ->
            Log.w(TAG, "BLE startScan failed: ${e.message}")
            scheduleBleReconnect()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (isScanning) {
                runCatching { scanner?.stopScan(this) }
                isScanning = false
            }
            Log.i(TAG, "Found tablet BLE service on ${device.address}; connecting GATT…")
            bluetoothGatt = device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.w(TAG, "BLE scan failed with code=$errorCode")
            onBleFailureDetected()
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE GATT connected; requesting HIGH priority & MTU 185")
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                if (!runCatching { gatt.requestMtu(185) }.getOrDefault(false)) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "BLE GATT disconnected (status=$status)")
                mainHandler.post { onBleFailureDetected() }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "BLE MTU negotiated: $mtu (status=$status)")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { onBleFailureDetected() }
                return
            }
            val service = gatt.getService(UUID.fromString(AppConfigConstants.PERF_BLE_SERVICE_UUID))
            if (service == null) {
                mainHandler.post { onBleFailureDetected() }
                return
            }
            gattControlChar = service.getCharacteristic(UUID.fromString(AppConfigConstants.PERF_BLE_CONTROL_CHAR_UUID))
            gattAckChar = service.getCharacteristic(UUID.fromString(AppConfigConstants.PERF_BLE_ACK_CHAR_UUID))

            val ackChar = gattAckChar
            if (gattControlChar == null || ackChar == null) {
                mainHandler.post { onBleFailureDetected() }
                return
            }

            // Enable notifications on ACK characteristic and read challenge
            gatt.setCharacteristicNotification(ackChar, true)
            val cccd = ackChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            } else {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(ackChar)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            gattAckChar?.let {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(it)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value ?: return
                handleAckOrChallengePayloadFromTablet(bytes)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: return
            handleAckOrChallengePayloadFromTablet(bytes)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "BLE write failed (status=$status); failing over to Firebase")
                mainHandler.post { onBleFailureDetected() }
            }
        }
    }

    private fun handleAckOrChallengePayloadFromTablet(bytes: ByteArray) {
        runCatching {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            val challenge = obj.optString("c", "")
            if (challenge.isNotEmpty() && !bleAuthenticated) {
                val hmac = PerformanceCryptoAuth.computeChallengeResponse(sharedSecret, challenge)
                val authReply = JSONObject().apply {
                    put("auth", hmac)
                }.toString().toByteArray(Charsets.UTF_8)
                writeRawBytesOverBle(authReply)
                return
            }

            if (obj.optInt("authOk", 0) == 1) {
                val now = System.currentTimeMillis()
                if (!bleAuthenticated) {
                    bleAuthenticated = true
                    bleAuthenticatedSinceMs = now
                    bleBackoffMs = 1500L
                    missedHeartbeats = 0
                    Log.i(TAG, "BLE link authenticated with HMAC-SHA256 shared secret!")
                }
                lastBlePongAtMs = now
                mainHandler.post { refreshLinkStatus() }
            }

            val pongTs = obj.optLong("pong", 0L)
            if (pongTs > 0L) {
                val now = System.currentTimeMillis()
                lastBlePongAtMs = now
                missedHeartbeats = 0
                val rtt = (now - pongTs).coerceIn(1L, 2000L)
                _linkStatus.value = _linkStatus.value.copy(btRttMs = rtt)
            }

            val ackSeq = obj.optLong("ack", -1L)
            if (ackSeq >= 0L) {
                mainHandler.post { onDiscreteAckReceived(ackSeq) }
            }
        }
    }

    private fun sendBlePing(nowMs: Long) {
        lastBlePingSentAtMs = nowMs
        val pingBytes = JSONObject().apply {
            put("ping", nowMs)
        }.toString().toByteArray(Charsets.UTF_8)
        if (!writeRawBytesOverBle(pingBytes)) {
            onBleFailureDetected()
        }
    }

    private fun writeMessageOverBle(msg: PerformanceControlMessage): Boolean {
        return writeRawBytesOverBle(msg.toCompactJsonBytes())
    }

    private fun writeRawBytesOverBle(bytes: ByteArray): Boolean {
        return try {
            if (useReversedPeripheralRole) {
                val srv = gattServer ?: return false
                val dev = serverConnectedClient ?: return false
                val ch = serverControlChar ?: return false
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                srv.notifyCharacteristicChanged(dev, ch, false)
            } else {
                val gatt = bluetoothGatt ?: return false
                val ch = gattControlChar ?: return false
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE write exception: ${e.message}")
            false
        }
    }

    // Reversed role: Phone as GATT Server (only used if Tablet cannot advertise)
    private var currentServerChallenge = UUID.randomUUID().toString().take(12)

    private fun startPhoneAsGattServer(btManager: BluetoothManager, adapter: BluetoothAdapter) {
        val server = btManager.openGattServer(context, reversedServerCallback) ?: return
        gattServer = server

        val service = BluetoothGattService(
            UUID.fromString(AppConfigConstants.PERF_BLE_SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val controlChar = BluetoothGattCharacteristic(
            UUID.fromString(AppConfigConstants.PERF_BLE_CONTROL_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        val ackChar = BluetoothGattCharacteristic(
            UUID.fromString(AppConfigConstants.PERF_BLE_ACK_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(controlChar)
        service.addCharacteristic(ackChar)
        server.addService(service)
        serverControlChar = controlChar
        serverAckChar = ackChar

        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: return
        advertiser = leAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(AppConfigConstants.PERF_BLE_SERVICE_UUID)))
            .build()
        runCatching {
            leAdvertiser.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Reversed BLE peripheral advertising started on phone")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.w(TAG, "Reversed BLE advertising failed on phone: code=$errorCode; running on Firebase only")
        }
    }

    private val reversedServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverConnectedClient = device
                currentServerChallenge = UUID.randomUUID().toString().take(12)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (serverConnectedClient?.address == device.address) {
                    serverConnectedClient = null
                    mainHandler.post { onBleFailureDetected() }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val payload = JSONObject().apply { put("c", currentServerChallenge) }
                .toString().toByteArray(Charsets.UTF_8)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, payload)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (value != null) {
                handleAckOrChallengePayloadFromTablet(value)
            }
        }
    }
}

/**
 * V9 Section 11: Tablet-side Live Receiver (BLE GATT Server + Firebase `/performance/live` & `/performance/state`).
 *
 * - Checks `BluetoothAdapter.isMultipleAdvertisementSupported()`.
 *   - If supported: advertises custom service `7a9e0001-4c8d-4f21-b863-59d4e21a9001` as a GATT server
 *     with zero pairing prompt (`PERMISSION_READ` / `PERMISSION_WRITE`), and authenticates incoming
 *     connections with the HMAC-SHA256 challenge/response handshake using `performance/bleSecret`.
 *   - If not supported: sets `performance/bleRoleOverride = "phone_peripheral"` and scans as a GATT client.
 * - Deduplicates overlapping BLE and Firebase messages by tracking `(highestEpoch, highestSeq)`
 *   and ignoring anything older or duplicated.
 */
@SuppressLint("MissingPermission")
class TabletPerformanceTransportReceiver(
    private val context: Context,
    private val onControlMessageAccepted: (PerformanceControlMessage) -> Unit,
    private val onSnapshotRestored: (PerformanceStateSnapshot) -> Unit
) {
    companion object {
        private const val TAG = "PerfBleTablet"
        private const val ROLE_OVERRIDE_PATH = "performance/bleRoleOverride"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val db = PerfFirebase.database()
    private val stateRef = db.getReference(AppConfigConstants.FIREBASE_PERF_STATE_PATH)
    private val liveRef = db.getReference(AppConfigConstants.FIREBASE_PERF_LIVE_PATH)
    private val ackRef = db.getReference(AppConfigConstants.FIREBASE_PERF_ACK_PATH)
    private val connectedRef = db.getReference(".info/connected")
    private val roleOverrideRef = db.getReference(ROLE_OVERRIDE_PATH)

    private val _linkStatus = MutableStateFlow(PerformanceLinkStatus())
    val linkStatus: StateFlow<PerformanceLinkStatus> = _linkStatus.asStateFlow()

    private var isStarted = false
    private var sharedSecret = "sdc_ble_default_shared_secret_v9"

    // Deduplication watermark (V9 Section 11: "The tablet applies the highest (epoch, sequence) it has seen")
    @Volatile
    private var highestEpoch: Long = 0L
    @Volatile
    private var highestSeq: Long = 0L

    // BLE Server / Client state
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var isAdvertising = false
    private val challengeByAddress = HashMap<String, String>()
    private val authenticatedDevices = HashSet<String>()
    private var lastBleTrafficAtMs = 0L
    private var firebaseConnected = false

    private val firebaseConnListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            firebaseConnected = snapshot.getValue(Boolean::class.java) ?: false
            refreshLinkStatus()
        }

        override fun onCancelled(error: DatabaseError) {
            firebaseConnected = false
            refreshLinkStatus()
        }
    }

    private val stateListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            if (!snapshot.exists()) return
            val snap = PerformanceStateSnapshot.fromSnapshot(snapshot)
            if (snap.epoch > highestEpoch || (snap.epoch == highestEpoch && snap.seq >= highestSeq)) {
                highestEpoch = snap.epoch
                highestSeq = snap.seq
                PerformanceStorageAndCacheManager.saveLocalStateSnapshot(context, snap)
                mainHandler.post { onSnapshotRestored(snap) }
            }
        }

        override fun onCancelled(error: DatabaseError) {}
    }

    private val liveListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val msg = PerformanceControlMessage.fromSnapshot(snapshot) ?: return
            handleIncomingControlMessage(msg, fromBle = false)
        }

        override fun onCancelled(error: DatabaseError) {}
    }

    fun start() {
        if (isStarted) return
        isStarted = true

        // Restore local snapshot immediately so tablet never goes blank on start or offline
        val localSnap = PerformanceStorageAndCacheManager.loadLocalStateSnapshot(context)
        if (localSnap.epoch > 0L) {
            highestEpoch = localSnap.epoch
            highestSeq = localSnap.seq
            onSnapshotRestored(localSnap)
        }

        connectedRef.addValueEventListener(firebaseConnListener)
        stateRef.addValueEventListener(stateListener)
        liveRef.addValueEventListener(liveListener)

        PerformanceStorageAndCacheManager.getOrCreateBleSharedSecret(context) { secret ->
            sharedSecret = secret
            if (isStarted) {
                startTabletBleServer()
            }
        }
    }

    fun stop() {
        isStarted = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { connectedRef.removeEventListener(firebaseConnListener) }
        runCatching { stateRef.removeEventListener(stateListener) }
        runCatching { liveRef.removeEventListener(liveListener) }
        teardownBleServer()
    }

    private fun handleIncomingControlMessage(msg: PerformanceControlMessage, fromBle: Boolean) {
        if (!msg.isNewerThan(highestEpoch, highestSeq)) {
            // Even if duplicated, send ACK if requested so phone knows it arrived
            if (msg.requiresAck && !fromBle) {
                ackRef.setValue(mapOf("e" to msg.epoch, "q" to msg.seq, "ts" to msg.sentAtMs))
            }
            return
        }
        highestEpoch = msg.epoch
        highestSeq = msg.seq

        if (fromBle) {
            lastBleTrafficAtMs = System.currentTimeMillis()
        } else {
            val netRtt = (System.currentTimeMillis() - msg.sentAtMs).coerceIn(5L, 5000L)
            _linkStatus.value = _linkStatus.value.copy(netRttMs = netRtt)
            if (msg.requiresAck) {
                ackRef.setValue(mapOf("e" to msg.epoch, "q" to msg.seq, "ts" to msg.sentAtMs))
            }
        }

        // Persist locally on tablet so crash/power loss recovery is instantaneous
        val snap = PerformanceStateSnapshot(
            enabled = msg.modeEnabled,
            sessionId = msg.sessionId,
            songId = msg.songId,
            sectionIndex = msg.sectionIndex,
            scrollY = msg.scrollY,
            zoom = msg.zoom,
            panX = msg.panX,
            panLocked = msg.panLocked,
            darkTheme = msg.darkTheme,
            epoch = msg.epoch,
            seq = msg.seq,
            updatedAt = System.currentTimeMillis()
        )
        PerformanceStorageAndCacheManager.saveLocalStateSnapshot(context, snap)
        refreshLinkStatus()
        mainHandler.post { onControlMessageAccepted(msg) }
    }

    private fun refreshLinkStatus() {
        val bleHealthy = authenticatedDevices.isNotEmpty() &&
            (System.currentTimeMillis() - lastBleTrafficAtMs) < 3500L
        val route = when {
            bleHealthy -> PerformanceLinkStatus.ActiveControlRoute.BLE
            firebaseConnected -> PerformanceLinkStatus.ActiveControlRoute.FIREBASE
            else -> PerformanceLinkStatus.ActiveControlRoute.NONE
        }
        _linkStatus.value = _linkStatus.value.copy(
            bleHealthy = bleHealthy,
            firebaseHealthy = firebaseConnected,
            activeRoute = route
        )
    }

    private fun startTabletBleServer() {
        teardownBleServer()
        if (!isStarted) return

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth disabled or missing on tablet; running on Firebase only")
            refreshLinkStatus()
            return
        }

        // V9 Section 11: Check whether the tablet supports multiple advertising
        if (!adapter.isMultipleAdvertisementSupported || adapter.bluetoothLeAdvertiser == null) {
            Log.w(TAG, "Tablet does not support BLE multiple advertising; requesting reversed BLE role via Firebase")
            roleOverrideRef.setValue("phone_peripheral")
            refreshLinkStatus()
            return
        }

        roleOverrideRef.setValue("tablet_peripheral")
        val server = runCatching { btManager.openGattServer(context, gattServerCallback) }.getOrNull()
        if (server == null) {
            Log.w(TAG, "Failed to open BluetoothGattServer on tablet; running on Firebase only")
            return
        }
        gattServer = server

        val service = BluetoothGattService(
            UUID.fromString(AppConfigConstants.PERF_BLE_SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val controlChar = BluetoothGattCharacteristic(
            UUID.fromString(AppConfigConstants.PERF_BLE_CONTROL_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val ackChar = BluetoothGattCharacteristic(
            UUID.fromString(AppConfigConstants.PERF_BLE_ACK_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        ackChar.addDescriptor(cccd)

        service.addCharacteristic(controlChar)
        service.addCharacteristic(ackChar)
        server.addService(service)
        ackCharacteristic = ackChar

        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: return
        advertiser = leAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(AppConfigConstants.PERF_BLE_SERVICE_UUID)))
            .build()

        runCatching {
            leAdvertiser.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
        }.onFailure { e ->
            Log.w(TAG, "Tablet BLE startAdvertising threw: ${e.message}")
            roleOverrideRef.setValue("phone_peripheral")
        }
    }

    private fun teardownBleServer() {
        authenticatedDevices.clear()
        challengeByAddress.clear()
        runCatching {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
            }
        }
        runCatching { gattServer?.close() }
        gattServer = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Tablet BLE GATT Server advertising service ${AppConfigConstants.PERF_BLE_SERVICE_UUID}")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.w(TAG, "Tablet BLE advertising failed (code=$errorCode); reversing roles to phone_peripheral")
            roleOverrideRef.setValue("phone_peripheral")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val nonce = UUID.randomUUID().toString().replace("-", "").take(12)
                challengeByAddress[addr] = nonce
                authenticatedDevices.remove(addr)
                Log.i(TAG, "BLE client connected ($addr); issued challenge nonce")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                challengeByAddress.remove(addr)
                authenticatedDevices.remove(addr)
                mainHandler.post { refreshLinkStatus() }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            // Immediately push challenge notification so phone can authenticate
            val nonce = challengeByAddress.getOrPut(device.address) {
                UUID.randomUUID().toString().replace("-", "").take(12)
            }
            sendAckNotification(device, JSONObject().apply { put("c", nonce) })
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val nonce = challengeByAddress.getOrPut(device.address) {
                UUID.randomUUID().toString().replace("-", "").take(12)
            }
            val payload = JSONObject().apply { put("c", nonce) }.toString().toByteArray(Charsets.UTF_8)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, payload)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            val bytes = value ?: return
            val addr = device.address

            runCatching {
                val text = String(bytes, Charsets.UTF_8)
                val obj = JSONObject(text)

                // 1. Check if this is an authentication response
                val authHmac = obj.optString("auth", "")
                if (authHmac.isNotEmpty()) {
                    val expectedNonce = challengeByAddress[addr] ?: return
                    val expectedHmac = PerformanceCryptoAuth.computeChallengeResponse(sharedSecret, expectedNonce)
                    if (authHmac.equals(expectedHmac, ignoreCase = true)) {
                        authenticatedDevices.add(addr)
                        lastBleTrafficAtMs = System.currentTimeMillis()
                        sendAckNotification(device, JSONObject().apply { put("authOk", 1) })
                        mainHandler.post { refreshLinkStatus() }
                        Log.i(TAG, "Authenticated BLE client $addr")
                    } else {
                        Log.w(TAG, "Rejected BLE client $addr: invalid HMAC response")
                    }
                    return
                }

                // Ignore unauthenticated clients
                if (!authenticatedDevices.contains(addr)) {
                    val nonce = challengeByAddress.getOrPut(addr) {
                        UUID.randomUUID().toString().replace("-", "").take(12)
                    }
                    sendAckNotification(device, JSONObject().apply { put("c", nonce) })
                    return
                }

                lastBleTrafficAtMs = System.currentTimeMillis()

                // 2. Check if this is a heartbeat ping
                val pingTs = obj.optLong("ping", 0L)
                if (pingTs > 0L) {
                    sendAckNotification(device, JSONObject().apply {
                        put("authOk", 1)
                        put("pong", pingTs)
                    })
                    mainHandler.post { refreshLinkStatus() }
                    return
                }

                // 3. Parse live control message
                val msg = PerformanceControlMessage.fromCompactJsonBytes(bytes) ?: return
                if (msg.requiresAck) {
                    sendAckNotification(device, JSONObject().apply {
                        put("authOk", 1)
                        put("ack", msg.seq)
                        put("pong", msg.sentAtMs)
                    })
                }
                handleIncomingControlMessage(msg, fromBle = true)
            }
        }
    }

    private fun sendAckNotification(device: BluetoothDevice, json: JSONObject) {
        val srv = gattServer ?: return
        val ch = ackCharacteristic ?: return
        runCatching {
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            srv.notifyCharacteristicChanged(device, ch, false)
        }
    }
}
