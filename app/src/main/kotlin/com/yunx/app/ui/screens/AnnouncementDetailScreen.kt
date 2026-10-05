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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.yunx.app.data.announcement.AnnouncementApi
import com.yunx.app.data.announcement.formatLocalDateTime
import com.yunx.app.data.announcement.parseIsoMillis
import com.yunx.app.data.announcement.relativeTime
import com.yunx.app.ui.components.GitHubMarkdownImageTransformer
import com.yunx.app.ui.components.RemoteImage
import com.yunx.app.ui.components.YunXLoading
import com.yunx.app.ui.components.compactMarkdownTypography
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.ui.viewmodel.AnnouncementViewModel

/**
 * 公告详情页（共享元素的**目标**侧：从列表里被点的那一行"长"成整页，key 见 [announcementSharedKey]）。
 *
 * 正文渲染复用 GitHub README 那一套：mikepenz GFM 渲染器（支持标题 / 列表 / 表格 / 代码 / 链接）
 * + 项目自研的 [GitHubMarkdownImageTransformer] 图片加载器。
 * ★ 刻意**不用 WebView** 渲染：`content` 支持 Markdown/HTML，进 WebView 就必须自己扛 XSS 与 CSP；
 *   走 Compose 渲染则 HTML 标签只是普通文本，不存在脚本执行面（代价是 HTML 片段不解析）。
 *
 * 详情接口会让 viewCount +1，所以 ViewModel 里按 id 缓存，本页重组 / 返回再进都不会重复请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementDetailPage(
    state: AnnouncementViewModel.DetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 独立全屏覆盖页：自带 Snackbar 宿主（覆盖层会遮挡主页 Scaffold 的 SnackbarHost）
    val snackbarHostState = rememberGlobalSnackbarHostState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("公告详情", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (state) {
                is AnnouncementViewModel.DetailUiState.Loaded -> AnnouncementDetailContent(item = state.item)
                is AnnouncementViewModel.DetailUiState.Failed -> AnnouncementDetailError(
                    message = state.message,
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.Center)
                )
                // Idle / Loading：都按加载中处理（宿主一进来就发起请求，Idle 只是一瞬间）
                else -> YunXLoading(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun AnnouncementDetailContent(
    item: AnnouncementApi.Announcement,
    modifier: Modifier = Modifier
) {
    // 正文排版与 README 预览共用同一份紧凑字号（见 ui/components/MarkdownTypography.kt）
    val typography = remember { compactMarkdownTypography() }
    val publisher = item.publisher.name.ifBlank { item.author }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item(key = "header") {
            Column {
                if (item.isPinned) {
                    AnnouncementChip(
                        text = "置顶公告",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnnouncementAvatar(
                        name = publisher.ifBlank { "公告" },
                        avatarUrl = item.publisher.avatarUrl,
                        size = 32.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        if (publisher.isNotBlank()) {
                            Text(
                                text = publisher,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 时间都解析不出来时（脏数据）整段不显示，避免出现 1970-01-01
                            if (item.effectiveMillis > 0L) {
                                Text(
                                    text = formatLocalDateTime(item.effectiveMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val relative = relativeTime(item.effectiveMillis)
                                if (relative.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "· $relative",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (item.viewCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Outlined.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = item.viewCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                val cover = item.coverImage
                if (!cover.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    // 封面按图片自身比例铺满宽度，不做裁切（公告封面常常是长图或截图）
                    RemoteImage(
                        url = cover,
                        contentDescription = null,
                        shape = MaterialTheme.shapes.large,
                        contentScale = ContentScale.Fit,
                        autoHeight = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        item(key = "content") {
            val content = item.content
            if (content.isNullOrBlank()) {
                Text(
                    text = "（本条公告没有正文）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Markdown(
                    content = content,
                    typography = typography,
                    imageTransformer = GitHubMarkdownImageTransformer
                )
            }
        }

        // 正文多图（服务端 images 数组）：作图片集附在正文之后，同样是图床直链、直接加载
        if (item.images.isNotEmpty()) {
            items(item.images) { imageUrl ->
                RemoteImage(
                    url = imageUrl,
                    contentDescription = null,
                    shape = MaterialTheme.shapes.large,
                    contentScale = ContentScale.Fit,
                    autoHeight = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        }

        item(key = "footer") {
            Column {
                Spacer(modifier = Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(modifier = Modifier.height(10.dp))
                val updatedMillis = parseIsoMillis(item.updatedAt) ?: item.effectiveMillis
                if (updatedMillis > 0L) {
                    Text(
                        text = buildString {
                            append("最后更新 ")
                            append(formatLocalDateTime(updatedMillis))
                            if (item.author.isNotBlank() && item.author != publisher) {
                                append(" · 作者 ")
                                append(item.author)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AnnouncementDetailError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "公告加载失败", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}
