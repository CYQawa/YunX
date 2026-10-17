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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.VideoAccountEntity
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.ui.items.logoRes
import com.yunx.app.ui.rememberGlobalSnackbarHostState

/**
 * 已登录视频平台的账号弹窗：账号列表（设主账号 / 改备注 / 退出）+ 添加账号 + 退出全部账号。
 *
 * ★ 与网盘账号弹窗分开实现：网盘的 [AccountListSection] 文案绑定在「列目录 / 转存 / 多账号下载」
 *   那套语义上，视频平台只有「解析时用哪个 Cookie」，措辞与操作项（多一个「退出全部账号」）都不同。
 *
 * @param accounts 该平台的全部账号（主账号在前，由 DAO 排序保证）
 * @param onRemoveAll 退出该平台全部账号（本机凭证全清）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoAccountSheet(
    platform: VideoPlatform,
    accounts: List<VideoAccountEntity>,
    onSetPrimary: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onRemoveAll: () -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit
) {
    var showRemoveAll by remember { mutableStateOf(false) }

    // 最后一个账号被退出后弹窗已无内容可展示，自动关闭（与网盘账号弹窗一致）
    LaunchedEffect(accounts.isEmpty()) { if (accounts.isEmpty()) onDismiss() }

    // ModalBottomSheet 为独立窗口，需自带 Snackbar 宿主
    val snackbarHostState = rememberGlobalSnackbarHostState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            // 头部：平台 Logo + 平台名 + 登录态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(platform.logoRes()),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = platform.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (accounts.size > 1) {
                                "已登录 · 共 ${accounts.size} 个账号"
                            } else {
                                "已登录"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "账号（${accounts.size}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column {
                    accounts.forEachIndexed { index, account ->
                        if (index > 0) HorizontalDivider()
                        VideoAccountRow(
                            account = account,
                            onSetPrimary = onSetPrimary,
                            onRename = onRename,
                            onRemove = onRemove
                        )
                    }
                    HorizontalDivider()
                    // 添加账号：走这个平台自己的登录页，登录后落成新的一行（由上层以 newAccount 语义打开）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onAddAccount)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "添加账号",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider()
                    // 退出全部：多账号场景下逐个退太慢，给一个一键清空的入口（二次确认）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRemoveAll = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "退出全部账号",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // 「登录后能得到什么」：语义与各平台解析能力绑定，放在弹窗底部作说明
            if (platform.loginBenefit.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = platform.loginBenefit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SnackbarHost(hostState = snackbarHostState)
        }
    }

    if (showRemoveAll) {
        AlertDialog(
            onDismissRequest = { showRemoveAll = false },
            title = { Text("退出全部账号") },
            text = {
                Text("确定要退出「${platform.displayName}」的全部 ${accounts.size} 个账号吗？只清除本机保存的登录凭证。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveAll = false
                        onRemoveAll()
                    }
                ) {
                    Text("全部退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showRemoveAll = false }) { Text("取消") } }
        )
    }
}

/** 单条视频账号：昵称/备注 + 主账号徽标（点整行切换），右侧菜单改名 / 退出 */
@Composable
private fun VideoAccountRow(
    account: VideoAccountEntity,
    onSetPrimary: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    val displayName = account.displayName()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 非主账号整行可点：点一下即设为主账号（主账号行点击无动作）
            .clickable(enabled = !account.isPrimary) { onSetPrimary(account.id) }
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = displayName.take(1),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = displayName, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (account.isPrimary) "主账号" else "点一下设为主账号",
                style = MaterialTheme.typography.labelSmall,
                color = if (account.isPrimary) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "账号操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("重命名备注") },
                    onClick = {
                        menuExpanded = false
                        showRename = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("退出该账号") },
                    onClick = {
                        menuExpanded = false
                        showRemove = true
                    }
                )
            }
        }
    }

    if (showRename) {
        var input by remember(account.id) { mutableStateOf(account.remark) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名备注") },
            text = {
                Column {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        label = { Text("备注") },
                        placeholder = { Text(account.nickname.ifBlank { "留空则显示昵称" }) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "同一平台登录多个账号时，备注能帮你区分（清空备注则显示昵称）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRename = false
                        onRename(account.id, input)
                    }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } }
        )
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("退出该账号") },
            text = {
                Text("确定要退出「$displayName」吗？只清除这一个账号在本机的登录凭证，其他账号不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemove = false
                        onRemove(account.id)
                    }
                ) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showRemove = false }) { Text("取消") } }
        )
    }
}
