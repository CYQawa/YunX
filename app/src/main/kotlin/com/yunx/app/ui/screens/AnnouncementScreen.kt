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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.announcement.AnnouncementApi
import com.yunx.app.data.announcement.relativeTime
import com.yunx.app.ui.components.RemoteImage
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.viewmodel.AnnouncementViewModel

/** 公告的共享元素 key：列表项（源）与详情页容器（目标）必须用同一个 key，形变才会发生 */
internal fun announcementSharedKey(id: String): String = "announcement-item-$id"

/**
 * 公告页宿主（全屏叠加页）：**列表 ↔ 详情**的容器变换在这里做。
 *
 * 两层共享元素各管一段，互不干扰：
 * - 外层（MainScreen）：顶栏公告图标 ↔ 公告整页，key = `OVERLAY_KEY_ANNOUNCEMENTS`；
 * - 内层（本文件）：列表项 ↔ 详情页，key = [announcementSharedKey]，所以这里再套一层
 *   [SharedTransitionLayout] —— 共享元素只在**同一个** layout 作用域内匹配，套一层就天然隔离了。
 *
 * 与 MainScreen 同一套「两个 AnimatedVisibility 互斥」写法（不是 AnimatedContent）：
 * 详情页打开时列表会被真的移出组合，退出时靠 [shownDetailId] 延迟清空，保证回收形变有内容可渲染。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AnnouncementScreen(
    viewModel: AnnouncementViewModel,
    onBack: () -> Unit,
    /** 从启动弹窗「查看详情」直接进详情页时带进来的公告 id（null = 先看列表） */
    initialDetailId: String? = null,
    modifier: Modifier = Modifier
) {
    // 当前是否在详情页（null = 列表页）
    var detailId by rememberSaveable { mutableStateOf(initialDetailId) }
    // 正在展示的详情 id：关闭时**保留**，退出动画要用它渲染详情页（同 MainScreen 的 shownRoute 手法）
    var shownDetailId by rememberSaveable { mutableStateOf(initialDetailId) }
    LaunchedEffect(detailId) {
        if (detailId != null) shownDetailId = detailId
    }
    // 打开页面就有数据：启动检查成功过就直接用那份，不会再请求一次
    LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    // 进入详情页才请求详情（详情接口会让浏览量 +1，ViewModel 里按 id 缓存，同一会话只请求一次）
    LaunchedEffect(detailId) { detailId?.let { viewModel.openDetail(it) } }

    // 返回键：详情页 → 列表页；列表页 → 关闭公告页（交回主界面）
    BackHandler {
        if (detailId != null) detailId = null else onBack()
    }

    val listState by viewModel.list.collectAsState()
    val detailState by viewModel.detail.collectAsState()
    val readIds by viewModel.readIds.collectAsState()

    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        val innerScope = this

        AnimatedVisibility(
            visible = detailId == null,
            enter = fadeIn(effectsDefault()),
            // ★ 退出时长必须 ≥ sharedBounds 形变的时长（默认 bounds 弹簧约 300ms）：
            //   退出一结束内容就被移出组合，回收形变会被截断（观感像没做动画）。
            exit = fadeOut(tween(durationMillis = 300))
        ) {
            val listScope = this
            AnnouncementListPage(
                state = listState,
                readIds = readIds,
                sharedScope = innerScope,
                rowAnimatedScope = listScope,
                onBack = onBack,
                onRefresh = { viewModel.refresh() },
                onLoadMore = { viewModel.loadMore() },
                onMarkAllRead = { viewModel.markAllRead() },
                onOpen = { detailId = it.id }
            )
        }

        AnimatedVisibility(
            visible = shownDetailId != null,
            enter = fadeIn(effectsDefault()),
            exit = fadeOut(tween(durationMillis = 300))
        ) {
            val detailScope = this
            val id = shownDetailId
            if (id != null) {
                val boundsModifier = with(innerScope) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(announcementSharedKey(id)),
                        animatedVisibilityScope = detailScope,
                        // ★ 容器变换必须用 RemeasureToBounds：列表项很窄、详情页是整屏，
                        //   默认的 ScaleToBounds 会把详情页整体缩放（文字被拉伸），
                        //   按目标尺寸重新测量才是「这一行长成了整页」。
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                    )
                }
                AnnouncementDetailPage(
                    state = detailState,
                    onBack = { detailId = null },
                    onRetry = { viewModel.retryDetail() },
                    modifier = boundsModifier
                )
            }
        }
    }
}

/**
 * 顶栏公告入口的未读红点角标（>99 显示 `99+`）。
 *
 * 为什么不用 material3 的 `BadgedBox`：那颗角标要叠在 24dp 图标上、自己控制偏移与最小尺寸，
 * 这里用 Surface + Text 手搓，样式完全可控（也免得跟着 alpha 版的组件 API 走）。
 */
@Composable
internal fun AnnouncementUnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * 启动弹窗：展示「未读的置顶公告」，没有置顶则展示「最新的一条未读」（候选逻辑见
 * `AnnouncementViewModel.pickPopupCandidate`）。
 *
 * 用 AlertDialog 而不是底部弹窗：它需要被明确看到（置顶公告相当于官方通知），
 * 也与「发现新版本」那套底部 UpdateSheet 在观感上区分开。
 * 两个出口都算已读：点「查看详情」进详情页、点「知道了」/ 点空白关掉。
 */
@Composable
fun AnnouncementPopupDialog(
    announcement: AnnouncementApi.Announcement,
    onDetail: () -> Unit,
    onDismiss: () -> Unit
) {
    val publisher = announcement.publisher.name.ifBlank { announcement.author }
    val meta = listOfNotNull(
        publisher.takeIf { it.isNotBlank() },
        relativeTime(announcement.effectiveMillis).takeIf { it.isNotBlank() }
    ).joinToString(" · ")

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            AnnouncementChip(
                text = if (announcement.isPinned) "置顶公告" else "最新公告",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        },
        title = {
            Text(
                text = announcement.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                val cover = announcement.coverImage
                if (!cover.isNullOrBlank()) {
                    // 弹窗高度有限：封面按比例铺满宽度，但最多 180dp，超出部分由 Fit 留白
                    RemoteImage(
                        url = cover,
                        contentDescription = null,
                        shape = MaterialTheme.shapes.medium,
                        contentScale = ContentScale.Fit,
                        autoHeight = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (announcement.summary.isNotBlank()) {
                    Text(
                        text = announcement.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDetail) { Text("查看详情") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}
