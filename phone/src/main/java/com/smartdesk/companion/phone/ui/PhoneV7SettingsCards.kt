package com.smartdesk.companion.phone.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.phone.config.PhoneSyncPreferences
import com.smartdesk.companion.shared.AlexaStatusPayload
import com.smartdesk.companion.shared.AppConfigConstants
import com.smartdesk.companion.shared.NightScheduleConfig
import com.smartdesk.companion.shared.PhoneDndPayload
import com.smartdesk.companion.shared.ProximityConfig
import com.smartdesk.companion.shared.TabletScreenStatus
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * V7 Section 8 & Section 10:
 * Consolidated V7 Phone Settings Cards appended below the existing sections:
 * - Night Schedule (`config/nightSchedule`)
 * - Pixel Shift (`config/pixelShift`)
 * - OTP Alerts (`config/otpAlerts`, `SMS` / `WhatsApp` / `Gmail`, `RECEIVE_SMS`, redaction warning, `Send test OTP`)
 * - Proximity Screen-Off (`config/proximity`, `status/tabletScreen`)
 * - Music & Clock Mode (`config/musicMode`, `phone/dnd`)
 * - Battery Optimisation Exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
 */
@Composable
fun PhoneV7SettingsSection(
    syncPreferences: PhoneSyncPreferences,
    hasSmsPermission: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    otpRedactionDetected: Boolean,
    nightScheduleConfig: NightScheduleConfig,
    pixelShiftEnabled: Boolean,
    otpAlertsEnabled: Boolean,
    proximityConfig: ProximityConfig,
    tabletScreenStatus: TabletScreenStatus,
    musicModeEnabled: Boolean,
    phoneDnd: PhoneDndPayload,
    alexaEnabled: Boolean,
    alexaStatus: AlexaStatusPayload,
    serverTimeOffsetMs: Long,
    onRequestSmsPermission: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onSaveNightSchedule: (enabled: Boolean, start: String, end: String, brightnessPct: Int) -> Unit,
    onTogglePixelShift: (Boolean) -> Unit,
    onToggleOtpAlerts: (Boolean) -> Unit,
    onSendTestOtp: () -> Unit,
    onSaveProximityConfig: (enabled: Boolean, offDelayMin: Int) -> Unit,
    onToggleMusicMode: (Boolean) -> Unit,
    onToggleAlexaEnabled: (Boolean) -> Unit
) {
    NightScheduleSettingsCard(
        config = nightScheduleConfig,
        onSave = onSaveNightSchedule
    )

    PixelShiftSettingsCard(
        enabled = pixelShiftEnabled,
        onToggle = onTogglePixelShift
    )

    OtpAlertsSettingsCard(
        syncPreferences = syncPreferences,
        otpAlertsEnabled = otpAlertsEnabled,
        hasSmsPermission = hasSmsPermission,
        otpRedactionDetected = otpRedactionDetected,
        onToggleOtpAlerts = onToggleOtpAlerts,
        onRequestSmsPermission = onRequestSmsPermission,
        onSendTestOtp = onSendTestOtp
    )

    ProximityScreenOffSettingsCard(
        config = proximityConfig,
        tabletScreenStatus = tabletScreenStatus,
        isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
        onRequestIgnoreBatteryOptimizations = onRequestIgnoreBatteryOptimizations,
        onSave = onSaveProximityConfig
    )

    MusicAndClockModeSettingsCard(
        enabled = musicModeEnabled,
        phoneDnd = phoneDnd,
        onToggle = onToggleMusicMode
    )

    AlexaVoiceControlSettingsCard(
        enabled = alexaEnabled,
        alexaStatus = alexaStatus,
        serverTimeOffsetMs = serverTimeOffsetMs,
        onToggle = onToggleAlexaEnabled
    )
}

@Composable
private fun NightScheduleSettingsCard(
    config: NightScheduleConfig,
    onSave: (enabled: Boolean, start: String, end: String, brightnessPct: Int) -> Unit
) {
    val context = LocalContext.current
    var enabled by remember(config.enabled) { mutableStateOf(config.enabled) }
    var startTime by remember(config.start) { mutableStateOf(config.start) }
    var endTime by remember(config.end) { mutableStateOf(config.end) }
    var brightnessPct by remember(config.brightnessPct) {
        mutableIntStateOf(config.clampedBrightnessPct)
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
            text = "7. Night Schedule (V7)",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Automatically dims the tablet window brightness and switches to the Night Dim theme during sleeping hours.",
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp
        )

        V7SwitchRow(
            title = "Night schedule",
            checked = enabled,
            onCheckedChange = {
                enabled = it
                onSave(it, startTime, endTime, brightnessPct)
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TimePickerButtonBox(
                label = "Start time",
                timeValue = startTime,
                modifier = Modifier.weight(1f),
                onClick = {
                    show24HourTimePicker(context, startTime) { picked ->
                        startTime = picked
                        onSave(enabled, picked, endTime, brightnessPct)
                    }
                }
            )

            TimePickerButtonBox(
                label = "End time",
                timeValue = endTime,
                modifier = Modifier.weight(1f),
                onClick = {
                    show24HourTimePicker(context, endTime) { picked ->
                        endTime = picked
                        onSave(enabled, startTime, picked, brightnessPct)
                    }
                }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Night brightness",
                    color = Color(0xFFE0E0E0),
                    fontSize = 14.sp
                )
                Text(
                    text = "${brightnessPct}%",
                    color = Color(0xFF63B8FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = brightnessPct.toFloat(),
                onValueChange = { brightnessPct = it.roundToInt().coerceIn(1, 40) },
                onValueChangeFinished = {
                    onSave(enabled, startTime, endTime, brightnessPct)
                },
                valueRange = 1f..40f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF63B8FF),
                    activeTrackColor = Color(0xFF1E88E5),
                    inactiveTrackColor = Color(0xFF2E3036)
                )
            )
        }
    }
}

@Composable
private fun PixelShiftSettingsCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "8. Pixel Shift (V7)",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Subtly shifts the dashboard container by ±4 dp every 3 minutes through a 9-point ring to prevent LCD image retention.",
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp
        )

        V7SwitchRow(
            title = "Pixel shift",
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}

@Composable
private fun OtpAlertsSettingsCard(
    syncPreferences: PhoneSyncPreferences,
    otpAlertsEnabled: Boolean,
    hasSmsPermission: Boolean,
    otpRedactionDetected: Boolean,
    onToggleOtpAlerts: (Boolean) -> Unit,
    onRequestSmsPermission: () -> Unit,
    onSendTestOtp: () -> Unit
) {
    val context = LocalContext.current
    var smsChecked by remember { mutableStateOf(syncPreferences.isOtpSmsEnabled()) }
    var whatsappChecked by remember { mutableStateOf(syncPreferences.isOtpWhatsappEnabled()) }
    var gmailChecked by remember { mutableStateOf(syncPreferences.isOtpGmailEnabled()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "9. OTP Full-Screen Alerts (V7)",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Takes over the tablet screen for 20 seconds with the sender name and OTP code in large digits, then keeps the OTP in the notification card list.",
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp
        )

        V7SwitchRow(
            title = "Full-screen OTP alerts",
            checked = otpAlertsEnabled,
            onCheckedChange = onToggleOtpAlerts
        )

        // Source checkboxes: SMS, WhatsApp, Gmail
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            V7CheckboxItem(
                label = "SMS",
                checked = smsChecked,
                onCheckedChange = {
                    smsChecked = it
                    syncPreferences.setOtpSmsEnabled(it)
                }
            )
            V7CheckboxItem(
                label = "WhatsApp",
                checked = whatsappChecked,
                onCheckedChange = {
                    whatsappChecked = it
                    syncPreferences.setOtpWhatsappEnabled(it)
                }
            )
            V7CheckboxItem(
                label = "Gmail",
                checked = gmailChecked,
                onCheckedChange = {
                    gmailChecked = it
                    syncPreferences.setOtpGmailEnabled(it)
                }
            )
        }

        // Direct SMS receiver permission status (bypasses Android 15 sensitive notification redaction)
        if (!hasSmsPermission) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF291E09), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "SMS Permission Needed for Direct OTP Reading",
                        color = Color(0xFFFFCA28),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Grant RECEIVE_SMS so SMS OTPs are read directly even when Android 15 hides sensitive notification text.",
                        color = Color(0xFFE0E0E0),
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = onRequestSmsPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFB300),
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("GRANT SMS PERMISSION", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Text(
                text = "Direct SMS OTP receiver: ACTIVE (RECEIVE_SMS granted)",
                color = Color(0xFF34D399),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Section 4.1: Android 15+ sensitive notification redaction warning
        if (otpRedactionDetected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2B1215), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFFF5252), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Android 15 Sensitive Notification Redaction Detected",
                        color = Color(0xFFFF8A80),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Android hid OTP notification content ('Sensitive notification content hidden'). SMS OTPs still work via RECEIVE_SMS. For WhatsApp/Gmail OTPs, open Phone Settings → Notifications and disable 'Enhanced notifications' or allow sensitive notifications.",
                        color = Color(0xFFE0E0E0),
                        fontSize = 12.sp
                    )
                    OutlinedButton(
                        onClick = {
                            syncPreferences.markSensitiveRedactionDetected(false)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("DISMISS WARNING", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section 4.7: Send test OTP button (source = "Swiggy", code = "482913")
        Button(
            onClick = {
                onSendTestOtp()
                Toast.makeText(
                    context,
                    "Sent test OTP (Swiggy • 482 913) to tablet",
                    Toast.LENGTH_SHORT
                ).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("SEND TEST OTP (SWIGGY • 482 913)", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProximityScreenOffSettingsCard(
    config: ProximityConfig,
    tabletScreenStatus: TabletScreenStatus,
    isIgnoringBatteryOptimizations: Boolean,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onSave: (enabled: Boolean, offDelayMin: Int) -> Unit
) {
    var enabled by remember(config.enabled) { mutableStateOf(config.enabled) }
    var offDelayMin by remember(config.offDelayMin) {
        mutableIntStateOf(config.clampedOffDelayMin)
    }

    val statusColor = when (tabletScreenStatus.state.lowercase(Locale.US)) {
        TabletScreenStatus.STATE_ON -> Color(0xFF34D399)
        TabletScreenStatus.STATE_WAITING -> Color(0xFFFFB300)
        TabletScreenStatus.STATE_OFF -> Color(0xFF9AA0A6)
        else -> Color(0xFF34D399)
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
            text = "10. Proximity Screen-Off (V7)",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Turns the tablet screen off when your phone hotspot disconnects or heartbeat stops, and wakes it immediately when you return.",
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp
        )

        V7SwitchRow(
            title = "Turn tablet screen off when phone leaves",
            checked = enabled,
            onCheckedChange = {
                enabled = it
                onSave(it, offDelayMin)
            }
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Turn off after",
                    color = Color(0xFFE0E0E0),
                    fontSize = 14.sp
                )
                Text(
                    text = "$offDelayMin min",
                    color = Color(0xFF63B8FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = offDelayMin.toFloat(),
                onValueChange = {
                    offDelayMin = it.roundToInt().coerceIn(
                        AppConfigConstants.PROXIMITY_MIN_OFF_DELAY_MIN,
                        AppConfigConstants.PROXIMITY_MAX_OFF_DELAY_MIN
                    )
                },
                onValueChangeFinished = {
                    onSave(enabled, offDelayMin)
                },
                valueRange = AppConfigConstants.PROXIMITY_MIN_OFF_DELAY_MIN.toFloat()..
                    AppConfigConstants.PROXIMITY_MAX_OFF_DELAY_MIN.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF63B8FF),
                    activeTrackColor = Color(0xFF1E88E5),
                    inactiveTrackColor = Color(0xFF2E3036)
                )
            )
        }

        if (!isIgnoringBatteryOptimizations) {
            OutlinedButton(
                onClick = onRequestIgnoreBatteryOptimizations,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "EXEMPT PHONE APP FROM BATTERY OPTIMISATION",
                    color = Color(0xFFFFCA28),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Text(
            text = "Tablet: ${tabletScreenStatus.state}",
            color = statusColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MusicAndClockModeSettingsCard(
    enabled: Boolean,
    phoneDnd: PhoneDndPayload,
    onToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "11. Music & Clock Mode (V7)",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "When your Moto G67 enters Do Not Disturb (e.g. Flip for DND), the tablet crossfades into a 50/50 split with the Float clock on the left and a spinning vinyl music player on the right.",
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp
        )

        V7SwitchRow(
            title = "Music & Clock mode when Do Not Disturb is on",
            checked = enabled,
            onCheckedChange = onToggle
        )

        Text(
            text = if (phoneDnd.active) {
                "Phone DND Status: ACTIVE (Music & Clock Mode live on tablet)"
            } else {
                "Phone DND Status: Off"
            },
            color = if (phoneDnd.active) Color(0xFF34D399) else Color(0xFF9AA0A6),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * V8 Section 7: Alexa Voice Control Card (Phase 1: Timetable only).
 * - Switch: "Allow Alexa voice control" (writes `config/alexa/enabled`, default `true`).
 * - Live status line from `status/alexa` (`lastIntent`, `lastAt`, `ok`, `error`).
 * - Collapsible "What can I say?" list showing only the phrases implemented in Phase 1.
 */
@Composable
private fun AlexaVoiceControlSettingsCard(
    enabled: Boolean,
    alexaStatus: AlexaStatusPayload,
    serverTimeOffsetMs: Long,
    onToggle: (Boolean) -> Unit
) {
    var showPhrases by remember { mutableStateOf(false) }
    var tickNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            tickNowMs = System.currentTimeMillis()
        }
    }

    val estimatedServerNow = tickNowMs + serverTimeOffsetMs
    val statusLine = remember(alexaStatus, estimatedServerNow) {
        alexaStatus.formatStatusLine(estimatedServerNow)
    }
    val statusColor = when {
        alexaStatus.lastIntent.isBlank() && alexaStatus.lastAt <= 0L -> Color(0xFF9AA0A6)
        !alexaStatus.ok -> Color(0xFFFF6B6B)
        else -> Color(0xFF34D399)
    }

    val voicePhrases = remember {
        listOf(
            "Timetable: \"Alexa, ask smart desk to show my timetable\"",
            "Add Task: \"Alexa, ask smart desk to add a task\" (guided: task → subject → due date → yes)",
            "What's Due: \"Alexa, ask smart desk what's due\" or \"what's due tomorrow\"",
            "Clock Theme: \"Alexa, ask smart desk to set clock theme to Cyber Neon\"",
            "Phone Battery: \"Alexa, ask smart desk what's my phone battery\"",
            "PC Stats: \"Alexa, ask smart desk for PC stats\"",
            "Music Mode: \"Alexa, ask smart desk to turn music mode on / off / auto\"",
            "Clear Tablet: \"Alexa, ask smart desk to clear notifications\""
        )
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
            text = "12. Alexa Voice Control (V8)",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Control your desk tablet hands-free using the personal Smart Desk Alexa skill.",
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp
        )

        V7SwitchRow(
            title = "Allow Alexa voice control",
            checked = enabled,
            onCheckedChange = onToggle
        )

        Text(
            text = statusLine,
            color = statusColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )

        // Collapsible "What can I say?" list (Phase 1 & Phase 2 phrases)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1B1F))
                .border(1.dp, Color(0xFF2E3036), RoundedCornerShape(12.dp))
                .clickable { showPhrases = !showPhrases }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "What can I say?",
                    color = Color(0xFF63B8FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (showPhrases) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle voice phrases",
                    tint = Color(0xFF63B8FF)
                )
            }

            if (showPhrases) {
                voicePhrases.forEach { phrase ->
                    Text(
                        text = "• $phrase",
                        color = Color(0xFFE0E0E0),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TimePickerButtonBox(
    label: String,
    timeValue: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E2025))
            .border(1.dp, Color(0xFF3A3D45), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFF9AA0A6),
            fontSize = 12.sp
        )
        Text(
            text = timeValue,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun V7SwitchRow(
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
            fontSize = 15.sp,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
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

@Composable
private fun V7CheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF00E676),
                checkmarkColor = Color.Black,
                uncheckedColor = Color(0xFF9AA0A6)
            )
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun show24HourTimePicker(
    context: Context,
    currentHhMm: String,
    onTimePicked: (String) -> Unit
) {
    val parts = currentHhMm.split(":")
    val initialHour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 23
    val initialMinute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0

    TimePickerDialog(
        context,
        { _, hourOfDay, minute ->
            val formatted = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
            onTimePicked(formatted)
        },
        initialHour,
        initialMinute,
        true
    ).show()
}

fun isAppIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun requestIgnoreBatteryOptimizations(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}
