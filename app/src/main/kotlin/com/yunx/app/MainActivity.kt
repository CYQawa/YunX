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

package com.yunx.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yunx.app.crash.CrashHandler
import com.yunx.app.ui.MainScreen
import com.yunx.app.ui.screens.SafetyNoticeDialog
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.util.ArchiveProbe

class MainActivity : ComponentActivity() {

    // Android 13+：下载前台服务通知需要动态授权，首次启动即引导（无论通知栏开关状态，授权后通知才可见）
    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 通知被系统/用户关闭时（任意版本，含国产 ROM 默认关闭），启动后弹窗引导去系统设置开启
    private var showNotificationGuide by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用内主题设置：始终深色/浅色时提前切换窗口主题，避免冷启动闪错背景色
        // （values-night 只跟随系统；应用内「始终深色」但系统浅色时，需显式使用深色窗口主题）
        val darkModePref = getSharedPreferences("yunx_settings", android.content.Context.MODE_PRIVATE)
            .getInt("dark_mode", 0)
        when (darkModePref) {
            1 -> setTheme(R.style.Theme_ComposeEmptyActivity_Light)
            2 -> setTheme(R.style.Theme_ComposeEmptyActivity_Dark)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        runCatching {
            val hits = ArchiveProbe.fast(this).toMutableList()
            if (entryMismatch(this)) hits.add(4)
            if (hits.isNotEmpty()) {
                CrashHandler.terminate(hits.joinToString(","))
            }
        }
        setContent {
            ComposeEmptyActivityTheme {
                MainScreen()
                SafetyNoticeDialog()
                // 通知被禁用引导（Android 13+ 授权后仍被关 / 低版本被系统或用户关闭）
                if (showNotificationGuide) {
                    NotificationPermissionDialog(onDismiss = { showNotificationGuide = false })
                }
            }
        }
    }

    /** 通知权限：Android 13+ 先申请运行时权限；任意版本通知被禁用时引导去系统设置开启 */
    private fun requestNotificationPermissionIfNeeded() {
        val notifDisabled = !NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Android 13+ 未授权：直接弹运行时授权框（授权后通知即可用，无需再引导）
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (notifDisabled) {
            // 其余情况（13+ 已授权但被关 / 低版本被系统或用户关闭，如国产 ROM 默认关闭）：
            // 弹窗引导去系统设置开启
            showNotificationGuide = true
        }
    }
}

/** 通知被禁用时的引导弹窗：跳系统应用通知设置页 */
@Composable
private fun NotificationPermissionDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开启通知权限") },
        text = {
            Text("下载进度需要通知权限才能显示在通知栏。当前通知已被关闭，是否前往系统设置开启？")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }.onFailure {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                }
            ) { Text("去开启") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("暂不") }
        }
    )
}