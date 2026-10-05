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
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.announcement.AnnouncementApi
import com.yunx.app.data.announcement.relativeTime
import com.yunx.app.ui.components.RemoteImage
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.viewmodel.AnnouncementViewModel
import kotlinx.coroutines.launch

/** 公告的共享元素 key：列表项（源）与详情页容器（目标）必须用同一个 key，形变才会发生 */
internal fun announcementSharedKey(id: String): String = "announcement-item-$id"

/**
 * 公告**图集**的共享元素 key：详情页缩略图（源）↔ 全屏看图（目标）。
 * 用公告 id + 图集下标（口径见 `announcementGallery`），保证同一个 layout 作用域里不撞 key。
 */
internal fun announcementImageSharedKey(announcementId: String, index: Int): String =
    "announcement-image-$announcementId-$index"

/**
 * 公告页宿主（全屏叠加页）：**列表 ↔ 详情 ↔ 全屏看图**的容器变换都在这里做。
 *
 * 共享元素分三层，key 前缀各不相同，互不干扰：
 * - 外层（MainScreen）：顶栏公告图标 ↔ 公告整页，key = `OVERLAY_KEY_ANNOUNCEMENTS`；
 * - 内层（本文件）：列表项 ↔ 详情页，key = [announcementSharedKey]，所以这里再套一层
 *   [SharedTransitionLayout] —— 共享元素只在**同一个** layout 作用域内匹配，套一层就天然隔离了；
 * - 再往下（同一个内层作用域里）：详情页缩略图 ↔ 全屏看图，key = [announcementImageSharedKey]。
 *   源在详情页那个 AnimatedVisibility 里、目标在看图页那个 AnimatedVisibility 里，同作用域即可匹配。
 *
 * 与 MainScreen 同一套「多个 AnimatedVisibility 互斥」写法（不是 AnimatedContent）：
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
    // 全屏看图：正在看第几张（-1 = 没在看）。shownViewerIndex 同上，退出动画期间保留
    var viewerIndex by rememberSaveable { mutableStateOf(-1) }
    var shownViewerIndex by rememberSaveable { mutableStateOf(-1) }
    // 打开页面就有数据：启动检查成功过就直接用那份，不会再请求一次
    LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    // 进入详情页才请求详情（详情接口会让浏览量 +1，ViewModel 里按 id 缓存，同一会话只请求一次）
    LaunchedEffect(detailId) { detailId?.let { viewModel.openDetail(it) } }

    // 返回键：全屏看图 → 详情页 → 列表页 → 关闭公告页（交回主界面），从里到外一层层退
    BackHandler {
        when {
            viewerIndex >= 0 -> viewerIndex = -1
            detailId != null -> detailId = null
            else -> onBack()
        }
    }

    val listState by viewModel.list.collectAsState()
    val detailState by viewModel.detail.collectAsState()
    val readIds by viewModel.readIds.collectAsState()

    // 图集只跟"当前这条详情"有关：详情状态里取，顺序与详情页一致（announcementGallery）
    val viewerItem = (detailState as? AnnouncementViewModel.DetailUiState.Loaded)?.item
    val viewerGallery = viewerItem?.let { announcementGallery(it) }.orEmpty()
    val viewingImage = viewerGallery.getOrNull(shownViewerIndex)
    // 可见性用真实状态 viewerIndex（同 detailId 的理由：shown 系列永远不清空，不能当 visible）
    val viewerVisible = viewerIndex in viewerGallery.indices

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
                // ★ 两个状态一起写：detailId 决定"去详情页"，shownDetailId 决定"渲染哪一条"。
                //   这里同步写死 shownDetailId，详情页在被打开的第一帧就能用上正确的共享元素 key
                //   （只靠下面那个 LaunchedEffect 会晚一帧，首帧等于没有形变目标）。
                onOpen = {
                    detailId = it.id
                    shownDetailId = it.id
                }
            )
        }

        AnimatedVisibility(
            // ★★ 可见性必须用**真实状态** detailId，绝不能用 shownDetailId：
            //   shownDetailId 只是「退出动画期间还要渲染哪一页」的记忆，它**永远不会被清空**
            //   （清空了退出时就没内容可渲染，回收形变会断）。拿它当 visible 会让详情页根本关不掉：
            //   点返回 → detailId 变 null → 列表淡入、详情却依然"可见" ⇒ 动画一结束又闪回详情页；
            //   而此时 detailId 已经是 null，详情页那个返回按钮再点就是空操作 ⇒ 看起来"按钮点不动了"。
            //   MainScreen 的叠加页是同一个道理：visible 用 overlayRoute != null，shownRoute 只用来取内容。
            visible = detailId != null,
            enter = fadeIn(effectsDefault()),
            exit = fadeOut(tween(durationMillis = 300))
        ) {
            val detailScope = this
            // 内容取 shownDetailId：退出期间它仍是刚关掉的那条，页面才不会瞬间变空
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
                    sharedScope = innerScope,
                    // ★ 给真实状态 viewerIndex（不是 shownViewerIndex）：缩略图的进出场要跟着"开关"走，
                    //   它一变 false，共享元素系统才会把缩略图判成 outgoing 一侧（见 AnnouncementGalleryImage）
                    openImageIndex = viewerIndex,
                    onBack = { detailId = null },
                    onRetry = { viewModel.retryDetail() },
                    // 详情页右上角刷新：强制重拉这一条（缓存一并作废）
                    onRefresh = { viewModel.reloadCurrentDetail() },
                    // ★ 两个状态一起写：viewerIndex 决定"去看图"，shownViewerIndex 决定"看哪一张"。
                    //   和详情页同理 —— 只靠 LaunchedEffect 会晚一帧，第一帧就没有形变目标。
                    onImageClick = { index ->
                        viewerIndex = index
                        shownViewerIndex = index
                    },
                    modifier = boundsModifier
                )
            }
        }

        // ★ 第三层共享元素（详情页内）：缩略图 ↔ 全屏看图，key = announcementImageSharedKey(...)
        //   和上面两层用的是不同 key 前缀，同一个 layout 作用域里不会互相抢匹配；
        //   整页黑底 + fillMaxSize 让它在最上面，且完全不透明 —— 形变结束后下层那张缩略图被彻底盖住，
        //   不会出现"缩略图跟着一起动"的重影。
        AnimatedVisibility(
            visible = viewerVisible,
            enter = fadeIn(effectsDefault()),
            // 同样 ≥ 300ms：退出一结束内容就被移出组合，回收形变会被截断（同列表/详情那一层）
            exit = fadeOut(tween(durationMillis = 300))
        ) {
            val viewerScope = this
            if (viewingImage != null && viewerItem != null) {
                val imageBoundsModifier = with(innerScope) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(
                            announcementImageSharedKey(viewerItem.id, shownViewerIndex)
                        ),
                        animatedVisibilityScope = viewerScope,
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                    )
                }
                AnnouncementImageViewerPage(
                    url = viewingImage.url,
                    isCover = viewingImage.isCover,
                    index = shownViewerIndex,
                    total = viewerGallery.size,
                    boundsModifier = imageBoundsModifier,
                    onClose = { viewerIndex = -1 }
                )
            }
        }
    }
}

/**
 * 全屏看图页（共享元素的**目标**侧：从被点的缩略图长成整张图）。
 *
 * 黑底不透明 + 图片按比例 Fit 居中；**双指缩放 / 拖动 / 双击放大**都支持（自研手势，不引第三方库，
 * 见下面的推导注释）；点左上角关闭、返回键关闭（返回键由宿主的 BackHandler 管）。
 *
 * 手势口径（和主流看图页一致，避免"点了没反应"）：
 * - 未放大时单击 = 关闭；已放大时单击 = 复位（再点一次才关）；
 * - 双击在 1x 与 [ViewerDoubleTapScale] 之间切换（带动画）；
 * - 双指缩放以**双指中心**为锚点，范围 [MinViewerScale] ~ [MaxViewerScale]；
 *   放大后单指拖动平移，平移量按容器边界夹住，图不会被拖出屏幕。
 *
 * ★ 刻意不做左右滑动翻页：翻页会让"当前这张"的共享元素 key 每滑一次就换一个，
 *   源（缩略图）和目标在多个下标之间反复重新匹配，最容易抖/串图；
 *   这里保持"一次看一张、关掉再点下一张"，整个进出过程源和目标始终是同一条，动画最稳。
 * ★ 黑底必须是**不透明**的：[boundsModifier] 让形变结束后缩略图那一份其实也被放大到整屏
 *   （共享元素两边会互相同步边界），不透明底把它彻底盖住，否则会透出重影。
 * ★ 缩放/平移只能加在共享元素**内部**（`boundsModifier.fillMaxSize().graphicsLayer(...)`）：
 *   加在外面会和共享元素自己的形变打架（形变本来就是靠 layer 位移+缩放实现的）。
 */
@Composable
private fun AnnouncementImageViewerPage(
    url: String,
    isCover: Boolean,
    index: Int,
    total: Int,
    boundsModifier: Modifier,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 缩放 / 平移状态：换图（url 变）时归零，避免上一张的缩放带到下一张
    var scale by remember(url) { mutableFloatStateOf(MinViewerScale) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    /** 把平移量夹在容器边界内（放大后图始终铺满可视区，不会拖出黑边） */
    fun clamp(newOffset: Offset, newScale: Float): Offset {
        val maxX = (containerSize.width * (newScale - 1f)) / 2f
        val maxY = (containerSize.height * (newScale - 1f)) / 2f
        return Offset(
            x = newOffset.x.coerceIn(-maxX, maxX),
            y = newOffset.y.coerceIn(-maxY, maxY)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            // 双指缩放 + 拖动。
            // ★ 以双指中心为锚点缩放：graphicsLayer 是**绕中心**缩放的（transformOrigin 默认 Center），
            //   设 c = 容器中心、g = 双指中心、o = 当前平移量，则屏幕点 g 对应的内容点在缩放前后要重合：
            //      缩放前内容点 x* = c + (g - c - o) / s
            //      要求     g  = c + (x* - c) * s' + o'
            //   ⇒ o' = (g - c) - (g - c - o) * (s' / s)，再加上这一次的 pan（屏幕像素，不受缩放影响）
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(MinViewerScale, MaxViewerScale)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val anchored = (centroid - center) - (centroid - center - offset) * (newScale / oldScale)
                    scale = newScale
                    offset = if (newScale <= MinViewerScale) Offset.Zero else clamp(anchored + pan, newScale)
                }
            }
            // 单击 / 双击。★ 两个 pointerInput 可以共存：变换手势负责缩放拖动，点击手势负责轻点，
            // 各自 consume 自己的事件（拖动结束时 pointer input 已消费，不会误触发"单击关闭"）
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (scale > MinViewerScale) {
                            scale = MinViewerScale
                            offset = Offset.Zero
                        } else {
                            onClose()
                        }
                    },
                    onDoubleTap = {
                        val target = if (scale > MinViewerScale) MinViewerScale else ViewerDoubleTapScale
                        scope.launch {
                            animate(scale, target) { value, _ ->
                                scale = value
                                offset = if (value <= MinViewerScale) Offset.Zero else clamp(offset, value)
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        RemoteImage(
            url = url,
            contentDescription = if (isCover) "封面图片" else "公告图片",
            contentScale = ContentScale.Fit,
            // 深色底上不能要浅色占位：透明占位让 Fit 留下的空白直接是黑底
            placeholderColor = Color.Transparent,
            // ★ fillMaxSize 是 RemeasureToBounds 的前提：内容尺寸 = 当前约束尺寸，
            //   形变过程中每一帧按动画尺寸重新测量，图片始终正好等于形变框，缩放不会跳
            modifier = boundsModifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                // 叠加页是 edge-to-edge 的（MainActivity 开了 enableEdgeToEdge），
                // 全屏黑底会铺到状态栏下面，按钮要自己躲开状态栏/导航栏
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "关闭",
                tint = Color.White
            )
        }
        // 底部一行小字：是不是封面 + 第几张（只有一张时不显示序号）
        val caption = buildString {
            if (isCover) append("封面")
            if (isCover && total > 1) append(" · ")
            if (total > 1) append("${index + 1} / $total")
        }
        if (caption.isNotEmpty()) {
            Surface(
                color = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 全屏看图的最小缩放（1x = 完整适配屏幕） */
private const val MinViewerScale = 1f

/** 全屏看图的最大缩放 */
private const val MaxViewerScale = 6f

/** 双击放大到的倍数（再双击回到 1x） */
private const val ViewerDoubleTapScale = 2.5f

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
                    // ★ 弹窗封面走**固定高度**，不用 autoHeight：弹窗高度必须可预期 ——
                    //   按图片原始比例的话，一张方图/长图就能把弹窗撑满、把标题和摘要挤成一团
                    //   （实测症状：图片占了大半个弹窗、标题看不见、摘要压在图上）。
                    //   写死 180dp + Crop 铺满，任何比例的图都只占 180dp。
                    RemoteImage(
                        url = cover,
                        contentDescription = null,
                        shape = MaterialTheme.shapes.medium,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (announcement.summary.isNotBlank()) {
                    Text(
                        text = announcement.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
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
