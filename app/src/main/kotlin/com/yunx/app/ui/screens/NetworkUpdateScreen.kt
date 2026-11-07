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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yunx.app.R
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.ListGroupPos
import com.yunx.app.ui.theme.ThemeController
import com.yunx.app.ui.theme.listGroupShape
import com.yunx.app.util.AppStrings

/**
 * 「网络与更新」二级页：接受预发布版更新 / GitHub 下载镜像 / 网络代理 这三项收在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkUpdateScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }

    // GitHub 下载镜像前缀：null/空 = 使用内置默认（UpdateChecker.MIRROR_PREFIX）
    var githubMirror by remember { mutableStateOf(settingsRepo.githubMirrorPrefix) }
    var showMirrorDialog by remember { mutableStateOf(false) }
    // 网络代理：本地状态驱动副标题，弹窗内使用临时变量编辑
    var proxyEnabled by remember { mutableStateOf(settingsRepo.proxyEnabled) }
    var proxyHost by remember { mutableStateOf(settingsRepo.proxyHost) }
    var proxyPort by remember { mutableStateOf(settingsRepo.proxyPort.toString()) }
    var showProxyDialog by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_network_update),
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
                    text = stringResource(R.string.network_update_page_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            SectionLabel(stringResource(R.string.section_update))

            SettingsItem(
                icon = Icons.Outlined.SystemUpdate,
                shape = listGroupShape(ListGroupPos.SINGLE),
                title = stringResource(R.string.settings_accept_prerelease),
                description = stringResource(
                    if (ThemeController.acceptPrereleaseUpdate) R.string.settings_accept_prerelease_desc_on
                    else R.string.settings_accept_prerelease_desc_off
                ),
                onClick = {
                    ThemeController.setAcceptPrereleaseUpdate(
                        context,
                        !ThemeController.acceptPrereleaseUpdate
                    )
                },
                checked = ThemeController.acceptPrereleaseUpdate,
                trailing = {
                    Switch(checked = ThemeController.acceptPrereleaseUpdate, onCheckedChange = null)
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            SectionLabel(stringResource(R.string.section_network))

            // GitHub 下载镜像：自定义前缀，留空使用内置默认镜像
            SettingsItem(
                icon = Icons.Outlined.Cloud,
                shape = listGroupShape(ListGroupPos.FIRST),
                title = stringResource(R.string.settings_github_mirror),
                description = githubMirror?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.settings_github_mirror_desc_custom, it) }
                    ?: stringResource(R.string.settings_github_mirror_desc_default, UpdateChecker.MIRROR_PREFIX),
                onClick = { showMirrorDialog = true }
            )

            Spacer(modifier = Modifier.height(ListGroupGap))

            // 网络代理：HTTP 代理（Clash/v2ray 等本地代理），未启用时直连
            SettingsItem(
                icon = Icons.Outlined.Security,
                shape = listGroupShape(ListGroupPos.LAST),
                title = stringResource(R.string.settings_proxy),
                description = if (proxyEnabled && proxyHost.isNotBlank()) {
                    stringResource(R.string.settings_proxy_desc_on, "$proxyHost:$proxyPort")
                } else {
                    stringResource(R.string.settings_proxy_desc_off)
                },
                onClick = { showProxyDialog = true }
            )
        }
    }

    // GitHub 下载镜像前缀设置弹窗（留空 = 使用内置默认镜像）
    if (showMirrorDialog) {
        var mirrorInput by remember { mutableStateOf(githubMirror ?: "") }
        AlertDialog(
            onDismissRequest = { showMirrorDialog = false },
            title = { Text(stringResource(R.string.dialog_mirror_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = mirrorInput,
                        onValueChange = { mirrorInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dialog_mirror_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true
                    )
                    Text(
                        text = stringResource(R.string.dialog_mirror_hint, UpdateChecker.MIRROR_PREFIX),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { mirrorInput = "" }) {
                        Text(stringResource(R.string.action_restore_default))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val raw = mirrorInput.trim()
                        if (raw.isBlank()) {
                            settingsRepo.githubMirrorPrefix = null
                            githubMirror = null
                            showMirrorDialog = false
                            SnackbarController.show(AppStrings.get(R.string.snack_mirror_default_restored))
                        } else if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                            SnackbarController.show(AppStrings.get(R.string.snack_mirror_invalid_prefix))
                        } else {
                            val normalized = if (raw.endsWith("/")) raw else "$raw/"
                            settingsRepo.githubMirrorPrefix = normalized
                            githubMirror = normalized
                            showMirrorDialog = false
                            SnackbarController.show(AppStrings.get(R.string.snack_mirror_updated))
                        }
                    }
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showMirrorDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 网络代理设置弹窗（HTTP 代理；未启用时直连）
    if (showProxyDialog) {
        // 弹窗内临时变量：取消时不回写已保存值
        var tempEnabled by remember { mutableStateOf(proxyEnabled) }
        var tempHost by remember { mutableStateOf(proxyHost) }
        var tempPort by remember { mutableStateOf(proxyPort) }
        AlertDialog(
            onDismissRequest = { showProxyDialog = false },
            title = { Text(stringResource(R.string.dialog_proxy_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.dialog_proxy_enable),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = tempEnabled, onCheckedChange = { tempEnabled = it })
                    }
                    OutlinedTextField(
                        value = tempHost,
                        onValueChange = { tempHost = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dialog_proxy_host)) },
                        singleLine = true,
                        enabled = tempEnabled
                    )
                    OutlinedTextField(
                        value = tempPort,
                        onValueChange = { tempPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dialog_proxy_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = tempEnabled
                    )
                    Text(
                        text = stringResource(R.string.dialog_proxy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tempEnabled) {
                            val host = tempHost.trim()
                            val port = tempPort.toIntOrNull()
                            when {
                                host.isBlank() ->
                                    SnackbarController.show(AppStrings.get(R.string.snack_proxy_host_required))
                                port == null || port !in 1..65535 ->
                                    SnackbarController.show(AppStrings.get(R.string.snack_proxy_port_invalid))
                                else -> {
                                    settingsRepo.proxyEnabled = true
                                    settingsRepo.proxyHost = host
                                    settingsRepo.proxyPort = port
                                    HttpClients.setProxy(host, port)
                                    proxyEnabled = true
                                    proxyHost = host
                                    proxyPort = port.toString()
                                    showProxyDialog = false
                                    SnackbarController.show(AppStrings.get(R.string.snack_proxy_enabled, "$host:$port"))
                                }
                            }
                        } else {
                            settingsRepo.proxyEnabled = false
                            HttpClients.setProxy(null, 0)
                            proxyEnabled = false
                            showProxyDialog = false
                            SnackbarController.show(AppStrings.get(R.string.snack_proxy_disabled))
                        }
                    }
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showProxyDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
