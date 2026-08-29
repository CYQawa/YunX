package com.yunx.app.ui.screens

import com.yunx.app.ui.SnackbarController
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.ui.items.MultiSelectAction
import com.yunx.app.ui.items.MultiSelectBar
import com.yunx.app.ui.components.ScrollToTopButton
import com.yunx.app.ui.resolve.DownloadLinkDialog
import com.yunx.app.ui.resolve.BackToParentItem
import com.yunx.app.ui.resolve.CrumbBar
import com.yunx.app.ui.resolve.ShareFileRow
import com.yunx.app.ui.viewmodel.UCCloudUiState
import com.yunx.app.ui.viewmodel.UCCoudViewModel

/**
 * UC 网盘云盘浏览页（参考夸克 CloudDriveScreen）：
 * - 目录浏览 + 下拉刷新 + 面包屑回退
 * - 长按多选（批量下载/分享/移动）
 * - 文件/文件夹操作菜单（下载/重命名/移动/分享）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UCCoudScreen(
    viewModel: UCCoudViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    onExit: () -> Unit,
    onDownloadStarted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    // 系统返回键 → 子目录返回上一级，根目录返回账号列表（对齐解析页返回行为）
    BackHandler {
        val s = state
        if (s is UCCloudUiState.Loaded && s.pathNames.isNotEmpty()) viewModel.back() else onExit()
    }
    // 文件列表滚动状态（返回顶部按钮用）
    val listState = rememberLazyListState()
    // 文件操作弹窗步骤
    var showActionSheet by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    // 删除确认（单文件/批量共用）
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.cloudMessage) {
        viewModel.cloudMessage?.let {
            SnackbarController.show(it)
            viewModel.consumeMessage()
        }
    }

    // 下载入队后切到下载页（一次性消费）
    LaunchedEffect(viewModel.downloadTriggered) {
        if (viewModel.downloadTriggered > 0) {
            viewModel.consumeDownloadTriggered()
            onDownloadStarted()
        }
    }

    // 单文件下载确认弹窗（对齐解析页：展示直链，长按可复制）
    viewModel.downloadLink?.let { link ->
        DownloadLinkDialog(
            link = link,
            onDownload = { viewModel.startDownload() },
            onDismiss = { viewModel.dismissDownloadDialog() }
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        // 目录切换（进入文件夹/返回）：列表淡入淡出过渡
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(140))
            },
            label = "ucCloudState"
        ) { s ->
            when (s) {
            is UCCloudUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            is UCCloudUiState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onExit) { Text("返回") }
                        TextButton(onClick = { viewModel.loadRoot() }) { Text("重试") }
                    }
                }
            }

            is UCCloudUiState.Loaded -> Box(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = viewModel.refreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
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
                                                text = if (viewModel.selected.size == s.files.size) "已全选" else "点击选择更多文件",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        TextButton(onClick = { viewModel.toggleSelectAll(s.files) }) {
                                            Text(if (viewModel.selected.size == s.files.size) "取消全选" else "全选")
                                        }
                                    } else {
                                        IconButton(onClick = onExit) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "UC网盘",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "共 ${s.files.size} 项",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                if (!viewModel.multiSelectMode) {
                                    CrumbBar(
                                        rootTitle = "UC网盘",
                                        pathNames = s.pathNames,
                                        onNavigate = { viewModel.navigateToLevel(it) }
                                    )
                                }
                            }
                        }

                        if (s.pathNames.isNotEmpty()) {
                            item {
                                BackToParentItem(onClick = { viewModel.back() })
                            }
                        }

                        if (s.files.isEmpty()) {
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

                        items(s.files, key = { it.fid }) { file ->
                            ShareFileRow(
                                file = file,
                                modifier = Modifier.animateItem(),
                                onClick = {
                                    if (viewModel.multiSelectMode) {
                                        viewModel.toggleSelect(file)
                                    } else if (file.isdir) {
                                        viewModel.openFolder(file)
                                    } else {
                                        viewModel.openActions(file)
                                        showActionSheet = true
                                    }
                                },
                                onMore = if (!viewModel.multiSelectMode && file.isdir) {
                                    {
                                        viewModel.openActions(file)
                                        showActionSheet = true
                                    }
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

                // 多选模式：底部批量操作栏（底部滑入淡入，退出反向）
                AnimatedVisibility(
                    visible = viewModel.multiSelectMode,
                    enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                    exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    MultiSelectBar(
                        count = viewModel.selected.size,
                        actions = listOf(
                            MultiSelectAction("下载", Icons.Outlined.Download, MaterialTheme.colorScheme.primary) {
                                viewModel.downloadSelected()
                            },
                            MultiSelectAction("分享", Icons.Outlined.Share, MaterialTheme.colorScheme.primary) {
                                showShare = true
                            },
                            MultiSelectAction("移动", Icons.Outlined.DriveFileMove, MaterialTheme.colorScheme.primary) {
                                viewModel.openMoveRoot()
                                showMove = true
                            },
                            MultiSelectAction("删除", Icons.Outlined.Delete, MaterialTheme.colorScheme.error) {
                                showDeleteConfirm = true
                            }
                        )
                    )
                }
        }
    }
    }
    }

    // 文件操作菜单（下载/重命名/移动/分享）
    if (showActionSheet && viewModel.actionFile != null) {
        UCActionSheet(
            file = viewModel.actionFile!!,
            viewModel = viewModel,
            onDownload = {
                showActionSheet = false
                viewModel.downloadFile()
            },
            onDownloadFolder = {
                showActionSheet = false
                viewModel.downloadFolder()
            },
            onRename = {
                showActionSheet = false
                showRename = true
            },
            onMove = {
                showActionSheet = false
                viewModel.openMoveRoot()
                showMove = true
            },
            onShare = {
                showActionSheet = false
                showShare = true
            },
            onDelete = {
                showActionSheet = false
                showDeleteConfirm = true
            },
            onDismiss = {
                showActionSheet = false
                viewModel.dismissActions()
            }
        )
    }

    // 重命名弹窗
    if (showRename && viewModel.actionFile != null) {
        RenameCloudDialog(
            file = viewModel.actionFile!!,
            viewModel = viewModel,
            onDismiss = { showRename = false }
        )
    }

    // 移动目录选择弹窗（单文件/多选共用；多选时 actionFile 为 null，不能依赖它判断）
    if (showMove) {
        UCMoveSheet(
            viewModel = viewModel,
            onDismiss = { showMove = false }
        )
    }

    // 分享设置弹窗
    if (showShare) {
        UCShareSheet(
            viewModel = viewModel,
            onDismiss = { showShare = false }
        )
    }

    // 分享结果
    viewModel.shareResult?.let { info ->
        ShareResultDialog(
            info = info,
            onDismiss = { viewModel.dismissShareResult() }
        )
    }

    // 删除确认（单文件/批量共用）
    if (showDeleteConfirm) {
        val deleting = if (viewModel.multiSelectMode) "选中的 ${viewModel.selected.size} 项" else "「${viewModel.actionFile?.fname ?: ""}」"
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除文件") },
            text = { Text("确定要删除$deleting 吗？删除后将移入回收站。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        if (viewModel.multiSelectMode) viewModel.deleteSelected() else viewModel.deleteFile()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // 操作执行中加载弹窗（下载文件夹/批量下载显示进度）
    if (viewModel.isOperating) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDownload() }) {
                    Text("中断", color = MaterialTheme.colorScheme.error)
                }
            },
            title = { Text("处理中") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = viewModel.folderProgress ?: "正在处理，请稍候…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }
}

/** UC 文件操作菜单：下载/重命名/移动/分享 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UCActionSheet(
    file: ShareFile,
    viewModel: UCCoudViewModel,
    onDownload: () -> Unit,
    onDownloadFolder: (() -> Unit)? = null,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (file.isdir) Icons.Outlined.DriveFileMove else Icons.Outlined.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.fname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        text = if (file.isdir) "文件夹" else "文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            if (!file.isdir) {
                UCActionItem(Icons.Outlined.Download, "下载", "使用内置下载功能保存到本机", MaterialTheme.colorScheme.primary, onDownload)
            } else if (onDownloadFolder != null) {
                UCActionItem(Icons.Outlined.Download, "下载文件夹", "递归下载整个文件夹，保持目录结构", MaterialTheme.colorScheme.primary, onDownloadFolder)
            }
            UCActionItem(Icons.Outlined.Share, "分享", "生成分享链接（可设提取码/有效期）", MaterialTheme.colorScheme.primary, onShare)
            UCActionItem(Icons.Outlined.DriveFileMove, "移动到", "移动到网盘的其他目录", MaterialTheme.colorScheme.primary, onMove)
            UCActionItem(Icons.Outlined.Edit, "重命名", "修改文件名", MaterialTheme.colorScheme.primary, onRename)
            UCActionItem(Icons.Outlined.Delete, "删除", "移入回收站", MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

@Composable
private fun UCActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = MaterialTheme.shapes.large,
            color = tint.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 重命名弹窗 */
@Composable
private fun RenameCloudDialog(
    file: ShareFile,
    viewModel: UCCoudViewModel,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(file.fname) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新文件名") },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    if (name.isNotBlank() && name != file.fname) viewModel.renameFile(name.trim())
                },
                enabled = name.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 移动目录选择弹窗（独立浏览，不影响主列表） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UCMoveSheet(
    viewModel: UCCoudViewModel,
    onDismiss: () -> Unit
) {
    val moveState by viewModel.moveUiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.openMoveRoot() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Text("移动到", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            CrumbBar(
                rootTitle = "根目录",
                pathNames = (moveState as? UCCloudUiState.Loaded)?.pathNames ?: emptyList(),
                onNavigate = { viewModel.moveNavigateToLevel(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
// 返回上一级：固定在目录区上方（不参与 AnimatedContent 过渡，避免与目录内容交叉叠加）
            if ((moveState as? UCCloudUiState.Loaded)?.pathNames?.isNotEmpty() == true) {
                BackToParentItem(onClick = { viewModel.moveBack() })
                Spacer(modifier = Modifier.height(4.dp))
            }
            // 移动目录切换：淡入过渡
            AnimatedContent(
                targetState = moveState,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "ucMoveState"
            ) { s ->
                when (s) {
                    is UCCloudUiState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    is UCCloudUiState.Error -> Box(
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                    is UCCloudUiState.Loaded -> {
                        val dirs = s.files.filter { it.isdir }
                    if (dirs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "当前目录没有子文件夹，可直接移动到此处",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(dirs, key = { it.fid }) { dir ->
                                ShareFileRow(file = dir, onClick = { viewModel.openMoveFolder(dir) })
                            }
                        }
                    }
                }
            }
            }
            Spacer(modifier = Modifier.height(16.dp))
            val dirName = (moveState as? UCCloudUiState.Loaded)?.pathNames?.lastOrNull() ?: "根目录"
            Button(
                onClick = {
                    val to = (moveState as? UCCloudUiState.Loaded)?.dirFid ?: "0"
                    // 多选模式走批量移动，单文件走单文件移动
                    if (viewModel.multiSelectMode) viewModel.moveSelected(to) else viewModel.moveFile(to)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("移动到此处（$dirName）")
            }
        }
    }
}

/** 分享设置弹窗（提取码/有效期） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UCShareSheet(
    viewModel: UCCoudViewModel,
    onDismiss: () -> Unit
) {
    var withPassword by remember { mutableStateOf(false) }
    var passcode by remember { mutableStateOf("") }
    var expiredType by remember { mutableStateOf(1) }
    val expireOptions = listOf("永久有效" to 1, "1 天" to 2, "7 天" to 3, "30 天" to 4)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Text("分享文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(16.dp))
            Text("提取码", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !withPassword,
                    onClick = { withPassword = false },
                    label = { Text("无提取码") },
                    colors = FilterChipDefaults.filterChipColors()
                )
                FilterChip(
                    selected = withPassword,
                    onClick = {
                        withPassword = true
                        if (passcode.isBlank()) passcode = randomPasscode()
                    },
                    label = { Text("设置提取码") },
                    colors = FilterChipDefaults.filterChipColors()
                )
            }
            if (withPassword) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = passcode,
                    onValueChange = { passcode = it.take(4).filter { c -> c.isLetterOrDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("4 位提取码") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("有效期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                expireOptions.forEach { (name, value) ->
                    FilterChip(
                        selected = expiredType == value,
                        onClick = { expiredType = value },
                        label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    if (viewModel.multiSelectMode) {
                        viewModel.shareSelected(
                            urlType = if (withPassword) 2 else 1,
                            passcode = passcode,
                            expiredType = expiredType
                        )
                    } else {
                        viewModel.shareFile(
                            urlType = if (withPassword) 2 else 1,
                            passcode = passcode,
                            expiredType = expiredType
                        )
                    }
                    onDismiss()
                },
                enabled = !withPassword || passcode.length == 4,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("创建分享")
            }
        }
    }
}

private fun randomPasscode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    return (1..4).map { chars.random() }.joinToString("")
}