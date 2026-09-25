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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.yunx.app.ui.components.YunXLoading
import com.yunx.app.ui.resolve.DownloadLinkDialog
import com.yunx.app.ui.resolve.BackToParentItem
import com.yunx.app.ui.resolve.CrumbBar
import com.yunx.app.ui.resolve.ShareFileRow
import com.yunx.app.ui.viewmodel.Pan123CloudUiState
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.effectsFast
import com.yunx.app.ui.theme.listGroupShape
import com.yunx.app.ui.theme.spatialDefault
import com.yunx.app.ui.theme.spatialFast

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
    // 批量操作弹窗：从多选底部栏直接进入某个步骤（分享/移动）
    var showBatchActions by remember { mutableStateOf(false) }
    var batchInitial by remember { mutableStateOf(BatchStep.MENU) }
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
                fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast())
            },
            label = "pan123CloudState"
        ) { s ->
            when (s) {
                is Pan123CloudUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { YunXLoading() }

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
                            // 列表组：各项首尾相接（只留 1dp 发丝缝区分行），行圆角按首/中/末分段给
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            item {
                                Column(modifier = Modifier.padding(bottom = 8.dp)) {
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
                                        enter = expandVertically(spatialDefault()) + fadeIn(effectsDefault()),
                                        exit = shrinkVertically(spatialFast()) + fadeOut(effectsFast())
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

                            // 返回上一级（独立于文件列表组，故自带下间距）
                            if (s.pathNames.isNotEmpty()) {
                                item {
                                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                        BackToParentItem(onClick = {
                                            // 记录当前目录滚动位置，返回上级后恢复上级位置
                                            scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                                            viewModel.back()
                                        })
                                    }
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

                            itemsIndexed(displayFiles, key = { _, f -> f.fid }) { index, file ->
                                ShareFileRow(
                                    file = file,
                                    shape = listGroupShape(index, displayFiles.size),
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
                        enter = slideInVertically(spatialDefault()) { it } + fadeIn(effectsDefault()),
                        exit = slideOutVertically(spatialFast()) { it } + fadeOut(effectsFast()),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        MultiSelectBar(
                            count = viewModel.selected.size,
                            actions = listOf(
                                MultiSelectAction("下载", Icons.Outlined.Download, MaterialTheme.colorScheme.primary) {
                                    viewModel.downloadSelected()
                                },
                                MultiSelectAction("分享", Icons.Outlined.Share, MaterialTheme.colorScheme.primary) {
                                    batchInitial = BatchStep.SHARE
                                    showBatchActions = true
                                },
                                MultiSelectAction("移动", Icons.Outlined.DriveFileMove, MaterialTheme.colorScheme.primary) {
                                    batchInitial = BatchStep.MOVE
                                    showBatchActions = true
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

    // 文件操作弹窗（单弹窗多步骤：菜单 → 移动/分享/重命名；六大网盘页同一实现）
    // ★ 删除确认与操作弹窗互斥展示：确认期间不关掉操作弹窗，否则 dismissActions() 会清空 actionFile
    val pendingDeleteTarget = when {
        !showDeleteConfirm -> null
        viewModel.multiSelectMode -> "选中的 ${viewModel.selected.size} 项"
        else -> viewModel.actionFile?.let { "「${it.fname}」" }
    }
    if (pendingDeleteTarget != null) {
        ConfirmDeleteSheet(
            target = pendingDeleteTarget,
            operating = viewModel.isOperating,
            onDismiss = {
                showDeleteConfirm = false
                viewModel.dismissActions()
            },
            onConfirm = { if (viewModel.multiSelectMode) viewModel.deleteSelected() else viewModel.deleteFile() }
        )
    } else if (showActionSheet && viewModel.actionFile != null) {
        FileActionSheet(
            file = viewModel.actionFile!!,
            operating = viewModel.isOperating,
            onDownload = { viewModel.downloadFile() },
            onDownloadFolder = { viewModel.downloadFolder() },
            onShare = { _, pwd, period -> viewModel.shareFile(period, pwd) },
            onRename = { viewModel.renameFile(it) },
            onDelete = { showDeleteConfirm = true },
            onDismiss = {
                showActionSheet = false
                viewModel.dismissActions()
            },
            moveStep = { onBack, onDone ->
                Pan123MoveStep(
                    subtitle = viewModel.actionFile?.fname ?: "",
                    viewModel = viewModel,
                    onBack = onBack,
                    onDone = onDone
                )
            }
        )
    }

    // 批量操作弹窗（多选底部栏的分享/移动/删除）
    if (showBatchActions) {
        BatchActionSheet(
            count = viewModel.selected.size,
            operating = viewModel.isOperating,
            onDownload = { viewModel.downloadSelected() },
            onShare = { _, pwd, period -> viewModel.shareSelected(period, pwd) },
            onDelete = {
                showBatchActions = false
                showDeleteConfirm = true
            },
            onDismiss = { showBatchActions = false },
            initialStep = batchInitial,
            moveStep = { onBack, onDone ->
                Pan123MoveStep(
                    subtitle = "已选 ${viewModel.selected.size} 项",
                    viewModel = viewModel,
                    onBack = onBack,
                    onDone = onDone
                )
            }
        )
    }

    viewModel.shareResult?.let { info ->
        ShareResultDialog(
            info = info,
            onDismiss = { viewModel.dismissShareResult() }
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
                    YunXLoading(modifier = Modifier.size(24.dp))
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


/** 移动目录选择弹窗（独立浏览，不影响主列表） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Pan123MoveStep(
subtitle: String,
viewModel: Pan123CloudViewModel,
onBack: () -> Unit,
onDone: () -> Unit
) {
val moveState by viewModel.moveUiState.collectAsState()
LaunchedEffect(Unit) { viewModel.openMoveRoot() }
Column(
    modifier = Modifier
        .fillMaxWidth()
        .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
) {
    StepHeader(title = "移动到", subtitle = subtitle, onBack = onBack)
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
            transitionSpec = { fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast()) },
            label = "pan123MoveState"
        ) { s ->
            when (s) {
                is Pan123CloudUiState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) { YunXLoading() }

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
                            // 目录列表同样拼成一组
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            itemsIndexed(dirs, key = { _, d -> d.fid }) { index, dir ->
                                ShareFileRow(
                                    file = dir,
                                    onClick = { viewModel.openMoveFolder(dir) },
                                    shape = listGroupShape(index, dirs.size)
                                )
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
                onDone()
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("移动到此处（$dirName）")
        }
}
}


private fun randomPan123Passcode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    return (1..4).map { chars.random() }.joinToString("")
}