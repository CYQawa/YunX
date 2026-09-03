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

import com.yunx.app.ui.SnackbarController
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.yunx.app.ui.viewmodel.Pan123CloudUiState
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel

/**
 * 123 云盘云盘浏览页（参考 139/百度云盘）：
 * - 目录浏览 + 下拉刷新 + 面包屑回退
 * - 长按多选（批量下载/分享/移动/删除）
 * - 文件/文件夹操作菜单（下载/重命名/移动/分享/删除）
 * 认证走 Bearer token（Pan123AccountEntity.accessToken），目录用 fileId（根="0"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pan123CloudScreen(
    viewModel: Pan123CloudViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    onExit: () -> Unit,
    onDownloadStarted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    // 系统返回键：多选模式下先退出多选；否则子目录返回上一级，根目录返回账号列表
    BackHandler {
        if (viewModel.multiSelectMode) {
            viewModel.exitMultiSelect()
        } else {
            val s = state
            if (s is Pan123CloudUiState.Loaded && s.pathNames.isNotEmpty()) viewModel.back() else onExit()
        }
    }
    // 文件列表滚动状态（返回顶部按钮用）
    val listState = rememberLazyListState()
    // 搜索过滤（本地过滤当前目录文件）
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    // 各目录滚动位置记忆：进入文件夹/返回时按目录路径恢复，避免返回后列表回到顶部
    val scrollPositions = remember { mutableStateMapOf<String, Int>() }
    val loadedState = state as? Pan123CloudUiState.Loaded
    val displayFiles = remember(loadedState?.files, searchQuery) {
        val files = loadedState?.files ?: emptyList()
        val q = searchQuery.trim()
        if (q.isEmpty()) files else files.filter { it.fname.contains(q, ignoreCase = true) }
    }
    val currentDirKey = remember(loadedState?.pathNames) {
        loadedState?.pathNames?.joinToString("/") ?: ""
    }
    var showActionSheet by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.cloudMessage) {
        viewModel.cloudMessage?.let {
            SnackbarController.show(it)
            viewModel.consumeMessage()
        }
    }

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
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(140))
            },
            label = "pan123CloudState"
        ) { s ->
            when (s) {
                is Pan123CloudUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is Pan123CloudUiState.Error -> Box(
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

                is Pan123CloudUiState.Loaded -> Box(modifier = Modifier.fillMaxSize()) {
                // 目录加载完成、列表挂载后恢复该目录上次滚动位置（避免 Loading 阶段误触发）
                val loadedKey = remember(s.pathNames) { s.pathNames.joinToString("/") }
                LaunchedEffect(loadedKey) {
                    listState.scrollToItem(scrollPositions[loadedKey] ?: 0)
                }
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
                                                    text = if (viewModel.selected.size == displayFiles.size) "已全选" else "点击选择更多文件",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            TextButton(onClick = { viewModel.toggleSelectAll(displayFiles) }) {
                                                Text(if (viewModel.selected.size == displayFiles.size) "取消全选" else "全选")
                                            }
                                        } else {
                                            IconButton(onClick = onExit) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "123云盘",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = if (searchQuery.isBlank()) "共 ${s.files.size} 项"
                                                    else "匹配 ${displayFiles.size} / ${s.files.size} 项",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            // 放大镜：点击展开/收起搜索框
                                            IconButton(onClick = { showSearch = !showSearch }) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Search,
                                                    contentDescription = if (showSearch) "关闭搜索" else "搜索文件",
                                                    tint = if (showSearch) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    if (!viewModel.multiSelectMode) {
                                        CrumbBar(
                                            rootTitle = "123云盘",
                                            pathNames = s.pathNames,
                                            onNavigate = { level ->
                                                scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                                                viewModel.navigateToLevel(level)
                                            }
                                        )
                                    }
                                    // 搜索框（点击放大镜展开；与面包屑保持间距 + 展开/收起动画）
                                    AnimatedVisibility(
                                    visible = showSearch && !viewModel.multiSelectMode,
                                        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                                        exit = shrinkVertically(tween(140)) + fadeOut(tween(120))
                                    ) {
                                        Column {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            OutlinedTextField(
                                                value = searchQuery,
                                                onValueChange = { searchQuery = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                placeholder = { Text("搜索当前目录文件") },
                                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                                trailingIcon = {
                                                    if (searchQuery.isNotEmpty()) {
                                                        IconButton(onClick = { searchQuery = "" }) {
                                                            Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                                                        }
                                                    }
                                                },
                                                singleLine = true,
                                                shape = MaterialTheme.shapes.large
                                            )
                                        }
                                    }
                                }
                            }

                            if (s.pathNames.isNotEmpty()) {
                                item {
                                    BackToParentItem(onClick = {
                                        // 记录当前目录滚动位置，返回上级后恢复上级位置
                                        scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                                        viewModel.back()
                                    })
                                }
                            }

                            if (displayFiles.isEmpty()) {
                                item {
                                    Text(
                                        text = if (s.files.isEmpty()) "此目录为空" else "未找到匹配「${searchQuery.trim()}」的文件",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            items(displayFiles, key = { it.fid }) { file ->
                                ShareFileRow(
                                    file = file,
                                    onClick = {
                                        if (viewModel.multiSelectMode) {
                                            viewModel.toggleSelect(file)
                                        } else if (file.isdir) {
                                            // 记录当前目录滚动位置，进入子目录后恢复子目录位置
                                            scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
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

    // 文件操作菜单
    if (showActionSheet && viewModel.actionFile != null) {
        Pan123ActionSheet(
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

    if (showRename && viewModel.actionFile != null) {
        Pan123RenameDialog(
            file = viewModel.actionFile!!,
            viewModel = viewModel,
            onDismiss = { showRename = false }
        )
    }

    if (showMove) {
        Pan123MoveSheet(
            viewModel = viewModel,
            onDismiss = { showMove = false }
        )
    }

    if (showShare) {
        Pan123ShareSheet(
            viewModel = viewModel,
            onDismiss = { showShare = false }
        )
    }

    viewModel.shareResult?.let { info ->
        ShareResultDialog(
            info = info,
            onDismiss = { viewModel.dismissShareResult() }
        )
    }

    if (showDeleteConfirm) {
        val deleting = if (viewModel.multiSelectMode) "选中的 ${viewModel.selected.size} 项" else "「${viewModel.actionFile?.fname ?: ""}」"
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除文件") },
            text = { Text("确定要删除$deleting 吗？删除后进入回收站。") },
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

/** 123 文件操作菜单：下载/分享/移动/重命名/删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Pan123ActionSheet(
    file: ShareFile,
    viewModel: Pan123CloudViewModel,
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
                Pan123ActionItem(Icons.Outlined.Download, "下载", "使用内置下载功能保存到本机", MaterialTheme.colorScheme.primary, onDownload)
            } else if (onDownloadFolder != null) {
                Pan123ActionItem(Icons.Outlined.Download, "下载文件夹", "递归下载整个文件夹，保持目录结构", MaterialTheme.colorScheme.primary, onDownloadFolder)
            }
            Pan123ActionItem(Icons.Outlined.Share, "分享", "生成分享链接（可设提取码/有效期）", MaterialTheme.colorScheme.primary, onShare)
            Pan123ActionItem(Icons.Outlined.DriveFileMove, "移动到", "移动到网盘的其他目录", MaterialTheme.colorScheme.primary, onMove)
            Pan123ActionItem(Icons.Outlined.Edit, "重命名", "修改文件名", MaterialTheme.colorScheme.primary, onRename)
            Pan123ActionItem(Icons.Outlined.Delete, "删除", "删除到回收站", MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

@Composable
private fun Pan123ActionItem(
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
private fun Pan123RenameDialog(
    file: ShareFile,
    viewModel: Pan123CloudViewModel,
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
private fun Pan123MoveSheet(
    viewModel: Pan123CloudViewModel,
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
                pathNames = (moveState as? Pan123CloudUiState.Loaded)?.pathNames ?: emptyList(),
                onNavigate = { viewModel.moveNavigateToLevel(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            if ((moveState as? Pan123CloudUiState.Loaded)?.pathNames?.isNotEmpty() == true) {
                BackToParentItem(onClick = { viewModel.moveBack() })
                Spacer(modifier = Modifier.height(4.dp))
            }
            AnimatedContent(
                targetState = moveState,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "pan123MoveState"
            ) { s ->
                when (s) {
                    is Pan123CloudUiState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    is Pan123CloudUiState.Error -> Box(
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                    is Pan123CloudUiState.Loaded -> {
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
            val dirName = (moveState as? Pan123CloudUiState.Loaded)?.pathNames?.lastOrNull() ?: "根目录"
            Button(
                onClick = {
                    val to = (moveState as? Pan123CloudUiState.Loaded)?.dirId ?: "0"
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

/** 分享设置弹窗（有效期 + 可选提取码） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Pan123ShareSheet(
    viewModel: Pan123CloudViewModel,
    onDismiss: () -> Unit
) {
    var withPassword by remember { mutableStateOf(false) }
    var passcode by remember { mutableStateOf("") }
    var period by remember { mutableStateOf<Int?>(null) }
    val periodOptions = listOf<Pair<String, Int?>>(
        "永久有效" to null,
        "1 天" to 1,
        "7 天" to 7,
        "30 天" to 30
    )
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (viewModel.multiSelectMode) "已选 ${viewModel.selected.size} 项" else "分享到 123 云盘链接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                        if (passcode.isBlank()) passcode = randomPan123Passcode()
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
                periodOptions.forEach { (name, value) ->
                    FilterChip(
                        selected = period == value,
                        onClick = { period = value },
                        label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    val pwd = if (withPassword) passcode.takeIf { it.isNotBlank() } else null
                    if (viewModel.multiSelectMode) {
                        viewModel.shareSelected(period, pwd)
                    } else {
                        viewModel.shareFile(period, pwd)
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

private fun randomPan123Passcode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    return (1..4).map { chars.random() }.joinToString("")
}