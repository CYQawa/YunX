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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.data.network.video.VideoParseResult
import com.yunx.app.ui.items.MultiSelectAction
import com.yunx.app.ui.items.MultiSelectBar
import com.yunx.app.ui.items.logoRes
import com.yunx.app.ui.preview.MediaPlayerPane
import com.yunx.app.ui.preview.PreviewKind
import com.yunx.app.ui.preview.PreviewLauncher
import com.yunx.app.ui.preview.PreviewMedia
import com.yunx.app.ui.preview.previewNetworkHeaders
import com.yunx.app.ui.resolve.formatSize
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.listGroupShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 图集每页展示的张数（两列 → 每页 5 行） */
private const val IMAGE_PAGE_SIZE = 10

/**
 * 视频/图文解析结果区：平台信息 + 作品描述 + 媒体列表（或图集网格）+ 一键下载。
 *
 * 由解析页（[ResolveScreen]）在 `ResolveUiState.Video` 状态下直接内嵌，不是全屏覆盖层，
 * 因此用普通 Column 跟随解析页现有布局；但仍自带不透明 [Surface] 底色，
 * 避免深色模式下半透明背景透出后面内容发黑。
 *
 * ★ **图片笔记直接铺两列缩略图**（不再是「一条图片一行 + 眼睛图标」）：图集的价值就是「看」，
 *   逼用户挨个点小眼睛才看得到图是没道理的。缩略图带解析时算好的请求头（[VideoMedia.headers]），
 *   点任意一张进全屏预览 —— 那里能左右翻页、能双指放大，顶栏有「下载」。
 *   **图片过多按页展示**（每页 [IMAGE_PAGE_SIZE] 张），否则一屏几百张、滚动条直接拉爆。
 *
 * ★ 「全部下载」固定在底部：它以前跟在媒体列表最后，图集一长就得先滚到底才够得着。
 */
@Composable
fun VideoResultSection(
    result: VideoParseResult,
    onDownloadMedia: (VideoMedia) -> Unit,
    onDownloadAll: () -> Unit,
    onDownloadSelected: (List<VideoMedia>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 空直链的图片既预览不了也下载不了：直接不算数，否则格子里是个灰块、还会把下标算歪
    val images = remember(result) {
        result.medias.filter { it.kind == VideoMediaKind.IMAGE && it.url.isNotBlank() }
    }
    // 全是图片 = 图片笔记，走网格；只要含视频/音频就仍按行展示（清晰度/大小得用文字说明）
    val isImageNote = images.isNotEmpty() && result.medias.none { it.kind != VideoMediaKind.IMAGE }

    // 图集多选（长按缩略图进入）。用 **url 当键**而不是下标：切页、以及同 url 的重复项都不会串选
    var selectionMode by remember(result) { mutableStateOf(false) }
    var selectedUrls by remember(result) { mutableStateOf(emptySet<String>()) }
    val exitSelection: () -> Unit = {
        selectionMode = false
        selectedUrls = emptySet()
    }
    // 多选时系统返回键先退出多选，而不是直接离开解析结果页
    BackHandler(enabled = selectionMode) { exitSelection() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        // 外层 Box：多选的悬浮操作栏要叠在内容之上（见 MultiSelectBar）
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectionMode) {
                    ImageSelectionTopBar(
                        selectedCount = selectedUrls.size,
                        allSelected = selectedUrls.size == images.size,
                        onExit = exitSelection,
                        onToggleSelectAll = {
                            selectedUrls = if (selectedUrls.size == images.size) {
                                emptySet()
                            } else {
                                images.map { it.url }.toSet()
                            }
                        }
                    )
                } else {
                    VideoResultTopBar(result = result, onBack = onBack)
                }

                if (isImageNote) {
                    ImageNoteContent(
                        result = result,
                        images = images,
                        selectionMode = selectionMode,
                        selectedUrls = selectedUrls,
                        onOpenImage = { clicked -> openMediaPreview(result, images, clicked, onDownloadMedia) },
                        onToggleSelect = { media ->
                            selectedUrls = if (media.url in selectedUrls) {
                                selectedUrls - media.url
                            } else {
                                selectedUrls + media.url
                            }
                        },
                        onEnterSelection = { media ->
                            selectionMode = true
                            selectedUrls = selectedUrls + media.url
                        },
                        onDownloadImage = onDownloadMedia,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        // 与网盘页一致：媒体各行首尾相接成一组，间距用统一发丝缝
                        verticalArrangement = Arrangement.spacedBy(ListGroupGap)
                    ) {
                        item { VideoMetaBlock(result) }

                        // 视频结果：**直接把第一条视频内联出来** —— 和图片笔记直接铺图同一个道理，
                        // 不必先点小眼睛才知道里面是什么。默认 `active = false`（不自动播放）：
                        // 一进页面就出声很唐突，用户点播放按钮才播。
                        firstVideoOf(result)?.let { video ->
                            item(key = "inline_video") {
                                InlineVideoPlayer(
                                    media = video.toPreviewMedia(result.title),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }

                        itemsIndexed(result.medias) { index, media ->
                            VideoMediaRow(
                                index = index,
                                count = result.medias.size,
                                media = media,
                                onDownload = { onDownloadMedia(media) },
                                // 预览：入口直接交给全局预览页（与当前页面无关的一次性动作，不用层层传回调）
                                onPreview = { openMediaPreview(result, listOf(media), media, onDownloadMedia) }
                            )
                        }
                    }
                }

                // 多选时不显示「全部下载」：底部让位给 MultiSelectBar 的「下载所选」
                if (!selectionMode) {
                    DownloadAllBar(result = result, onDownloadAll = onDownloadAll)
                }
            }

            if (selectionMode) {
                MultiSelectBar(
                    count = selectedUrls.size,
                    actions = listOf(
                        MultiSelectAction(
                            label = "下载所选",
                            icon = Icons.Outlined.Download,
                            tint = MaterialTheme.colorScheme.primary
                        ) {
                            // 走批量入口：多张会在下载页收纳成一个可折叠的文件夹组（见 downloadVideoMedias）
                            onDownloadSelected(images.filter { it.url in selectedUrls })
                            exitSelection()
                        }
                    )
                )
            }
        }
    }
}

/** 顶栏：返回 + 平台徽标 + 平台名 + 标题（固定不滚动，返回入口始终可见） */
@Composable
private fun VideoResultTopBar(result: VideoParseResult, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        // 平台 Logo：按图标自身形状原样画（不裁圆、不垫底色，理由见 PlatformLogos 的 KDoc）
        Image(
            painter = painterResource(result.platform.logoRes()),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.platform.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = result.title.ifBlank { "视频内容" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 多选态的顶栏：结构照搬网盘多选（`ShareDetailScreen`）—— 返回键变成「退出多选」，
 * 标题换成「已选 N 张」，右侧给「全选 / 取消全选」。
 *
 * ★ 与网盘保持同一套措辞与位置：用户在多选这件事上不该学两遍。
 */
@Composable
private fun ImageSelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onToggleSelectAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onExit) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出多选")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "已选 $selectedCount 张",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (allSelected) "已全选" else "点击图片继续选择",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onToggleSelectAll) {
            Text(if (allSelected) "取消全选" else "全选")
        }
    }
}

/**
 * 图片笔记内容：作品信息 + （多页时）分页条 + 两列缩略图网格。
 *
 * 分页条固定在网格上方而不是塞进网格首项：翻页控件必须**始终够得着**，
 * 不然第二页以后要滚回顶部才能翻回去。
 */
@Composable
private fun ImageNoteContent(
    result: VideoParseResult,
    images: List<VideoMedia>,
    selectionMode: Boolean,
    selectedUrls: Set<String>,
    onOpenImage: (VideoMedia) -> Unit,
    onToggleSelect: (VideoMedia) -> Unit,
    onEnterSelection: (VideoMedia) -> Unit,
    onDownloadImage: (VideoMedia) -> Unit,
    modifier: Modifier = Modifier
) {
    val pageCount = (images.size + IMAGE_PAGE_SIZE - 1) / IMAGE_PAGE_SIZE
    // 以 images 为 key：换了笔记（新解析结果）就回到第一页，不留在上一个笔记的页码上
    var page by remember(images) { mutableStateOf(0) }
    val pageImages = remember(page, images) {
        images.drop(page * IMAGE_PAGE_SIZE).take(IMAGE_PAGE_SIZE)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        VideoMetaBlock(
            result = result,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
        )
        if (pageCount > 1) {
            ImagePagerBar(
                page = page,
                pageCount = pageCount,
                total = images.size,
                onPageChange = { page = it }
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            // 多选时底部留出悬浮操作栏的高度，否则最后一行会被压在工具栏下面
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = if (selectionMode) 96.dp else 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(count = pageImages.size, key = { pageImages[it].url }) { i ->
                val media = pageImages[i]
                ImageGridCell(
                    media = media,
                    // 角标用**全局序号**（不是页内序号），否则第二页又从 1/9 开始数
                    index = page * IMAGE_PAGE_SIZE + i,
                    total = images.size,
                    selectionMode = selectionMode,
                    selected = media.url in selectedUrls,
                    // 多选态下点格子是「选中/取消」，平时是「打开大图」
                    onClick = { if (selectionMode) onToggleSelect(media) else onOpenImage(media) },
                    onLongClick = { onEnterSelection(media) },
                    onDownload = { onDownloadImage(media) }
                )
            }
        }
    }
}

/** 图集分页条：`共 N 张 · 第 1/3 页` + 上一页 / 下一页 */
@Composable
private fun ImagePagerBar(page: Int, pageCount: Int, total: Int, onPageChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "共 $total 张 · 第 ${page + 1}/$pageCount 页",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onPageChange(page - 1) }, enabled = page > 0) { Text("上一页") }
        TextButton(onClick = { onPageChange(page + 1) }, enabled = page < pageCount - 1) { Text("下一页") }
    }
}

/**
 * 图集缩略图：1:1 裁剪 + 左上角「3/9」角标 + 右下角下载按钮；**长按进入多选**。
 *
 * ★ 请求头必须和全屏大图**完全一致**（同一份 [VideoMedia.headers]）：图集 CDN 校验 Referer/Cookie，
 *   缩略图漏带就会是一片灰块 —— 而点开大图又能看，这种「只有网格是坏的」最难排查。
 * ★ 多选态下右下角的下载按钮让位给勾选框：此时点格子是「选中/取消」，再摆个下载按钮只会误触
 *   （要下多张走底部工具栏的「下载所选」）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGridCell(
    media: VideoMedia,
    index: Int,
    total: Int,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    val request = remember(context, media.url, media.headers) {
        ImageRequest.Builder(context)
            .data(media.url)
            .httpHeaders(previewNetworkHeaders(media.headers))
            .build()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Box {
            AsyncImage(
                model = request,
                contentDescription = "第 ${index + 1} 张图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // 选中标记：压一层主色薄纱。亮底图片上只画勾选框很容易看不出来
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
                )
            }

            CellBadge(
                text = "${index + 1}/$total",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            )

            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    // null = 不接管点击：整格的 onClick 负责切换选中，避免点勾选框有两条路径
                    onCheckedChange = null,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            } else {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(34.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = "下载这一张",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** 缩略图角标（半透明胶囊）：底色用它而不是纯色，压在明暗不一的图片上都读得清 */
@Composable
private fun CellBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 底部固定的「全部下载」：列表与网格两种布局共用同一个入口 */
@Composable
private fun DownloadAllBar(result: VideoParseResult, onDownloadAll: () -> Unit) {
    // 没有媒体时不占位（空结果通常意味着解析没拿到直链）
    if (result.medias.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Button(
            onClick = onDownloadAll,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(48.dp)
        ) {
            Text("全部下载（${result.medias.size} 项）")
        }
    }
}

/** 作品信息块：作者、发布时间、正文（超长折叠，点击展开） */
@Composable
private fun VideoMetaBlock(result: VideoParseResult, modifier: Modifier = Modifier) {
    // 元信息行：作者 / 发布时间（时间戳为 0 表示未知，不显示）
    val metaLine = buildList {
        if (result.author.isNotBlank()) add(result.author)
        val date = formatVideoDate(result.timestampSeconds)
        if (date.isNotBlank()) add(date)
        if (result.medias.isNotEmpty()) add("共 ${result.medias.size} 项")
    }.joinToString("  ·  ")

    Column(modifier = modifier.padding(bottom = 12.dp)) {
        if (metaLine.isNotBlank()) {
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (result.text.isNotBlank()) {
            var expanded by remember(result.text) { mutableStateOf(false) }
            val canExpand = result.text.length > 60
            Text(
                text = result.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = if (metaLine.isNotBlank()) 6.dp else 0.dp)
                    .clickable(enabled = canExpand) { expanded = !expanded }
            )
            if (canExpand) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { expanded = !expanded }
                )
            }
        }
    }
}

/** 结果里的第一条视频（内联播放器只放一条；多条视频时其余仍在下面的列表里，各自可预览） */
private fun firstVideoOf(result: VideoParseResult): VideoMedia? =
    result.medias.firstOrNull { it.kind == VideoMediaKind.VIDEO && it.url.isNotBlank() }

/**
 * 内联视频播放器：结果页里直接放一个播放器，**默认停住不播**（`active = false`）。
 *
 * ★ 高度按 16:9 固定：结果页在滚动容器里，播放器必须有确定高度；实际画面按内容等比缩放（留黑边），
 *   不猜视频的真实比例。
 * ★ 不给全屏按钮（`allowFullscreen = false`）：全屏要宿主页面配合，内联场景没有对应 UI；
 *   要看全屏走上面那条媒体行的「预览」进全屏预览页。
 */
@Composable
private fun InlineVideoPlayer(media: PreviewMedia, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.large),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        MediaPlayerPane(
            media = media,
            active = false,
            onFullscreenChanged = {},
            allowFullscreen = false,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** 单条媒体行：序号 + 类型 + 清晰度 + 大小，右侧「预览」图标 + 「下载」 */
@Composable
private fun VideoMediaRow(
    index: Int,
    count: Int,
    media: VideoMedia,
    onDownload: () -> Unit,
    onPreview: () -> Unit
) {
    val subtitle = buildList {
        if (media.quality.isNotBlank()) add(media.quality)
        // size <= 0 表示未知：不显示大小，而不是显示 "0 B"
        if (media.size > 0) add(formatSize(media.size))
    }.joinToString("  ·  ")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = listGroupShape(index, count),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = "${index + 1}. ${videoMediaKindLabel(media.kind)}",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 预览：图片/视频/音频都能直接在线看，不必先下载
                    IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Visibility,
                            contentDescription = "预览",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    TextButton(onClick = onDownload) {
                        Text("下载")
                    }
                }
            },
            // 底色由外层 Card 决定，这里必须透明
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

/** 媒体类型中文标签 */
private fun videoMediaKindLabel(kind: VideoMediaKind): String = when (kind) {
    VideoMediaKind.VIDEO -> "视频"
    VideoMediaKind.IMAGE -> "图片"
    VideoMediaKind.AUDIO -> "音频"
}

/** 发布时间戳（秒）→ `yyyy-MM-dd`；为 0 或非法时返回空串（调用方据此不显示） */
private fun formatVideoDate(timestampSeconds: Long): String {
    if (timestampSeconds <= 0L) return ""
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampSeconds * 1000L))
    }.getOrDefault("")
}

/**
 * 点「预览」/点缩略图：把媒体转成预览条目交给全局预览页（[PreviewLauncher]）。
 *
 * ★ [group] 是**可左右翻页的一组**（图集传全部图片，视频/音频只传自己），[clicked] 是用户点的那个；
 *   `startIndex` 用**引用相等**在组内定位，即便组里有同 url 的重复项也不会翻错。
 * ★ [onDownloadMedia] 经预览页「下载」按钮回传当前页下标后再换回原始 [VideoMedia] ——
 *   于是预览页不必认识解析结果的数据模型（见 `PreviewRequest.onDownload`）。
 */
private fun openMediaPreview(
    result: VideoParseResult,
    group: List<VideoMedia>,
    clicked: VideoMedia,
    onDownloadMedia: (VideoMedia) -> Unit
) {
    // 先滤掉空直链：PreviewLauncher 内部还会再滤一次，两边滤的结果不一致就会把下标错位到别的图上
    val source = group.filter { it.url.isNotBlank() }
    if (source.isEmpty()) return
    val startIndex = source.indexOfFirst { it === clicked }
    PreviewLauncher.openMedia(
        items = source.map { it.toPreviewMedia(result.title) },
        startIndex = if (startIndex >= 0) startIndex else 0,
        title = result.title.ifBlank { clicked.fileName },
        onDownload = { index -> source.getOrNull(index)?.let(onDownloadMedia) }
    )
}

/** VideoMedia → PreviewMedia：类型映射 + 请求头 / HLS 透传（标题缺失时回退作品标题） */
private fun VideoMedia.toPreviewMedia(workTitle: String): PreviewMedia = PreviewMedia(
    url = url,
    kind = when (kind) {
        VideoMediaKind.VIDEO -> PreviewKind.VIDEO
        VideoMediaKind.IMAGE -> PreviewKind.IMAGE
        VideoMediaKind.AUDIO -> PreviewKind.AUDIO
    },
    title = fileName.ifBlank { workTitle },
    headers = headers,
    isHls = isHls,
    // B站 DASH 分轨：把合并音轨一起交给预览页，由它提示「预览只有画面，下载后才会合轨」
    audioUrl = audioUrl,
    audioHeaders = audioHeaders
)
