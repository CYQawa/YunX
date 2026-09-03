package com.yunx.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.util.TextCipher
import kotlinx.coroutines.delay

@Composable
fun SafetyNoticeDialog() {
    val context = LocalContext.current
    val lockSeconds = 2
    var visible by remember { mutableStateOf(!isAcknowledged(context)) }
    var seconds by remember { mutableIntStateOf(lockSeconds) }

    LaunchedEffect(Unit) {
        while (seconds > 0) {
            delay(1000)
            seconds--
        }
    }

    if (!visible) return

    AlertDialog(
        onDismissRequest = {
            if (seconds <= 0) {
                visible = false
                acknowledge(context)
            }
        },
        icon = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Security,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        title = {
            Column {
                Text(
                    text = TextCipher.dTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = TextCipher.dOfficial,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        text = {
            Column {
                Text(
                    text = TextCipher.dBody,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = TextCipher.dUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    visible = false
                    acknowledge(context)
                },
                enabled = seconds <= 0
            ) {
                Text(
                    if (seconds > 0) {
                        TextCipher.dCountdown.replace("%d", seconds.toString())
                    } else {
                        TextCipher.dBtn
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { copyUrl(context) }) {
                Text(TextCipher.dCopy)
            }
        }
    )
}

private fun isAcknowledged(context: Context): Boolean {
    return runCatching {
        context.getSharedPreferences("yunx_settings", Context.MODE_PRIVATE)
            .getBoolean(TextCipher.pNoticeFlag, false)
    }.getOrDefault(false)
}

private fun acknowledge(context: Context) {
    runCatching {
        context.getSharedPreferences("yunx_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(TextCipher.pNoticeFlag, true)
            .apply()
    }
}

private fun copyUrl(context: Context) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(TextCipher.dUrl, TextCipher.dUrl))
        Toast.makeText(context, TextCipher.dCopied, Toast.LENGTH_SHORT).show()
    }
}