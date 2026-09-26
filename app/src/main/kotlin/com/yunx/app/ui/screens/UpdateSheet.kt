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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.update.UpdateChecker

/** Release 说明里没给 html_url 时的兜底跳转地址 */
private const val RELEASES_PAGE_FALLBACK = "https://github.com/CYQawa/YunX/releases/latest"

/**
 * 发现新版本的**底部弹窗**。
 *
 * 按钮层级（旧版四个按钮平铺、主次不分，这里按重要性压成三层）：
 * 1. 主按钮（填充、整行）：说明里有「网盘下载」条目时是「网盘更新」（走内置解析），否则是「从 GitHub 下载」；
 * 2. 次按钮（描边、整行）：命中网盘链接时是「GitHub 下载」（再弹一层让用户选 GitHub 直连 / 镜像站 / 打开页面），
 *    没命中时是「使用镜像站下载」；
 * 3. 弱按钮（纯文字、居中）：忽略本次 / 稍后。
 *
 * ★ 弹窗只有这一处实现：启动检查、设置页手动检查、设置页开发调试预览全部复用 [UpdateSheet]，
 *   状态与下载逻辑由 MainScreen 统一持有（不要再在设置页里另写一份）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    currentVersion: String,
    release: UpdateChecker.Release,
    /** GitHub 直链下载（内置下载器） */
    onDownload: () -> Unit,
    /** 镜像站（加速通道）下载 */
    onDownloadMirror: () -> Unit,
    /** 网盘更新：把说明里匹配到的网盘链接交给解析流程 */
    onNetdiskUpdate: (url: String) -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 说明里有没有「[网盘下载](...)」条目：它决定主按钮与次级入口的形态
    val netdiskUrl = remember(release.body) { UpdateChecker.netdiskDownloadUrl(release.body) }
    // GitHub 下载方式选择弹窗（命中网盘链接时，直连/镜像/页面被折叠进这一个入口）
    var showGithubOptions by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onLater,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            // 标题行：图标 + 标题 + 版本号
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "发现新版本",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = release.tagName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "当前 $currentVersion",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "更新内容",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            // 更新说明（可滚动 + 限高，长说明不撑爆弹窗）
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Text(
                    text = release.body.ifBlank { "暂无更新说明" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ① 主按钮：命中网盘链接时走网盘更新，否则从 GitHub 下载
            if (netdiskUrl != null) {
                Button(
                    onClick = { onNetdiskUpdate(netdiskUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("网盘更新")
                }
            } else {
                Button(
                    onClick = onDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("从 GitHub 下载")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ② 次按钮
            if (netdiskUrl != null) {
                OutlinedButton(
                    onClick = { showGithubOptions = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GitHub 下载")
                }
            } else {
                OutlinedButton(
                    onClick = onDownloadMirror,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("使用镜像站下载")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ③ 弱按钮：忽略本次 / 稍后
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onIgnore) {
                    Text("忽略本次", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onLater) {
                    Text("稍后", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // GitHub 下载方式（只有命中网盘链接时才用得到）：直连 / 镜像站 / 打开页面
    if (showGithubOptions) {
        AlertDialog(
            onDismissRequest = { showGithubOptions = false },
            title = { Text("GitHub 下载") },
            text = {
                Column {
                    Button(
                        onClick = {
                            showGithubOptions = false
                            onDownload()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GitHub 直连下载")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showGithubOptions = false
                            onDownloadMirror()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("镜像站加速下载")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showGithubOptions = false
                            // 打开 Release 页面（html_url 缺失时退回 releases/latest）
                            val url = release.htmlUrl.ifBlank { RELEASES_PAGE_FALLBACK }
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("打开 GitHub 页面")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGithubOptions = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}
