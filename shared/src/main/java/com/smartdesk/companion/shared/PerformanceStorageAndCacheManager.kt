package com.smartdesk.companion.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * V9 Sections 2, 3, 4 & 5:
 * Manages:
 * - Local synchronous persistence of Performance Mode state (`isPerformanceModeEnabled(context)`)
 *   so paused features (notifications, timetable, PC stats) stay paused across process restarts.
 * - Local disk caching of [PerformanceSession] metadata and stitched song strip tiles on both
 *   phone and tablet.
 * - Chunked upload & download of song strip tiles in Firebase Realtime Database
 *   (`performance/chunks/<sessionId>/<songId>`).
 * - Automatic synchronisation of each session with the dashboard `/tasks` node using
 *   [SubjectCatalog.PERFORMANCE_SESSION_ID].
 * - Atomic session deletion across Firebase + local cache with a persistent `pendingDeletes`
 *   retry queue on app start.
 */
object PerformanceStorageAndCacheManager {

    private const val PREFS_NAME = "smart_desk_performance_v9_prefs"
    private const val KEY_MODE_ENABLED = "perf_mode_enabled"
    private const val KEY_STATE_SNAPSHOT_JSON = "perf_state_snapshot_json"
    private const val KEY_BLE_SHARED_SECRET = "perf_ble_shared_secret"
    private const val KEY_PENDING_DELETES_JSON = "perf_pending_deletes_json"

    private val ioExecutor = Executors.newFixedThreadPool(3)
    private val memoryTileCache = ConcurrentHashMap<String, Bitmap>()

    @Volatile
    private var cachedModeEnabledFlag: Boolean? = null

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun addModeStateListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeModeStateListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * V9 Section 4: Fast, synchronous check used by notification listener, timetable widget/listener,
     * and PC stats widget/listener on both phone and tablet.
     * Persisted in SharedPreferences so process restarts mid-performance never briefly resume them.
     */
    fun isPerformanceModeEnabled(context: Context): Boolean {
        cachedModeEnabledFlag?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_MODE_ENABLED, false)
        cachedModeEnabledFlag = enabled
        return enabled
    }

    fun setLocalPerformanceModeEnabled(context: Context, enabled: Boolean) {
        val prev = cachedModeEnabledFlag
        cachedModeEnabledFlag = enabled
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MODE_ENABLED, enabled).commit()
        if (prev != enabled) {
            listeners.forEach { runCatching { it(enabled) } }
        }
    }

    fun saveLocalStateSnapshot(context: Context, snapshot: PerformanceStateSnapshot) {
        setLocalPerformanceModeEnabled(context, snapshot.enabled)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATE_SNAPSHOT_JSON, snapshot.toJsonString()).apply()
    }

    fun loadLocalStateSnapshot(context: Context): PerformanceStateSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_STATE_SNAPSHOT_JSON, null)
        val parsed = PerformanceStateSnapshot.fromJsonString(raw)
        val enabledFlag = prefs.getBoolean(KEY_MODE_ENABLED, parsed.enabled)
        return parsed.copy(enabled = enabledFlag)
    }

    fun getOrCreateBleSharedSecret(context: Context, onReady: (String) -> Unit) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_BLE_SHARED_SECRET, null)
        val dbRef = PerfFirebase.database()
            .getReference(AppConfigConstants.FIREBASE_PERF_BLE_SECRET_PATH)

        dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remoteSecret = snapshot.getValue(String::class.java)
                if (!remoteSecret.isNullOrBlank() && remoteSecret.length >= 16) {
                    prefs.edit().putString(KEY_BLE_SHARED_SECRET, remoteSecret).apply()
                    onReady(remoteSecret)
                } else {
                    val newSecret = cached?.takeIf { it.length >= 16 }
                        ?: "sdc_ble_${ java.util.UUID.randomUUID().toString().replace("-", "") }"
                    prefs.edit().putString(KEY_BLE_SHARED_SECRET, newSecret).apply()
                    dbRef.setValue(newSecret)
                    onReady(newSecret)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                val fallback = cached ?: "sdc_ble_default_shared_secret_v9"
                onReady(fallback)
            }
        })
    }

    // =========================================================================
    // Local Disk Cache Directories
    // =========================================================================

    private fun rootCacheDir(context: Context): File =
        File(context.applicationContext.filesDir, "performance_cache").apply { mkdirs() }

    private fun sessionCacheDir(context: Context, sessionId: String): File =
        File(rootCacheDir(context), "session_$sessionId").apply { mkdirs() }

    private fun sessionJsonFile(context: Context, sessionId: String): File =
        File(sessionCacheDir(context, sessionId), "session.json")

    private fun tileFile(context: Context, sessionId: String, songId: String, tileIndex: Int): File =
        File(sessionCacheDir(context, sessionId), "${songId}_tile_${tileIndex}.webp")

    fun saveSessionToLocalCache(
        context: Context,
        session: PerformanceSession,
        songTiles: Map<String, List<PerformanceSongProcessor.ProcessedStripTile>> = emptyMap()
    ) {
        try {
            sessionJsonFile(context, session.sessionId).writeText(
                session.toJson().toString(),
                Charsets.UTF_8
            )
            for ((songId, tiles) in songTiles) {
                for (tile in tiles) {
                    val f = tileFile(context, session.sessionId, songId, tile.tileIndex)
                    f.writeBytes(tile.pngBytes)
                    memoryTileCache.remove("${session.sessionId}:${songId}:${tile.tileIndex}")
                }
            }
        } catch (_: Exception) {
        }
    }

    fun loadSessionFromLocalCache(context: Context, sessionId: String): PerformanceSession? {
        if (sessionId.isBlank()) return null
        return runCatching {
            val f = sessionJsonFile(context, sessionId)
            if (!f.exists()) return null
            PerformanceSession.fromJson(JSONObject(f.readText(Charsets.UTF_8)))
        }.getOrNull()
    }

    fun loadAllLocallyCachedSessions(context: Context): List<PerformanceSession> {
        val root = rootCacheDir(context)
        val dirs = root.listFiles() ?: return emptyList()
        val list = mutableListOf<PerformanceSession>()
        for (dir in dirs) {
            if (dir.isDirectory && dir.name.startsWith("session_")) {
                val f = File(dir, "session.json")
                if (f.exists()) {
                    runCatching {
                        list.add(PerformanceSession.fromJson(JSONObject(f.readText(Charsets.UTF_8))))
                    }
                }
            }
        }
        return sortSessionsUpcomingFirst(list)
    }

    fun hasAllTilesCachedLocally(context: Context, session: PerformanceSession): Boolean {
        if (session.songsById.isEmpty()) return true
        for (song in session.orderedSongs) {
            val expectedCount = song.tileHeightsPx.size
            for (tileIdx in 0 until expectedCount) {
                val f = tileFile(context, session.sessionId, song.songId, tileIdx)
                if (!f.exists() || f.length() == 0L) return false
            }
        }
        return true
    }

    fun loadTileBitmap(
        context: Context,
        sessionId: String,
        songId: String,
        tileIndex: Int
    ): Bitmap? {
        val cacheKey = "$sessionId:$songId:$tileIndex"
        memoryTileCache[cacheKey]?.let { bmp ->
            if (!bmp.isRecycled) return bmp
        }
        val f = tileFile(context, sessionId, songId, tileIndex)
        if (!f.exists()) return null
        return runCatching {
            val decoded = BitmapFactory.decodeFile(f.absolutePath)
            if (decoded != null) {
                if (memoryTileCache.size > 24) {
                    memoryTileCache.clear()
                }
                memoryTileCache[cacheKey] = decoded
            }
            decoded
        }.getOrNull()
    }

    fun deleteLocalSessionCache(context: Context, sessionId: String) {
        runCatching {
            val prefix = "$sessionId:"
            memoryTileCache.keys.removeIf { it.startsWith(prefix) }
            sessionCacheDir(context, sessionId).deleteRecursively()
        }
    }

    // =========================================================================
    // Session Ordering Helper (V9 Section 5: "upcoming first")
    // =========================================================================

    fun sortSessionsUpcomingFirst(sessions: Collection<PerformanceSession>): List<PerformanceSession> {
        val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val (upcomingOrToday, past) = sessions.partition { it.dateIso >= todayIso }
        val sortedUpcoming = upcomingOrToday.sortedWith(
            compareBy<PerformanceSession> { it.dateIso }.thenBy { it.name.lowercase(java.util.Locale.US) }
        )
        val sortedPast = past.sortedWith(
            compareByDescending<PerformanceSession> { it.dateIso }.thenBy { it.name.lowercase(java.util.Locale.US) }
        )
        return sortedUpcoming + sortedPast
    }

    // =========================================================================
    // Firebase Upload (Session + Chunked Strip Tiles + Matching Task Entry)
    // =========================================================================

    fun uploadSessionAndTilesToFirebase(
        context: Context,
        session: PerformanceSession,
        newSongTiles: Map<String, List<PerformanceSongProcessor.ProcessedStripTile>>,
        removedSongIds: Set<String> = emptySet(),
        onProgress: ((Float, String) -> Unit)? = null,
        onComplete: (Boolean, String?) -> Unit
    ) {
        ioExecutor.execute {
            try {
                // Save locally first so phone has instant offline copy
                saveSessionToLocalCache(context, session, newSongTiles)
                val db = PerfFirebase.database().reference

                // 1. Upload tile chunks for each new/modified song
                val songEntries = newSongTiles.entries.toList()
                val totalTileCount = songEntries.sumOf { it.value.size }.coerceAtLeast(1)
                var uploadedTiles = 0

                for ((songId, tiles) in songEntries) {
                    val song = session.songsById[songId] ?: continue
                    val songChunkPayload = HashMap<String, Any?>()
                    songChunkPayload["fingerprint"] = song.contentFingerprint
                    songChunkPayload["tileCount"] = tiles.size
                    songChunkPayload["updatedAt"] = System.currentTimeMillis()

                    val tilesMap = HashMap<String, Any?>()
                    for (tile in tiles) {
                        val b64 = Base64.encodeToString(tile.pngBytes, Base64.NO_WRAP)
                        val chunkSize = AppConfigConstants.PERF_RTDB_CHUNK_CHAR_SIZE
                        val chunks = b64.chunked(chunkSize)
                        val singleTileMap = HashMap<String, Any?>()
                        singleTileMap["widthPx"] = tile.widthPx
                        singleTileMap["heightPx"] = tile.heightPx
                        singleTileMap["chunkCount"] = chunks.size
                        for ((cIdx, chunkStr) in chunks.withIndex()) {
                            singleTileMap["c_$cIdx"] = chunkStr
                        }
                        tilesMap["tile_${tile.tileIndex}"] = singleTileMap
                        uploadedTiles++
                        val prog = 0.15f + 0.65f * (uploadedTiles.toFloat() / totalTileCount.toFloat())
                        onProgress?.invoke(prog, "Uploading strip tiles ($uploadedTiles/$totalTileCount)…")
                    }
                    songChunkPayload["tiles"] = tilesMap

                    val latch = java.util.concurrent.CountDownLatch(1)
                    var writeError: String? = null
                    db.child(AppConfigConstants.FIREBASE_PERF_CHUNKS_PATH)
                        .child(session.sessionId)
                        .child(songId)
                        .setValue(songChunkPayload)
                        .addOnCompleteListener { task ->
                            if (!task.isSuccessful) {
                                writeError = task.exception?.localizedMessage ?: "Chunk upload failed"
                            }
                            latch.countDown()
                        }
                    val completedInTime = latch.await(25, java.util.concurrent.TimeUnit.SECONDS)
                    if (!completedInTime && writeError == null) {
                        writeError = "Upload timed out. Check internet connection and Firebase database rules."
                    }
                    if (writeError != null) {
                        onComplete(false, writeError)
                        return@execute
                    }
                }

                // 2. Clean up any songs removed during session edit
                for (removedId in removedSongIds) {
                    db.child(AppConfigConstants.FIREBASE_PERF_CHUNKS_PATH)
                        .child(session.sessionId)
                        .child(removedId)
                        .removeValue()
                }

                onProgress?.invoke(0.88f, "Saving session & syncing task entry…")
                // 3. Read existing task completion state if editing an existing session
                val taskId = session.taskId.ifBlank { "perf_session_${session.sessionId}" }
                var existingCompleted = false
                var existingCompletedAt: Long? = null
                var existingCreatedAt = System.currentTimeMillis()

                val taskSnapLatch = java.util.concurrent.CountDownLatch(1)
                db.child(AppConfigConstants.FIREBASE_TASKS_PATH).child(taskId)
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (snapshot.exists()) {
                                existingCompleted = snapshot.child("completed").getValue(Boolean::class.java) ?: false
                                existingCompletedAt = snapshot.child("completedAt").getValue(Long::class.java)
                                existingCreatedAt = snapshot.child("createdAt").getValue(Long::class.java)
                                    ?: existingCreatedAt
                            }
                            taskSnapLatch.countDown()
                        }

                        override fun onCancelled(error: DatabaseError) {
                            taskSnapLatch.countDown()
                        }
                    })
                taskSnapLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)

                val taskMap = mapOf(
                    "id" to taskId,
                    "title" to session.name,
                    "subjectId" to SubjectCatalog.PERFORMANCE_SESSION_ID,
                    "deadline" to session.dateIso,
                    "completed" to existingCompleted,
                    "createdAt" to existingCreatedAt,
                    "completedAt" to existingCompletedAt
                )

                val multiUpdates = HashMap<String, Any?>()
                multiUpdates["/${AppConfigConstants.FIREBASE_PERF_SESSIONS_PATH}/${session.sessionId}"] =
                    session.copy(taskId = taskId).toMap()
                multiUpdates["/${AppConfigConstants.FIREBASE_TASKS_PATH}/$taskId"] = taskMap

                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (finished.compareAndSet(false, true)) {
                        onComplete(
                            false,
                            "Timed out talking to Firebase. Saved on this phone only. Check internet and Firebase rules, then try again."
                        )
                    }
                }
                timeoutHandler.postDelayed(timeoutRunnable, 25_000L)

                db.updateChildren(multiUpdates).addOnCompleteListener { task ->
                    if (!finished.compareAndSet(false, true)) return@addOnCompleteListener
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    if (task.isSuccessful) {
                        onProgress?.invoke(1.0f, "Session saved!")
                        onComplete(true, null)
                    } else {
                        onComplete(false, task.exception?.localizedMessage ?: "Failed to save session")
                    }
                }
            } catch (e: Exception) {
                onComplete(false, e.localizedMessage ?: "Unexpected error saving session")
            }
        }
    }

    /**
     * Fast metadata-only update when reordering songs, editing structure puzzle pieces,
     * moving upper/lower region sliders, or adding/editing/deleting pinned notes.
     */
    fun updateSessionStructureOrNotesLive(
        context: Context,
        updatedSession: PerformanceSession
    ) {
        saveSessionToLocalCache(context, updatedSession)
        PerfFirebase.database()
            .getReference(AppConfigConstants.FIREBASE_PERF_SESSIONS_PATH)
            .child(updatedSession.sessionId)
            .setValue(updatedSession.toMap())
    }

    // =========================================================================
    // Tablet Download & Local Caching of Session Tiles
    // =========================================================================

    fun ensureSessionTilesCached(
        context: Context,
        session: PerformanceSession,
        reportTabletCacheStatus: Boolean,
        onCacheStateChanged: ((Boolean) -> Unit)? = null
    ) {
        if (hasAllTilesCachedLocally(context, session)) {
            saveSessionToLocalCache(context, session)
            if (reportTabletCacheStatus) {
                reportTabletSessionCacheReady(session.sessionId, ready = true)
            }
            onCacheStateChanged?.invoke(true)
            return
        }

        if (reportTabletCacheStatus) {
            reportTabletSessionCacheReady(session.sessionId, ready = false)
        }
        onCacheStateChanged?.invoke(false)

        ioExecutor.execute {
            val db = PerfFirebase.database().reference
            var allSucceeded = true

            for (song in session.orderedSongs) {
                val expectedTiles = song.tileHeightsPx.size
                val missingAny = (0 until expectedTiles).any { idx ->
                    val f = tileFile(context, session.sessionId, song.songId, idx)
                    !f.exists() || f.length() == 0L
                }
                if (!missingAny) continue

                val latch = java.util.concurrent.CountDownLatch(1)
                db.child(AppConfigConstants.FIREBASE_PERF_CHUNKS_PATH)
                    .child(session.sessionId)
                    .child(song.songId)
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            try {
                                val tilesNode = snapshot.child("tiles")
                                for (tileIdx in 0 until expectedTiles) {
                                    val tSnap = tilesNode.child("tile_$tileIdx")
                                    val chunkCount = tSnap.child("chunkCount").getValue(Int::class.java)
                                        ?: tSnap.child("chunkCount").getValue(Long::class.java)?.toInt()
                                        ?: 0
                                    if (chunkCount <= 0) {
                                        allSucceeded = false
                                        continue
                                    }
                                    val sb = StringBuilder()
                                    for (cIdx in 0 until chunkCount) {
                                        val part = tSnap.child("c_$cIdx").getValue(String::class.java).orEmpty()
                                        sb.append(part)
                                    }
                                    val rawBytes = Base64.decode(sb.toString(), Base64.DEFAULT)
                                    val outFile = tileFile(context, session.sessionId, song.songId, tileIdx)
                                    outFile.writeBytes(rawBytes)
                                }
                            } catch (_: Exception) {
                                allSucceeded = false
                            } finally {
                                latch.countDown()
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            allSucceeded = false
                            latch.countDown()
                        }
                    })
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            }

            saveSessionToLocalCache(context, session)
            val nowReady = allSucceeded && hasAllTilesCachedLocally(context, session)
            if (reportTabletCacheStatus) {
                reportTabletSessionCacheReady(session.sessionId, ready = nowReady)
            }
            onCacheStateChanged?.invoke(nowReady)
        }
    }

    private fun reportTabletSessionCacheReady(sessionId: String, ready: Boolean) {
        if (sessionId.isBlank()) return
        PerfFirebase.database()
            .getReference(AppConfigConstants.FIREBASE_PERF_TABLET_CACHE_PATH)
            .child(sessionId)
            .setValue(
                mapOf(
                    "ready" to ready,
                    "updatedAt" to System.currentTimeMillis()
                )
            )
    }

    // =========================================================================
    // Atomic Session Deletion + Pending-Deletes Retry Queue (V9 Section 5)
    // =========================================================================

    fun deleteSessionEverywhere(
        context: Context,
        session: PerformanceSession,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val sessionId = session.sessionId
        val taskId = session.taskId.ifBlank { "perf_session_$sessionId" }

        // 1. Record in persistent local pending-deletes queue first so nothing is orphaned if killed
        addPendingDelete(context, sessionId, taskId)

        // 2. Delete local cache immediately
        deleteLocalSessionCache(context, sessionId)

        // 3. Execute atomic multi-path deletion in Firebase Realtime Database
        executeRemoteDeleteForSession(context, sessionId, taskId, onComplete)
    }

    private fun executeRemoteDeleteForSession(
        context: Context,
        sessionId: String,
        taskId: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val db = PerfFirebase.database().reference
        val updates = HashMap<String, Any?>()
        updates["/${AppConfigConstants.FIREBASE_PERF_SESSIONS_PATH}/$sessionId"] = null
        updates["/${AppConfigConstants.FIREBASE_PERF_CHUNKS_PATH}/$sessionId"] = null
        updates["/${AppConfigConstants.FIREBASE_PERF_TABLET_CACHE_PATH}/$sessionId"] = null
        updates["/${AppConfigConstants.FIREBASE_TASKS_PATH}/$taskId"] = null
        updates["/${AppConfigConstants.FIREBASE_PERF_PENDING_DELETES_PATH}/$sessionId"] = null

        db.updateChildren(updates).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                removePendingDelete(context, sessionId)
                onComplete?.invoke(true, null)
            } else {
                onComplete?.invoke(
                    false,
                    task.exception?.localizedMessage
                        ?: "Remote deletion pending — queued for automatic retry on next connection."
                )
            }
        }
    }

    private fun addPendingDelete(context: Context, sessionId: String, taskId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = loadPendingDeletesMap(prefs).toMutableMap()
        current[sessionId] = taskId
        savePendingDeletesMap(prefs, current)
        // Also mirror to Firebase pendingDeletes node
        runCatching {
            PerfFirebase.database()
                .getReference(AppConfigConstants.FIREBASE_PERF_PENDING_DELETES_PATH)
                .child(sessionId)
                .setValue(taskId)
        }
    }

    private fun removePendingDelete(context: Context, sessionId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = loadPendingDeletesMap(prefs).toMutableMap()
        if (current.remove(sessionId) != null) {
            savePendingDeletesMap(prefs, current)
        }
    }

    private fun loadPendingDeletesMap(prefs: android.content.SharedPreferences): Map<String, String> {
        val raw = prefs.getString(KEY_PENDING_DELETES_JSON, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            val map = LinkedHashMap<String, String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sId = obj.optString("sessionId", "")
                val tId = obj.optString("taskId", "perf_session_$sId")
                if (sId.isNotBlank()) map[sId] = tId
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun savePendingDeletesMap(
        prefs: android.content.SharedPreferences,
        map: Map<String, String>
    ) {
        val arr = JSONArray()
        for ((sId, tId) in map) {
            arr.put(
                JSONObject().apply {
                    put("sessionId", sId)
                    put("taskId", tId)
                }
            )
        }
        prefs.edit().putString(KEY_PENDING_DELETES_JSON, arr.toString()).apply()
    }

    /**
     * V9 Section 5: Retries any pending session deletions on app startup so nothing is ever
     * orphaned in storage.
     */
    fun retryPendingDeletesOnAppStart(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = loadPendingDeletesMap(prefs)
        for ((sessionId, taskId) in pending) {
            deleteLocalSessionCache(context, sessionId)
            executeRemoteDeleteForSession(context, sessionId, taskId, null)
        }
    }
}
