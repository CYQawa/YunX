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

package com.yunx.app.ui.preview

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.mikepenz.markdown.m3.Markdown
import com.yunx.app.data.network.video.VideoHttp
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.GitHubMarkdownImageTransformer
import com.yunx.app.ui.components.YunXWavyLoading
import com.yunx.app.ui.components.compactMarkdownTypography
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import java.net.URI
import kotlinx.coroutines.CancellationException

/**
 * 全屏在线预览页：图片 / 视频 / 音频 / 文本。
 *
 * 由 [PreviewLauncher]（全局一次性入口）发起，`MainScreen` 在 return 链里挂载 —— 见那里的注释。
 *
 * 三条贯穿全页的约定：
 * 1. **必须带请求头**：网盘直链与 B站/微博/Pixiv 的 CDN 都校验 `Referer`/`Cookie`/`UA`，
 *    少一个就 403。图片走 Coil 的逐请求请求头，播放器走 `DefaultHttpDataSource` 的默认请求属性，文本走 OkHttp。
 * 2. **失败不静默**：统一走 [SnackbarController]（本页是全屏页，宿主就是本页的 [rememberGlobalSnackbarHostState]）。
 * 3. **退出即释放**：播放器 `release()`，图片请求随组合销毁。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPreviewScreen(
    request: PreviewRequest,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = rememberGlobalSnackbarHostState()
    // 系统返回键等价于返回按钮
    BackHandler(onBack = onClose)
    // 视频点「全屏」时收起本页自己的顶栏，让画面占满整屏
    var immersive by remember { mutableStateOf(false) }
    val title = remember(request) { previewTitle(request) }
    // 当前页下标：顶栏「下载」下的是**正在看的这一条**（翻页后跟着变）
    var currentPage by remember(request) { mutableStateOf(request.startIndex) }
    // 只有调用方给了下载回调才显示按钮（网盘预览不传 → 顶栏保持原样）
    val downloadCurrent = request.onDownload?.takeIf { request.media.isNotEmpty() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // ★ 底色必须显式给 surface：不给的话叠加在其它页面之上时，深色模式下会露出系统的黑底
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!immersive) {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (downloadCurrent != null) {
                            IconButton(onClick = { downloadCurrent(currentPage) }) {
                                Icon(Icons.Outlined.Download, contentDescription = "下载")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                // 文本预览（仅网盘提供）：走 textUrl + textFileName
                request.textUrl.isNotBlank() -> TextPreviewPane(
                    request = request,
                    modifier = Modifier.fillMaxSize()
                )
                // 媒体列表（图片可翻页；视频/音频逐条切换）
                request.media.isNotEmpty() -> MediaPager(
                    request = request,
                    onFullscreenChanged = { immersive = it },
                    onCurrentPageChange = { currentPage = it },
                    modifier = Modifier.fillMaxSize()
                )
                else -> EmptyPreview(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** 标题：优先请求自带的标题，其次第一条媒体标题（文本预览退文件名），都没有就用「预览」 */
private fun previewTitle(request: PreviewRequest): String {
    val fromMedia = request.media.firstOrNull()?.title.orEmpty()
    return request.title.ifBlank { fromMedia.ifBlank { request.textFileName } }.ifBlank { "预览" }
}

// ---------------------------------------------------------------------------
// 媒体列表（图片翻页 / 视频播放）
// ---------------------------------------------------------------------------

/**
 * 一次预览的全部媒体用 [HorizontalPager] 左右切换（图片图集尤其常见）。
 *
 * ★ 相邻页不预加载：Pager 默认 `beyondViewportPageCount = 0`，只组合当前页。相邻页被预加载时视频页会出现
 *   两个播放器实例 —— 这正是「切页时两个视频叠着播」的来源。配合每页自己 `playWhenReady = 是否当前页`，
 *   绝不会叠着播。
 */
@Composable
private fun MediaPager(
    request: PreviewRequest,
    onFullscreenChanged: (Boolean) -> Unit,
    onCurrentPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val media = request.media
    val pagerState = rememberPagerState(
        initialPage = request.startIndex.coerceIn(0, media.lastIndex)
    ) { media.size }

    // 把「当前在看第几条」同步给上层：顶栏的「下载」要下的是这一条（翻页不同、下的也不同）
    LaunchedEffect(pagerState.currentPage) { onCurrentPageChange(pagerState.currentPage) }

    Box(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = media.size > 1
        ) { page ->
            val item = media[page]
            when (item.kind) {
                PreviewKind.IMAGE -> ZoomableImage(
                    media = item,
                    modifier = Modifier.fillMaxSize()
                )
                PreviewKind.VIDEO, PreviewKind.AUDIO -> MediaPlayerPane(
                    media = item,
                    active = page == pagerState.currentPage,
                    onFullscreenChanged = onFullscreenChanged,
                    modifier = Modifier.fillMaxSize()
                )
                // 文本不走媒体列表（由 PreviewLauncher.openText 单独发起），真出现就如实说明
                PreviewKind.TEXT -> EmptyPreview(
                    message = "该文件请用「文本预览」打开",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 页码「3 / 9」：只在多于一条媒体时显示
        if (media.size > 1) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${media.size}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 图片
// ---------------------------------------------------------------------------

/**
 * 图片页：Coil 3 加载（**逐请求带 Referer/Cookie/UA**）+ 双指缩放 + 放大后拖动。
 *
 * ★ 请求头必须逐请求带：网盘直链 / B站 / Pixiv 的图床都会校验 Referer，不带一律 403。
 *   这里用 Coil 3 提供的 `ImageRequest.Builder.httpHeaders(NetworkHeaders)`（`coil-network-core` 的扩展，
 *   已随 `coil-network-okhttp` 进入编译期 classpath，见 build.gradle.kts 里的注释）。
 *
 * 手势：单指且未放大时不消费事件 —— 让父级 [HorizontalPager] 正常翻页；
 * 一旦是双指捏合、或已经放大，才消费事件做缩放/平移（此时翻页让位于看图）。
 */
@Composable
private fun ZoomableImage(media: PreviewMedia, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 每张图各自记缩放与位移；换图（url 变）即归零，避免继承上一张的状态
    var scale by remember(media.url) { mutableStateOf(1f) }
    var offset by remember(media.url) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var loading by remember(media.url) { mutableStateOf(true) }
    val request = remember(context, media.url, media.headers) {
        ImageRequest.Builder(context)
            .data(media.url)
            .httpHeaders(previewNetworkHeaders(media.headers))
            .build()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(media.url) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        // 单指 + 未放大：不消费，交给 Pager 翻页
                        if (pressed < 2 && scale <= 1f) continue
                        val zoomChange = if (pressed > 1) event.calculateZoom() else 1f
                        val pan = event.calculatePan()
                        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
                        // 放大后能拖动的范围：左右各半个"多出来的"宽度，防止把图拖出屏幕外
                        val maxX = ((viewport.width * nextScale - viewport.width) / 2f).coerceAtLeast(0f)
                        val maxY = ((viewport.height * nextScale - viewport.height) / 2f).coerceAtLeast(0f)
                        scale = nextScale
                        offset = Offset(
                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                            (offset.y + pan.y).coerceIn(-maxY, maxY)
                        )
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = media.title.ifBlank { "预览图片" },
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onLoading = { loading = true },
            onSuccess = { loading = false },
            onError = {
                loading = false
                SnackbarController.show("图片加载失败：直链可能已失效或需要鉴权，可先下载后查看")
            }
        )
        if (loading) {
            YunXWavyLoading(modifier = Modifier.width(140.dp))
        }
    }
}

/**
 * 逐请求请求头（Coil 3：`coil3.network.NetworkHeaders`）。没有 UA 时补项目统一桌面 UA，避免被 CDN 拒。
 *
 * ★ `internal` 而不是 private：解析结果页的图片缩略图（`VideoResultSection` 的两列网格）也要用同一套
 *   请求头 —— 图集缩略图和全屏大图必须带一模一样的 Referer/Cookie，否则一个能出一个出不来。
 */
internal fun previewNetworkHeaders(headers: Map<String, String>): NetworkHeaders {
    val builder = NetworkHeaders.Builder()
    var hasUserAgent = false
    headers.forEach { (name, value) ->
        if (value.isNotBlank()) {
            builder.set(name, value)
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
        }
    }
    if (!hasUserAgent) builder.set("User-Agent", VideoHttp.UA_CHROME)
    return builder.build()
}

// ---------------------------------------------------------------------------
// 视频 / 音频
// ---------------------------------------------------------------------------

/**
 * 视频 / 音频页：Media3 ExoPlayer + 官方 `PlayerView`（自带播放/暂停、进度条、倍速设置、全屏按钮）。
 *
 * 为什么用 `PlayerView` 而不是 Compose 版播放器：`media3-ui-compose` 还在 alpha，官方 View 控件最稳。
 *
 * ★ 请求头经 `DefaultHttpDataSource.Factory.setDefaultRequestProperties` 进播放链路：没有 Referer/Cookie
 *   的直链（网盘、B站 CDN）会直接 403 / 黑屏。UA 单独交给 `setUserAgent`，并从请求头里摘掉，
 *   避免「请求属性里的 UA」与「setUserAgent 的 UA」两份互相覆盖（谁后写入取决于库内部实现，不能赌）。
 *
 * ★ `DefaultMediaSourceFactory` 会按内容自动选 Progressive / HLS 数据源（`media3-exoplayer-hls` 已在
 *   classpath 上，m3u8 无需手动指定）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MediaPlayerPane(
    media: PreviewMedia,
    active: Boolean,
    onFullscreenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** 内联（结果页）用：不给全屏按钮 —— 全屏要宿主页面配合，内联场景没有对应 UI */
    allowFullscreen: Boolean = true
) {
    val context = LocalContext.current
    // 换媒体就换播放器：`remember(media.url)` 之外的重组不会重建，翻页时旧实例随组合销毁（见下 release）
    val player = remember(context, media.url) { buildPlayer(context, media) }
    // 页面正在被切走时不要弹已经过期的错误提示
    val activeState = rememberUpdatedState(active)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!activeState.value) return
                SnackbarController.show(
                    "在线播放失败（${error.errorCodeName}）：可先下载后用本地播放器打开"
                )
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // ★ 退出预览页 / 换媒体必须释放：否则解码器、音频输出与网络连接会一直被占着
            player.release()
        }
    }

    LaunchedEffect(player, media.url, media.audioUrl) {
        if (media.audioUrl.isNotBlank()) {
            // ★ 音视频分轨（哔哩哔哩 DASH 高清）：两路各自带请求头，流式合并播放
            //   MergingMediaSource 按同一 timeline 播放视频轨 + 音轨，声音随画面同步
            val videoSource = DefaultMediaSourceFactory(
                httpDataSourceFactory(media.headers)
            ).createMediaSource(MediaItem.fromUri(media.url))
            val audioSource = DefaultMediaSourceFactory(
                httpDataSourceFactory(media.audioHeaders.ifEmpty { media.headers })
            ).createMediaSource(MediaItem.fromUri(media.audioUrl))
            player.setMediaSource(MergingMediaSource(videoSource, audioSource))
        } else {
            player.setMediaItem(MediaItem.fromUri(media.url))
        }
        player.prepare()
    }
    // 只有当前页在播：翻到别的媒体时先停住（页面被移出组合时上面的 release 会接手）
    LaunchedEffect(player, active) {
        player.playWhenReady = active
    }

    Column(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    // ★ 只有设过监听器，media3 才会显示全屏按钮 —— 内联场景不设，按钮自然不出现
                    if (allowFullscreen) {
                        setFullscreenButtonClickListener { isFullScreen -> onFullscreenChanged(isFullScreen) }
                    }
                    // ★ 必须写 this.player：块内 `player` 会先解析成外层的局部 val（局部变量优先于
                    // 隐式接收者成员），写成 `player = player` 会被判成给 val 赋值而编译失败
                    this.player = player
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

/** 建播放器：把请求头与 UA 装进数据源工厂，其余交给 ExoPlayer 的默认数据源选择 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, media: PreviewMedia): ExoPlayer {
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory(media.headers)))
        .build()
}

/**
 * 把请求头与 UA 拆成 `DefaultHttpDataSource.Factory`（预览合并播放的两路各建一个）。
 * - `User-Agent` 单独走 `setUserAgent`（media3 特殊处理 UA，不走普通请求属性）；
 * - 其余头（Referer/Cookie 等）进 `setDefaultRequestProperties`，随每个请求带上。
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun httpDataSourceFactory(headers: Map<String, String>): DefaultHttpDataSource.Factory {
    var userAgent: String? = null
    val requestProperties = LinkedHashMap<String, String>()
    headers.forEach { (name, value) ->
        if (name.equals("User-Agent", ignoreCase = true)) userAgent = value else requestProperties[name] = value
    }
    return DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(requestProperties)
        .setUserAgent(userAgent?.takeIf { it.isNotBlank() } ?: VideoHttp.UA_CHROME)
}

// ---------------------------------------------------------------------------
// 文本
// ---------------------------------------------------------------------------

private sealed interface TextPreviewState {
    data object Loading : TextPreviewState
    data class Loaded(val content: PreviewTextContent) : TextPreviewState
    data class Failed(val message: String) : TextPreviewState
}

/** 文本预览：取前 512KB → 解码 → 按扩展名决定 Markdown 渲染还是等宽纯文本 */
@Composable
private fun TextPreviewPane(request: PreviewRequest, modifier: Modifier = Modifier) {
    var state by remember(request.textUrl) { mutableStateOf<TextPreviewState>(TextPreviewState.Loading) }

    LaunchedEffect(request.textUrl, request.textHeaders) {
        state = TextPreviewState.Loading
        state = try {
            val loaded = PreviewTextLoader.load(request.textUrl, request.textHeaders)
            if (loaded.content.isBlank()) {
                // 取到了但没内容（空文件 / 全是不可解码的字节）：不能留一个空白页
                val message = "文件内容为空或无法解码"
                SnackbarController.show(message)
                TextPreviewState.Failed(message)
            } else {
                TextPreviewState.Loaded(loaded)
            }
        } catch (cancellation: CancellationException) {
            // 页面已经关掉 / 请求被取消：这不是失败，别弹提示
            throw cancellation
        } catch (error: Throwable) {
            val message = "读取文件失败：${error.message ?: "未知错误"}"
            SnackbarController.show(message)
            TextPreviewState.Failed(message)
        }
    }

    when (val current = state) {
        TextPreviewState.Loading -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                YunXWavyLoading(modifier = Modifier.width(160.dp))
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "正在读取前 512KB…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        is TextPreviewState.Failed -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = current.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
        is TextPreviewState.Loaded -> TextContentPane(
            request = request,
            content = current.content,
            modifier = modifier
        )
    }
}

@Composable
private fun TextContentPane(
    request: PreviewRequest,
    content: PreviewTextContent,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(modifier = modifier.fillMaxSize()) {
        // 文件名 + 截断说明（响应是 206 / 读满上限时）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = request.textFileName.ifBlank { "文本文件" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (content.truncated) {
                Text(
                    text = "已截断，完整内容请下载后查看（仅读取前 512KB）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (PreviewTypes.isMarkdown(request.textFileName)) {
            // Markdown 用项目已有的 mikepenz GFM 渲染器（与 README / 公告详情同一套排版与图片加载）
            val rendered = remember(content.content, request.textUrl) {
                absolutizeMarkdownLinks(content.content, request.textUrl)
            }
            val typography = remember { compactMarkdownTypography() }
            Markdown(
                content = rendered,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                typography = typography,
                imageTransformer = GitHubMarkdownImageTransformer
            )
        } else {
            // 其余一律等宽纯文本：保留原始缩进（日志 / 代码 / 字幕），可选中复制，不做语法高亮（不引依赖）
            SelectionContainer {
                Text(
                    text = content.content,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * 把 Markdown 里的相对链接/相对图片补成绝对 URL（以文本文件自身的直链为基准）。
 * 不处理的话，`![](img/x.png)` 这类相对路径在渲染器里会变成无意义的链接。
 */
private fun absolutizeMarkdownLinks(markdown: String, baseUrl: String): String {
    val base = runCatching { URI(baseUrl) }.getOrNull() ?: return markdown
    return MD_LINK_TARGET.replace(markdown) { match ->
        val target = match.groupValues[1]
        val absolute = target.startsWith("http://") ||
            target.startsWith("https://") ||
            target.startsWith("data:") ||
            target.startsWith("mailto:") ||
            target.startsWith("//") || // 协议相对
            target.startsWith("#")     // 页内锚点
        if (absolute) {
            match.value
        } else {
            val resolved = runCatching { base.resolve(target).toString() }.getOrNull()
            if (resolved.isNullOrBlank()) match.value else "]($resolved"
        }
    }
}

/** Markdown 链接目标：`](target` （排除空白/右括号），足够覆盖图片与普通链接 */
private val MD_LINK_TARGET = Regex("""\]\(\s*([^)\s]+)""")

// ---------------------------------------------------------------------------
// 空态
// ---------------------------------------------------------------------------

@Composable
private fun EmptyPreview(
    modifier: Modifier = Modifier,
    message: String = "没有可预览的内容"
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
