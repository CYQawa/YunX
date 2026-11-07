/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.MainActivity
import com.yunx.app.R
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.items.CustomFabMenu
import com.yunx.app.ui.items.FabMenuItem
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.ui.theme.LocaleController
import com.yunx.app.util.AppStrings

/**
 * 崩溃界面（运行在独立进程 :crash）：
 * 展示崩溃报告，并通过 CustomFabMenu 提供「复制崩溃信息 / 重启应用 / 退出应用」。
 */
class CrashActivity : ComponentActivity() {

    /** 与应用内语言保持一致（独立进程同样要按用户选择解析资源） */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleController.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crashLog = intent.getStringExtra(CrashHandler.EXTRA_CRASH_LOG)
            ?: AppStrings.get(R.string.vm_crash_unknown_error)
        setContent {
            ComposeEmptyActivityTheme {
                CrashScreen(crashLog = crashLog)
            }
        }
    }
}

@Composable
private fun CrashScreen(crashLog: String) {
    val context = LocalContext.current
    var fabExpanded by remember { mutableStateOf(false) }

    val menuItems = remember(crashLog) {
        listOf(
            FabMenuItem(
                label = AppStrings.get(R.string.vm_crash_copy_info),
                icon = Icons.Outlined.ContentCopy,
                onClick = {
                    copyToClipboard(context, crashLog)
                    SnackbarController.show(AppStrings.get(R.string.vm_crash_info_copied))
                }
            ),
            FabMenuItem(
                label = AppStrings.get(R.string.vm_crash_restart_app),
                icon = Icons.Outlined.Refresh,
                onClick = { restartApp(context) }
            ),
            FabMenuItem(
                label = AppStrings.get(R.string.vm_crash_exit_app),
                icon = Icons.Outlined.ExitToApp,
                onClick = { exitApp(context) }
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            // 错误图标
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.vm_crash_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.vm_crash_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            // 崩溃报告（可滚动）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Text(
                    text = crashLog,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(88.dp)) // 给 FAB 留出空间
        }

        CustomFabMenu(
            expanded = fabExpanded,
            onCheckedChange = { fabExpanded = it },
            items = menuItems
        )

        // 全局 Snackbar（崩溃页为独立 Activity，需自带宿主）
        GlobalSnackbarHost()
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("crash_log", text))
}

private fun restartApp(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    context.startActivity(intent)
    if (context is Activity) context.finish()
    Process.killProcess(Process.myPid())
}

private fun exitApp(context: Context) {
    if (context is Activity) context.finishAffinity()
    Process.killProcess(Process.myPid())
}