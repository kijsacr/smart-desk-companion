package com.smartdesk.companion.phone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.phone.service.PhonePerformanceForegroundService
import com.smartdesk.companion.shared.ChordSheetSectionAnalyzer
import com.smartdesk.companion.shared.PerformanceControlMessage
import com.smartdesk.companion.shared.PerformanceLinkStatus
import com.smartdesk.companion.shared.PerformanceSession
import com.smartdesk.companion.shared.PerformanceSong
import com.smartdesk.companion.shared.PerformanceSongProcessor
import com.smartdesk.companion.shared.PerformanceStateSnapshot
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import com.smartdesk.companion.shared.SampleChordSheetsCatalog
import com.smartdesk.companion.shared.SectionCategory
import com.smartdesk.companion.shared.SongPinnedNote
import com.smartdesk.companion.shared.SongSectionRegion
import com.smartdesk.companion.shared.SongStructureItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Navigation destination inside Performance Mode on Phone.
 */
enum class PhonePerformanceView {
    SESSION_LIST,
    SESSION_EDITOR,
    ACTIVE_PERFORMANCE,
    SONG_STRUCTURE
}

/**
 * Root container for Phone Performance Mode.
 * Strictly NO HAPTIC FEEDBACK anywhere.
 */
@Composable
fun PhonePerformanceRoot(
    isSplitScreen: Boolean,
    onExitMode: () -> Unit
) {
    val context = LocalContext.current
    val stateSnapshot by PhonePerformanceForegroundService.stateSnapshot.collectAsState()
    val sessions by PhonePerformanceForegroundService.sessions.collectAsState()
    val linkStatus by PhonePerformanceForegroundService.linkStatus.collectAsState()
    val tabletCacheReadyMap by PhonePerformanceForegroundService.tabletCacheReadyBySession.collectAsState()

    var currentView by remember { mutableStateOf(PhonePerformanceView.SESSION_LIST) }
    var sessionToEdit by remember { mutableStateOf<PerformanceSession?>(null) }

    // If an active session is chosen and we are not editing, jump to active performance screen
    LaunchedEffect(stateSnapshot.enabled, stateSnapshot.sessionId) {
        if (stateSnapshot.enabled && stateSnapshot.sessionId.isNotBlank()) {
            if (currentView != PhonePerformanceView.SESSION_EDITOR &&
                currentView != PhonePerformanceView.SONG_STRUCTURE
            ) {
                currentView = PhonePerformanceView.ACTIVE_PERFORMANCE
            }
        } else if (stateSnapshot.enabled && stateSnapshot.sessionId.isBlank()) {
            if (currentView != PhonePerformanceView.SESSION_EDITOR) {
                currentView = PhonePerformanceView.SESSION_LIST
            }
        }
    }

    val currentSession = remember(sessions, stateSnapshot.sessionId) {
        sessions.firstOrNull { it.sessionId == stateSnapshot.sessionId }
    }
    val isTabletCacheReady = remember(tabletCacheReadyMap, stateSnapshot.sessionId) {
        tabletCacheReadyMap[stateSnapshot.sessionId] == true
    }

    if (isSplitScreen && currentView == PhonePerformanceView.ACTIVE_PERFORMANCE && currentSession != null) {
        // V9 Section 8: Split-screen layout
        PhonePerformanceSplitScreenLayout(
            session = currentSession,
            stateSnapshot = stateSnapshot,
            linkStatus = linkStatus,
            isTabletCacheReady = isTabletCacheReady
        )
    } else {
        when (currentView) {
            PhonePerformanceView.SESSION_LIST -> {
                PhonePerformanceSessionListScreen(
                    sessions = sessions,
                    linkStatus = linkStatus,
                    onSelectSession = { chosen ->
                        PhonePerformanceForegroundService.selectSession(context, chosen)
                        currentView = PhonePerformanceView.ACTIVE_PERFORMANCE
                    },
                    onCreateNew = {
                        sessionToEdit = null
                        currentView = PhonePerformanceView.SESSION_EDITOR
                    },
                    onEditSession = { sess ->
                        sessionToEdit = sess
                        currentView = PhonePerformanceView.SESSION_EDITOR
                    },
                    onDeleteSession = { toDelete ->
                        PerformanceStorageAndCacheManager.deleteSessionEverywhere(context, toDelete) { ok, err ->
                            if (ok) {
                                PhonePerformanceForegroundService.removeLocalSessionInMemory(toDelete.sessionId)
                                if (stateSnapshot.sessionId == toDelete.sessionId) {
                                    PhonePerformanceForegroundService.updateDiscreteState(context, "session") {
                                        it.copy(sessionId = "", songId = "", sectionIndex = 0)
                                    }
                                }
                            }
                        }
                    },
                    onExit = onExitMode
                )
            }

            PhonePerformanceView.SESSION_EDITOR -> {
                PhonePerformanceSessionEditorScreen(
                    existingSession = sessionToEdit,
                    onSaveComplete = { savedSession ->
                        PhonePerformanceForegroundService.refreshLocalSessionInMemory(savedSession)
                        PhonePerformanceForegroundService.selectSession(context, savedSession)
                        currentView = PhonePerformanceView.ACTIVE_PERFORMANCE
                    },
                    onCancel = {
                        if (stateSnapshot.sessionId.isNotBlank()) {
                            currentView = PhonePerformanceView.ACTIVE_PERFORMANCE
                        } else {
                            currentView = PhonePerformanceView.SESSION_LIST
                        }
                    }
                )
            }

            PhonePerformanceView.ACTIVE_PERFORMANCE -> {
                if (currentSession != null) {
                    PhonePerformanceFullScreenLayout(
                        session = currentSession,
                        stateSnapshot = stateSnapshot,
                        linkStatus = linkStatus,
                        isTabletCacheReady = isTabletCacheReady,
                        onOpenStructure = {
                            currentView = PhonePerformanceView.SONG_STRUCTURE
                        }
                    )
                } else {
                    PhonePerformanceSessionListScreen(
                        sessions = sessions,
                        linkStatus = linkStatus,
                        onSelectSession = { chosen ->
                            PhonePerformanceForegroundService.selectSession(context, chosen)
                        },
                        onCreateNew = {
                            sessionToEdit = null
                            currentView = PhonePerformanceView.SESSION_EDITOR
                        },
                        onEditSession = { sess ->
                            sessionToEdit = sess
                            currentView = PhonePerformanceView.SESSION_EDITOR
                        },
                        onDeleteSession = {},
                        onExit = onExitMode
                    )
                }
            }

            PhonePerformanceView.SONG_STRUCTURE -> {
                if (currentSession != null) {
                    val activeSong = currentSession.findSong(stateSnapshot.songId)
                        ?: currentSession.orderedSongs.firstOrNull()
                    if (activeSong != null) {
                        PhonePerformanceStructureScreen(
                            session = currentSession,
                            song = activeSong,
                            linkStatus = linkStatus,
                            stateSnapshot = stateSnapshot,
                            onClose = {
                                currentView = PhonePerformanceView.ACTIVE_PERFORMANCE
                            }
                        )
                    } else {
                        currentView = PhonePerformanceView.ACTIVE_PERFORMANCE
                    }
                } else {
                    currentView = PhonePerformanceView.SESSION_LIST
                }
            }
        }
    }
}

// =============================================================================
// 1. Session List Screen (Section 5)
// =============================================================================

@Composable
private fun PhonePerformanceSessionListScreen(
    sessions: List<PerformanceSession>,
    linkStatus: PerformanceLinkStatus,
    onSelectSession: (PerformanceSession) -> Unit,
    onCreateNew: () -> Unit,
    onEditSession: (PerformanceSession) -> Unit,
    onDeleteSession: (PerformanceSession) -> Unit,
    onExit: () -> Unit
) {
    var sessionPendingDelete by remember { mutableStateOf<PerformanceSession?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F12))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color(0xFF231136), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFFAB47BC), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFFE1BEE7),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = "Performance Mode",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Select or create session",
                        color = Color(0xFF9E9EAA),
                        fontSize = 13.sp
                    )
                }
            }

            PerformanceConnectionDots(
                status = linkStatus,
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // New Session Button
        Button(
            onClick = onCreateNew,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6A1B9A),
                contentColor = Color.White
            )
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "New Session", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No sessions created yet",
                        color = Color(0xFF757582),
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap 'New Session' above to add chord sheets from PDF or plain text.",
                        color = Color(0xFF5A5A66),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sessions, key = { it.sessionId }) { session ->
                    SessionRowCard(
                        session = session,
                        onTap = { onSelectSession(session) },
                        onEdit = { onEditSession(session) },
                        onDelete = { sessionPendingDelete = session }
                    )
                }
            }
        }
    }

    sessionPendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            title = { Text(text = "Delete Session?") },
            text = {
                Text(
                    text = "Are you sure you want to delete '${toDelete.name}'? This will permanently delete the session, all attached strip tiles, notes, and the matching dashboard task on both devices."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSession(toDelete)
                        sessionPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Delete Everywhere")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { sessionPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SessionRowCard(
    session: PerformanceSession,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF191920)),
        border = BorderStroke(1.dp, Color(0xFF333342))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = session.dateIso,
                        color = Color(0xFFCE93D8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(text = "•", color = Color(0xFF5A5A66))
                    Text(
                        text = "${session.songCount} ${if (session.songCount == 1) "song" else "songs"}",
                        color = Color(0xFFA0A0AF),
                        fontSize = 13.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onEdit) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFFB0B0C0))
                }
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF8A80))
                }
            }
        }
    }
}

// =============================================================================
// 2. Session Create / Edit Screen (Section 5 & 6)
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhonePerformanceSessionEditorScreen(
    existingSession: PerformanceSession?,
    onSaveComplete: (PerformanceSession) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sessionName by remember { mutableStateOf(existingSession?.name ?: "") }
    var sessionDateIso by remember {
        mutableStateOf(
            existingSession?.dateIso
                ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        )
    }

    val songs = remember {
        mutableStateListOf<PerformanceSong>().apply {
            existingSession?.orderedSongs?.let { addAll(it) }
        }
    }

    // Keep tiles mapped by songId for new or processed songs
    val newSongTiles = remember {
        mutableMapOf<String, List<PerformanceSongProcessor.ProcessedStripTile>>()
    }
    val removedSongIds = remember { mutableSetOf<String>() }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTextSongDialog by remember { mutableStateOf(false) }
    var textSongTitle by remember { mutableStateOf("") }
    var textSongBody by remember { mutableStateOf("") }

    var isProcessing by remember { mutableStateOf(false) }
    var processingProgress by remember { mutableFloatStateOf(0f) }
    var processingStatus by remember { mutableStateOf("") }

    // Multi-PDF picker
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isProcessing = true
            try {
                for ((idx, uri) in uris.withIndex()) {
                    val displayName = queryDisplayName(context, uri) ?: "Song ${songs.size + 1}.pdf"
                    processingStatus = "Processing ${idx + 1}/${uris.size}: $displayName"
                    val result = PerformanceSongProcessor.processPdfUri(
                        context = context,
                        uri = uri,
                        displayFileName = displayName,
                        onProgress = { p, msg ->
                            val overall = (idx.toFloat() + p) / uris.size.toFloat()
                            processingProgress = overall
                            processingStatus = "$displayName: $msg"
                        }
                    )
                    songs.add(result.song)
                    newSongTiles[result.song.songId] = result.tiles
                }
            } catch (e: Exception) {
                processingStatus = "Error: ${e.localizedMessage}"
            } finally {
                isProcessing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F12))
            .padding(18.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(onClick = onCancel) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = if (existingSession == null) "Create Session" else "Edit Session",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Session Name
        OutlinedTextField(
            value = sessionName,
            onValueChange = { sessionName = it },
            label = { Text("Session Name (Required)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Session Date
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDatePicker = true }
                .background(Color(0xFF1C1C24), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF3E3E50), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Performance Date", color = Color(0xFF9E9EAA), fontSize = 12.sp)
                Text(text = sessionDateIso, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { showDatePicker = true }) {
                Text("Change", color = Color(0xFFCE93D8))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Add Song Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF6A1B9A))
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add PDF", fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = {
                    textSongTitle = ""
                    textSongBody = ""
                    showTextSongDialog = true
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF00838F))
            ) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Type/Paste", fontSize = 13.sp)
            }
        }

        // Handy helper for 1-tap testing of the 4 sample PDFs / songs
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = {
                coroutineScope.launch {
                    isProcessing = true
                    try {
                        for ((idx, sample) in SampleChordSheetsCatalog.ALL_FOUR_SAMPLES.withIndex()) {
                            processingStatus = "Loading sample ${idx + 1}/4: ${sample.title}"
                            val result = PerformanceSongProcessor.processTextSong(
                                title = sample.title,
                                rawText = sample.fullText,
                                onProgress = { p, msg ->
                                    processingProgress = (idx.toFloat() + p) / 4f
                                    processingStatus = "${sample.title}: $msg"
                                }
                            )
                            songs.add(result.song)
                            newSongTiles[result.song.songId] = result.tiles
                        }
                    } finally {
                        isProcessing = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF283593)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Load 4 Sample Songs", fontSize = 13.sp)
        }

        // Processing Progress Indicator
        if (isProcessing) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { processingProgress },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFAB47BC),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = processingStatus,
                color = Color(0xFFCE93D8),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Songs in Set (${songs.size})",
            color = Color(0xFF9E9EAA),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))

        // Draggable reorder song list (NO HAPTICS!)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(songs, key = { _, item -> item.songId }) { index, song ->
                SongEditorItemRow(
                    index = index,
                    song = song,
                    canMoveUp = index > 0,
                    canMoveDown = index < songs.lastIndex,
                    onMoveUp = {
                        val item = songs.removeAt(index)
                        songs.add(index - 1, item)
                    },
                    onMoveDown = {
                        val item = songs.removeAt(index)
                        songs.add(index + 1, item)
                    },
                    onRemove = {
                        songs.removeAt(index)
                        removedSongIds.add(song.songId)
                        newSongTiles.remove(song.songId)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Save Button
        Button(
            onClick = {
                if (sessionName.isBlank()) return@Button
                isProcessing = true
                val sessionId = existingSession?.sessionId ?: "sess_${UUID.randomUUID().toString().take(8)}"
                val songOrder = songs.map { it.songId }
                val songMap = songs.associateBy { it.songId }
                val sessionToSave = PerformanceSession(
                    id = sessionId,
                    name = sessionName.trim(),
                    dateIso = sessionDateIso,
                    songOrder = songOrder,
                    songs = songMap,
                    taskId = existingSession?.taskId ?: "perf_session_$sessionId",
                    updatedAt = System.currentTimeMillis()
                )

                PerformanceStorageAndCacheManager.uploadSessionAndTilesToFirebase(
                    context = context,
                    session = sessionToSave,
                    newSongTiles = newSongTiles,
                    removedSongIds = removedSongIds,
                    onProgress = { p, msg ->
                        processingProgress = p
                        processingStatus = msg
                    },
                    onComplete = { success, err ->
                        isProcessing = false
                        if (success) {
                            onSaveComplete(sessionToSave)
                        } else {
                            processingProgress = 0f
                            processingStatus = "Save failed: ${err ?: "unknown error"}"
                        }
                    }
                )
            },
            enabled = sessionName.isNotBlank() && songs.isNotEmpty() && !isProcessing,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))
        ) {
            Text("Save Session & Sync to Tablet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }

    // Date Picker Dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val localDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        sessionDateIso = localDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Type / Paste Text Song Dialog
    if (showTextSongDialog) {
        AlertDialog(
            onDismissRequest = { showTextSongDialog = false },
            title = { Text("Type / Paste Text Song") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = textSongTitle,
                        onValueChange = { textSongTitle = it },
                        label = { Text("Song Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) {
                                    textSongBody = clip
                                }
                            }
                        ) {
                            Text("Paste from Clipboard")
                        }
                    }

                    OutlinedTextField(
                        value = textSongBody,
                        onValueChange = { textSongBody = it },
                        label = { Text("Chord Sheet Text") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (textSongBody.isNotBlank()) {
                            val cleanTitle = textSongTitle.ifBlank { "Untitled Song" }
                            val res = PerformanceSongProcessor.processTextSong(cleanTitle, textSongBody)
                            songs.add(res.song)
                            newSongTiles[res.song.songId] = res.tiles
                            showTextSongDialog = false
                        }
                    }
                ) {
                    Text("Add Song")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showTextSongDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SongEditorItemRow(
    index: Int,
    song: PerformanceSong,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E26), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF333342), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = "${index + 1}.",
                color = Color(0xFF9E9EAA),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Column {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.regions.size} sections • ${song.tileHeightsPx.size} tiles",
                    color = Color(0xFF757582),
                    fontSize = 12.sp
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                Text("▲", color = if (canMoveUp) Color.White else Color(0xFF444455), fontSize = 12.sp)
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
                Text("▼", color = if (canMoveDown) Color.White else Color(0xFF444455), fontSize = 12.sp)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Remove", tint = Color(0xFFFF8A80), modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1 && cursor.moveToFirst()) {
            cursor.getString(nameIndex)
        } else null
    }
}

// =============================================================================
// 3. Full-screen Performance Screen (Section 7)
// =============================================================================

@Composable
private fun PhonePerformanceFullScreenLayout(
    session: PerformanceSession,
    stateSnapshot: PerformanceStateSnapshot,
    linkStatus: PerformanceLinkStatus,
    isTabletCacheReady: Boolean,
    onOpenStructure: () -> Unit
) {
    val context = LocalContext.current
    val currentSong = remember(session, stateSnapshot.songId) {
        session.findSong(stateSnapshot.songId) ?: session.orderedSongs.firstOrNull()
    }

    var chevronNotice by remember { mutableStateOf<String?>(null) }
    var panLockAnimVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0E))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // Top Control Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onOpenStructure) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Structure", tint = Color(0xFFCE93D8))
                }
                IconButton(
                    onClick = {
                        PhonePerformanceForegroundService.updateDiscreteState(context, PerformanceControlMessage.TYPE_THEME) {
                            it.copy(darkTheme = !it.darkTheme)
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (stateSnapshot.darkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                        contentDescription = "Toggle Theme",
                        tint = Color(0xFFFFD54F)
                    )
                }
                IconButton(
                    onClick = {
                        val nextLocked = !stateSnapshot.panLocked
                        PhonePerformanceForegroundService.updateDiscreteState(context, PerformanceControlMessage.TYPE_PAN) {
                            it.copy(panLocked = nextLocked)
                        }
                        chevronNotice = if (nextLocked) "Zoom & Pan Locked" else "Zoom & Pan Unlocked"
                    }
                ) {
                    Icon(
                        imageVector = if (stateSnapshot.panLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (stateSnapshot.panLocked) "Unlock Zoom and Pan" else "Lock Zoom and Pan",
                        tint = if (stateSnapshot.panLocked) Color(0xFF93C5FD) else Color(0xFF8E9198)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isTabletCacheReady) {
                    Text(
                        text = "tablet syncing…",
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                PerformanceConnectionDots(status = linkStatus)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Large Dark Touchpad Box in the middle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF14141A))
                .border(
                    1.5.dp,
                    if (stateSnapshot.panLocked) Color(0xFF64B5F6) else Color(0xFF282834),
                    RoundedCornerShape(24.dp)
                )
        ) {
            PhoneTouchpadGestureArea(
                song = currentSong,
                stateSnapshot = stateSnapshot,
                onSectionChanged = { secName ->
                    chevronNotice = secName
                },
                onPanLockToggled = {
                    panLockAnimVisible = true
                }
            )

            // Section switch chevron + brief label pill (about 0.7s)
            val chevronAlpha by animateFloatAsState(
                targetValue = if (chevronNotice != null) 1f else 0f,
                animationSpec = tween(180),
                label = "chevronAlpha"
            )
            Box(
                modifier = Modifier.align(Alignment.Center)
            ) {
            if (chevronAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .alpha(chevronAlpha)
                        .background(Color(0xE6231136), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFAB47BC), RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = chevronNotice.orEmpty(),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            }

            // Pan lock icon indicator
            if (stateSnapshot.panLocked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .background(Color(0xCC1E3A8A), RoundedCornerShape(8.dp))
                        .padding(6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Lock, contentDescription = "Pan Locked", tint = Color(0xFF93C5FD), modifier = Modifier.size(16.dp))
                }
            }
        }

        // Section notice auto-dismiss after 0.7s
        LaunchedEffect(chevronNotice) {
            if (chevronNotice != null) {
                delay(700L)
                chevronNotice = null
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Song Row: Horizontally scrolling row of small file icons with song title
        // Drag to reorder live (Section 7: saved to session, NO HAPTICS)
        PhoneSongRowSelector(
            session = session,
            currentSongId = stateSnapshot.songId,
            onSongSelected = { songId ->
                if (session.findSong(songId) == null) return@PhoneSongRowSelector
                PhonePerformanceForegroundService.updateDiscreteState(context, PerformanceControlMessage.TYPE_SONG) {
                    it.copy(songId = songId, sectionIndex = 0, scrollY = 0f, zoom = 1f, panX = 0f)
                }
            },
            onReorderLive = { reorderedSongIds ->
                val updatedSession = session.copy(songOrder = reorderedSongIds)
                PerformanceStorageAndCacheManager.updateSessionStructureOrNotesLive(context, updatedSession)
                PhonePerformanceForegroundService.refreshLocalSessionInMemory(updatedSession)
            }
        )
    }
}

// =============================================================================
// Touchpad Gesture Classifier & Google TV Remote Touch Animations (Section 7)
// Strictly ZERO HAPTICS / ZERO SOUNDS
// =============================================================================

private enum class GestureMode {
    UNDECIDED,
    ONE_FINGER_SCROLL,
    ONE_FINGER_SWIPE_SECTION,
    TWO_FINGER_ZOOM,
    TWO_FINGER_PAN
}

@Composable
private fun PhoneTouchpadGestureArea(
    song: PerformanceSong?,
    stateSnapshot: PerformanceStateSnapshot,
    onSectionChanged: (String) -> Unit,
    onPanLockToggled: () -> Unit
) {
    val context = LocalContext.current
    val latestSong by rememberUpdatedState(song)
    val latestSnap by rememberUpdatedState(stateSnapshot)
    val latestOnSectionChanged by rememberUpdatedState(onSectionChanged)
    val latestOnPanLockToggled by rememberUpdatedState(onPanLockToggled)

    var finger1Pos by remember { mutableStateOf<Offset?>(null) }
    var finger2Pos by remember { mutableStateOf<Offset?>(null) }
    var touchActive by remember { mutableStateOf(false) }

    var gestureMode by remember { mutableStateOf(GestureMode.UNDECIDED) }
    var initialDist by remember { mutableFloatStateOf(0f) }
    var initialZoom by remember { mutableFloatStateOf(1f) }
    var downStartPos by remember { mutableStateOf(Offset.Zero) }
    var downTimeMs by remember { mutableLongStateOf(0L) }
    var lastTapUpMs by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    var maxFingerCount by remember { mutableIntStateOf(0) }
    var prevDownCount by remember { mutableIntStateOf(0) }
    var sectionSwitchedThisGesture by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val changes = event.changes
                        val now = System.currentTimeMillis()
                        val pressedChanges = changes.filter { it.pressed }
                        val downCount = pressedChanges.size

                        if (downCount == 0) {
                            // Touch release: check quick tap / double-tap for Zoom+Pan Lock
                            if (prevDownCount == 1 && maxFingerCount == 1 && gestureMode == GestureMode.UNDECIDED) {
                                val upPos = finger1Pos ?: downStartPos
                                val moveDist = (upPos - downStartPos).getDistance()
                                if (now - downTimeMs < 260L && moveDist < 32f) {
                                    if (now - lastTapUpMs < 320L && (upPos - lastTapPos).getDistance() < 90f) {
                                        val nextLock = !latestSnap.panLocked
                                        PhonePerformanceForegroundService.updateDiscreteState(
                                            context,
                                            PerformanceControlMessage.TYPE_PAN
                                        ) { it.copy(panLocked = nextLock) }
                                        latestOnPanLockToggled()
                                        lastTapUpMs = 0L
                                    } else {
                                        lastTapUpMs = now
                                        lastTapPos = upPos
                                    }
                                }
                            }
                            finger1Pos = null
                            finger2Pos = null
                            touchActive = false
                            gestureMode = GestureMode.UNDECIDED
                            initialDist = 0f
                            maxFingerCount = 0
                            prevDownCount = 0
                            sectionSwitchedThisGesture = false
                            continue
                        }

                        if (prevDownCount == 0) {
                            downStartPos = pressedChanges[0].position
                            downTimeMs = now
                            gestureMode = GestureMode.UNDECIDED
                            initialDist = 0f
                            maxFingerCount = downCount
                            sectionSwitchedThisGesture = false
                        } else if (downCount > maxFingerCount) {
                            maxFingerCount = downCount
                        }

                        touchActive = true
                        prevDownCount = downCount

                        if (downCount == 1) {
                            // Reset 2-finger pinch baseline so 1-finger scroll is never misread as zoom
                            initialDist = 0f
                            val change = pressedChanges[0]
                            finger1Pos = change.position
                            finger2Pos = null

                            if (maxFingerCount == 1) {
                                val totalDx = change.position.x - downStartPos.x
                                val totalDy = change.position.y - downStartPos.y

                                if (gestureMode == GestureMode.UNDECIDED) {
                                    when {
                                        abs(totalDx) > 65f && abs(totalDx) > abs(totalDy) * 1.35f -> {
                                            gestureMode = GestureMode.ONE_FINGER_SWIPE_SECTION
                                        }
                                        abs(totalDy) > 16f && abs(totalDy) >= abs(totalDx) -> {
                                            gestureMode = GestureMode.ONE_FINGER_SCROLL
                                        }
                                    }
                                }

                                when (gestureMode) {
                                    GestureMode.ONE_FINGER_SWIPE_SECTION -> {
                                        change.consume()
                                        val activeSong = latestSong
                                        if (!sectionSwitchedThisGesture && activeSong != null && activeSong.activeStructure.isNotEmpty()) {
                                            sectionSwitchedThisGesture = true
                                            val structSize = activeSong.activeStructure.size
                                            val curIdx = latestSnap.sectionIndex.coerceIn(0, structSize - 1)
                                            val nextIdx = if (totalDx < 0f) {
                                                // Swipe LEFT -> NEXT slide
                                                min(structSize - 1, curIdx + 1)
                                            } else {
                                                // Swipe RIGHT -> PREVIOUS slide
                                                max(0, curIdx - 1)
                                            }
                                            if (nextIdx != curIdx) {
                                                val targetStruct = activeSong.activeStructure[nextIdx]
                                                val reg = activeSong.findRegion(targetStruct.regionId)
                                                val label = reg?.displayLabel ?: "Slide ${nextIdx + 1}"
                                                latestOnSectionChanged(label)
                                                PhonePerformanceForegroundService.updateDiscreteState(
                                                    context,
                                                    PerformanceControlMessage.TYPE_SECTION
                                                ) {
                                                    it.copy(sectionIndex = nextIdx, scrollY = 0f)
                                                }
                                            }
                                        }
                                    }

                                    GestureMode.ONE_FINGER_SCROLL -> {
                                        change.consume()
                                        val dy = change.position.y - change.previousPosition.y
                                        val deltaNorm = -dy / 1400f
                                        val nextScroll = (latestSnap.scrollY + deltaNorm).coerceIn(0f, 1f)
                                        PhonePerformanceForegroundService.updateContinuousState(
                                            context,
                                            PerformanceControlMessage.TYPE_SCROLL
                                        ) { it.copy(scrollY = nextScroll) }
                                    }

                                    else -> {}
                                }
                            }
                        } else {
                            // 2+ fingers: pinch zoom & 2-finger horizontal pan (disabled when Zoom+Pan Lock is active)
                            val c1 = pressedChanges[0]
                            val c2 = pressedChanges[1]
                            val p1 = c1.position
                            val p2 = c2.position
                            finger1Pos = p1
                            finger2Pos = p2

                            if (latestSnap.panLocked) {
                                initialDist = 0f
                                c1.consume()
                                c2.consume()
                                continue
                            }

                            val currentDist = (p1 - p2).getDistance()
                            val avgDx = ((p1.x - c1.previousPosition.x) + (p2.x - c2.previousPosition.x)) * 0.5f

                            if (initialDist <= 0f) {
                                initialDist = currentDist
                                initialZoom = latestSnap.zoom
                            }

                            if (gestureMode == GestureMode.UNDECIDED || gestureMode == GestureMode.ONE_FINGER_SCROLL) {
                                val distDelta = abs(currentDist - initialDist)
                                when {
                                    distDelta > 28f -> {
                                        gestureMode = GestureMode.TWO_FINGER_ZOOM
                                    }
                                    abs(avgDx) > 14f -> {
                                        gestureMode = GestureMode.TWO_FINGER_PAN
                                    }
                                }
                            }

                            when (gestureMode) {
                                GestureMode.TWO_FINGER_ZOOM -> {
                                    c1.consume()
                                    c2.consume()
                                    if (initialDist > 0f) {
                                        val factor = currentDist / initialDist
                                        val nextZoom = (initialZoom * factor).coerceIn(0.75f, 2.5f)
                                        PhonePerformanceForegroundService.updateContinuousState(
                                            context,
                                            PerformanceControlMessage.TYPE_ZOOM
                                        ) { it.copy(zoom = nextZoom) }
                                    }
                                }

                                GestureMode.TWO_FINGER_PAN -> {
                                    c1.consume()
                                    c2.consume()
                                    val deltaPan = avgDx / 1100f
                                    val nextPan = (latestSnap.panX + deltaPan).coerceIn(-0.8f, 0.8f)
                                    PhonePerformanceForegroundService.updateContinuousState(
                                        context,
                                        PerformanceControlMessage.TYPE_PAN
                                    ) { it.copy(panX = nextPan) }
                                }

                                else -> {}
                            }
                        }
                    }
                }
            }
    ) {
        // Lightweight visual touch animations (Canvas only, low power, NO HAPTICS)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val f1 = finger1Pos
            val f2 = finger2Pos

            if (f1 != null && f2 != null) {
                // Two fingers: two glows with connecting line
                drawLine(
                    color = Color(0x66AB47BC),
                    start = f1,
                    end = f2,
                    strokeWidth = 3f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                drawCircle(
                    color = Color(0x80CE93D8),
                    radius = 36f,
                    center = f1
                )
                drawCircle(
                    color = Color(0x80CE93D8),
                    radius = 36f,
                    center = f2
                )
            } else if (f1 != null) {
                // One finger: soft radial glow with quick ripple
                drawCircle(
                    color = Color(0x4DAB47BC),
                    radius = 48f,
                    center = f1
                )
                drawCircle(
                    color = Color(0x99E1BEE7),
                    radius = 16f,
                    center = f1
                )
            }
        }
    }
}

// =============================================================================
// Song Row Component (Section 7)
// =============================================================================

@Composable
private fun PhoneSongRowSelector(
    session: PerformanceSession,
    currentSongId: String,
    onSongSelected: (String) -> Unit,
    onReorderLive: (List<String>) -> Unit
) {
    val songs = session.orderedSongs
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(songs, key = { _, s -> s.songId }) { idx, s ->
            val isCurrent = s.songId == currentSongId
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isCurrent) Color(0xFF3B1C5A) else Color(0xFF181820))
                    .border(
                        1.2.dp,
                        if (isCurrent) Color(0xFFAB47BC) else Color(0xFF2C2C38),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onSongSelected(s.songId) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (isCurrent) Color(0xFFE1BEE7) else Color(0xFF888899),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${idx + 1}. ${s.title}",
                    color = if (isCurrent) Color.White else Color(0xFFBBBBC8),
                    fontSize = 13.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

// =============================================================================
// 4. Split-Screen Layout (Section 8)
// =============================================================================

@Composable
private fun PhonePerformanceSplitScreenLayout(
    session: PerformanceSession,
    stateSnapshot: PerformanceStateSnapshot,
    linkStatus: PerformanceLinkStatus,
    isTabletCacheReady: Boolean
) {
    val context = LocalContext.current
    val currentSong = remember(session, stateSnapshot.songId) {
        session.findSong(stateSnapshot.songId) ?: session.orderedSongs.firstOrNull()
    }
    val latestSession by rememberUpdatedState(session)
    val latestSnap by rememberUpdatedState(stateSnapshot)
    val latestSong by rememberUpdatedState(currentSong)

    var flashedSectionName by remember { mutableStateOf<String?>(null) }
    var flashedSongName by remember { mutableStateOf<String?>(null) }

    // 3-second hold progress ring (visual only, NO HAPTICS)
    var isHoldingForSongSwitch by remember { mutableStateOf(false) }
    val holdProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D11))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        val activeSong = latestSong ?: return@detectTapGestures
                        val struct = activeSong.activeStructure
                        if (struct.isEmpty()) return@detectTapGestures
                        val curIdx = latestSnap.sectionIndex.coerceIn(0, struct.size - 1)
                        val nextIdx = min(struct.size - 1, curIdx + 1)
                        if (nextIdx != curIdx) {
                            val reg = activeSong.findRegion(struct[nextIdx].regionId)
                            flashedSectionName = reg?.displayLabel ?: "Slide ${nextIdx + 1}"
                            PhonePerformanceForegroundService.updateDiscreteState(
                                context,
                                PerformanceControlMessage.TYPE_SECTION
                            ) { it.copy(sectionIndex = nextIdx, scrollY = 0f) }
                        }
                    },
                    onDoubleTap = {
                        val activeSong = latestSong ?: return@detectTapGestures
                        val struct = activeSong.activeStructure
                        if (struct.isEmpty()) return@detectTapGestures
                        val curIdx = latestSnap.sectionIndex.coerceIn(0, struct.size - 1)
                        val prevIdx = max(0, curIdx - 1)
                        if (prevIdx != curIdx) {
                            val reg = activeSong.findRegion(struct[prevIdx].regionId)
                            flashedSectionName = reg?.displayLabel ?: "Slide ${prevIdx + 1}"
                            PhonePerformanceForegroundService.updateDiscreteState(
                                context,
                                PerformanceControlMessage.TYPE_SECTION
                            ) { it.copy(sectionIndex = prevIdx, scrollY = 0f) }
                        }
                    },
                    onPress = {
                        // Press and hold for 3 seconds -> go to next song in arranged order (wrapping)
                        isHoldingForSongSwitch = true
                        holdProgress.snapTo(0f)
                        val animJob = coroutineScope.launch {
                            holdProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 3000, easing = LinearEasing)
                            )
                        }

                        val released = tryAwaitRelease()
                        animJob.cancel()
                        if (released && holdProgress.value >= 0.98f) {
                            val ordered = latestSession.orderedSongs
                            if (ordered.isNotEmpty()) {
                                val curSongIdx = ordered.indexOfFirst { it.songId == latestSnap.songId }
                                val nextSongIdx = if (curSongIdx >= 0) (curSongIdx + 1) % ordered.size else 0
                                val nextSong = ordered[nextSongIdx]
                                flashedSongName = nextSong.title

                                PhonePerformanceForegroundService.updateDiscreteState(
                                    context,
                                    PerformanceControlMessage.TYPE_SONG
                                ) {
                                    it.copy(
                                        songId = nextSong.songId,
                                        sectionIndex = 0,
                                        scrollY = 0f
                                    )
                                }
                            }
                        }
                        isHoldingForSongSwitch = false
                        holdProgress.snapTo(0f)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Subtle visual hint
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = currentSong?.title ?: "No Song Selected",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Single tap: Next | Double tap: Prev | Hold 3s: Next Song",
                color = Color(0xFF6E6E7E),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }

        // 3-second Hold Progress Ring
        if (isHoldingForSongSwitch && holdProgress.value > 0.02f) {
            Canvas(modifier = Modifier.size(110.dp)) {
                drawCircle(
                    color = Color(0x33AB47BC),
                    radius = size.minDimension * 0.48f,
                    style = Stroke(width = 8f)
                )
                drawArc(
                    color = Color(0xFFE1BEE7),
                    startAngle = -90f,
                    sweepAngle = holdProgress.value * 360f,
                    useCenter = false,
                    style = Stroke(width = 8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }
        }

        // Flashed section / song name pill
        AnimatedVisibility(
            visible = flashedSectionName != null || flashedSongName != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xE6231136), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFFAB47BC), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = flashedSongName ?: flashedSectionName.orEmpty(),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Non-interactive connection dots in corner
        PerformanceConnectionDots(
            status = linkStatus,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        )

        LaunchedEffect(flashedSectionName) {
            if (flashedSectionName != null) {
                delay(800L)
                flashedSectionName = null
            }
        }
        LaunchedEffect(flashedSongName) {
            if (flashedSongName != null) {
                delay(1200L)
                flashedSongName = null
            }
        }
    }
}

// =============================================================================
// 5. Structure Screen (Section 9)
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhonePerformanceStructureScreen(
    session: PerformanceSession,
    song: PerformanceSong,
    linkStatus: PerformanceLinkStatus,
    stateSnapshot: PerformanceStateSnapshot,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var structureList by remember { mutableStateOf(song.activeStructure) }
    var selectedRegionId by remember {
        mutableStateOf(structureList.firstOrNull()?.regionId ?: song.regions.firstOrNull()?.regionId.orEmpty())
    }

    val selectedRegion = remember(song, selectedRegionId) {
        song.findRegion(selectedRegionId) ?: song.regions.firstOrNull()
    }

    var sliderYStart by remember(selectedRegion) {
        mutableFloatStateOf(selectedRegion?.yStartNorm ?: 0f)
    }
    var sliderYEnd by remember(selectedRegion) {
        mutableFloatStateOf(selectedRegion?.yEndNorm ?: 1f)
    }

    var isAddingNote by remember { mutableStateOf(false) }
    var noteAnchorLineIdx by remember { mutableIntStateOf(0) }
    var noteText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F14))
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column {
                    Text(
                        text = "Slides: ${song.title}",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Reorder slides • Drag top/bottom handles on full song",
                        color = Color(0xFF888899),
                        fontSize = 11.sp
                    )
                }
            }

            TextButton(
                onClick = {
                    structureList = song.defaultStructure
                    val updatedSong = song.copy(customStructure = song.defaultStructure)
                    val updatedSess = session.updateSong(updatedSong)
                    PerformanceStorageAndCacheManager.updateSessionStructureOrNotesLive(context, updatedSess)
                    PhonePerformanceForegroundService.refreshLocalSessionInMemory(updatedSess)
                }
            ) {
                Text("Reset", color = Color(0xFFCE93D8), fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Top Horizontal Slide Sorter (PowerPoint-style slide cards)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(structureList, key = { idx, it -> "${it.instanceId}_$idx" }) { sIdx, item ->
                val region = song.findRegion(item.regionId)
                val isSelected = item.regionId == selectedRegionId
                val accent = region?.category?.accentColor ?: Color(0xFFAB47BC)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) accent.copy(alpha = 0.24f) else Color(0xFF1A1A26),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) accent else Color(0xFF2E2E3E)
                    ),
                    modifier = Modifier
                        .width(126.dp)
                        .clickable { selectedRegionId = item.regionId }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Slide ${sIdx + 1}",
                                color = Color(0xFF8E92A4),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "+",
                                    color = Color(0xFF81C784),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        val nextList = structureList.toMutableList()
                                        nextList.add(
                                            sIdx + 1,
                                            SongStructureItem(
                                                instanceId = "struct_${UUID.randomUUID().toString().take(6)}",
                                                regionId = item.regionId
                                            )
                                        )
                                        structureList = nextList
                                        val updatedSong = song.copy(customStructure = nextList)
                                        val updatedSess = session.updateSong(updatedSong)
                                        PerformanceStorageAndCacheManager.updateSessionStructureOrNotesLive(context, updatedSess)
                                        PhonePerformanceForegroundService.refreshLocalSessionInMemory(updatedSess)
                                    }
                                )
                                if (structureList.size > 1) {
                                    Text(
                                        text = "×",
                                        color = Color(0xFFE57373),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable {
                                            val nextList = structureList.toMutableList()
                                            nextList.removeAt(sIdx)
                                            structureList = nextList
                                            val updatedSong = song.copy(customStructure = nextList)
                                            val updatedSess = session.updateSong(updatedSong)
                                            PerformanceStorageAndCacheManager.updateSessionStructureOrNotesLive(context, updatedSess)
                                            PhonePerformanceForegroundService.refreshLocalSessionInMemory(updatedSess)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = region?.displayLabel ?: "Section",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "◀",
                                color = if (sIdx > 0) Color(0xFFB0BEC5) else Color(0xFF3A3A4A),
                                fontSize = 12.sp,
                                modifier = Modifier.clickable(enabled = sIdx > 0) {
                                    val nextList = structureList.toMutableList()
                                    val moved = nextList.removeAt(sIdx)
                                    nextList.add(sIdx - 1, moved)
                                    structureList = nextList
                                    val updatedSong = song.copy(customStructure = nextList)
                                    val updatedSess = session.updateSong(updatedSong)
                                    PerformanceStorageAndCacheManager.updateSessionStructureOrNotesLive(context, updatedSess)
                                    PhonePerformanceForegroundService.refreshLocalSessionInMemory(updatedSess)
                                }
                            )
                            Text(
                                text = "▶",
                                color = if (sIdx < structureList.lastIndex) Color(0xFFB0BEC5) else Color(0xFF3A3A4A),
                                fontSize = 12.sp,
                                modifier = Modifier.clickable(enabled = sIdx < structureList.lastIndex) {
                                    val nextList = structureList.toMutableList()
                                    val moved = nextList.removeAt(sIdx)
                                    nextList.add(sIdx + 1, moved)
                                    structureList = nextList
                                    val updatedSong = song.copy(customStructure = nextList)
                                    val updatedSess = session.updateSong(updatedSong)
                                    PerformanceStorageAndCacheManager.updateSessionStructureOrNotesLive(context, updatedSess)
                                    PhonePerformanceForegroundService.refreshLocalSessionInMemory(updatedSess)
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Main 2-column area: Left = Super-zoomed-out full song with draggable TOP/BOT visual limits; Right = Selected slide details & notes
        if (selectedRegion != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF181822), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF2E2E3E), RoundedCornerShape(16.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Left Column (~45%): Super-zoomed-out full song PDF preview + draggable TOP & BOT limit bars
                Column(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Full Song (${(sliderYStart * 100).toInt()}%–${(sliderYEnd * 100).toInt()}%)",
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ZoomedOutSongBoundsPreview(
                        sessionId = session.sessionId,
                        song = song,
                        accentColor = selectedRegion.category.accentColor,
                        yStartNorm = sliderYStart,
                        yEndNorm = sliderYEnd,
                        onBoundsChanged = { newStart, newEnd ->
                            sliderYStart = newStart
                            sliderYEnd = newEnd
                            val updatedReg = selectedRegion.copy(yStartNorm = newStart, yEndNorm = newEnd)
                            val updatedSong = song.updateRegion(updatedReg)
                            val updatedSess = session.updateSong(updatedSong)
                            PerformanceStorageAndCacheManager.updateSessionStructureOrNotesLive(context, updatedSess)
                            PhonePerformanceForegroundService.refreshLocalSessionInMemory(updatedSess)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0E0E14))
                            .border(1.dp, Color(0xFF2A2A38), RoundedCornerShape(10.dp))
                    )
                }

                // Right Column (~55%): Selected Slide Lines & Pinned Notes
                Column(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedRegion.displayLabel,
                            color = selectedRegion.category.accentColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(
                            onClick = {
                                noteText = ""
                                noteAnchorLineIdx = 0
                                isAddingNote = true
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Note", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (selectedRegion.lines.isEmpty()) {
                        Text("No detected lines in this region (slider bounds applied)", color = Color(0xFF757582), fontSize = 13.sp)
                    } else {
                        selectedRegion.lines.forEachIndexed { lineIdx, line ->
                            val noteForLine = song.notes.firstOrNull {
                                it.regionId == selectedRegion.regionId && it.lineIndexInSection == lineIdx
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        noteAnchorLineIdx = lineIdx
                                        noteText = noteForLine?.text.orEmpty()
                                        isAddingNote = true
                                    }
                                    .padding(vertical = 3.dp)
                            ) {
                                Text(
                                    text = line.text,
                                    color = if (line.isChordLine) Color(0xFFFFCA28) else Color(0xFFE0E0E0),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (line.isChordLine) FontWeight.Bold else FontWeight.Normal
                                )

                                if (noteForLine != null) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF2C193C), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "📌 ${noteForLine.text}",
                                            color = Color(0xFFE1BEE7),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        IconButton(
                                            onClick = {
                                                val nextNotes = song.notes.filterNot { it.noteId == noteForLine.noteId }
                                                val updatedSong = song.copy(notes = nextNotes)
                                                val updatedSess = session.updateSong(updatedSong)
                                                PerformanceStorageAndCacheManager.updateSessionStructureOrNotesLive(context, updatedSess)
                                                PhonePerformanceForegroundService.refreshLocalSessionInMemory(updatedSess)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Close, contentDescription = "Delete Note", tint = Color(0xFFFF8A80), modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Diagnostic Footer (Section 9: e.g. "BT 38 ms / Net 210 ms")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = linkStatus.formatDiagnosticsFooter(),
                color = Color(0xFF6E6E80),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    // Add / Edit Note Dialog
    if (isAddingNote && selectedRegion != null) {
        AlertDialog(
            onDismissRequest = { isAddingNote = false },
            title = { Text("Pinned Note for ${selectedRegion.displayLabel}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Anchored after line #${noteAnchorLineIdx + 1}",
                        color = Color(0xFF9E9EAA),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Note content (e.g. Solo 8 bars, Drop drums)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (noteText.isNotBlank()) {
                            val newNote = SongPinnedNote(
                                noteId = "note_${UUID.randomUUID().toString().take(6)}",
                                regionId = selectedRegion.regionId,
                                lineIndexInSection = noteAnchorLineIdx,
                                text = noteText.trim()
                            )
                            val filtered = song.notes.filterNot {
                                it.regionId == selectedRegion.regionId && it.lineIndexInSection == noteAnchorLineIdx
                            }
                            val updatedSong = song.copy(notes = filtered + newNote)
                            val updatedSess = session.updateSong(updatedSong)
                            PerformanceStorageAndCacheManager.updateSessionStructureOrNotesLive(context, updatedSess)
                            PhonePerformanceForegroundService.refreshLocalSessionInMemory(updatedSess)
                            isAddingNote = false
                        }
                    }
                ) {
                    Text("Pin Note")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { isAddingNote = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PuzzlePieceBadge(
    displayLabel: String,
    category: SectionCategory,
    isSelected: Boolean,
    onTap: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) category.containerColor else category.containerColor.copy(alpha = 0.5f))
                .border(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) Color.White else category.accentColor,
                    RoundedCornerShape(10.dp)
                )
                .clickable { onTap() }
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = displayLabel,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Duplicate") },
                onClick = {
                    showMenu = false
                    onDuplicate()
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = Color(0xFFFF5252)) },
                onClick = {
                    showMenu = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun ZoomedOutSongBoundsPreview(
    sessionId: String,
    song: PerformanceSong,
    accentColor: Color,
    yStartNorm: Float,
    yEndNorm: Float,
    onBoundsChanged: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestStart by rememberUpdatedState(yStartNorm)
    val latestEnd by rememberUpdatedState(yEndNorm)
    val latestOnChanged by rememberUpdatedState(onBoundsChanged)

    val tileHeights = song.tileHeightsPx
    val totalStripW = max(1, song.stripWidthPx)
    val totalStripH = max(1, tileHeights.sum())

    val filterPaint = remember {
        android.graphics.Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val boxW = max(1f, constraints.maxWidth.toFloat())
        val boxH = max(1f, constraints.maxHeight.toFloat())

        val fitScale = min(boxW / totalStripW.toFloat(), boxH / totalStripH.toFloat())
        val scaledW = totalStripW * fitScale
        val scaledH = totalStripH * fitScale
        val originX = (boxW - scaledW) * 0.5f
        val originY = (boxH - scaledH) * 0.5f

        var draggingHandle by remember { mutableStateOf<Int>(0) } // 1 = top, 2 = bottom

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(song.songId) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                draggingHandle = 0
                                continue
                            }
                            val change = pressed[0]
                            val yNorm = ((change.position.y - originY) / scaledH).coerceIn(0f, 1f)
                            if (draggingHandle == 0) {
                                val distTop = abs(yNorm - latestStart)
                                val distBot = abs(yNorm - latestEnd)
                                draggingHandle = if (distTop <= distBot) 1 else 2
                            }
                            change.consume()
                            if (draggingHandle == 1) {
                                val clampedStart = yNorm.coerceIn(0f, (latestEnd - 0.03f).coerceAtLeast(0f))
                                latestOnChanged(clampedStart, latestEnd)
                            } else {
                                val clampedEnd = yNorm.coerceIn((latestStart + 0.03f).coerceAtMost(1f), 1f)
                                latestOnChanged(latestStart, clampedEnd)
                            }
                        }
                    }
                }
        ) {
            drawIntoCanvas { composeCanvas ->
                val canvas = composeCanvas.nativeCanvas
                var tileTopPx = 0f
                for (tileIdx in tileHeights.indices) {
                    val tileH = tileHeights[tileIdx].toFloat()
                    val bmp = PerformanceStorageAndCacheManager.loadTileBitmap(
                        context = context,
                        sessionId = sessionId,
                        songId = song.songId,
                        tileIndex = tileIdx
                    )
                    if (bmp != null && !bmp.isRecycled) {
                        val dstTop = originY + tileTopPx * fitScale
                        val dstBottom = dstTop + tileH * fitScale
                        val srcRect = android.graphics.Rect(0, 0, bmp.width, bmp.height)
                        val dstRect = android.graphics.RectF(originX, dstTop, originX + scaledW, dstBottom)
                        canvas.drawBitmap(bmp, srcRect, dstRect, filterPaint)
                    }
                    tileTopPx += tileH
                }

                val topY = originY + yStartNorm * scaledH
                val botY = originY + yEndNorm * scaledH

                // Dim outside the selected section limits
                val dimPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(170, 10, 10, 16)
                    style = android.graphics.Paint.Style.FILL
                }
                if (topY > originY) {
                    canvas.drawRect(originX, originY, originX + scaledW, topY, dimPaint)
                }
                if (botY < originY + scaledH) {
                    canvas.drawRect(originX, botY, originX + scaledW, originY + scaledH, dimPaint)
                }

                // Highlight selected slice border
                val borderPaint = android.graphics.Paint().apply {
                    color = accentColor.toArgb()
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 3f
                }
                canvas.drawRect(originX, topY, originX + scaledW, botY, borderPaint)

                // Top limit handle bar (blue)
                val topPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#64B5F6")
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawRect(0f, topY - 2f, size.width, topY + 2f, topPaint)
                canvas.drawRoundRect(
                    android.graphics.RectF(size.width - 42f, topY - 10f, size.width - 4f, topY + 10f),
                    8f,
                    8f,
                    topPaint
                )

                // Bottom limit handle bar (amber)
                val botPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#FFB74D")
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawRect(0f, botY - 2f, size.width, botY + 2f, botPaint)
                canvas.drawRoundRect(
                    android.graphics.RectF(size.width - 42f, botY - 10f, size.width - 4f, botY + 10f),
                    8f,
                    8f,
                    botPaint
                )
            }
        }
    }
}
