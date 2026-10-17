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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.MultiAccountEntity

/**
 * 账号列表（多账号）：同一平台的多个账号、设主账号 / 改备注 / 退出，以及「添加账号」。
 *
 * ★ 10 个平台的账号弹窗共用这一段：这些操作只关心公共字段，所以参数用 [MultiAccountEntity]
 *   而不是各自的实体类型（各弹窗自己的凭证详情卡仍展示主账号那一条）。
 *
 * @param onSetPrimary 点某个非主账号行时调用（把它设为主账号）
 * @param onRename 改备注（传空串 = 清掉备注，回退显示昵称）
 * @param onRemove 退出单个账号
 * @param onEmpty 账号被清空时回调 —— 弹窗已经没有内容可展示，由调用方关掉它
 */
@Composable
internal fun AccountListSection(
    accounts: List<MultiAccountEntity>,
    onSetPrimary: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onAddAccount: () -> Unit,
    onEmpty: () -> Unit
) {
    // 最后一个账号被退出后弹窗没有内容可展示了，自动关闭
    LaunchedEffect(accounts.isEmpty()) { if (accounts.isEmpty()) onEmpty() }

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
                AccountRow(
                    account = account,
                    onSetPrimary = onSetPrimary,
                    onRename = onRename,
                    onRemove = onRemove
                )
            }
            HorizontalDivider()
            // 添加账号：复用该平台原来的登录入口（网页登录 / 账号密码登录），只是落成新的一行
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
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "主账号用于列目录、转存、上传等需要单一身份的操作；" +
            "开启「多账号下载」后，下载会自动用上同一网盘的其他账号。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 单条账号：昵称/备注 + 主账号徽标（点整行可切换），右侧菜单改名 / 退出 */
@Composable
private fun AccountRow(
    account: MultiAccountEntity,
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
                        text = "同一网盘登录多个账号时，备注能帮你区分（清空备注则显示昵称）。",
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

/** 账号显示名：备注优先，回退昵称，再回退占位文案 */
internal fun MultiAccountEntity.displayName(): String =
    remark.ifBlank { nickname }.ifBlank { "未命名账号" }
