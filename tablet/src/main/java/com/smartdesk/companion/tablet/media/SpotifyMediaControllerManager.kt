package com.smartdesk.companion.tablet.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import com.smartdesk.companion.tablet.service.TabletMediaNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class MediaPlaybackState(
    val hasNotificationPermission: Boolean = false,
    val isSpotifyInstalled: Boolean = false,
    val isSessionActive: Boolean = false,
    val sourcePackage: String = "",
    val trackTitle: String = "No Active Track",
    val artistName: String = "Open Spotify on Tablet or Connect via Spotify Connect",
    val albumName: String = "",
    val albumArt: Bitmap? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Module D: Dedicated Spotify & Media Remote Controls
 * Hooks directly into the tablet's local Android [MediaSessionManager] to read
 * metadata, album art, playback state, and send low-latency transport controls
 * (play, pause, skip, track scrubbing) to the Spotify app installed on the tablet.
 */
class SpotifyMediaControllerManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val mediaSessionManager: MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeController: MediaController? = null
    private var progressTickerJob: Job? = null
    private var listenerRegistered = false

    private val _playbackState = MutableStateFlow(
        MediaPlaybackState(
            hasNotificationPermission = TabletMediaNotificationListenerService.isPermissionGranted(context),
            isSpotifyInstalled = checkSpotifyInstalled()
        )
    )
    val playbackState: StateFlow<MediaPlaybackState> = _playbackState.asStateFlow()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindBestController(controllers)
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateFromController(activeController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateFromController(activeController)
        }

        override fun onSessionDestroyed() {
            refreshSessions()
        }
    }

    init {
        scope.launch(Dispatchers.Main.immediate) {
            TabletMediaNotificationListenerService.sessionRefreshTrigger.collect {
                refreshSessions()
            }
        }
        startProgressTicker()
        refreshSessions()
    }

    fun refreshSessions() {
        val hasPerm = TabletMediaNotificationListenerService.isPermissionGranted(context)
        val spotifyInstalled = checkSpotifyInstalled()

        _playbackState.update {
            it.copy(
                hasNotificationPermission = hasPerm,
                isSpotifyInstalled = spotifyInstalled
            )
        }

        if (!hasPerm || mediaSessionManager == null) {
            detachCurrentController()
            return
        }

        val component = TabletMediaNotificationListenerService.componentName(context)
        try {
            if (!listenerRegistered) {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                    sessionsChangedListener,
                    component,
                    mainHandler
                )
                listenerRegistered = true
            }
            val controllers = mediaSessionManager.getActiveSessions(component)
            bindBestController(controllers)
        } catch (_: SecurityException) {
            detachCurrentController()
        } catch (_: Exception) {
            detachCurrentController()
        }
    }

    private fun bindBestController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            detachCurrentController()
            return
        }

        // Prioritize Spotify (`com.spotify.music`), preferring an actively playing session
        val chosen = controllers.firstOrNull {
            it.packageName == TabletMediaNotificationListenerService.SPOTIFY_PACKAGE &&
                it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            it.packageName == TabletMediaNotificationListenerService.SPOTIFY_PACKAGE
        } ?: controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.first()

        if (activeController?.sessionToken != chosen.sessionToken) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = chosen
            chosen.registerCallback(controllerCallback, mainHandler)
        }

        updateFromController(chosen)
    }

    private fun detachCurrentController() {
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        _playbackState.update { current ->
            current.copy(
                isSessionActive = false,
                isPlaying = false
            )
        }
    }

    private fun updateFromController(controller: MediaController?) {
        if (controller == null) {
            detachCurrentController()
            return
        }

        val metadata = controller.metadata
        val pbState = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Spotify Ready"

        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: "Tap Play or connect via Spotify Connect"

        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)

        val rawBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val scaledArt = rawBitmap?.let { scaleBitmapSafely(it, 240) }

        val isPlaying = pbState?.state == PlaybackState.STATE_PLAYING ||
            pbState?.state == PlaybackState.STATE_BUFFERING

        val currentPos = computeInterpolatedPosition(pbState, duration)

        _playbackState.update {
            it.copy(
                isSessionActive = true,
                sourcePackage = controller.packageName,
                trackTitle = title,
                artistName = artist,
                albumName = album,
                albumArt = scaledArt,
                isPlaying = isPlaying,
                positionMs = currentPos,
                durationMs = duration
            )
        }
    }

    private fun computeInterpolatedPosition(state: PlaybackState?, durationMs: Long): Long {
        if (state == null) return 0L
        var position = state.position.coerceAtLeast(0L)
        if (state.state == PlaybackState.STATE_PLAYING) {
            val timeDelta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            if (timeDelta > 0) {
                val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
                position += (timeDelta * speed).toLong()
            }
        }
        return if (durationMs > 0L) position.coerceIn(0L, durationMs) else position
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                delay(1000L)
                val controller = activeController
                if (controller != null && _playbackState.value.isPlaying) {
                    val duration = _playbackState.value.durationMs
                    val updatedPos = computeInterpolatedPosition(controller.playbackState, duration)
                    _playbackState.update { it.copy(positionMs = updatedPos) }
                }
            }
        }
    }

    fun togglePlayPause() {
        val controller = activeController
        if (controller != null) {
            val state = controller.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        } else {
            // Fallback: dispatch system media play/pause key event to wake up Spotify
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun skipNext() {
        val controller = activeController
        if (controller != null) {
            controller.transportControls.skipToNext()
        } else {
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    fun skipPrevious() {
        val controller = activeController
        if (controller != null) {
            controller.transportControls.skipToPrevious()
        } else {
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = activeController ?: return
        val clamped = positionMs.coerceIn(0L, _playbackState.value.durationMs.coerceAtLeast(0L))
        controller.transportControls.seekTo(clamped)
        _playbackState.update { it.copy(positionMs = clamped) }
    }

    fun seekToFraction(fraction: Float) {
        val duration = _playbackState.value.durationMs
        if (duration <= 0L) return
        val targetMs = (duration * fraction.coerceIn(0f, 1f)).toLong()
        seekTo(targetMs)
    }

    fun openSpotifyApp() {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(TabletMediaNotificationListenerService.SPOTIFY_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launchIntent) }
        }
    }

    fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun dispatchMediaKeyEvent(keyCode: Int) {
        val am = audioManager ?: return
        val now = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        am.dispatchMediaKeyEvent(downEvent)
        am.dispatchMediaKeyEvent(upEvent)
    }

    private fun checkSpotifyInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(
                TabletMediaNotificationListenerService.SPOTIFY_PACKAGE,
                0
            )
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun scaleBitmapSafely(source: Bitmap, maxDim: Int): Bitmap {
        if (source.width <= maxDim && source.height <= maxDim) return source
        val ratio = minOf(
            maxDim.toFloat() / source.width.toFloat(),
            maxDim.toFloat() / source.height.toFloat()
        )
        val targetW = (source.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    fun release() {
        progressTickerJob?.cancel()
        activeController?.unregisterCallback(controllerCallback)
        if (listenerRegistered) {
            runCatching {
                mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            }
            listenerRegistered = false
        }
    }
}
