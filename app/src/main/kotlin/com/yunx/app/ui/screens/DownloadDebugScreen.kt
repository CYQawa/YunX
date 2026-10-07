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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.download.ChunkDebug
import com.yunx.app.data.download.DownloadDebugLog
import com.yunx.app.data.download.DownloadResourceDebug
import com.yunx.app.data.download.DownloadStats
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.FileNameText
import com.yunx.app.ui.components.YunXWavyProgress
import com.yunx.app.ui.viewmodel.DownloadViewModel
import com.yunx.app.util.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 调试页刷新间隔（毫秒）：拉取式快照，页面关掉即停 */
private const val DEBUG_REFRESH_MS = 1000L

/** 「疑似卡死」判定：已跑够久、瞬时速度低于阈值且还没下完（与 DownloadManager 的抢占下限同一口径 12KB/s） */
private const val STUCK_MIN_AGE_MS = 30_000L
private const val STUCK_MIN_BPS = 12 * 1024L

/**
 * 下载调试页（下载页长按任务 → 「调试信息」）。
 *
 * 需先在「设置 → 关于云析 → 长按 → 开发调试 → 下载调试」打开开关；否则菜单里不会出现入口。
 * 页面在「下载」Tab 内全屏展开（与 ResolveScreen 内嵌 ShareDetailScreen 同款），自上而下：
 * 任务卡（可暂停/继续）→ 全局资源 → 每个线程（在飞分片）在干什么 → 调试操作 → 实时日志跟随。
 *
 * ★ 数据是**拉取式**的：每秒拉一次，离开页面即取消 —— 不打开本页时对下载没有任何额外开销。
 */
@Composable
internal fun DownloadDebugScreen(
    task: DownloadTaskEntity,
    stats: DownloadStats?,
    viewModel: DownloadViewModel,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chunks by remember { mutableStateOf<List<ChunkDebug>>(emptyList()) }
    var resources by remember { mutableStateOf<DownloadResourceDebug?>(null) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }

    BackHandler(onBack = onBack)

    // 每秒轮询（LaunchedEffect 随本页离开组合树自动取消）
    LaunchedEffect(task.id) {
        while (true) {
            chunks = viewModel.chunkDebugSnapshot(task.id)
            resources = viewModel.resourceDebugSnapshot()
            logLines = viewModel.debugLogEntries(task.id)
            delay(DEBUG_REFRESH_MS)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部返回行（本页在 Tab 内容区内展开，用行内返回而不是第二个 TopAppBar）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "下载调试",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 任务卡：与下载页同一套文案与进度口径
            DebugTaskCard(
                task = task,
                stats = stats,
                onPause = onPause,
                onResume = onResume,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            val listState = rememberLazyListState()
            // 列表项数固定可算：资源面板(1) + 线程标题(1) + 线程行 + 操作行(1) + 日志标题(1) + 日志行
            // 线程/日志为空时各占一行占位说明，所以取 max(…, 1)。
            // 固定项 = 资源面板 + 线程标题 + 线程概况 + 操作行 + 日志标题 = 5；线程/日志为空时各占一行占位
            val logRows = logLines.size.coerceAtLeast(1)
            val totalItems = 5 + chunks.size + logRows
            // ★ 「实时跟随」只在用户**本来就停在底部**时才生效：往上翻看线程/历史日志时绝不再把页面拽到底部
            //   （用户反馈：看着线程忽然被拉到底）。
            //   判定只在用户主动滚动时更新 —— 新日志追加也会让 canScrollForward 短暂变 true，
            //   若不加 isScrollInProgress 条件就会被误判成「用户翻上去了」而永久停止跟随。
            var follow by remember { mutableStateOf(true) }
            LaunchedEffect(listState) {
                snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
                    .collect { (scrolling, canScrollForward) ->
                        if (scrolling) follow = !canScrollForward
                    }
            }
            LaunchedEffect(logLines.size) {
                if (logLines.isNotEmpty() && follow) {
                    runCatching { listState.animateScrollToItem((totalItems - 1).coerceAtLeast(0)) }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { ResourcePanel(resources) }
                item { SectionTitle("线程（每个在飞分片在干什么）") }
                item {
                    // 线程概况：在飞 = 有分片的 worker，空闲 = 总 worker 数 - 在飞（worker 领到片之前为空闲）
                    val totalWorkers = stats?.chunkCount ?: 0
                    HintText(
                        when {
                            chunks.isEmpty() ->
                                "当前没有在飞分片（未开始 / 已暂停 / 已完成，或该任务走的是单流 / Gopeed 引擎下载）。"
                            totalWorkers > 0 ->
                                "在飞 ${chunks.size} 路 · 空闲 ${(totalWorkers - chunks.size).coerceAtLeast(0)} 路（共 $totalWorkers 个 worker）"
                            else -> "在飞 ${chunks.size} 路"
                        }
                    )
                }
                if (chunks.isNotEmpty()) {
                    items(chunks, key = { it.key }) { chunk ->
                        ChunkRow(
                            chunk = chunk,
                            onForceReconnect = {
                                val ok = viewModel.forcePreemptChunk(task.id, chunk.key)
                                SnackbarController.show(
                                    if (ok) "已标记 ${chunk.key} 断开换连接续传" else "该分片已结束，无法重连"
                                )
                            }
                        )
                    }
                }
                item {
                    DebugActions(
                        onCopySnapshot = {
                            copyDebugText(
                                context,
                                buildSnapshotText(
                                    task = task,
                                    stats = stats,
                                    chunks = chunks,
                                    resources = resources,
                                    env = DownloadDebugLog.environmentSnapshot(context)
                                )
                            )
                            SnackbarController.show("调试快照已复制")
                        },
                        onExportLog = {
                            // 写文件 + 拉起分享要离开主线程（与设置页导出同一口径）
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    DownloadDebugLog.exportToFile(context, task)
                                }
                                if (file != null && LogExporter.share(context, file)) {
                                    SnackbarController.show("已导出完整下载日志（${file.name}）")
                                } else {
                                    SnackbarController.show("导出失败，请稍后重试")
                                }
                            }
                        }
                    )
                }
                item { SectionTitle("实时日志（主要变更节点 / 错误节点）") }
                if (logLines.isEmpty()) {
                    item { HintText("暂无日志：该任务创建时「下载调试」未开启，或开关刚被关闭（关闭会清空日志）。") }
                } else {
                    items(logLines.size) { i -> LogLine(logLines[i]) }
                }
            }
        }
    }
}

/** 任务卡：文件名 / 状态 / 进度条 / 实时速度 / 暂停-继续，口径与下载页一致 */
@Composable
private fun DebugTaskCard(
    task: DownloadTaskEntity,
    stats: DownloadStats?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 合并阶段（-1 = 不在合并）：合并时下载早已 100%，进度条改显示合并百分比（与下载页同一口径）
    val mergePercent = stats?.mergePercent ?: -1
    val merging = mergePercent >= 0
    val fraction = when {
        merging -> mergePercent / 100f
        task.totalSize > 0 -> (task.downloadedSize.toFloat() / task.totalSize).coerceIn(0f, 1f)
        else -> 0f
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
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
                    FileNameText(
                        text = task.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (merging) "合并中 · $mergePercent%" else taskStatusLine(task),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                    else -> Unit // 已完成：调试页不提供「打开/删除」，回下载页操作
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            YunXWavyProgress(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = if (task.status == DownloadTaskEntity.STATUS_FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                waving = !merging && task.status == DownloadTaskEntity.STATUS_DOWNLOADING
            )
            Spacer(modifier = Modifier.height(8.dp))
            val liveText = when {
                merging -> "正在合并分片 · $mergePercent%"
                else -> stats
                    ?.takeIf { it.speed > 0 }
                    ?.let { "${formatSpeed(it.speed)} · 剩余 ${formatRemain(it.remainMillis)} · ${it.chunkCount} 线程" }
            }
            Text(
                text = liveText ?: progressText(task),
                style = MaterialTheme.typography.labelSmall,
                color = if (liveText != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** 全局资源面板：在飞配额占用 / 活跃任务数 / 堆内存 */
@Composable
private fun ResourcePanel(resources: DownloadResourceDebug?) {
    SectionTitle("全局资源")
    if (resources == null) {
        HintText("读取中…")
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            InfoRow("在飞分片", "${resources.inflightUsed} / ${resources.inflightCap}")
            InfoRow("活跃任务", resources.activeTasks.toString())
            InfoRow("线程池", "线程 ${resources.poolSize} · 忙 ${resources.poolActive} · 队列 ${resources.poolQueue}")
            InfoRow(
                "堆内存",
                "${resources.heapUsed / 1024 / 1024} MB / ${resources.heapMax / 1024 / 1024} MB"
            )
        }
    }
}

/** 单个在飞分片：key / 区间 / 已收 / 瞬时速度 / 已跑时长 / 抢占情况 + 「重连」 */
@Composable
private fun ChunkRow(chunk: ChunkDebug, onForceReconnect: () -> Unit) {
    val percent = if (chunk.size > 0) (chunk.received * 100 / chunk.size).toInt() else 0
    val preemptNote = buildString {
        if (chunk.preemptCount > 0) append(" · 已抢占 ${chunk.preemptCount} 次")
        if (chunk.preempting) append(" · 待换连接")
    }
    // 「疑似卡死」：跑得够久、又慢得离谱且没下完（多半是连接被 CDN 挂住，可用右侧「重连」换一条）
    val stuck = chunk.elapsedMs >= STUCK_MIN_AGE_MS &&
        chunk.bps < STUCK_MIN_BPS &&
        chunk.received < chunk.size
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${chunk.key}　worker #${chunk.worker}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "区间 ${chunk.start}-${chunk.start + chunk.size - 1} · " +
                        "已收 ${formatSize(chunk.received)}/${formatSize(chunk.size)}（$percent%）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "瞬时 ${formatSpeed(chunk.bps)} · 已跑 ${chunk.elapsedMs / 1000}s$preemptNote" +
                        if (stuck) " · 疑似卡死" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stuck) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            TextButton(onClick = onForceReconnect) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("重连")
            }
        }
    }
}

/** 调试操作：复制快照 / 导出完整日志 */
@Composable
private fun DebugActions(onCopySnapshot: () -> Unit, onExportLog: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = onCopySnapshot) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("复制快照")
        }
        TextButton(onClick = onExportLog) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("导出完整日志")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LogLine(line: String) {
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 调试快照文本：任务字段 + 当前环境 + 在飞分片表 + 全局资源（复制到剪贴板用；直链已脱敏） */
private fun buildSnapshotText(
    task: DownloadTaskEntity,
    stats: DownloadStats?,
    chunks: List<ChunkDebug>,
    resources: DownloadResourceDebug?,
    env: String
): String = buildString {
    appendLine("云析（YunX）下载调试快照")
    appendLine("任务 id：${task.id}　文件名：${task.fileName}")
    appendLine("状态：${DownloadTaskEntity.statusText(task.status)}　平台：${task.platform.ifBlank { "-" }}")
    appendLine("执行器：${task.engineTaskId.ifBlank { "内置分片下载器" }}")
    appendLine("大小：${task.downloadedSize} / ${task.totalSize} 字节")
    appendLine("环境：$env")
    if (stats != null) {
        appendLine("实时：${formatSpeed(stats.speed)}　剩余 ${formatRemain(stats.remainMillis)}　${stats.chunkCount} 线程")
    }
    if (task.errorMsg.isNotBlank()) appendLine("失败原因：${task.errorMsg}")
    appendLine()
    appendLine("---------- 在飞分片（${chunks.size}）----------")
    if (chunks.isEmpty()) {
        appendLine("（无）")
    } else {
        chunks.forEach {
            appendLine(
                "${it.key} | worker#${it.worker} | 区间 ${it.start}-${it.start + it.size - 1} | " +
                    "已收 ${it.received}/${it.size} | 瞬时 ${it.bps}B/s | 已跑 ${it.elapsedMs}ms | " +
                    "抢占 ${it.preemptCount} 次${if (it.preempting) "（待换连接）" else ""}"
            )
        }
    }
    appendLine()
    appendLine("---------- 全局资源 ----------")
    if (resources == null) {
        appendLine("（读取中）")
    } else {
        appendLine("在飞分片：${resources.inflightUsed}/${resources.inflightCap}")
        appendLine("活跃任务：${resources.activeTasks}")
        appendLine("分片 IO 线程池：线程 ${resources.poolSize} · 忙 ${resources.poolActive} · 队列 ${resources.poolQueue}")
        appendLine("堆内存：${resources.heapUsed / 1024 / 1024}MB / ${resources.heapMax / 1024 / 1024}MB")
    }
}

private fun copyDebugText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("yunx_download_debug", text))
}
