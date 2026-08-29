package com.yunx.app.ui.resolve

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.yunx.app.data.db.BookmarkEntity
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.components.ScrollToTopButton
import com.yunx.app.ui.items.MultiSelectAction
import com.yunx.app.ui.items.MultiSelectBar
import com.yunx.app.ui.screens.AddToBookmarkDialog
import com.yunx.app.ui.screens.BaiduSaveSheet
import com.yunx.app.ui.screens.C139SaveSheet
import com.yunx.app.ui.screens.Pan123SaveSheet
import com.yunx.app.ui.screens.SaveToCloudSheet
import com.yunx.app.ui.screens.UCSaveSheet
import com.yunx.app.ui.screens.XunleiSaveSheet
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel

/** 百度非会员限速阈值：>300MB 提示 */
private const val BAIDU_LIMIT_BYTES = 300L * 1024 * 1024

/**
 * 分享详情页：展示分享标题与文件列表，支持进入文件夹、点击文件获取下载直链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareDetailScreen(
    session: ShareSession,
    files: List<ShareFile>,
    viewModel: ResolveViewModel,
    /** 夸克云盘浏览 ViewModel（转存目录选择用；与网盘页同一实例） */
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
    scrollBehavior: TopAppBarScrollBehavior,
    /** 顶部左上角返回：退出文件页回到输入页（输入框内容保留） */
    onExit: () -> Unit,
    /** 列表「返回上一级」：子目录回上级，根目录回输入页 */
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pathNames = viewModel.pathNames
    // 百度 >300MB 限速提示（解析页百度分享下载）
    val context = LocalContext.current
    val baiduSettings = remember { SettingsRepository(context) }
    var baiduLimitDismissed by remember { mutableStateOf(baiduSettings.baiduLimitHintDismissed) }
    var showBaiduLimitDialog by remember { mutableStateOf(false) }
    var pendingBaiduAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 「添加至收藏」弹窗
    var showAddBookmark by remember { mutableStateOf(false) }

    /** 百度分享下载前检查：>300MB 且未忽略时弹提示，确认后执行 */
    fun checkBaiduLimit(file: ShareFile, proceed: () -> Unit) {
        if (viewModel.isBaidu && !baiduLimitDismissed && file.fsize > BAIDU_LIMIT_BYTES) {
            pendingBaiduAction = proceed
            showBaiduLimitDialog = true
        } else {
            proceed()
        }
    }
    // 系统返回键 → 返回上一级目录 / 根目录回输入页（而不是退出应用）
    BackHandler { onBack() }
    // 文件列表滚动状态（返回顶部按钮用）
    val listState = rememberLazyListState()
    // 多选模式：底部批量操作栏 + 处理中弹窗
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 16.dp,
                        bottom = if (viewModel.multiSelectMode) 96.dp else 16.dp
                    ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (viewModel.multiSelectMode) {
                            // 多选模式：取消选择
                            IconButton(onClick = { viewModel.exitMultiSelect() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消选择")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "已选 ${viewModel.selected.size} 项",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (viewModel.selected.size == files.size) "已全选" else "点击选择更多文件",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { viewModel.toggleSelectAll(files) }) {
                                Text(if (viewModel.selected.size == files.size) "取消全选" else "全选")
                            }
                        } else {
                            IconButton(onClick = onExit) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title.ifBlank { "分享内容" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "共 ${files.size} 项",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { showAddBookmark = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.BookmarkAdd,
                                    contentDescription = "添加至收藏",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    // 可点击面包屑（多选模式下隐藏）
                    if (!viewModel.multiSelectMode) {
                        CrumbBar(
                            rootTitle = session.title.ifBlank { "分享内容" },
                            pathNames = pathNames,
                            onNavigate = { viewModel.navigateToLevel(it) }
                        )
                    }
                }
            }

            // 返回上一级（单独列表项；根目录时不显示）
            if (pathNames.isNotEmpty()) {
                item {
                    BackToParentItem(onClick = onBack)
                }
            }

            if (files.isEmpty()) {
                item {
                    Text(
                        text = "此目录为空",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            items(files, key = { it.fid }) { file ->
                ShareFileRow(
                    file = file,
                    modifier = Modifier.animateItem(),
                    onClick = {
                        if (viewModel.multiSelectMode) {
                            viewModel.toggleSelect(file)
                        } else if (file.isdir) {
                            viewModel.openFolder(file)
                        } else {
                            checkBaiduLimit(file) { viewModel.fetchDownloadLink(file) }
                        }
                    },
                    // 仅夸克分享显示转存按钮（多选时隐藏）
                    onSave = if (!viewModel.multiSelectMode && viewModel.canSave) {
                        { viewModel.requestSave(file) }
                    } else {
                        null
                    },
                    onLongClick = if (!viewModel.multiSelectMode) {
                        { viewModel.enterMultiSelect(file) }
                    } else {
                        null
                    },
                    selected = viewModel.selected.contains(file),
                    showCheckbox = viewModel.multiSelectMode
                )
            }
        }

        // 返回顶部按钮（上滑离开顶部后显示；多选模式下上移避开底部批量栏）
        ScrollToTopButton(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = if (viewModel.multiSelectMode) 104.dp else 16.dp
                )
        )

        // 多选模式：底部批量操作栏（转存/下载）
        if (viewModel.multiSelectMode) {
            MultiSelectBar(
                count = viewModel.selected.size,
                actions = buildList {
                    // 转存仅夸克分享支持
                    if (viewModel.canSave) {
                        add(
                            MultiSelectAction("转存", Icons.Outlined.SaveAlt, MaterialTheme.colorScheme.primary) {
                                viewModel.batchSaveToCloud()
                            }
                        )
                    }
                    add(
                        MultiSelectAction("下载", Icons.Outlined.Download, MaterialTheme.colorScheme.primary) {
                            // 百度批量下载：选中项含 >300MB 文件时先弹限速提示
                            val hasBig = viewModel.selected.any { it.fsize > BAIDU_LIMIT_BYTES }
                            if (viewModel.isBaidu && !baiduLimitDismissed && hasBig) {
                                pendingBaiduAction = { viewModel.batchDownload() }
                                showBaiduLimitDialog = true
                            } else {
                                viewModel.batchDownload()
                            }
                        }
                    )
                }
            )
        }
    }

    // 百度 >300MB 限速提示弹窗（解析页百度分享下载，可勾选不再显示）
    if (showBaiduLimitDialog) {
        var neverShow by remember { mutableStateOf(baiduLimitDismissed) }
        AlertDialog(
            onDismissRequest = { showBaiduLimitDialog = false },
            title = { Text("下载大文件提示") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "百度网盘非会员超过300MB会被限速，下载速度可能较慢。是否继续下载？",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = neverShow, onCheckedChange = { neverShow = it })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("不再显示此提示", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBaiduLimitDialog = false
                        baiduSettings.baiduLimitHintDismissed = neverShow
                        baiduLimitDismissed = neverShow
                        pendingBaiduAction?.invoke()
                        pendingBaiduAction = null
                    }
                ) { Text("继续下载") }
            },
            dismissButton = {
                TextButton(onClick = { showBaiduLimitDialog = false }) { Text("取消") }
            }
        )
    }

    // 批量处理中：加载弹窗（批量下载显示获取进度，如 "正在获取下载链接 2/5"；可中断）
    if (viewModel.isBatchWorking) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelBatch() }) {
                    Text("中断", color = MaterialTheme.colorScheme.error)
                }
            },
            title = { Text("批量处理中") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = viewModel.batchProgress?.let { "正在获取下载链接 $it" }
                            ?: "正在批量处理，请稍候…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }

    // 添加至收藏弹窗（当前分享链接，支持自定义标题与分类）
    if (showAddBookmark) {
        AddToBookmarkDialog(
            title = session.title.ifBlank { "分享内容" },
            initialCategory = BookmarkEntity.DEFAULT_CATEGORY,
            categories = BookmarkEntity.PRESET_CATEGORIES,
            onConfirm = { title, category ->
                showAddBookmark = false
                viewModel.addCurrentToBookmark(title, category)
            },
            onDismiss = { showAddBookmark = false }
        )
    }

    // 转存弹窗：浏览网盘目录并保存（单文件转存；夸克/迅雷/百度按平台选目录选择器）
    if (viewModel.saveTarget != null) {
        when {
            viewModel.isSaveXunlei -> XunleiSaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = xunleiCloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            viewModel.isSaveBaidu -> BaiduSaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = baiduCloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            viewModel.isSaveC139 -> C139SaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = c139CloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            viewModel.isSaveUC -> UCSaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = ucCloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            viewModel.isSavePan123 -> Pan123SaveSheet(
                resolveViewModel = viewModel,
                cloudViewModel = pan123CloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
            else -> SaveToCloudSheet(
                resolveViewModel = viewModel,
                cloudViewModel = quarkCloudViewModel,
                onDismiss = { viewModel.dismissSave() }
            )
        }
    }
}

@Composable
internal fun BackToParentItem(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "返回上一级",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 可点击面包屑：根标题 > 目录1 > 目录2。
 * 非当前层可点击回退到对应目录；当前层高亮（文件夹图标 + 主题色）。
 * 横向滚动并自动定位到当前层。
 */
@Composable
internal fun CrumbBar(
    rootTitle: String,
    pathNames: List<String>,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val crumbs = buildList {
        add(rootTitle.ifBlank { "根目录" })
        pathNames.forEach { add(it) }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs.size, crumbs.lastOrNull()) {
        scroll.scrollTo(scroll.maxValue)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(start = 8.dp, top = 4.dp)
    ) {
        crumbs.forEachIndexed { i, name ->
            val isLast = i == crumbs.size - 1
            if (!isLast) {
                // 可点击层级：点击回退到该目录
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable { onNavigate(i) }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            } else {
                // 当前层：高亮 + 文件夹图标（不可点）
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShareFileRow(
    file: ShareFile,
    onClick: () -> Unit,
    /** 非空时行尾显示「转存」按钮 */
    onSave: (() -> Unit)? = null,
    /** 非空时行尾显示「更多」按钮（打开文件操作菜单） */
    onMore: (() -> Unit)? = null,
    /** 长按进入多选（多选模式下为 null） */
    onLongClick: (() -> Unit)? = null,
    /** 多选模式：是否选中 */
    selected: Boolean = false,
    /** 是否显示行首复选框（仅多选模式列表传 true；移动/转存等选择器不显示） */
    showCheckbox: Boolean = false,
    /** 列表项动画等（调用方传入 Modifier.animateItem()） */
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选模式：行首复选框（仅多选列表显示）
            if (showCheckbox) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (file.isdir) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (file.isdir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (file.isdir) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 文件名过长时滚动播放显示
                Text(
                    text = file.fname,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (file.isdir) "文件夹" else formatSize(file.fsize),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onSave != null) {
                IconButton(onClick = onSave, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.SaveAlt,
                        contentDescription = "转存",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (onMore != null) {
                IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "更多",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

internal fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format("%.1f %s", value, units[i])
}