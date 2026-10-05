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

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yunx.app.data.announcement.AnnouncementApi
import com.yunx.app.data.announcement.relativeTime
import com.yunx.app.ui.components.RemoteImage
import com.yunx.app.ui.components.YunXLoading
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.listGroupShape
import com.yunx.app.ui.viewmodel.AnnouncementViewModel

/**
 * 公告列表页（全屏叠加页，从顶栏公告图标做容器变换进来）。
 *
 * ★ 共享元素：每一行都用 [announcementSharedKey] 与本行的 id 注册 `sharedBounds`，
 *   点进去时这一行"长成"详情页整页（目标侧见 AnnouncementDetailScreen 的调用处）。
 *   源侧修饰符必须在这里构造 —— `rememberSharedContentState` 是 @Composable，
 *   只能在 composable 作用域里调用（同 MainScreen 里收藏图标那一段的注释）。
 *
 * 列表口径：一次拿满 [AnnouncementApi.PAGE_SIZE] 条（接口上限 100），
 * 只有公告总数超过 100 条时底部才会出现「加载更多」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AnnouncementListPage(
    state: AnnouncementViewModel.ListUiState,
    readIds: Set<String>,
    sharedScope: SharedTransitionScope,
    rowAnimatedScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onMarkAllRead: () -> Unit,
    onOpen: (AnnouncementApi.Announcement) -> Unit,
    modifier: Modifier = Modifier
) {
    // 独立全屏覆盖页：自带 Snackbar 宿主（覆盖层会遮挡主页 Scaffold 的 SnackbarHost）
    val snackbarHostState = rememberGlobalSnackbarHostState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("公告", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = onMarkAllRead) {
                            Icon(Icons.Outlined.DoneAll, contentDescription = "全部标为已读")
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
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
            when {
                // 有数据：正常列表（刷新 / 翻页失败只用 Snackbar 提示，不把已有内容换成错误页）
                state.items.isNotEmpty() -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        // 列表组：各项首尾相接（只留发丝缝），行圆角按首/中/末分段给
                        verticalArrangement = Arrangement.spacedBy(ListGroupGap)
                    ) {
                        itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                            val rowShape = listGroupShape(index, state.items.size)
                            val boundsModifier = with(sharedScope) {
                                Modifier.sharedBounds(
                                    rememberSharedContentState(announcementSharedKey(item.id)),
                                    animatedVisibilityScope = rowAnimatedScope,
                                    // ★ 容器变换用 RemeasureToBounds：行很窄、详情页是整屏，
                                    //   默认的 ScaleToBounds 会把详情页整体缩放（文字被拉伸变形）
                                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                                )
                            }
                            AnnouncementRow(
                                item = item,
                                unread = item.id !in readIds,
                                shape = rowShape,
                                onClick = { onOpen(item) },
                                modifier = boundsModifier
                            )
                        }
                        if (state.hasMore) {
                            item(key = "load-more") {
                                LoadMoreFooter(loading = state.loadingMore, onClick = onLoadMore)
                            }
                        }
                    }
                }

                // 首屏加载（刷新也算）：只有一条数据都没有时才占满页面
                state.loading || state.refreshing -> YunXLoading(
                    modifier = Modifier.align(Alignment.Center)
                )

                state.error != null -> AnnouncementErrorState(
                    message = state.error,
                    onRetry = onRefresh,
                    modifier = Modifier.align(Alignment.Center)
                )

                else -> AnnouncementEmptyState(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/** 单条公告行：置顶标签 + 未读小圆点 + 标题 + 摘要 + 发布者/时间/浏览量 + 右侧封面缩略图 */
@Composable
private fun AnnouncementRow(
    item: AnnouncementApi.Announcement,
    unread: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                if (item.isPinned || unread) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.isPinned) {
                            AnnouncementChip(
                                text = "置顶",
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        if (item.isPinned && unread) Spacer(modifier = Modifier.width(6.dp))
                        if (unread) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    // 未读加粗：一眼能看出哪几条还没看过（与顶栏红点同源）
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val publisher = item.publisher.name.ifBlank { item.author }
                    if (publisher.isNotBlank()) {
                        Text(
                            text = publisher,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    val time = relativeTime(item.effectiveMillis)
                    if (time.isNotBlank()) {
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (item.viewCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
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

            val cover = item.coverImage
            if (!cover.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(12.dp))
                RemoteImage(
                    url = cover,
                    contentDescription = null,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(72.dp)
                )
            }
        }
    }
}

/** 公告标签（置顶 / 最新）：与收藏页的平台标签同一套观感 */
@Composable
internal fun AnnouncementChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * 发布者头像：有直链就加载，没有则退回项目里既有的「首字色块」方案
 * （见 DriveScreen 的品牌头像，不额外引入占位图资源）。
 */
@Composable
internal fun AnnouncementAvatar(
    name: String,
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    if (!avatarUrl.isNullOrBlank()) {
        RemoteImage(
            url = avatarUrl,
            contentDescription = null,
            shape = CircleShape,
            modifier = modifier.size(size)
        )
        return
    }
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name.trim().take(1).ifEmpty { "公" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/** 翻页footer：加载中的小 spinner 用 CircularProgressIndicator（<24dp 的行内加载，见 ExpressiveLoading 的选型约定） */
@Composable
private fun LoadMoreFooter(loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            TextButton(onClick = onClick) { Text("加载更多") }
        }
    }
}

@Composable
private fun AnnouncementEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Campaign,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "暂无公告", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "有新公告时会在这里显示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AnnouncementErrorState(
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
