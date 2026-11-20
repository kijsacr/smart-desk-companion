package com.smartdesk.companion.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.smartdesk.companion.phone.service.PhonePerformanceForegroundService
import com.smartdesk.companion.phone.ui.PerformanceEndConfirmDialogActivity
import com.smartdesk.companion.phone.ui.PhonePerformanceRoot
import com.smartdesk.companion.shared.PerformanceStorageAndCacheManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.phone.config.ForwardedAlertItem
import com.smartdesk.companion.phone.config.PhoneSyncPreferences
import com.smartdesk.companion.phone.service.PhoneNotificationListenerService
import com.smartdesk.companion.phone.service.PhonePresenceAndBatteryService
import com.smartdesk.companion.phone.sync.PhoneTaskAndThemeManager
import com.smartdesk.companion.phone.sync.TimetableCommandSender
import com.smartdesk.companion.phone.ui.PhoneV7SettingsSection
import com.smartdesk.companion.phone.ui.isAppIgnoringBatteryOptimizations
import com.smartdesk.companion.phone.ui.requestIgnoreBatteryOptimizations
import com.smartdesk.companion.phone.widget.PcMonitorWidgetProvider
import com.smartdesk.companion.phone.widget.TimetableWidgetProvider
import com.smartdesk.companion.shared.AlexaStatusPayload
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.BatteryAutomationConfig
import com.smartdesk.companion.shared.BatteryAutomationStatus
import com.smartdesk.companion.shared.BatteryProtectionStateMachine
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.shared.ClockThemeCatalog
import com.smartdesk.companion.shared.NightScheduleConfig
import com.smartdesk.companion.shared.PcStatsPayload
import com.smartdesk.companion.shared.PhoneDndPayload
import com.smartdesk.companion.shared.ProximityConfig
import com.smartdesk.companion.shared.SubjectCatalog
import com.smartdesk.companion.shared.SubjectEntry
import com.smartdesk.companion.shared.TabletScreenStatus
import com.smartdesk.companion.shared.TaskItem
import com.smartdesk.companion.shared.TaskScheduleLogic
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

private enum class PhoneDestination {
    HOME_TASK_CREATION,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private lateinit var syncPreferences: PhoneSyncPreferences
    private lateinit var taskAndThemeManager: PhoneTaskAndThemeManager
    private var hasPermissionState = mutableStateOf(false)
    private var hasSmsPermissionState = mutableStateOf(false)
    private var isIgnoringBatteryOptimizationsState = mutableStateOf(true)
    private var isInMultiWindowModeState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncPreferences = PhoneSyncPreferences(applicationContext)
        taskAndThemeManager = PhoneTaskAndThemeManager(syncPreferences)
        PhonePresenceAndBatteryService.start(applicationContext)
        PhonePerformanceForegroundService.initializeFromDiskIfNeeded(applicationContext)
        refreshPermissionsAndBatteryExemption()

        isInMultiWindowModeState.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInMultiWindowMode else false

        if (intent?.getBooleanExtra("extra_exit_performance_to_dashboard", false) == true) {
            PhonePerformanceForegroundService.endPerformanceMode(applicationContext)
        } else if (intent?.getBooleanExtra("extra_open_performance_screen", false) == true) {
            PhonePerformanceForegroundService.startPerformanceMode(applicationContext)
        }

        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECEIVE_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                101
            )
        }

        setContent {
            val hasPermission by hasPermissionState
            val hasSmsPermission by hasSmsPermissionState
            val isIgnoringBatteryOpt by isIgnoringBatteryOptimizationsState
            val recentForwarded by PhoneSyncPreferences.recentForwarded.collectAsState()
            val lastSyncStatus by PhoneSyncPreferences.lastSyncStatus.collectAsState()
            val otpRedactionDetected by PhoneSyncPreferences.otpRedactionDetected.collectAsState()
            val allTasks by taskAndThemeManager.tasks.collectAsState()
            val selectedThemeId by taskAndThemeManager.selectedThemeId.collectAsState()
            val pcMonitorEnabled by taskAndThemeManager.pcMonitorEnabled.collectAsState()
            val pcFullScreenEnabled by taskAndThemeManager.pcFullScreenEnabled.collectAsState()
            val pcStats by taskAndThemeManager.pcStats.collectAsState()
            val batteryConfig by taskAndThemeManager.batteryConfig.collectAsState()
            val batteryStatus by taskAndThemeManager.batteryStatus.collectAsState()
            val serverTimeOffsetMs by taskAndThemeManager.serverTimeOffsetMs.collectAsState()
            val nightScheduleConfig by taskAndThemeManager.nightScheduleConfig.collectAsState()
            val pixelShiftEnabled by taskAndThemeManager.pixelShiftEnabled.collectAsState()
            val otpAlertsEnabled by taskAndThemeManager.otpAlertsEnabled.collectAsState()
            val proximityConfig by taskAndThemeManager.proximityConfig.collectAsState()
            val tabletScreenStatus by taskAndThemeManager.tabletScreenStatus.collectAsState()
            val musicModeEnabled by taskAndThemeManager.musicModeEnabled.collectAsState()
            val phoneDnd by taskAndThemeManager.phoneDnd.collectAsState()
            val alexaEnabled by taskAndThemeManager.alexaEnabled.collectAsState()
            val alexaStatus by taskAndThemeManager.alexaStatus.collectAsState()

            val perfState by PhonePerformanceForegroundService.stateSnapshot.collectAsState()
            val isMultiWindow by isInMultiWindowModeState

            if (perfState.enabled) {
                // V9 Section 2: Back button does NOT end Performance Mode
                BackHandler(enabled = true) {}

                PhonePerformanceRoot(
                    isSplitScreen = isMultiWindow,
                    onExitMode = {
                        val dialogIntent = Intent(applicationContext, PerformanceEndConfirmDialogActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        }
                        startActivity(dialogIntent)
                    }
                )
            } else {
                // Section 4.1: Start destination is the Task Creation screen (Home)
                var currentScreen by remember { mutableStateOf(PhoneDestination.HOME_TASK_CREATION) }

                BackHandler(enabled = currentScreen == PhoneDestination.SETTINGS) {
                    currentScreen = PhoneDestination.HOME_TASK_CREATION
                }

                when (currentScreen) {
                PhoneDestination.HOME_TASK_CREATION -> {
                    PhoneTaskCreationHomeScreen(
                        allTasks = allTasks,
                        onOpenSettings = { currentScreen = PhoneDestination.SETTINGS },
                        onCreateTask = { title, subjectId, deadlineIso, onDone ->
                            taskAndThemeManager.createTask(title, subjectId, deadlineIso, onDone)
                        },
                        onToggleTaskCompleted = { taskId, completed ->
                            taskAndThemeManager.setTaskCompleted(taskId, completed)
                        }
                    )
                }

                PhoneDestination.SETTINGS -> {
                    PhoneSettingsScreen(
                        hasNotificationPermission = hasPermission,
                        hasSmsPermission = hasSmsPermission,
                        isIgnoringBatteryOptimizations = isIgnoringBatteryOpt,
                        otpRedactionDetected = otpRedactionDetected,
                        syncPreferences = syncPreferences,
                        statusText = lastSyncStatus,
                        recentItems = recentForwarded,
                        selectedThemeId = selectedThemeId,
                        pcMonitorEnabled = pcMonitorEnabled,
                        pcFullScreenEnabled = pcFullScreenEnabled,
                        pcStats = pcStats,
                        batteryConfig = batteryConfig,
                        batteryStatus = batteryStatus,
                        serverTimeOffsetMs = serverTimeOffsetMs,
                        nightScheduleConfig = nightScheduleConfig,
                        pixelShiftEnabled = pixelShiftEnabled,
                        otpAlertsEnabled = otpAlertsEnabled,
                        proximityConfig = proximityConfig,
                        tabletScreenStatus = tabletScreenStatus,
                        musicModeEnabled = musicModeEnabled,
                        phoneDnd = phoneDnd,
                        alexaEnabled = alexaEnabled,
                        alexaStatus = alexaStatus,
                        onSelectClockTheme = { themeId ->
                            taskAndThemeManager.selectClockTheme(themeId)
                        },
                        onTogglePcMonitor = { enabled ->
                            taskAndThemeManager.setPcMonitorEnabled(enabled)
                        },
                        onTogglePcFullScreen = { enabled ->
                            taskAndThemeManager.setPcFullScreenEnabled(enabled)
                        },
                        onSaveBatteryConfig = { enabled, cutAt, resumeAt, offUrl, onUrl, method ->
                            taskAndThemeManager.saveBatteryAutomationConfig(
                                enabled = enabled,
                                rawCutAt = cutAt,
                                rawResumeAt = resumeAt,
                                offUrl = offUrl,
                                onUrl = onUrl,
                                method = method
                            )
                        },
                        onTestBatteryWebhook = { label, url, method, onResult ->
                            taskAndThemeManager.testWebhookOrPhoneAlert(label, url, method, onResult)
                        },
                        onRequestSmsPermission = {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.RECEIVE_SMS),
                                102
                            )
                        },
                        onRequestIgnoreBatteryOptimizations = {
                            requestIgnoreBatteryOptimizations(this)
                        },
                        onSaveNightSchedule = { enabled, start, end, brightnessPct ->
                            taskAndThemeManager.saveNightScheduleConfig(
                                enabled = enabled,
                                start = start,
                                end = end,
                                brightnessPct = brightnessPct
                            )
                        },
                        onTogglePixelShift = { enabled ->
                            taskAndThemeManager.setPixelShiftEnabled(enabled)
                        },
                        onToggleOtpAlerts = { enabled ->
                            taskAndThemeManager.setOtpAlertsEnabled(enabled)
                        },
                        onSendTestOtp = {
                            PhoneNotificationListenerService.sendSampleTestOtp(applicationContext)
                        },
                        onSaveProximityConfig = { enabled, offDelayMin ->
                            taskAndThemeManager.saveProximityConfig(
                                enabled = enabled,
                                offDelayMin = offDelayMin
                            )
                        },
                        onToggleMusicMode = { enabled ->
                            taskAndThemeManager.setMusicModeEnabled(enabled)
                        },
                        onToggleAlexaEnabled = { enabled ->
                            taskAndThemeManager.setAlexaEnabled(enabled)
                        },
                        onBackToHome = {
                            currentScreen = PhoneDestination.HOME_TASK_CREATION
                        },
                        onFirebaseConfigSaved = {
                            taskAndThemeManager.reconnect()
                            PhonePresenceAndBatteryService.start(applicationContext)
                        },
                        onOpenPermissionSettings = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onSendTestNotification = {
                            val nowText = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                            PhoneNotificationListenerService.pushPayloadToFirebase(
                                context = applicationContext,
                                packageName = "com.whatsapp",
                                title = "Sarah",
                                message = "See you at lunch!",
                                timestamp = nowText
                            )
                        },
                        onTriggerTimetableOnTablet = {
                            TimetableCommandSender.sendShowTimetableCommand(applicationContext) { success, _, msg ->
                                runOnUiThread {
                                    Toast.makeText(
                                        applicationContext,
                                        msg,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    if (success) {
                                        TimetableWidgetProvider.updateAllWidgets(applicationContext)
                                    }
                                }
                            }
                        },
                        onClearRemoteNotifications = {
                            // Module I: Clear Tablet clears ONLY /notifications, never /tasks
                            runCatching {
                                syncPreferences.resolveDatabaseReference().removeValue()
                                PhoneSyncPreferences.updateStatus("Cleared /notifications on tablet")
                            }
                        }
                    )
                }
            }
        }
    }
}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("extra_exit_performance_to_dashboard", false)) {
            PhonePerformanceForegroundService.endPerformanceMode(applicationContext)
        } else if (intent.getBooleanExtra("extra_open_performance_screen", false)) {
            PhonePerformanceForegroundService.startPerformanceMode(applicationContext)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        isInMultiWindowModeState.value = isInMultiWindowMode
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionsAndBatteryExemption()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionsAndBatteryExemption()
        PhonePresenceAndBatteryService.start(applicationContext)
        runCatching {
            TimetableWidgetProvider.updateAllWidgets(applicationContext)
            PcMonitorWidgetProvider.updateAllWidgets(applicationContext)
        }
    }

    private fun refreshPermissionsAndBatteryExemption() {
        hasPermissionState.value = PhoneNotificationListenerService.isPermissionGranted(this)
        hasSmsPermissionState.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        isIgnoringBatteryOptimizationsState.value = isAppIgnoringBatteryOptimizations(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::taskAndThemeManager.isInitialized) {
            taskAndThemeManager.release()
        }
    }
}

/**
 * Section 4.2: Home / Task Creation Screen [NEW]
 * 1. Top bar: Settings button on the top left, screen title "New Task".
 * 2. Task title text field (required).
 * 3. Subject dropdown listing every subject from SubjectCatalog, with Others last.
 * 4. Deadline field opening a Material 3 DatePickerDialog (calendar mode; dates before today disabled).
 * 5. Add Task button (enabled only when title, subject, and deadline are all set).
 * 6. Pending Tasks list (sorted nearest deadline first; red urgency for tomorrow, today, overdue; checkbox on left).
 * 7. Collapsible Completed section at the bottom to untick tasks marked done by mistake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneTaskCreationHomeScreen(
    allTasks: List<TaskItem>,
    onOpenSettings: () -> Unit,
    onCreateTask: (title: String, subjectId: String, deadlineIso: String, onDone: (Boolean) -> Unit) -> Unit,
    onToggleTaskCompleted: (taskId: String, completed: Boolean) -> Unit
) {
    val context = LocalContext.current

    var taskTitle by remember { mutableStateOf("") }
    var selectedSubject by remember { mutableStateOf<SubjectEntry?>(null) }
    var selectedDeadlineDate by remember { mutableStateOf<LocalDate?>(null) }

    var showSubjectDropdown by remember { mutableStateOf(false) }
    var showDatePickerDialog by remember { mutableStateOf(false) }
    var showCompletedSection by remember { mutableStateOf(false) }
    var confirmationMessage by remember { mutableStateOf<String?>(null) }

    // Re-evaluate deadline labels and urgency every minute
    var minuteTick by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            minuteTick = System.currentTimeMillis() / 60_000L
        }
    }

    LaunchedEffect(confirmationMessage) {
        if (confirmationMessage != null) {
            delay(2500L)
            confirmationMessage = null
        }
    }

    val today = remember(minuteTick) { LocalDate.now() }
    val pendingTasks = remember(allTasks, minuteTick) {
        TaskScheduleLogic.sortPendingTasks(allTasks)
    }
    val completedTasks = remember(allTasks) {
        TaskScheduleLogic.sortCompletedTasks(allTasks)
    }

    val isFormValid = taskTitle.isNotBlank() &&
        selectedSubject != null &&
        selectedDeadlineDate != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Top Bar: Settings button on top-left + screen title "New Task"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF1A1B1E), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF2E3036), RoundedCornerShape(12.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Open Settings",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Text(
                text = "New Task",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
        }

        // Task Creation Form Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141518), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF2A2C32), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 2. Task Title Text Field (required)
            OutlinedTextField(
                value = taskTitle,
                onValueChange = { taskTitle = it },
                label = { Text("Task title (required)") },
                placeholder = { Text("e.g. Chapter 5 problem set") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF63B8FF),
                    unfocusedBorderColor = Color(0xFF3A3D45),
                    focusedLabelColor = Color(0xFF63B8FF),
                    unfocusedLabelColor = Color(0xFF9AA0A6)
                )
            )

            // 3. Subject Dropdown
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E2025))
                        .border(1.dp, Color(0xFF3A3D45), RoundedCornerShape(12.dp))
                        .clickable { showSubjectDropdown = true }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        val currentSub = selectedSubject
                        if (currentSub != null) {
                            Icon(
                                imageVector = currentSub.icon,
                                contentDescription = currentSub.name,
                                tint = Color(0xFF63B8FF),
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = currentSub.name,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = "Select Subject",
                                color = Color(0xFF9AA0A6),
                                fontSize = 15.sp
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Choose Subject",
                        tint = Color(0xFF9AA0A6)
                    )
                }

                DropdownMenu(
                    expanded = showSubjectDropdown,
                    onDismissRequest = { showSubjectDropdown = false },
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .background(Color(0xFF1F2126))
                ) {
                    SubjectCatalog.allSubjects.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = entry.icon,
                                        contentDescription = entry.name,
                                        tint = Color(0xFF63B8FF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = entry.name,
                                        color = Color.White,
                                        fontSize = 15.sp
                                    )
                                }
                            },
                            onClick = {
                                selectedSubject = entry
                                showSubjectDropdown = false
                            }
                        )
                    }
                }
            }

            // 4. Deadline Field (opens Material 3 Calendar DatePicker)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E2025))
                    .border(1.dp, Color(0xFF3A3D45), RoundedCornerShape(12.dp))
                    .clickable { showDatePickerDialog = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = "Select Deadline",
                        tint = Color(0xFF63B8FF),
                        modifier = Modifier.size(22.dp)
                    )
                    val dateVal = selectedDeadlineDate
                    Text(
                        text = if (dateVal != null) {
                            TaskScheduleLogic.formatFullDateForField(dateVal)
                        } else {
                            "Select Deadline Date"
                        },
                        color = if (dateVal != null) Color.White else Color(0xFF9AA0A6),
                        fontSize = 15.sp,
                        fontWeight = if (dateVal != null) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                Text(
                    text = "Calendar",
                    color = Color(0xFF63B8FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 5. Add Task Button (enabled only when title, subject, and deadline are all set)
            Button(
                onClick = {
                    val sub = selectedSubject ?: return@Button
                    val deadlineDate = selectedDeadlineDate ?: return@Button
                    val isoDeadline = TaskScheduleLogic.formatIsoDate(deadlineDate)
                    onCreateTask(taskTitle, sub.id, isoDeadline) { success ->
                        if (success) {
                            taskTitle = ""
                            selectedSubject = null
                            selectedDeadlineDate = null
                            confirmationMessage = "Task added and synced to tablet"
                            Toast.makeText(context, "Task added", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = isFormValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E88E5),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF26282E),
                    disabledContentColor = Color(0xFF686C75)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "ADD TASK",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (confirmationMessage != null) {
                Text(
                    text = confirmationMessage.orEmpty(),
                    color = Color(0xFF34D399),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 6. Pending Tasks List (scrollable, same sort order and red highlight as tablet)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "PENDING TASKS (${pendingTasks.size})",
                color = Color(0xFF9AA0A6),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            if (pendingTasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF141518), RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "No pending tasks",
                        color = Color(0xFF686C75),
                        fontSize = 14.sp
                    )
                }
            } else {
                pendingTasks.forEach { task ->
                    PhoneTaskRowCard(
                        task = task,
                        today = today,
                        onCheckedChange = { checked ->
                            onToggleTaskCompleted(task.id, checked)
                        }
                    )
                }
            }
        }

        // 7. Collapsible Completed Section at the bottom
        if (completedTasks.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121316), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF25272D), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCompletedSection = !showCompletedSection },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Completed (${completedTasks.size})",
                        color = Color(0xFF9AA0A6),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = if (showCompletedSection) {
                            Icons.Default.ExpandLess
                        } else {
                            Icons.Default.ExpandMore
                        },
                        contentDescription = "Toggle Completed Tasks",
                        tint = Color(0xFF9AA0A6)
                    )
                }

                if (showCompletedSection) {
                    completedTasks.forEach { task ->
                        PhoneCompletedTaskRow(
                            task = task,
                            onUncheck = {
                                onToggleTaskCompleted(task.id, false)
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showDatePickerDialog) {
        val todayStartUtcMillis = remember(today) {
            today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val initialSelectedUtcMillis = remember(selectedDeadlineDate, todayStartUtcMillis) {
            selectedDeadlineDate
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli()
                ?: todayStartUtcMillis
        }

        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialSelectedUtcMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    return utcTimeMillis >= todayStartUtcMillis
                }

                override fun isSelectableYear(year: Int): Boolean {
                    return year >= today.year
                }
            }
        )

        DatePickerDialog(
            onDismissRequest = { showDatePickerDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis
                        if (millis != null) {
                            val chosenDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            if (!chosenDate.isBefore(today)) {
                                selectedDeadlineDate = chosenDate
                            }
                        }
                        showDatePickerDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerDialog = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun PhoneTaskRowCard(
    task: TaskItem,
    today: LocalDate,
    onCheckedChange: (Boolean) -> Unit
) {
    val subject = remember(task.subjectId) { SubjectCatalog.findById(task.subjectId) }
    val isPerfSession = remember(task.subjectId) { SubjectCatalog.isPerformanceSession(task.subjectId) }
    val deadlineInfo = remember(task.deadline, today) {
        TaskScheduleLogic.evaluateDeadline(task.deadline, today)
    }

    val cardBgColor = when {
        isPerfSession -> Color(0xFF231136)
        deadlineInfo.isUrgentRed -> Color(0xFF2B1215)
        else -> Color(0xFF202124)
    }
    val cardBorderColor = when {
        isPerfSession -> Color(0xFFAB47BC).copy(alpha = 0.85f)
        deadlineInfo.isUrgentRed -> Color(0xFFFF5252).copy(alpha = 0.78f)
        else -> Color(0xFF2E3036)
    }
    val iconBoxBgColor = when {
        isPerfSession -> Color(0xFF3B1C5A)
        deadlineInfo.isUrgentRed -> Color(0xFF4A181D)
        else -> Color(0xFF2C2E33)
    }
    val iconTintColor = when {
        isPerfSession -> Color(0xFFE1BEE7)
        deadlineInfo.isUrgentRed -> Color(0xFFFF8A80)
        else -> Color(0xFF8AB4F8)
    }
    val deadlineLabelColor = when {
        isPerfSession -> Color(0xFFCE93D8)
        deadlineInfo.isUrgentRed -> Color(0xFFFF6B6B)
        else -> Color(0xFF9AA0A6)
    }
    val subtitleColor = when {
        isPerfSession -> Color(0xFFCE93D8)
        deadlineInfo.isUrgentRed -> Color(0xFFFFB4AB)
        else -> Color(0xFF9AA0A6)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBgColor, RoundedCornerShape(18.dp))
            .border(1.dp, cardBorderColor, RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = task.completed,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF34D399),
                uncheckedColor = when {
                    isPerfSession -> Color(0xFFCE93D8)
                    deadlineInfo.isUrgentRed -> Color(0xFFFF8A80)
                    else -> Color(0xFF9AA0A6)
                }
            )
        )

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBoxBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = subject.icon,
                contentDescription = subject.name,
                tint = iconTintColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = task.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subject.name,
                color = subtitleColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = deadlineInfo.label,
            color = deadlineLabelColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 6.dp)
        )
    }
}

@Composable
private fun PhoneCompletedTaskRow(
    task: TaskItem,
    onUncheck: () -> Unit
) {
    val subject = remember(task.subjectId) { SubjectCatalog.findById(task.subjectId) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF18191D), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = true,
            onCheckedChange = { isChecked ->
                if (!isChecked) onUncheck()
            },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF4B5563),
                checkmarkColor = Color.White
            )
        )

        Icon(
            imageVector = subject.icon,
            contentDescription = subject.name,
            tint = Color(0xFF6B7280),
            modifier = Modifier.size(18.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                color = Color(0xFF9CA3AF),
                fontSize = 14.sp,
                textDecoration = TextDecoration.LineThrough,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${subject.name} • ${task.deadline}",
                color = Color(0xFF6B7280),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Section 4.1: Phone Settings Screen
 * Preserves all existing phone screen features (Notification Relay Service status,
 * Firebase Database URL/Node Path, Save/Test/Clear Tablet buttons, App Filters with
 * the new Calendar toggle, Recent Relayed Payloads) and adds a Back arrow to return
 * to Home plus the new Clock Theme selector at the bottom (Module G).
 */
@Composable
private fun PhoneSettingsScreen(
    hasNotificationPermission: Boolean,
    hasSmsPermission: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    otpRedactionDetected: Boolean,
    syncPreferences: PhoneSyncPreferences,
    statusText: String,
    recentItems: List<ForwardedAlertItem>,
    selectedThemeId: String,
    pcMonitorEnabled: Boolean,
    pcFullScreenEnabled: Boolean,
    pcStats: PcStatsPayload?,
    batteryConfig: BatteryAutomationConfig,
    batteryStatus: BatteryAutomationStatus,
    serverTimeOffsetMs: Long,
    nightScheduleConfig: NightScheduleConfig,
    pixelShiftEnabled: Boolean,
    otpAlertsEnabled: Boolean,
    proximityConfig: ProximityConfig,
    tabletScreenStatus: TabletScreenStatus,
    musicModeEnabled: Boolean,
    phoneDnd: PhoneDndPayload,
    alexaEnabled: Boolean,
    alexaStatus: AlexaStatusPayload,
    onSelectClockTheme: (String) -> Unit,
    onTogglePcMonitor: (Boolean) -> Unit,
    onTogglePcFullScreen: (Boolean) -> Unit,
    onSaveBatteryConfig: (
        enabled: Boolean,
        cutAt: Int,
        resumeAt: Int,
        offUrl: String,
        onUrl: String,
        method: String
    ) -> Unit,
    onTestBatteryWebhook: (
        label: String,
        url: String,
        method: String,
        onResult: (Boolean, String) -> Unit
    ) -> Unit,
    onRequestSmsPermission: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onSaveNightSchedule: (enabled: Boolean, start: String, end: String, brightnessPct: Int) -> Unit,
    onTogglePixelShift: (Boolean) -> Unit,
    onToggleOtpAlerts: (Boolean) -> Unit,
    onSendTestOtp: () -> Unit,
    onSaveProximityConfig: (enabled: Boolean, offDelayMin: Int) -> Unit,
    onToggleMusicMode: (Boolean) -> Unit,
    onToggleAlexaEnabled: (Boolean) -> Unit,
    onBackToHome: () -> Unit,
    onFirebaseConfigSaved: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onSendTestNotification: () -> Unit,
    onTriggerTimetableOnTablet: () -> Unit,
    onClearRemoteNotifications: () -> Unit
) {
    var dbUrl by remember { mutableStateOf(syncPreferences.getDatabaseUrl()) }
    var nodePath by remember { mutableStateOf(syncPreferences.getNodePath()) }

    val filterToggleStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            for (spec in syncPreferences.appFilterSpecs) {
                put(spec.prefKey, syncPreferences.isFilterEnabled(spec.prefKey, spec.defaultEnabled))
            }
        }
    }
    var allowAllApps by remember { mutableStateOf(syncPreferences.isAllowAllAppsEnabled()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Top Header with Back Arrow returning to Home
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(
                onClick = onBackToHome,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF1A1B1E), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF2E3036), RoundedCornerShape(12.dp))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to New Task",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column {
                Text(
                    text = "Smart Desk Phone Companion",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = statusText,
                    color = Color(0xFF80CBC4),
                    fontSize = 13.sp
                )
            }
        }

        // Card 1: Notification Listener Service Permission
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121212), RoundedCornerShape(16.dp))
                .border(
                    1.5.dp,
                    if (hasNotificationPermission) Color(0xFF00E676) else Color(0xFFFFB300),
                    RoundedCornerShape(16.dp)
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (hasNotificationPermission) {
                    "1. Notification Relay Service: ACTIVE"
                } else {
                    "1. Notification Relay Service: PERMISSION NEEDED"
                },
                color = if (hasNotificationPermission) Color(0xFF00E676) else Color(0xFFFFCA28),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Captures incoming status bar notifications and pushes JSON payloads to your Lenovo Yoga Tab 11 over your phone hotspot / Wi-Fi.",
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp
            )
            Button(
                onClick = onOpenPermissionSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (hasNotificationPermission) Color(0xFF263238) else Color(0xFFFFB300),
                    contentColor = if (hasNotificationPermission) Color.White else Color.Black
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (hasNotificationPermission) {
                        "OPEN NOTIFICATION ACCESS SETTINGS"
                    } else {
                        "GRANT NOTIFICATION ACCESS"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Card 2: Firebase Realtime Database Connection
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121212), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "2. Firebase Realtime Database Target",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = dbUrl,
                onValueChange = { dbUrl = it },
                label = { Text("Firebase Database URL (leave blank for google-services.json)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF64B5F6),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            OutlinedTextField(
                value = nodePath,
                onValueChange = { nodePath = it },
                label = { Text("Database Node Path (default: notifications)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF64B5F6),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            Button(
                onClick = {
                    syncPreferences.setFirebaseConfig(dbUrl, nodePath)
                    onFirebaseConfigSaved()
                    PhoneSyncPreferences.updateStatus("Saved Firebase target config")
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SAVE FIREBASE CONFIGURATION", fontWeight = FontWeight.Bold)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onSendTestNotification,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("SEND TEST ALERT", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onClearRemoteNotifications,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("CLEAR TABLET", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = onTriggerTimetableOnTablet,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3949AB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SHOW TIMETABLE (20S ON TABLET)", fontWeight = FontWeight.Bold)
            }
        }

        // Card 3: Data-Driven App Filters (Module B + Calendar Toggle)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121212), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "3. App Filters (Module B)",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Spotify & media player notifications on your phone are automatically blocked from syncing.",
                color = Color(0xFF9E9E9E),
                fontSize = 13.sp
            )

            syncPreferences.appFilterSpecs.forEach { spec ->
                val isChecked = filterToggleStates[spec.prefKey] ?: spec.defaultEnabled
                FilterToggleRow(
                    title = spec.label,
                    checked = isChecked,
                    onCheckedChange = { enabled ->
                        filterToggleStates[spec.prefKey] = enabled
                        syncPreferences.setFilterEnabled(spec.prefKey, enabled)
                    }
                )
            }

            FilterToggleRow(
                title = "Allow All Other Apps",
                checked = allowAllApps,
                onCheckedChange = {
                    allowAllApps = it
                    syncPreferences.setAllowAllAppsEnabled(it)
                }
            )
        }

        // Card 4: Recent Forwarded Payloads Log
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121212), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Recent Relayed Payloads (${recentItems.size}/5)",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            if (recentItems.isEmpty()) {
                Text(
                    text = "No notifications forwarded yet in this session.",
                    color = Color(0xFF757575),
                    fontSize = 14.sp
                )
            } else {
                recentItems.forEach { item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1C1C1C), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = item.packageName,
                                    color = Color(0xFF80CBC4),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = item.timestamp,
                                    color = Color(0xFF9E9E9E),
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = item.title,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = item.message,
                                color = Color(0xFFCCCCCC),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // Card 5: Module G — Clock Theme Picker [NEW]
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121212), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "4. Clock Theme (Module G)",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tap any theme below to update the Float clock colours on your tablet live:",
                color = Color(0xFF9E9E9E),
                fontSize = 13.sp
            )

            ClockThemeCatalog.allThemes.forEach { theme ->
                ClockThemeSwatchRow(
                    theme = theme,
                    isSelected = theme.id == selectedThemeId,
                    onClick = { onSelectClockTheme(theme.id) }
                )
            }
        }

        // Card 6: V6 Section 1.6 — PC Resource Monitor [NEW]
        PcMonitorSettingsCard(
            pcMonitorEnabled = pcMonitorEnabled,
            pcFullScreenEnabled = pcFullScreenEnabled,
            pcStats = pcStats,
            serverTimeOffsetMs = serverTimeOffsetMs,
            onTogglePcMonitor = onTogglePcMonitor,
            onTogglePcFullScreen = onTogglePcFullScreen
        )

        // Card 7: V6 Section 2.4 — Smart Battery Protection [NEW]
        BatteryProtectionSettingsCard(
            batteryConfig = batteryConfig,
            batteryStatus = batteryStatus,
            serverTimeOffsetMs = serverTimeOffsetMs,
            onSaveBatteryConfig = onSaveBatteryConfig,
            onTestBatteryWebhook = onTestBatteryWebhook
        )

        // Cards 8–12: V7 Section 8 & V8 Section 7 — Night Schedule, Pixel Shift, OTP Alerts, Proximity, Music Mode & Alexa
        PhoneV7SettingsSection(
            syncPreferences = syncPreferences,
            hasSmsPermission = hasSmsPermission,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
            otpRedactionDetected = otpRedactionDetected,
            nightScheduleConfig = nightScheduleConfig,
            pixelShiftEnabled = pixelShiftEnabled,
            otpAlertsEnabled = otpAlertsEnabled,
            proximityConfig = proximityConfig,
            tabletScreenStatus = tabletScreenStatus,
            musicModeEnabled = musicModeEnabled,
            phoneDnd = phoneDnd,
            alexaEnabled = alexaEnabled,
            alexaStatus = alexaStatus,
            serverTimeOffsetMs = serverTimeOffsetMs,
            onRequestSmsPermission = onRequestSmsPermission,
            onRequestIgnoreBatteryOptimizations = onRequestIgnoreBatteryOptimizations,
            onSaveNightSchedule = onSaveNightSchedule,
            onTogglePixelShift = onTogglePixelShift,
            onToggleOtpAlerts = onToggleOtpAlerts,
            onSendTestOtp = onSendTestOtp,
            onSaveProximityConfig = onSaveProximityConfig,
            onToggleMusicMode = onToggleMusicMode,
            onToggleAlexaEnabled = onToggleAlexaEnabled
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PcMonitorSettingsCard(
    pcMonitorEnabled: Boolean,
    pcFullScreenEnabled: Boolean,
    pcStats: PcStatsPayload?,
    serverTimeOffsetMs: Long,
    onTogglePcMonitor: (Boolean) -> Unit,
    onTogglePcFullScreen: (Boolean) -> Unit
) {
    var tickNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000L)
            tickNowMs = System.currentTimeMillis()
        }
    }

    val estimatedServerNow = tickNowMs + serverTimeOffsetMs
    val isOffline = pcStats == null || pcStats.isOffline(estimatedServerNow)
    val statusLine = if (isOffline || pcStats == null) {
        "Laptop offline"
    } else {
        val secAgo = pcStats.secondsAgo(estimatedServerNow)
        val tempLabel = when (pcStats.tempSource?.lowercase(Locale.US)) {
            "gpu" -> "Temp: GPU (${pcStats.temp ?: "—"}°C)"
            "cpu" -> "Temp: CPU (${pcStats.temp ?: "—"}°C)"
            else -> "Temp: none"
        }
        val hostPrefix = if (pcStats.host.isNotBlank()) "${pcStats.host} • " else ""
        "${hostPrefix}Laptop last seen ${secAgo} s ago • $tempLabel"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "5. PC Resource Monitor (V6)",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        FilterToggleRow(
            title = "Show PC stats pill on tablet",
            checked = pcMonitorEnabled,
            onCheckedChange = onTogglePcMonitor
        )

        FilterToggleRow(
            title = "Full-screen PC Monitor on tablet",
            checked = pcFullScreenEnabled,
            onCheckedChange = onTogglePcFullScreen
        )

        Text(
            text = statusLine,
            color = if (isOffline) Color(0xFF9AA0A6) else Color(0xFF34D399),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BatteryProtectionSettingsCard(
    batteryConfig: BatteryAutomationConfig,
    batteryStatus: BatteryAutomationStatus,
    serverTimeOffsetMs: Long,
    onSaveBatteryConfig: (
        enabled: Boolean,
        cutAt: Int,
        resumeAt: Int,
        offUrl: String,
        onUrl: String,
        method: String
    ) -> Unit,
    onTestBatteryWebhook: (
        label: String,
        url: String,
        method: String,
        onResult: (Boolean, String) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current

    var enabled by remember(batteryConfig.enabled) { mutableStateOf(batteryConfig.enabled) }
    var cutAtText by remember(batteryConfig.cutAt) { mutableStateOf(batteryConfig.cutAt.toString()) }
    var resumeAtText by remember(batteryConfig.resumeAt) { mutableStateOf(batteryConfig.resumeAt.toString()) }
    var offUrl by remember(batteryConfig.offUrl) { mutableStateOf(batteryConfig.offUrl) }
    var onUrl by remember(batteryConfig.onUrl) { mutableStateOf(batteryConfig.onUrl) }
    var method by remember(batteryConfig.method) { mutableStateOf(batteryConfig.normalizedMethod) }

    var feedbackText by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }

    var tickNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            tickNowMs = System.currentTimeMillis()
        }
    }

    val estimatedServerNow = tickNowMs + serverTimeOffsetMs
    val minAgoText = remember(batteryStatus.lastCommandAt, estimatedServerNow) {
        if (batteryStatus.lastCommandAt <= 0L) {
            ""
        } else {
            val mins = ((estimatedServerNow - batteryStatus.lastCommandAt).coerceAtLeast(0L)) / 60_000L
            if (mins == 0L) "just now" else "$mins min ago"
        }
    }

    val liveStatusText = when {
        batteryStatus.isError -> {
            "Error: ${batteryStatus.error ?: "webhook failed"}"
        }
        batteryStatus.isHolding -> {
            val suffix = if (minAgoText.isNotEmpty()) ", $minAgoText" else ""
            "Holding (power cut at ${batteryConfig.cutAt}%$suffix)"
        }
        batteryStatus.state.equals(BatteryAutomationStatus.STATE_CHARGING, ignoreCase = true) -> {
            val lvl = if (batteryStatus.batteryLevel in 0..100) " (${batteryStatus.batteryLevel}%)" else ""
            "Charging$lvl"
        }
        else -> {
            val lvl = if (batteryStatus.batteryLevel in 0..100) " (${batteryStatus.batteryLevel}%)" else ""
            "Idle$lvl"
        }
    }

    val liveStatusColor = when {
        batteryStatus.isError -> Color(0xFFFFB300)
        batteryStatus.isHolding -> Color(0xFF63B8FF)
        batteryStatus.state.equals(BatteryAutomationStatus.STATE_CHARGING, ignoreCase = true) -> Color(0xFF34D399)
        else -> Color(0xFF9AA0A6)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "6. Smart Battery Protection (V6)",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Keeps your tablet battery between 25% and 80%. Leave webhook URLs blank to alert your phone at 80% / 25% (or combine with Lenovo's built-in Battery Protection Mode in Tablet Settings).",
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp
        )

        FilterToggleRow(
            title = "Battery protection",
            checked = enabled,
            onCheckedChange = { enabled = it }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = cutAtText,
                onValueChange = { cutAtText = it.filter { ch -> ch.isDigit() }.take(2) },
                label = { Text("Cut power at % (50–95)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF64B5F6),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            OutlinedTextField(
                value = resumeAtText,
                onValueChange = { resumeAtText = it.filter { ch -> ch.isDigit() }.take(2) },
                label = { Text("Restore at % (10–50)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF64B5F6),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )
        }

        OutlinedTextField(
            value = offUrl,
            onValueChange = { offUrl = it },
            label = { Text("Power-off webhook URL (optional)") },
            placeholder = { Text("https://... (leave blank for phone alert only)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF64B5F6),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        OutlinedTextField(
            value = onUrl,
            onValueChange = { onUrl = it },
            label = { Text("Power-on webhook URL (optional)") },
            placeholder = { Text("https://... (leave blank for phone alert only)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF64B5F6),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        // GET / POST HTTP Method Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Webhook Method:",
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            listOf("GET", "POST").forEach { option ->
                val isSelected = method.equals(option, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Color(0xFF1E88E5) else Color(0xFF1E2025))
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFF63B8FF) else Color(0xFF3A3D45),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { method = option }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Test OFF and Test ON buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    feedbackText = "Testing OFF…"
                    feedbackIsError = false
                    onTestBatteryWebhook("OFF", offUrl, method) { ok, msg ->
                        feedbackText = msg
                        feedbackIsError = !ok
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("TEST OFF", color = Color(0xFF80CBC4), fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = {
                    feedbackText = "Testing ON…"
                    feedbackIsError = false
                    onTestBatteryWebhook("ON", onUrl, method) { ok, msg ->
                        feedbackText = msg
                        feedbackIsError = !ok
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("TEST ON", color = Color(0xFF80CBC4), fontWeight = FontWeight.Bold)
            }
        }

        // Save button
        Button(
            onClick = {
                val parsedCut = cutAtText.toIntOrNull() ?: AppConfigConstants.BATTERY_DEFAULT_CUT_AT
                val parsedResume = resumeAtText.toIntOrNull() ?: AppConfigConstants.BATTERY_DEFAULT_RESUME_AT
                val (validCut, validResume) = BatteryProtectionStateMachine.sanitizeThresholds(
                    parsedCut,
                    parsedResume
                )
                cutAtText = validCut.toString()
                resumeAtText = validResume.toString()

                onSaveBatteryConfig(
                    enabled,
                    validCut,
                    validResume,
                    offUrl,
                    onUrl,
                    method
                )
                feedbackIsError = false
                feedbackText = "Saved Battery Protection (${validResume}% – ${validCut}%)"
                Toast.makeText(context, "Saved Battery Protection", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("SAVE BATTERY PROTECTION", fontWeight = FontWeight.Bold)
        }

        if (feedbackText != null) {
            Text(
                text = feedbackText.orEmpty(),
                color = if (feedbackIsError) Color(0xFFFFB300) else Color(0xFF34D399),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Live status line from status/batteryAutomation
        Text(
            text = "Status: $liveStatusText",
            color = liveStatusColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ClockThemeSwatchRow(
    theme: ClockTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Color(0xFF1F2937) else Color(0xFF1A1B1E))
            .border(
                width = if (isSelected) 1.8.dp else 1.dp,
                color = if (isSelected) Color(0xFF63B8FF) else Color(0xFF2E3036),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            // 4 Overlapping Digit Swatch Circles + Colon Dot Preview
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((-6).dp)
            ) {
                theme.digitColors.forEachIndexed { index, color ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, Color.Black.copy(alpha = 0.45f), CircleShape)
                    )
                    if (index == 1) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(theme.colonDotColor)
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = theme.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = theme.mood,
                    color = Color(0xFF9AA0A6),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected Theme",
                tint = Color(0xFF63B8FF),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun FilterToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color(0xFFE0E0E0),
            fontSize = 15.sp
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color(0xFF00E676)
            )
        )
    }
}
