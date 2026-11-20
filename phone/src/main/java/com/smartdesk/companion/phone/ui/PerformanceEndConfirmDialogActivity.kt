package com.smartdesk.companion.phone.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdesk.companion.phone.MainActivity
import com.smartdesk.companion.phone.service.PhonePerformanceForegroundService

/**
 * V9 Section 2:
 * Small transparent dialog-style Activity that floats over whatever app is showing
 * (such as Wing q or the home screen) when the Performance Mode widget is tapped while active.
 *
 * If user cancels: dismisses cleanly and returns to the underlying app without disrupting it.
 * If user confirms: ends Performance Mode, resumes paused features, and brings MainActivity
 * to the dashboard tasks screen.
 *
 * Strictly NO HAPTIC FEEDBACK anywhere.
 */
class PerformanceEndConfirmDialogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PerformanceEndDialogContent(
                onCancel = {
                    finish()
                },
                onConfirmEnd = {
                    PhonePerformanceForegroundService.endPerformanceMode(applicationContext)
                    val homeIntent = Intent(applicationContext, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("extra_exit_performance_to_dashboard", true)
                    }
                    startActivity(homeIntent)
                    finish()
                }
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        finish()
    }
}

@Composable
private fun PerformanceEndDialogContent(
    onCancel: () -> Unit,
    onConfirmEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(Color(0xFF1E1E24), RoundedCornerShape(22.dp))
                .border(1.5.dp, Color(0xFFAB47BC).copy(alpha = 0.8f), RoundedCornerShape(22.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* consume inside click */ }
                )
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(38.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "End Performance Mode?",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "This will return both your phone and tablet to the normal dashboard and resume notification syncing, timetable, and PC stats.",
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF555566))
                ) {
                    Text(
                        text = "Cancel",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = onConfirmEnd,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "End Mode",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
