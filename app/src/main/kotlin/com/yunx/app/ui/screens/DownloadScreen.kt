package com.yunx.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.download.DownloadStats
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.viewmodel.DownloadViewModel
import java.io.File

/**
 * 下载页：任务列表（分片多线程下载 / 断点续传）、进度展示、暂停/继续/删除/打开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: DownloadViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tasks by viewModel.tasks.collectAsState()
    val stats by viewModel.stats.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    // Android 9- 写公共目录需要 WRITE_EXTERNAL_STORAGE
    val needLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showAddDialog = true
        else SnackbarController.show("需要存储权限才能保存到下载目录")
    }
    val hasPermission = remember {
        if (needLegacyPermission) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (tasks.isEmpty()) {
            EmptyDownloadState(modifier = Modifier.align(Alignment.Center))
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 批量操作栏：全部暂停 / 全部开始 / 删除全部
                DownloadBatchBar(
                    hasActive = tasks.any {
                        it.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                            it.status == DownloadTaskEntity.STATUS_PENDING
                    },
                    hasResumable = tasks.any {
                        it.status == DownloadTaskEntity.STATUS_PAUSED ||
                            it.status == DownloadTaskEntity.STATUS_FAILED
                    },
                    onPauseAll = { viewModel.pauseAll() },
                    onResumeAll = { viewModel.resumeAll() },
                    onDeleteAll = { showDeleteAllConfirm = true }
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 根目录任务（无相对路径）单独显示
                    val rootTasks = tasks.filter { !it.fileName.contains('/') }
                    // 文件夹下载任务：按「顶级目录」分组（整个文件夹归为一组，内部子文件夹不拆开）
                    val folderGroups = tasks.filter { it.fileName.contains('/') }
                        .groupBy { it.fileName.substringBefore('/') }

                    items(rootTasks, key = { it.id }) { task ->
                        DownloadTaskCard(
                            task = task,
                            stats = stats[task.id],
                            onPause = { viewModel.pause(task.id) },
                            onResume = { viewModel.resume(task.id) },
                            onRemove = { pendingDelete = task },
                            onRedownload = { viewModel.redownload(task) }
                        )
                    }

                    folderGroups.forEach { (folder, groupTasks) ->
                        item(key = "folder_$folder") {
                            FolderDownloadGroup(
                                folder = folder,
                                tasks = groupTasks,
                                stats = stats,
                                onPause = { viewModel.pause(it) },
                                onResume = { viewModel.resume(it) },
                                onRemove = { pendingDelete = it },
                                onRedownload = { viewModel.redownload(it) }
                            )
                        }
                    }
                }
            }
        }

        }

    if (showAddDialog) {
        AddDownloadDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url, name ->
                showAddDialog = false
                viewModel.enqueue(url, name)
            }
        )
    }

    // 删除二次确认（可选同时删除本地文件）
    pendingDelete?.let { task ->
        DeleteConfirmDialog(
            task = task,
            onDismiss = { pendingDelete = null },
            onConfirm = { deleteLocal ->
                pendingDelete = null
                viewModel.remove(task.id, deleteLocal)
            }
        )
    }

    // 删除全部任务二次确认（可选同时删除本地文件）
    if (showDeleteAllConfirm) {
        var deleteAllLocal by remember { mutableStateOf(false) }
        val hasCompletedFile = tasks.any {
            it.status == DownloadTaskEntity.STATUS_COMPLETED && it.savePath.isNotBlank()
        }
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("删除全部任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "确定删除所有下载任务吗？删除后任务记录将被清除，且不可恢复。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (hasCompletedFile) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = deleteAllLocal,
                                onCheckedChange = { deleteAllLocal = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "同时删除本地文件",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = "勾选后将一并删除所有已下载到 Download 目录的文件，且不可恢复。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllConfirm = false
                        viewModel.removeAll(deleteAllLocal)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("全部删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 批量操作栏：全部暂停 / 全部开始 / 删除全部（Material3 紧凑按钮，无可用操作时禁用） */
@Composable
private fun DownloadBatchBar(
    hasActive: Boolean,
    hasResumable: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onDeleteAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onPauseAll, enabled = hasActive) {
            Icon(
                imageVector = Icons.Outlined.Pause,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("全部暂停")
        }
        TextButton(onClick = onResumeAll, enabled = hasResumable) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("全部开始")
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onDeleteAll) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("删除全部", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    task: DownloadTaskEntity,
    onDismiss: () -> Unit,
    onConfirm: (deleteLocal: Boolean) -> Unit
) {
    var deleteLocal by remember { mutableStateOf(false) }
    val hasLocalFile = task.status == DownloadTaskEntity.STATUS_COMPLETED && task.savePath.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除下载任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "确定删除「${task.fileName}」吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (hasLocalFile) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = deleteLocal,
                            onCheckedChange = { deleteLocal = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "同时删除本地文件",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Text(
                    text = if (hasLocalFile) {
                        "勾选后将一并删除已下载到 Download 目录的文件，且不可恢复。"
                    } else if (task.status == DownloadTaskEntity.STATUS_COMPLETED) {
                        "该任务没有已完成的本地文件。"
                    } else {
                        "该任务尚未完成，删除后将同时清除已下载的临时文件，且不可恢复。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteLocal) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun EmptyDownloadState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无下载任务",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "解析分享后点击文件即可加入下载队列\n也可点击右下角按钮手动添加",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 文件夹下载组：同一「顶级目录」下的所有任务合并为一个可展开卡片。
 * 收起时显示文件夹名 + 统计 + 总体进度；展开后显示子任务（含子文件夹内文件）紧凑列表。
 */
@Composable
private fun FolderDownloadGroup(
    folder: String,
    tasks: List<DownloadTaskEntity>,
    stats: Map<Long, DownloadStats>,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onRemove: (DownloadTaskEntity) -> Unit,
    onRedownload: (DownloadTaskEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val completed = tasks.count { it.status == DownloadTaskEntity.STATUS_COMPLETED }
    val totalSize = tasks.sumOf { it.totalSize }
    // 聚合显示钳制：任何单项竞态残留都不会让"已下载 > 总大小"
    val downloaded = minOf(tasks.sumOf { it.downloadedSize }, totalSize)
    val fraction = if (totalSize > 0) {
        (downloaded.toFloat() / totalSize).coerceIn(0f, 1f)
    } else 0f
    val done = completed == tasks.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            // 头部：点击展开/收起
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 文件夹图标（圆角方块，主色容器）
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${completed}/${tasks.size} 个文件 · ${formatSize(downloaded)} / ${formatSize(totalSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 总体进度徽标
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (done) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Text(
                        text = if (done) "已完成" else "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (done) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 展开区：总体进度条 + 子任务紧凑列表
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(200)) + expandVertically(tween(200), expandFrom = Alignment.Top),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150), shrinkTowards = Alignment.Top)
            ) {
                Column {
                    // 总体进度条（已完成时隐藏）
                    if (!done) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }
                    // 子任务列表（紧凑行，含子文件夹内文件）
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tasks.forEach { task ->
                            DownloadSubTaskRow(
                                task = task,
                                stats = stats[task.id],
                                onPause = { onPause(task.id) },
                                onResume = { onResume(task.id) },
                                onRemove = { onRemove(task) },
                                onRedownload = { onRedownload(task) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 文件夹组内子任务紧凑行：相对路径 + 状态 + 进度条 + 操作按钮。
 * 相比独立任务卡更轻量，适合嵌套在文件夹组内。
 */
@Composable
private fun DownloadSubTaskRow(
    task: DownloadTaskEntity,
    stats: DownloadStats?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRedownload: () -> Unit
) {
    val context = LocalContext.current
    val isDownloading = task.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
        task.status == DownloadTaskEntity.STATUS_PENDING
    val fraction = if (task.totalSize > 0) {
        (task.downloadedSize.toFloat() / task.totalSize).coerceIn(0f, 1f)
    } else 0f
    // 显示相对路径（去掉顶级目录前缀，如 "A/B/b.mp4" → "B/b.mp4"）
    val displayName = task.fileName.substringAfter('/')
    // 长按任务行弹出操作菜单（复制直链 / 重新下载 / 删除）
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 文件小图标
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            isDownloading && stats != null && stats.speed > 0 ->
                                "${DownloadTaskEntity.statusText(task.status)} · ${formatSpeed(stats.speed)}"
                            task.status == DownloadTaskEntity.STATUS_COMPLETED && task.avgSpeed > 0 ->
                                "${DownloadTaskEntity.statusText(task.status)} · 平均 ${formatSpeed(task.avgSpeed)}"
                            else -> taskStatusLine(task)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task.status == DownloadTaskEntity.STATUS_FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                // 主操作（暂停/继续/重试/打开）
                when (task.status) {
                    DownloadTaskEntity.STATUS_DOWNLOADING,
                    DownloadTaskEntity.STATUS_PENDING -> IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Pause, contentDescription = "暂停",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                        )
                    }
                    DownloadTaskEntity.STATUS_PAUSED -> IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.PlayArrow, contentDescription = "继续",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                        )
                    }
                    DownloadTaskEntity.STATUS_FAILED -> IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Refresh, contentDescription = "重试",
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)
                        )
                    }
                    DownloadTaskEntity.STATUS_COMPLETED -> IconButton(
                        onClick = { openSavedFile(context, task.savePath) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.OpenInNew, contentDescription = "打开",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                        )
                    }
                }
                // 删除
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Delete, contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)
                    )
                }
            }
            // 细进度条（完成态折叠，带过渡动画）
            AnimatedVisibility(
                visible = task.status != DownloadTaskEntity.STATUS_COMPLETED,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
            ) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5f)),
                    color = if (task.status == DownloadTaskEntity.STATUS_FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }

        // 长按任务行弹出操作菜单（复制直链 / 重新下载 / 删除）
        if (showMenu) {
            AlertDialog(
                onDismissRequest = { showMenu = false },
                title = {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                text = {
                    Column {
                        TextButton(onClick = {
                            showMenu = false
                            copyToClipboard(context, task.url)
                            SnackbarController.show("直链已复制")
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("复制直链")
                        }
                        TextButton(onClick = {
                            showMenu = false
                            onRedownload()
                        }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("重新下载")
                        }
                        TextButton(onClick = {
                            showMenu = false
                            onRemove()
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("删除任务")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMenu = false }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTaskEntity,
    stats: DownloadStats?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRedownload: () -> Unit
) {
    val context = LocalContext.current
    val isDownloading = task.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
        task.status == DownloadTaskEntity.STATUS_PENDING
    val fraction = if (task.totalSize > 0) {
        (task.downloadedSize.toFloat() / task.totalSize).coerceIn(0f, 1f)
    } else 0f
    // 长按任务卡弹出操作菜单（复制直链 / 重新下载 / 删除）
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClick = {},
                onLongClick = { showMenu = true }
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = taskStatusLine(task),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 主操作按钮
                when (task.status) {
                    DownloadTaskEntity.STATUS_DOWNLOADING,
                    DownloadTaskEntity.STATUS_PENDING -> IconButton(onClick = onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = "暂停", tint = MaterialTheme.colorScheme.primary)
                    }
                    DownloadTaskEntity.STATUS_PAUSED -> IconButton(onClick = onResume) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "继续", tint = MaterialTheme.colorScheme.primary)
                    }
                    DownloadTaskEntity.STATUS_FAILED -> IconButton(onClick = onResume) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重试", tint = MaterialTheme.colorScheme.error)
                    }
                                        DownloadTaskEntity.STATUS_COMPLETED -> Row {
                        // APK 文件：额外显示「安装」按钮
                        if (task.fileName.endsWith(".apk", true)) {
                            IconButton(onClick = { installApk(context, task.savePath, task.fileName) }) {
                                Icon(
                                    imageVector = Icons.Outlined.SystemUpdate,
                                    contentDescription = "安装",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = {
                            openSavedFile(context, task.savePath)
                        }) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = "打开", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 失败原因（红色小字展示具体错误）
            if (task.status == DownloadTaskEntity.STATUS_FAILED && task.errorMsg.isNotBlank()) {
                Text(
                    text = "失败原因：${task.errorMsg}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // 实时统计 + 进度条：完成态整体折叠（带高度过渡动画，不残留空白）
            AnimatedVisibility(
                visible = task.status != DownloadTaskEntity.STATUS_COMPLETED,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
            ) {
                Column {
                    if (isDownloading && stats != null && stats.speed > 0) {
                        Text(
                            text = "${formatSpeed(stats.speed)} · 剩余 ${formatRemain(stats.remainMillis)} · ${stats.chunkCount} 线程",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = when (task.status) {
                            DownloadTaskEntity.STATUS_FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (task.status == DownloadTaskEntity.STATUS_COMPLETED) {
                        if (task.avgSpeed > 0) {
                            "平均 ${formatSpeed(task.avgSpeed)} · ${formatSize(task.totalSize)}"
                        } else {
                            formatSize(task.totalSize)
                        }
                    } else {
                        progressText(task)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // 长按任务卡弹出操作菜单（复制直链 / 重新下载 / 删除）
        if (showMenu) {
            AlertDialog(
                onDismissRequest = { showMenu = false },
                title = {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                text = {
                    Column {
                        TextButton(onClick = {
                            showMenu = false
                            copyToClipboard(context, task.url)
                            SnackbarController.show("直链已复制")
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("复制直链")
                        }
                        TextButton(onClick = {
                            showMenu = false
                            onRedownload()
                        }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("重新下载")
                        }
                        TextButton(onClick = {
                            showMenu = false
                            onRemove()
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("删除任务")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMenu = false }) { Text("取消") }
                }
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("yunx_url", text))
}

private fun taskStatusLine(task: DownloadTaskEntity): String {
    val status = DownloadTaskEntity.statusText(task.status)
    return if (task.totalSize > 0) {
        // 显示值钳制到 total（防恢复竞态残留导致显示超总大小）
        val shown = minOf(task.downloadedSize, task.totalSize)
        "$status · ${formatSize(shown)} / ${formatSize(task.totalSize)}"
    } else {
        status
    }
}

private fun progressText(task: DownloadTaskEntity): String {
    if (task.totalSize <= 0) return ""
    // 显示值钳制到 total（防恢复竞态残留导致显示超总大小）
    val shown = minOf(task.downloadedSize, task.totalSize)
    val percent = (shown * 100 / task.totalSize).toInt().coerceIn(0, 100)
    return "已下载 ${formatSize(shown)} / ${formatSize(task.totalSize)} · $percent%"
}

private fun formatSize(bytes: Long): String {
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

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 B/s"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSec.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format("%.1f %s", value, units[i])
}

private fun formatRemain(millis: Long): String {
    if (millis < 0) return "计算中"
    val sec = millis / 1000
    return when {
        sec < 60 -> "${sec}秒"
        sec < 3600 -> "${sec / 60}分${sec % 60}秒"
        else -> "${sec / 3600}时${(sec % 3600) / 60}分"
    }
}

private fun openSavedFile(context: android.content.Context, savePath: String) {
    if (savePath.isBlank()) return
    val uri = if (savePath.startsWith("content://")) {
        Uri.parse(savePath)
    } else {
        // Android 7.0+ 禁止暴露 file:// URI，必须经 FileProvider 转 content://
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(savePath))
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "打开文件"))
    }.onFailure {
        SnackbarController.show("无法打开该文件")
    }
}

/** 安装 APK：检查「安装未知来源应用」权限（Android 8+），ACTION_VIEW 调起系统安装器 */
private fun installApk(context: android.content.Context, savePath: String, fileName: String) {
    if (savePath.isBlank()) {
        SnackbarController.show("文件不存在")
        return
    }
    // Android 8+：需先授予「安装未知来源应用」
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        SnackbarController.show("请先允许安装未知来源应用")
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }.onFailure {
            SnackbarController.show("无法打开设置")
        }
        return
    }
    val uri = if (savePath.startsWith("content://")) {
        Uri.parse(savePath)
    } else {
        // Android 7.0+ 禁止暴露 file:// URI，必须经 FileProvider 转 content://
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(savePath))
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "安装应用"))
    }.onFailure {
        SnackbarController.show("无法打开安装器")
    }
}

@Composable
private fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加下载任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "下载文件将保存到 ${Environment.DIRECTORY_DOWNLOADS} 目录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        if (name.isBlank()) name = it.substringAfterLast('/').take(80)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("文件直链 URL") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("保存文件名") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(url.trim(), name.trim()) },
                enabled = url.isNotBlank() && name.isNotBlank()
            ) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}