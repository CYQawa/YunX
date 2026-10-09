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

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunx.app.data.db.BookmarkEntity
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.ui.SnackbarController
import com.mikepenz.markdown.m3.Markdown
import com.yunx.app.ui.components.FileNameText
import com.yunx.app.ui.components.GitHubMarkdownImageTransformer
import com.yunx.app.ui.components.compactMarkdownTypography
import com.yunx.app.ui.resolve.DownloadLinkDialog
import com.yunx.app.ui.resolve.ShareDetailScreen
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.BookmarkViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunx.app.ui.viewmodel.Pan115CloudViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.ResolveUiState
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel
import com.yunx.app.ui.components.YunXLoading
import com.yunx.app.ui.theme.ThemeController
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.effectsFast
import com.yunx.app.ui.theme.spatialDefault
import com.yunx.app.ui.theme.spatialFast

/**
 * 解析页：输入分享链接与提取码 → 解析 → 展示分享详情 → 获取下载直链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ResolveViewModel,
    /** 夸克云盘浏览 ViewModel（分享文件转存目录选择用） */
    quarkCloudViewModel: QuarkCloudViewModel,
    /** 迅雷网盘云盘浏览 ViewModel（迅雷分享转存目录选择用） */
    xunleiCloudViewModel: XunleiCloudViewModel,
    /** 百度网盘云盘浏览 ViewModel（百度分享转存目录选择用） */
    baiduCloudViewModel: BaiduCloudViewModel,
    /** 139 网盘云盘浏览 ViewModel（139 分享转存目录选择用） */
    c139CloudViewModel: C139CloudViewModel,
    /** UC 网盘云盘浏览 ViewModel（UC 分享转存目录选择用） */
    ucCloudViewModel: UCCoudViewModel,
    /** 123 云盘浏览 ViewModel（123 分享转存目录选择用） */
    pan123CloudViewModel: Pan123CloudViewModel,
    /** 115 网盘浏览 ViewModel（115 分享转存目录选择用） */
    pan115CloudViewModel: Pan115CloudViewModel,
    /** 光鸭云盘浏览 ViewModel（光鸭分享转存目录选择用） */
    guangyaCloudViewModel: GuangYaCloudViewModel,
    /** 收藏 ViewModel：主页快捷方式（已添加到主页的收藏链接）数据源 */
    bookmarkViewModel: BookmarkViewModel,
    /** 打开「收藏网盘链接」页（主页快捷方式区块的「管理」入口） */
    onOpenBookmarks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState
    val downloadLink = viewModel.downloadLink
    val downloadError = viewModel.downloadError
    val context = LocalContext.current
    // 主页快捷方式：已「添加到主页」的收藏，在输入页下方以网格展示
    val homeBookmarks by bookmarkViewModel.homeBookmarks.collectAsState()

    // 详情页文件列表滚动状态（提升到 AnimatedContent 外层：进入文件夹/返回时列表重建，
    // 若放在 ShareDetailScreen 内会随目录切换丢失，导致返回后列表回到顶部）
    val detailListState = rememberLazyListState()
    // 各目录滚动位置记忆（key = 目录路径；进入文件夹/返回时恢复对应位置）
    val detailScrollPositions = remember { mutableStateMapOf<String, Int>() }

    // 输入框状态提升到页面层：进入详情/文件夹再返回时不清空
    var link by rememberSaveable { mutableStateOf("") }
    var pwd by rememberSaveable { mutableStateOf("") }
    var pwdEdited by rememberSaveable { mutableStateOf(false) }

    // 剪贴板分享链接提示状态：待提示的剪贴板文本 + 已忽略的文本
    // 用 rememberSaveable：切换 Tab 后返回仍保留（避免「忽略后切页回来又弹」）
    var clipboardSuggestion by rememberSaveable { mutableStateOf<String?>(null) }
    var ignoredClipboard by rememberSaveable { mutableStateOf<String?>(null) }

    // 检测函数：读取剪贴板，满足条件则设置提示（三重触发：组合时 / ON_RESUME / 剪贴板变化）
    val maybeSuggestClipboard: () -> Unit = {
        val text = readClipboardSafely(context)
        if (text != null &&
            state is ResolveUiState.Idle &&
            text.isNotBlank() &&
            text != link &&
            text != ignoredClipboard &&
            (ShareLinkParser.parse(text) != null || GitHubLinkParser.parse(text) != null)
        ) {
            clipboardSuggestion = text
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // 设置页「自动识别剪贴板」：关闭后不注册监听、不读剪贴板，并立刻收起已有提示
    val clipboardSuggestEnabled = ThemeController.clipboardSuggestEnabled
    DisposableEffect(lifecycleOwner, clipboard, clipboardSuggestEnabled) {
        if (!clipboardSuggestEnabled) {
            clipboardSuggestion = null
            onDispose { }
        } else {
            // 剪贴板变化立即检测（前台最灵敏，复制即提示）
            val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
                maybeSuggestClipboard()
                // 部分 ROM 剪贴板内容写入有延迟，300ms 后重试一次
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    maybeSuggestClipboard()
                }, 300)
            }
            clipboard.addPrimaryClipChangedListener(clipListener)
            // 打开应用 / 从后台切回时检测
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) maybeSuggestClipboard()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            // 冷启动兜底：组合完成立即检测一次（避免 ON_RESUME 早于 observer 注册导致漏检）
            maybeSuggestClipboard()
            onDispose {
                clipboard.removePrimaryClipChangedListener(clipListener)
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    // Android 11 及以下：轻量轮询兜底（2s 一次）。
    // 部分 ROM（如 vivo）剪贴板监听不触发时仍能识别；Android 12+ 读剪贴板会弹系统提示，不轮询。
    // 设置页关闭开关后同样不轮询（此时应用完全不读剪贴板）。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        LaunchedEffect(clipboardSuggestEnabled) {
            if (!clipboardSuggestEnabled) return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(2000)
                maybeSuggestClipboard()
            }
        }
    }

    // 链接变化时自动匹配提取码（用户未手动输入时）
    LaunchedEffect(link) {
        if (!pwdEdited && pwd.isEmpty()) {
            ShareLinkParser.parse(link)?.pwd?.let { pwd = it }
        }
    }

    // 下载错误提示
    LaunchedEffect(downloadError) {
        downloadError?.let {
            SnackbarController.show(it)
            viewModel.consumeDownloadError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 状态切换过渡：输入态/加载/详情/错误之间平滑淡入淡出（对齐网盘页：不上下移动）
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast())
            },
            label = "resolveState"
        ) { s ->
            when (s) {
                is ResolveUiState.Detail -> ShareDetailScreen(
            session = s.session,
            files = s.files,
            viewModel = viewModel,
            quarkCloudViewModel = quarkCloudViewModel,
            xunleiCloudViewModel = xunleiCloudViewModel,
            baiduCloudViewModel = baiduCloudViewModel,
            c139CloudViewModel = c139CloudViewModel,
            ucCloudViewModel = ucCloudViewModel,
            pan123CloudViewModel = pan123CloudViewModel,
            pan115CloudViewModel = pan115CloudViewModel,
            guangyaCloudViewModel = guangyaCloudViewModel,
            scrollBehavior = scrollBehavior,
            listState = detailListState,
            scrollPositions = detailScrollPositions,
                    // 顶部左上角返回：退出文件页回到输入页（输入框内容保留）
                    onExit = { viewModel.backToInput() },
                    // 列表「返回上一级」：子目录回上级，根目录回输入页
                    onBack = { viewModel.navigateBack() },
                    // GitHub 专属：forked from 头部、README 底部（只在仓库首页显示，进子目录/退回账号列表不残留）
                    extraHeaderContent = if (viewModel.githubAtRepoRoot) {
                        {
                            val parent = viewModel.githubParentFullName
                            if (parent != null) {
                                TextButton(
                                    onClick = { viewModel.openGitHubParentRepo() },
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "forked from $parent",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    } else null,
                    extraFooterContent = if (viewModel.githubAtRepoRoot) {
                        {
                            val md = viewModel.githubReadme
                            val owner = viewModel.githubRepoOwner
                            val repo = viewModel.githubRepoName
                            val branch = viewModel.githubDefaultBranch
                            if (!md.isNullOrBlank() && owner != null && repo != null && branch != null) {
                                Column(modifier = Modifier.padding(top = 12.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(MaterialTheme.colorScheme.outlineVariant)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "README",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    // 用成熟 GFM 库渲染 README（表格/任务列表/代码高亮/HTML 子集/emoji/自动链接）
                                    // 预处理：相对链接与相对图片补全为绝对 URL（raw.githubusercontent.com）
                                    val processed = remember(md, owner, repo, branch) {
                                        preprocessReadme(md, owner, repo, branch)
                                    }
                                    // 注入自定义图片加载器（OkHttp 自研，无 Coil）
                                    GitHubMarkdownImageTransformer.mirrorPrefix = remember {
                                        com.yunx.app.data.prefs.SettingsRepository(context)
                                            .githubMirrorPrefix?.ifBlank { null }
                                    }
                                    // 紧凑字号：与更新说明共用同一份排版（见 ui/components/MarkdownTypography.kt）
                                    val compactTypography = remember { compactMarkdownTypography() }
                                    Markdown(
                                        content = processed,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                        typography = compactTypography,
                                        imageTransformer = GitHubMarkdownImageTransformer
                                    )
                                }
                            }
                        }
                    } else null,
                    fileBadge = if (viewModel.isGitHubPlatform) {
                        { file ->
                            val label = viewModel.githubBadges[file.fid]
                            if (!label.isNullOrBlank()) {
                                GitHubBadge(label)
                            }
                        }
                    } else null,
                    // 仅 GitHub 平台启用下拉刷新当前节点
                    onRefresh = if (viewModel.isGitHubPlatform) {
                        { viewModel.refreshGitHubCurrentNode() }
                    } else null,
                    refreshing = viewModel.githubRefreshing
                )
                is ResolveUiState.Loading -> LoadingContent()
                else -> ResolveInputContent(
                    viewModel = viewModel,
                    scrollBehavior = scrollBehavior,
                    state = s,
                    link = link,
                    onLinkChange = { link = it },
                    pwd = pwd,
                    onPwdChange = {
                        pwd = it
                        pwdEdited = true
                    },
                    onClearLink = {
                        link = ""
                        pwd = ""
                        pwdEdited = false
                    },
                    onClearPwd = { pwd = "" },
                    // 主页快捷方式：点击直接解析（链接回填输入框，返回输入页时能看到当前解析的链接）
                    homeBookmarks = homeBookmarks,
                    onOpenShortcut = { bookmark ->
                        link = bookmark.link
                        pwd = bookmark.pwd
                        pwdEdited = true
                        // GitHub 收藏（收藏时存的仓库链接）不走网盘解析
                        val github = GitHubLinkParser.parse(bookmark.link)
                        if (github != null) {
                            viewModel.startGitHubResolve(github)
                        } else {
                            viewModel.startResolve(bookmark.link, bookmark.pwd)
                        }
                    },
                    onOpenBookmarks = onOpenBookmarks,
                    onRemoveShortcut = { bookmark ->
                        bookmarkViewModel.setHomePinned(bookmark.id, false)
                    }
                )
            }
        }

        // 剪贴板分享链接提示卡片（仅输入页、有待提示内容时显示，带弹出动画）
        // animatedSuggestion 保留最后提示内容，保证退出动画期间卡片不消失
        var animatedSuggestion by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(clipboardSuggestion) {
            clipboardSuggestion?.let { animatedSuggestion = it }
        }
        AnimatedVisibility(
            visible = state is ResolveUiState.Idle && clipboardSuggestion != null,
            enter = fadeIn(effectsDefault()) +
                slideInVertically(spatialDefault()) { -it / 2 } +
                scaleIn(tween(250, delayMillis = 60)),   // 保留 tween：弹簧规格无法表达 60ms 延迟
            exit = fadeOut(effectsFast()) +
                slideOutVertically(spatialFast()) { -it / 2 } +
                scaleOut(spatialFast()),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            animatedSuggestion?.let { suggestion ->
                val shareParsed = ShareLinkParser.parse(suggestion)
                val githubParsed = GitHubLinkParser.parse(suggestion)
                ClipboardSuggestCard(
                    platformName = when {
                        githubParsed != null -> "GitHub"
                        shareParsed != null -> platformLabel(shareParsed.platform)
                        else -> "网盘"
                    },
                    onPaste = {
                        link = suggestion
                        pwd = shareParsed?.pwd.orEmpty()
                        pwdEdited = true
                        clipboardSuggestion = null
                        // GitHub 链接走 ViewModel 的 GitHub 解析入口（复用 ShareDetailScreen 框架）
                        if (githubParsed != null) {
                            viewModel.startGitHubResolve(githubParsed)
                        } else {
                            viewModel.startResolve(suggestion, shareParsed?.pwd)
                        }
                    },
                    onDismiss = {
                        ignoredClipboard = suggestion
                        clipboardSuggestion = null
                    }
                )
            }
        }
    }

    // 获取下载直链加载弹窗（转存/取链需要时间，避免无反馈）
    if (viewModel.isFetchingDownloadLink) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("获取下载链接") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    YunXLoading(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "正在获取下载链接，请稍候…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }

    // 下载直链弹窗
    downloadLink?.let { link ->
        DownloadLinkDialog(
            link = link,
            onDownload = { viewModel.startDownload(link) },
            onDismiss = { viewModel.dismissDownloadDialog() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolveInputContent(
    viewModel: ResolveViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    state: ResolveUiState,
    link: String,
    onLinkChange: (String) -> Unit,
    pwd: String,
    onPwdChange: (String) -> Unit,
    onClearLink: () -> Unit,
    onClearPwd: () -> Unit,
    /** 主页快捷方式数据（已添加到主页的收藏） */
    homeBookmarks: List<BookmarkEntity>,
    /** 点击快捷方式：直接解析该收藏链接 */
    onOpenShortcut: (BookmarkEntity) -> Unit,
    /** 打开收藏页管理快捷方式 */
    onOpenBookmarks: () -> Unit,
    /** 长按快捷方式确认后从主页移除 */
    onRemoveShortcut: (BookmarkEntity) -> Unit
) {
    val isLoading = state is ResolveUiState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "粘贴分享链接，一键解析分享内容",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = link,
            onValueChange = onLinkChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：https://pan.quark.cn/s/xxxx 或 迅雷口令") },
            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
            trailingIcon = {
                if (link.isNotEmpty()) {
                    IconButton(onClick = onClearLink) {
                        Icon(Icons.Filled.Close, contentDescription = "清空链接")
                    }
                }
            },
            minLines = 3,
            maxLines = 6,
            shape = MaterialTheme.shapes.large
        )

        OutlinedTextField(
            value = pwd,
            onValueChange = onPwdChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("提取码（可选）") },
            placeholder = { Text("自动识别或手动输入") },
            trailingIcon = {
                if (pwd.isNotEmpty()) {
                    IconButton(onClick = onClearPwd) {
                        Icon(Icons.Filled.Close, contentDescription = "清空提取码")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )

        Button(
            onClick = {
                // 优先识别 GitHub 链接（仓库 / 账号 / 文件直链），走 ViewModel 的 GitHub 解析入口
                val github = GitHubLinkParser.parse(link)
                if (github != null) {
                    viewModel.startGitHubResolve(github)
                } else {
                    viewModel.startResolve(link, pwd)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = link.isNotBlank() && !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("解析中…")
            } else {
                Text("开始解析")
            }
        }

        if (state is ResolveUiState.Error) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // 主页快捷方式：收藏页「添加到主页」的链接在这里以网格呈现，点击直接解析
        HomeShortcutsSection(
            bookmarks = homeBookmarks,
            onOpen = onOpenShortcut,
            onOpenBookmarks = onOpenBookmarks,
            onRemove = onRemoveShortcut
        )
    }
}

/** 主页快捷方式列数（4 列在窄屏也能放下两字标题，观感贴近桌面图标网格） */
private const val HOME_SHORTCUT_COLUMNS = 4

/**
 * 主页快捷方式区块：展示已「添加到主页」的收藏链接。
 *
 * 外层已是 verticalScroll，这里不能用 LazyVerticalGrid（同方向嵌套滚动会崩），
 * 因此用 chunked 手写行网格，末行用 Spacer 占位保证每个格子等宽。
 */
@Composable
private fun HomeShortcutsSection(
    bookmarks: List<BookmarkEntity>,
    onOpen: (BookmarkEntity) -> Unit,
    onOpenBookmarks: () -> Unit,
    onRemove: (BookmarkEntity) -> Unit
) {
    // 待确认移除的快捷方式（长按触发，避免误触直接消失）
    var removing by remember { mutableStateOf<BookmarkEntity?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "快捷方式",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpenBookmarks) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "管理", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (bookmarks.isEmpty()) {
            // 空态：引导到收藏页添加，避免这里只剩一片空白
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddToHomeScreen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "还没有主页快捷方式",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "在「收藏网盘链接」页长按一条收藏 → 添加到主页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bookmarks.chunked(HOME_SHORTCUT_COLUMNS).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { bookmark ->
                            HomeShortcutTile(
                                bookmark = bookmark,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpen(bookmark) },
                                onLongClick = { removing = bookmark }
                            )
                        }
                        // 末行不足一列时补空位，保证每个格子宽度一致
                        repeat(HOME_SHORTCUT_COLUMNS - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    removing?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("从主页移除") },
            text = {
                Text(
                    text = "「${bookmark.title.ifBlank { bookmark.link }}」将不再显示在主页快捷方式中",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(bookmark)
                        removing = null
                    }
                ) {
                    Text(text = "移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(text = "取消")
                }
            }
        )
    }
}

/** 主页快捷方式单个瓦片：圆角色块（平台简称）+ 标题，长按移除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShortcutTile(
    bookmark: BookmarkEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            val label = homeTileLabel(bookmark)
            if (label.isEmpty()) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Text(
                    text = label,
                    // 字越多字号越小：4 个字（自定义上限）也能塞进 48dp 方块
                    style = when {
                        label.length <= 2 -> MaterialTheme.typography.titleMedium
                        label.length == 3 -> MaterialTheme.typography.labelLarge
                        else -> MaterialTheme.typography.labelSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        FileNameText(
            text = bookmark.title.ifBlank { bookmark.link },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

/** 快捷方式色块最多显示几个字（标题截断、自定义文字都受它约束） */
internal const val HOME_LABEL_MAX_LENGTH = 4

/**
 * 快捷方式色块文字：自定义文字 > 标题前几个字 > 平台简称（标题为空时的兜底）。
 * 返回空串表示没有可显示的文字，调用处退回通用链接图标。
 * 收藏页的「自定义图标文字」弹窗也用它做占位提示，保证"自动文字"只有一个实现。
 */
internal fun homeTileLabel(bookmark: BookmarkEntity): String {
    val custom = bookmark.homeLabel.trim()
    if (custom.isNotEmpty()) return custom.take(HOME_LABEL_MAX_LENGTH)
    val title = bookmark.title.trim()
    if (title.isNotEmpty()) return title.take(HOME_LABEL_MAX_LENGTH)
    return (platformShortLabel(bookmark.platform) ?: "").take(HOME_LABEL_MAX_LENGTH)
}

/** 平台简称（色块兜底文字）；未知平台返回 null，调用处退回通用链接图标 */
private fun platformShortLabel(platform: String): String? = when (platform) {
    "QUARK" -> "夸克"
    "UC" -> "UC"
    "XUNLEI" -> "迅雷"
    "BAIDU" -> "百度"
    "C139" -> "139"
    "PAN123" -> "123"
    "PAN115" -> "115"
    "GUANGYA" -> "光鸭"
    "ILANZOU" -> "优享"
    "LANZOU" -> "蓝奏"
    "GITHUB" -> "GitHub"
    else -> null
}

/** 全屏加载中（进入文件夹/解析中展示，避免闪回输入页） */
@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            YunXLoading(modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "加载中…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 安全读取剪贴板最新文本；失败返回 null（部分 ROM 可能限制剪贴板访问） */
private fun readClipboardSafely(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
}.getOrNull()

/** 平台名称（提示卡片展示） */
private fun platformLabel(platform: SharePlatform): String = when (platform) {
    SharePlatform.QUARK -> "夸克网盘"
    SharePlatform.UC -> "UC 网盘"
    SharePlatform.XUNLEI -> "迅雷网盘"
    SharePlatform.BAIDU -> "百度网盘"
    SharePlatform.C139 -> "139 网盘"
    SharePlatform.PAN123 -> "123云盘"
    SharePlatform.PAN115 -> "115网盘"
    SharePlatform.GUANGYA -> "光鸭云盘"
    SharePlatform.ILANZOU -> "蓝奏云优享版"
    SharePlatform.LANZOU -> "蓝奏云"
    SharePlatform.GITHUB -> "GitHub"
}

/** 剪贴板分享链接提示卡片：检测到分享链接时，询问是否粘贴解析 */
@Composable
private fun ClipboardSuggestCard(
    platformName: String,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "检测到 $platformName 分享链接",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "是否粘贴到解析框并开始解析？",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("忽略", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = onPaste,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("粘贴并解析")
                }
            }
        }
    }
}
/**
 * GitHub 文件徽章：紧凑彩色标签。
 * 最新=绿、预发布=橙、草稿=灰、其余（Fork/语言）=中性次要色。
 */
@Composable
private fun GitHubBadge(label: String) {
    val (bg, fg) = when (label) {
        "最新" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "预发布" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "草稿" -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Box(
        modifier = Modifier
            .background(bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg
        )
    }
}

/**
 * README 预处理：把 Markdown 中的相对链接/相对图片补全为绝对 URL。
 * - [text](./x) / [text](x) → https://github.com/{owner}/{repo}/blob/{branch}/x
 * - ![alt](./img.png) / ![alt](img.png) → https://raw.githubusercontent.com/{owner}/{repo}/{branch}/img.png
 * - http(s)/mailto 开头的绝对 URL 不动。
 */
private fun preprocessReadme(md: String, owner: String, repo: String, branch: String): String {
    val blobBase = "https://github.com/$owner/$repo/blob/$branch/"
    val rawBase = "https://raw.githubusercontent.com/$owner/$repo/$branch/"
    // 图片 ![alt](url)
    var out = Regex("!\\[([^]]*)\\]\\(([^)]+)\\)").replace(md) { m ->
        val alt = m.groupValues[1]
        val url = m.groupValues[2].trim()
        val resolved = resolveRel(rawBase, url)
        "![$alt]($resolved)"
    }
    // 普通链接 [text](url)（排除已处理的图片）
    out = Regex("(?<!!)\\[([^]]+)\\]\\(([^)]+)\\)").replace(out) { m ->
        val label = m.groupValues[1]
        val url = m.groupValues[2].trim()
        val resolved = resolveRel(blobBase, url)
        "[$label]($resolved)"
    }
    return out
}

/**
 * 把 README 相对链接补全为绝对 URL。
 * - 绝对 URL（http/https/mailto）原样返回；
 * - 相对路径用 URI.resolve 处理 `./`、`../`（上溯目录），避免 `../` 被当作普通路径段拼错。
 */
private fun resolveRel(base: String, rel: String): String {
    if (rel.startsWith("http://") || rel.startsWith("https://") || rel.startsWith("mailto:")) return rel
    // 含转义括号的链接（如 [a](b\(c\))）按原样保留，不做路径补全，避免被错误补全
    if (rel.contains('\\')) return rel
    return runCatching { java.net.URI(base).resolve(rel).toString() }.getOrDefault(base + rel)
}
