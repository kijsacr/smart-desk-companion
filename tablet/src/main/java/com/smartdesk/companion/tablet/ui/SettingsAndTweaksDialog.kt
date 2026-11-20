package com.smartdesk.companion.tablet.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdesk.companion.shared.ClockTheme
import com.smartdesk.companion.tablet.ui.touch.ghostTouchSafeClickable

@Composable
fun SettingsAndTweaksDialog(
    initialDatabaseUrl: String,
    initialNodePath: String,
    statusMessage: String,
    hasNotificationListenerPermission: Boolean,
    activeClockTheme: ClockTheme,
    onSaveFirebaseConfig: (url: String, nodePath: String) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onPushTestNotification: () -> Unit,
    onDismissAllNotifications: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var dbUrl by remember(initialDatabaseUrl) { mutableStateOf(initialDatabaseUrl) }
    var nodePath by remember(initialNodePath) { mutableStateOf(initialNodePath) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .background(Color(0xFF0E0E10), RoundedCornerShape(22.dp))
                .border(1.5.dp, Color(0xFF2C2C30), RoundedCornerShape(22.dp))
                .padding(26.dp)
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Smart Desk Setup & Preferences",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$statusMessage • Active Clock Theme: ${activeClockTheme.name}",
                            color = Color(0xFF63B8FF),
                            fontSize = 14.sp
                        )
                    }

                    LargeDialogActionButton(
                        label = "DONE",
                        backgroundColor = Color(0xFF242428),
                        borderColor = Color(0xFF48484E),
                        onClick = onDismiss
                    )
                }

                // Section 1: Spotify Local MediaSession Permission
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161619), RoundedCornerShape(16.dp))
                        .border(
                            1.dp,
                            if (hasNotificationListenerPermission) Color(0xFF28F76B) else Color(0xFFFFB300),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (hasNotificationListenerPermission) {
                            "1. Spotify Session Bridge: CONNECTED"
                        } else {
                            "1. Spotify Session Bridge: PERMISSION NEEDED"
                        },
                        color = if (hasNotificationListenerPermission) Color(0xFF28F76B) else Color(0xFFFFCA28),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Links the compact bottom Spotify bar directly to your tablet's Spotify app notification/session.",
                        color = Color(0xFFCCCCCC),
                        fontSize = 14.sp
                    )
                    if (!hasNotificationListenerPermission) {
                        LargeDialogActionButton(
                            label = "GRANT NOTIFICATION ACCESS ON TABLET",
                            backgroundColor = Color(0xFF1DB954),
                            textColor = Color.Black,
                            borderColor = Color(0xFF1DB954),
                            onClick = onOpenNotificationAccess
                        )
                    }
                }

                // Section 2: Firebase Realtime Database Configuration
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161619), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "2. Firebase Realtime Database Sync",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = dbUrl,
                        onValueChange = { dbUrl = it },
                        label = { Text("Firebase Database URL (e.g. https://your-project-default-rtdb.firebaseio.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF63B8FF),
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedLabelColor = Color(0xFF63B8FF),
                            unfocusedLabelColor = Color(0xFF999999)
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
                            focusedBorderColor = Color(0xFF63B8FF),
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedLabelColor = Color(0xFF63B8FF),
                            unfocusedLabelColor = Color(0xFF999999)
                        )
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LargeDialogActionButton(
                            label = "SAVE & CONNECT",
                            backgroundColor = Color(0xFF005BC5),
                            borderColor = Color(0xFF63B8FF),
                            onClick = {
                                onSaveFirebaseConfig(dbUrl, nodePath)
                                onDismiss()
                            }
                        )

                        LargeDialogActionButton(
                            label = "ADD DEMO ALERT",
                            backgroundColor = Color(0xFF222831),
                            borderColor = Color(0xFF63B8FF),
                            onClick = onPushTestNotification
                        )

                        LargeDialogActionButton(
                            label = "CLEAR ALERTS",
                            backgroundColor = Color(0xFF2C1517),
                            borderColor = Color(0xFFFF5252),
                            onClick = onDismissAllNotifications
                        )
                    }
                }

                // Section 3: Lenovo Yoga Tab 11 System Tweaks
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161619), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "3. Lenovo Yoga Tab 11 System Tweaks",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LargeDialogActionButton(
                            label = "LENOVO BATTERY PROTECTION",
                            backgroundColor = Color(0xFF1B3326),
                            borderColor = Color(0xFF34D399),
                            onClick = { openSystemSettings(context, Intent.ACTION_POWER_USAGE_SUMMARY) }
                        )
                        LargeDialogActionButton(
                            label = "DEVELOPER OPTIONS",
                            backgroundColor = Color(0xFF202024),
                            borderColor = Color(0xFF55555E),
                            onClick = { openSystemSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
                        )
                        LargeDialogActionButton(
                            label = "SYSTEM SETTINGS",
                            backgroundColor = Color(0xFF202024),
                            borderColor = Color(0xFF55555E),
                            onClick = { openSystemSettings(context, Settings.ACTION_SETTINGS) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LargeDialogActionButton(
    label: String,
    backgroundColor: Color,
    borderColor: Color,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(58.dp)
            .background(backgroundColor, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .ghostTouchSafeClickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun openSystemSettings(context: Context, action: String) {
    val intent = Intent(action).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
