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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.yunx.app.ui.items.MultiSelectAction
import com.yunx.app.ui.items.MultiSelectBar
import com.yunx.app.ui.components.ScrollToTopButton
import com.yunx.app.ui.resolve.DownloadLinkDialog
import com.yunx.app.ui.resolve.BackToParentItem
import com.yunx.app.ui.resolve.CrumbBar
import com.yunx.app.ui.resolve.ShareFileRow
import com.yunx.app.ui.viewmodel.QuarkCloudUiState
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel

/**
 * 夸克云盘浏览页：展示个人网盘文件，支持进入文件夹 / 返回 / 面包屑回退。
 * 复用解析详情页的 ShareFileRow / CrumbBar / BackToParentItem 组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudDriveScreen(
    viewModel: QuarkCloudViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    onExit: () -> Unit,
    /** 下载入队后通知上层切换到「下载」Tab（对齐解析页行为） */
    onDownloadStarted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    // 系统返回键 → 子目录返回上一级，根目录返回账号列表（对齐解析页返回行为）
    BackHandler {
        val s = state
        if (s is QuarkCloudUiState.Loaded && s.pathNames.isNotEmpty()) viewModel.back() else onExit()
    }
    // 文件列表滚动状态（返回顶部按钮用）
    val listState = rememberLazyListState()
    // 批量操作弹窗（多选模式底部栏触发：分享/移动需要设置或选目录，下载/删除直接执行）
    var showBatchActions by remember { mutableStateOf(false) }
    var batchInitial by remember { mutableStateOf(com.yunx.app.ui.screens.BatchStep.MENU) }
    // 批量删除二次确认
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 操作结果 Toast（放在本层：弹窗关闭后仍能正常弹出）
    LaunchedEffect(viewModel.cloudMessage) {
        viewModel.cloudMessage?.let {
            SnackbarController.show(it)
            viewModel.consumeMessage()
        }
    }

    // 下载入队后通知上层切换到「下载」Tab（消费事件，避免再次进入本页重复触发）
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

    // 不透明背景包裹：避免 Tab 内切换时透出下层内容（账号列表）导致视觉重叠
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
            label = "cloudState"
        ) { s ->
            when (s) {
            is QuarkCloudUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            is QuarkCloudUiState.Error -> Box(
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

            is QuarkCloudUiState.Loaded -> Box(modifier = Modifier.fillMaxSize()) {
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
                                    text = "夸克网盘",
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
                    // 可点击面包屑（多选模式下隐藏）
                    if (!viewModel.multiSelectMode) {
                        CrumbBar(
                            rootTitle = "夸克网盘",
                            pathNames = s.pathNames,
                            onNavigate = { viewModel.navigateToLevel(it) }
                        )
                    }
                }
            }

            // 返回上一级（根目录时不显示）
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
                        }
                    },
                    // 多选模式：隐藏行尾按钮；非多选时文件夹显示「更多」、全部可长按进入多选
                    onMore = if (!viewModel.multiSelectMode && file.isdir) {
                        { viewModel.openActions(file) }
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
                                // 批量下载：保持网盘页显示处理中弹窗，不自动切页
                                viewModel.downloadSelected()
                            },
                            MultiSelectAction("分享", Icons.Outlined.Share, MaterialTheme.colorScheme.primary) {
                                batchInitial = com.yunx.app.ui.screens.BatchStep.SHARE
                                showBatchActions = true
                            },
                            MultiSelectAction("移动", Icons.Outlined.DriveFileMove, MaterialTheme.colorScheme.primary) {
                                batchInitial = com.yunx.app.ui.screens.BatchStep.MOVE
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
    }

    // 文件操作弹窗（更多按钮/点击文件 → 下载/分享/移动/重命名/删除）
    viewModel.actionFile?.let { file ->
        FileActionSheet(
            file = file,
            viewModel = viewModel,
            onDismiss = { viewModel.dismissActions() }
        )
    }

    // 批量操作弹窗（长按多选 → 底部栏分享/移动）
    if (showBatchActions) {
        BatchActionSheet(
            viewModel = viewModel,
            initialStep = batchInitial,
            onDismiss = { showBatchActions = false }
        )
    }

    // 批量删除二次确认（底部栏点删除直接弹确认）
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除文件") },
            text = { Text("确定要删除选中的 ${viewModel.selected.size} 项吗？删除后将移入回收站。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteSelected()
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

    // 操作执行中：加载弹窗（下载取链/分享/移动/删除；下载文件夹显示进度）
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
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