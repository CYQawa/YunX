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

package com.yunx.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunx.app.util.PermissionState

/**
 * 引导页第 3 页：权限准备。
 *
 * ★ 项目的权限申请统一收在这里（首次启动一次性过一遍），各功能内部只留"兜底"：
 *   通知权限（可选）、后台运行/忽略电池优化（可选）、存储权限（Android 9- 为**必要权限**）。
 *   通知与后台运行可以跳过；存储权限拿不到时不给跳过 —— Android 9 及以下未授权时，底部「开始使用」
 *   按钮会置灰禁用（文案不变），授权入口就是本页的「存储权限」卡片；授权成功返回后按钮自动恢复可点。
 *
 * ★ 状态必须在 `ON_RESUME` 时重查：授权框 / 系统设置页都会让 Activity 走一次
 *   onPause → onResume，只靠 `remember` 初值会出现"授权完返回，卡片还显示未授权"。
 */
@Composable
internal fun PermissionPage(
    storageGranted: Boolean,
    storageDenied: Boolean,
    onRequestStorage: () -> Unit
) {
    val context = LocalContext.current
    var notificationsEnabled by remember { mutableStateOf(PermissionState.notificationsEnabled(context)) }
    var batteryIgnored by remember { mutableStateOf(PermissionState.batteryOptimizationIgnored(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = PermissionState.notificationsEnabled(context)
                batteryIgnored = PermissionState.batteryOptimizationIgnored(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 通知：13+ 才有可申请的运行时权限，其余情况跳系统设置页（存储权限的发起在 OnboardingScreen）
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationsEnabled = PermissionState.notificationsEnabled(context)
    }

    val storageRequired = PermissionState.storagePermissionRequired()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "权限准备",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (storageRequired) {
                "云析只用下面这几项系统授权。存储权限是 Android 9 及以下保存文件的必要权限，必须授权；" +
                    "其余两项可以跳过，之后也能在「设置」里随时更改"
            } else {
                "云析只用下面这几项系统授权，都可以跳过，之后也能在「设置」里随时更改"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        PermissionCard(
            icon = Icons.Outlined.Notifications,
            title = "通知权限",
            desc = "下载进度、完成提醒显示在通知栏，也能让前台服务更稳地活下去；关闭后下载照常进行，只是看不到进度。",
            granted = notificationsEnabled,
            grantedText = "已开启",
            actionText = if (PermissionState.canRequestNotifications(context)) "授权" else "去设置",
            onAction = {
                if (PermissionState.canRequestNotifications(context)) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // 13+ 已授权但被系统/用户关闭，或低版本通知总开关被关：只能去系统设置开
                    PermissionState.openNotificationSettings(context)
                }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Outlined.BatteryChargingFull,
            title = "后台运行",
            desc = "把云析加入「忽略电池优化」白名单，锁屏或切到后台后下载不会被系统冻结（推荐开启）。",
            granted = batteryIgnored,
            grantedText = "已加入白名单",
            actionText = "去设置",
            onAction = { PermissionState.requestIgnoreBatteryOptimizations(context) }
        )
        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Outlined.Storage,
            title = "存储权限",
            desc = if (storageRequired) {
                "保存文件到系统「下载」目录需要存储权限；Android 9 及以下这是必要权限，必须授权才能保存文件。"
            } else {
                "Android 10 及以上保存文件走系统媒体库，不需要存储权限。"
            },
            granted = storageGranted,
            grantedText = if (storageRequired) "已授权" else "无需授权",
            actionText = if (storageRequired && !storageGranted) {
                // 授权框已被拒过一次：再点也弹不出来（或被勾了"不再询问"），只能去应用详情页手动开
                if (storageDenied) "去设置授权" else "授权"
            } else {
                null
            },
            onAction = onRequestStorage
        )

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = if (storageRequired) {
                "跳过的影响：没有通知权限 → 通知栏看不到下载进度；没有后台运行白名单 → 息屏后下载可能被暂停。" +
                    "存储权限是必要权限，未授权无法保存文件，所以必须授权后才能开始使用。"
            } else {
                "跳过的影响：没有通知权限 → 通知栏看不到下载进度；没有后台运行白名单 → 息屏后下载可能被暂停。" +
                    "Android 10 及以上保存文件不需要存储权限。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** 权限卡片：图标 + 标题 +（已授权徽标 / 操作按钮）+ 说明 */
@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    desc: String,
    granted: Boolean,
    grantedText: String,
    actionText: String?,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (granted) {
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = grantedText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
            // ★ 已授权就不显示操作按钮：「去设置」只在真的需要用户动手时才出现
            if (!granted && actionText != null) {
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(text = actionText, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
