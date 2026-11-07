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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunx.app.R
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.ListGroupPos
import com.yunx.app.ui.theme.listGroupShape
import com.yunx.app.util.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「下载设置」二级页：并发 / 限速 / 重试 / 取链方式 / 后台与通知 等重项都收在这里，
 * 设置主页只保留常用入口，避免主页过长过乱。
 *
 * ★ 「多账号下载」在引擎模式下隐藏；它的入口页由 [onMultiAccountClick] 打开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSettingsScreen(
    onBack: () -> Unit,
    onMultiAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }

    // 引擎模式下若干「只对内置分片下载器有意义」的项要隐藏（引擎不读限速、重试是引擎内部策略）
    val engineOn = settingsRepo.downloadEngine == SettingsRepository.ENGINE_GOPEED

    var maxConcurrent by remember { mutableStateOf(settingsRepo.maxConcurrentDownloads) }
    var speedLimitBps by remember { mutableStateOf(settingsRepo.downloadSpeedLimit) }
    var retryCount by remember { mutableStateOf(settingsRepo.downloadRetryCount) }
    var quarkNoSave by remember { mutableStateOf(settingsRepo.quarkNoSaveDownload) }
    var keepLocked by remember { mutableStateOf(settingsRepo.keepDownloadWhenLocked) }
    var showSpeed by remember { mutableStateOf(settingsRepo.notificationShowSpeed) }

    var showConcurrencyDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showRetryDialog by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }

    // 通知是否可用（areNotificationsEnabled 不是 Compose 状态源，手动提升为状态，返回页面时刷新）
    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 通知权限（Android 13+）：未授权时点击「通知栏下载进度」先申请，授权后生效
    val notifyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (granted) {
            showSpeed = true
            settingsRepo.notificationShowSpeed = true
        }
    }

    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_download_settings),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Text(
                    text = stringResource(R.string.download_settings_page_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            SectionLabel(stringResource(R.string.section_download_strategy))

            // 最大同时下载任务数：内置下载器与 Gopeed 引擎共用同一上限
            SettingsItem(
                icon = Icons.Outlined.Layers,
                shape = listGroupShape(if (engineOn) ListGroupPos.SINGLE else ListGroupPos.FIRST),
                title = stringResource(R.string.settings_max_tasks),
                description = stringResource(R.string.settings_max_tasks_desc, maxConcurrent),
                onClick = { showConcurrencyDialog = true }
            )

            // 引擎不支持限速；重试是引擎内部自己的策略 ⇒ 这两项只在内置分片下载器下显示
            AnimatedVisibility(visible = !engineOn, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    Spacer(modifier = Modifier.height(ListGroupGap))

                    SettingsItem(
                        icon = Icons.Outlined.Speed,
                        shape = listGroupShape(ListGroupPos.MIDDLE),
                        title = stringResource(R.string.settings_speed_limit),
                        description = if (speedLimitBps <= 0) {
                            stringResource(R.string.settings_speed_limit_desc_unlimited)
                        } else {
                            stringResource(R.string.settings_speed_limit_desc, formatSpeed(speedLimitBps))
                        },
                        onClick = { showSpeedDialog = true }
                    )

                    Spacer(modifier = Modifier.height(ListGroupGap))

                    SettingsItem(
                        icon = Icons.Outlined.Refresh,
                        shape = listGroupShape(ListGroupPos.LAST),
                        title = stringResource(R.string.settings_auto_retry),
                        description = if (retryCount == 0) {
                            stringResource(R.string.settings_auto_retry_desc_never)
                        } else {
                            stringResource(R.string.settings_auto_retry_desc, retryCount)
                        },
                        onClick = { showRetryDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionLabel(stringResource(R.string.section_download_background))

            // 取链方式：免转存（默认）↔ 转存。只影响夸克
            SettingsItem(
                icon = Icons.Outlined.SwapHoriz,
                shape = listGroupShape(ListGroupPos.FIRST),
                title = stringResource(R.string.settings_no_transfer),
                description = stringResource(
                    if (quarkNoSave) R.string.settings_no_transfer_desc_on
                    else R.string.settings_no_transfer_desc_off
                ),
                onClick = {
                    quarkNoSave = !quarkNoSave
                    settingsRepo.quarkNoSaveDownload = quarkNoSave
                },
                checked = quarkNoSave,
                trailing = { Switch(checked = quarkNoSave, onCheckedChange = null) }
            )

            Spacer(modifier = Modifier.height(ListGroupGap))

            SettingsItem(
                icon = Icons.Outlined.Power,
                shape = listGroupShape(ListGroupPos.MIDDLE),
                title = stringResource(R.string.settings_keep_downloading),
                description = stringResource(R.string.settings_keep_downloading_desc),
                onClick = {
                    keepLocked = !keepLocked
                    settingsRepo.keepDownloadWhenLocked = keepLocked
                    if (keepLocked) {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        if (pm?.isIgnoringBatteryOptimizations(context.packageName) != true) {
                            showBatteryDialog = true
                        }
                    }
                },
                checked = keepLocked,
                trailing = { Switch(checked = keepLocked, onCheckedChange = null) }
            )

            Spacer(modifier = Modifier.height(ListGroupGap))

            SettingsItem(
                icon = Icons.Outlined.Notifications,
                shape = listGroupShape(ListGroupPos.LAST),
                title = stringResource(R.string.settings_notification_progress),
                description = when {
                    !notificationsEnabled -> stringResource(R.string.settings_notification_progress_desc_no_perm)
                    showSpeed -> stringResource(R.string.settings_notification_progress_desc_on)
                    else -> stringResource(R.string.settings_notification_progress_desc_simple)
                },
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (!notificationsEnabled) {
                        openNotificationSettings(context)
                    } else {
                        showSpeed = !showSpeed
                        settingsRepo.notificationShowSpeed = showSpeed
                    }
                },
                checked = if (notificationsEnabled) showSpeed else null,
                trailing = { Switch(checked = showSpeed, onCheckedChange = null) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            SectionLabel(stringResource(R.string.section_download_more))

            SettingsItem(
                icon = Icons.Outlined.SwitchAccount,
                shape = listGroupShape(ListGroupPos.SINGLE),
                title = stringResource(R.string.settings_multi_account_download),
                description = stringResource(R.string.settings_multi_account_download_desc),
                onClick = onMultiAccountClick
            )
        }
    }

    // 最大同时下载任务数
    if (showConcurrencyDialog) {
        val options = listOf(1, 2, 3, 5, 8)
        AlertDialog(
            onDismissRequest = { showConcurrencyDialog = false },
            title = { Text(stringResource(R.string.dialog_max_tasks_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { v ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = maxConcurrent == v,
                                onClick = {
                                    maxConcurrent = v
                                    settingsRepo.maxConcurrentDownloads = v
                                    showConcurrencyDialog = false
                                    // 引擎自己不读这个设置，只在启动时同步一次，所以这里立刻补一次下发
                                    scope.launch {
                                        val applied = runCatching {
                                            withContext(Dispatchers.IO) { GopeedEngine.applyMaxRunning(context) }
                                        }.getOrNull()
                                        if (applied == null &&
                                            GopeedEngine.state.value == GopeedEngine.State.RUNNING
                                        ) {
                                            SnackbarController.show(AppStrings.get(R.string.dl_engine_concurrency_apply_failed))
                                        }
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.option_max_tasks, v),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConcurrencyDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 下载速度限制：预设档位 + 自定义（KB/s）
    if (showSpeedDialog) {
        val presets = listOf(0L, 1L * 1024 * 1024, 2L * 1024 * 1024, 5L * 1024 * 1024, 10L * 1024 * 1024)
        var tempSelected by remember { mutableStateOf<Long?>(null) }
        var customKb by remember {
            mutableStateOf(
                if (speedLimitBps > 0 && speedLimitBps !in presets) (speedLimitBps / 1024).toString() else ""
            )
        }
        val effective = tempSelected ?: speedLimitBps
        val isCustom = when {
            tempSelected == -1L -> true
            tempSelected == null -> speedLimitBps > 0 && speedLimitBps !in presets
            else -> false
        }
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text(stringResource(R.string.dialog_speed_limit_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    presets.forEach { v ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !isCustom && effective == v,
                                onClick = { tempSelected = v }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (v == 0L) stringResource(R.string.option_speed_unlimited)
                                else formatSpeed(v),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isCustom,
                            onClick = {
                                tempSelected = -1L
                                if (speedLimitBps > 0 && speedLimitBps !in presets && customKb.isBlank()) {
                                    customKb = (speedLimitBps / 1024).toString()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = customKb,
                            onValueChange = {
                                customKb = it.filter(Char::isDigit).take(6)
                                tempSelected = -1L
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.option_custom_kb)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isCustom) {
                            val kb = customKb.toLongOrNull()?.coerceAtLeast(1L)
                            if (kb != null) {
                                speedLimitBps = kb * 1024
                                settingsRepo.downloadSpeedLimit = kb * 1024
                            }
                        } else if (tempSelected != null) {
                            val v = tempSelected ?: speedLimitBps
                            speedLimitBps = v
                            settingsRepo.downloadSpeedLimit = v
                        }
                        showSpeedDialog = false
                    }
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 失败自动重试次数
    if (showRetryDialog) {
        val options = listOf(0, 1, 2, 3, 5, 8, 10)
        AlertDialog(
            onDismissRequest = { showRetryDialog = false },
            title = { Text(stringResource(R.string.dialog_auto_retry_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { v ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = retryCount == v,
                                onClick = {
                                    retryCount = v
                                    settingsRepo.downloadRetryCount = v
                                    showRetryDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (v == 0) stringResource(R.string.option_retry_never)
                                else stringResource(R.string.option_retry_times, v),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRetryDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 锁屏保持下载：引导加入「忽略电池优化」白名单
    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text(stringResource(R.string.dialog_battery_title)) },
            text = {
                Text(
                    text = stringResource(R.string.dialog_battery_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatteryDialog = false
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                ) { Text(stringResource(R.string.action_go_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) {
                    Text(stringResource(R.string.action_not_now))
                }
            }
        )
    }
}
