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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
 * 版面顺序：标题/发布者/时间 → **正文** → **横向图集** → 最后更新。
 * ★ 封面不再单独压在正文上面：它和图集里的正文图片合成同一条横向列表，封面排第一并打「封面」标签
 *   （见 [announcementGallery]），列表项点开是全屏看图（共享元素在 AnnouncementScreen 里注册）。
 *
 * 详情接口会让 viewCount +1，所以 ViewModel 里按 id 缓存，本页重组 / 返回再进都不会重复请求。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AnnouncementDetailPage(
    state: AnnouncementViewModel.DetailUiState,
    sharedScope: SharedTransitionScope,
    /** 详情页所在的 AnimatedVisibility 作用域：缩略图的共享元素以它为"可见性来源" */
    pageAnimatedScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    /** 点第几张图（下标对 [announcementGallery] 的顺序而言）→ 宿主打开全屏看图 */
    onImageClick: (Int) -> Unit,
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
                is AnnouncementViewModel.DetailUiState.Loaded -> AnnouncementDetailContent(
                    item = state.item,
                    sharedScope = sharedScope,
                    pageAnimatedScope = pageAnimatedScope,
                    onImageClick = onImageClick
                )
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AnnouncementDetailContent(
    item: AnnouncementApi.Announcement,
    sharedScope: SharedTransitionScope,
    pageAnimatedScope: AnimatedVisibilityScope,
    onImageClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 正文排版与 README 预览共用同一份紧凑字号（见 ui/components/MarkdownTypography.kt）
    val typography = remember { compactMarkdownTypography() }
    val publisher = item.publisher.name.ifBlank { item.author }
    // 图集：封面（若有）在前 + 正文图集，排在正文下面横向展示。纯数据拼装，开销可忽略，不必 remember
    val gallery = announcementGallery(item)

    // ★ 滚动位置按公告 id 归零：列表页连点两条公告时，详情页的组合槽位会被复用
    //   （返回动画还没播完就点下一条；命中详情缓存时也不会经过 Loading 分支，整棵 LazyColumn 原地换内容），
    //   不重置的话新公告会继承上一条的滚动位置，看起来像"内容错位/串了"。
    val detailListState = rememberLazyListState()
    LaunchedEffect(item.id) {
        detailListState.scrollToItem(0)
    }

    LazyColumn(
        state = detailListState,
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
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        item(key = "content") {
            val content = item.content
            if (content.isNullOrBlank()) {
                // 只有真的一张图都没有时才提示"没有正文"：纯图片公告（正文空、只有图集）不该出现这句话
                if (gallery.isEmpty()) {
                    Text(
                        text = "（本条公告没有正文）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Markdown(
                    content = content,
                    typography = typography,
                    imageTransformer = GitHubMarkdownImageTransformer
                )
            }
        }

        // 图集（封面 + 服务端 images）：排在正文**下面**，点开全屏看图（共享元素在 AnnouncementScreen 注册）
        if (gallery.isNotEmpty()) {
            item(key = "gallery") {
                Column {
                    Spacer(modifier = Modifier.height(20.dp))
                    if (gallery.size == 1) {
                        // 只有一张（最常见的是「有封面、没有正文图」）：按原图比例铺满宽度、不裁切 ——
                        // 唯一一张图缩成小方块会白丢信息；点开仍然能全屏看
                        AnnouncementGalleryImage(
                            announcementId = item.id,
                            index = 0,
                            image = gallery[0],
                            hero = true,
                            sharedScope = sharedScope,
                            animatedScope = pageAnimatedScope,
                            onClick = { onImageClick(0) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "图片",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${gallery.size} 张",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        // 多张才横向排列：封面固定在第一张，左右滑动翻看正文配图
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(
                                items = gallery,
                                key = { index, image -> "gallery-$index-${image.url}" }
                            ) { index, image ->
                                AnnouncementGalleryImage(
                                    announcementId = item.id,
                                    index = index,
                                    image = image,
                                    hero = false,
                                    sharedScope = sharedScope,
                                    animatedScope = pageAnimatedScope,
                                    onClick = { onImageClick(index) },
                                    modifier = Modifier.size(GalleryThumbSize)
                                )
                            }
                        }
                    }
                }
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

/**
 * 图集里的一张图。[isCover] = 服务端封面（`coverImage`），在列表里固定排第一并打「封面」标签。
 *
 * 接口只给了两块图片数据（封面 `coverImage` + 正文图集 `images`），没有统一图片数组，
 * 所以"封面也算图集的一张"这件事只能在前端拼（见 [announcementGallery]）。
 */
internal data class AnnouncementImage(
    val url: String,
    val isCover: Boolean = false
)

/**
 * 把一条公告的两处图片拼成展示用图集：**封面在前** → 正文图集按服务端顺序；
 * 空串过滤掉，封面同时出现在 `images` 里时去重（否则详情页会出现两张一模一样的图）。
 *
 * 顺序即"第几张"的口径：全屏看图的下标、共享元素 key 的下标都用它的下标，两处必须用同一个函数取。
 */
internal fun announcementGallery(item: AnnouncementApi.Announcement): List<AnnouncementImage> {
    val cover = item.coverImage?.trim().orEmpty()
    val gallery = ArrayList<AnnouncementImage>(item.images.size + 1)
    if (cover.isNotEmpty()) {
        gallery += AnnouncementImage(url = cover, isCover = true)
    }
    item.images.forEach { raw ->
        val url = raw.trim()
        if (url.isNotEmpty() && url != cover) {
            gallery += AnnouncementImage(url = url)
        }
    }
    return gallery
}

/** 图集缩略图边长（正方形裁切，行内高度一致，横向滑动时观感整齐） */
private val GalleryThumbSize: Dp = 132.dp

/**
 * 图集里的一张图，两种排布共用同一套共享元素（**源**侧：点开时长成全屏看图那整张图）：
 * - `hero = true`：**单图**，`modifier` 传 `fillMaxWidth()`，按原图比例铺满宽度、不裁切；
 * - `hero = false`：**横向列表里的缩略图**，`modifier` 传 `Modifier.size(132.dp)`，正方形 `Crop`。
 *
 * ★ 两层 Box 的分工（`RemeasureToBounds` 的硬要求，写错了动画不跟手）：
 * - **外层**给尺寸（定尺寸 / 定宽）：共享元素"尺寸由约束决定" —— [SharedTransitionScope.sharedBounds]
 *   在形变时会用**动画中的尺寸**重新测量内容，内容写死尺寸就不会跟着放大；
 *   同时外层把布局尺寸钉住，形变期间列表不会因为某一项"变大"而抖动（hero 走 `autoHeight`，
 *   高度由位图比例算，同样随约束走）。
 * - **内层** `fillMaxSize` / `fillMaxWidth` + 圆角 + 点击：真正参与共享元素的内容，尺寸永远等于当前约束。
 *
 * ★ 共享元素修饰符在这里构造（不在调用处）：`rememberSharedContentState` 是 @Composable，
 *   只能在 composable 作用域里调用；两种排布共用一处，下标口径不会各写一套。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AnnouncementGalleryImage(
    announcementId: String,
    index: Int,
    image: AnnouncementImage,
    /** true = 单图大图（铺满宽度、不裁切），false = 横向列表里的方形缩略图 */
    hero: Boolean,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = if (hero) MaterialTheme.shapes.large else MaterialTheme.shapes.medium
    val boundsModifier = with(sharedScope) {
        Modifier.sharedBounds(
            rememberSharedContentState(announcementImageSharedKey(announcementId, index)),
            animatedVisibilityScope = animatedScope,
            // 源侧是窄条 / 小方框、目标是整屏，必须按目标尺寸重新测量（默认的 ScaleToBounds 会拉伸内容）
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
        )
    }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .then(if (hero) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .then(boundsModifier)
                .clip(shape)
                .clickable(onClick = onClick)
        ) {
            RemoteImage(
                url = image.url,
                contentDescription = if (image.isCover) "封面图片" else "公告图片",
                shape = shape,
                // 横向列表里的缩略图统一 Crop 成方块（原图比例差异很大，Fit 会让行高参差不齐）；
                // 单图（hero）铺满宽度、按原始比例完整显示
                contentScale = if (hero) ContentScale.Fit else ContentScale.Crop,
                autoHeight = hero,
                modifier = if (hero) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
            )
            if (image.isCover) {
                AnnouncementChip(
                    text = "封面",
                    // 标签压在图片上：用实心 primary 而不是容器色，保证任何底图上都看得清
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(if (hero) 8.dp else 6.dp)
                )
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
