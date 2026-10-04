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

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.ui.resolve.formatSize
import com.yunx.app.util.PermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 测试链接：阿里云镜像上的文件清单压缩包（约 20 MB），用来快速验证引擎能否正常下载 */
private const val TEST_URL_ALIYUN = "https://mirrors.aliyun.com/ubuntu/ls-lR.gz"

/** 测试链接：Cloudflare 固定大小测速文件（10 MB） */
private const val TEST_URL_CLOUDFLARE = "https://speed.cloudflare.com/__down?bytes=10485760"

/**
 * Gopeed 内置下载引擎的验证页（设置 → Gopeed 下载引擎）。
 *
 * 引擎管理页（设置 → Gopeed 下载引擎）。
 *
 * 导入 AAR → 加载 .so → 启停引擎 → 建一个测试任务 → 看进度/暂停/继续/删除，并列出下载目录里的
 * 真实文件做交叉验证；同时负责引导「引擎按真实路径写公共目录」所需的权限
 * （Android 11+ 的「所有文件访问」、Android 10- 的运行时存储权限，见 §3.29）。
 *
 * 现有的 DownloadManager / DownloadService 链路仍然独立运行——两套下载器并存，见 Agent.md §3.29。
 */
@Composable
fun GopeedScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engineState by GopeedEngine.state.collectAsState()
    // 「所有文件访问」授权状态（只有 Android 11+ 需要）：用户从系统设置返回时要刷新，
    // 下载目录会跟着在「公共 Download/YunX」和「应用私有目录」之间切换
    var allFilesReady by remember { mutableStateOf(PermissionState.allFilesAccessGranted()) }
    // Android 10- 走真实路径还需要运行时 WRITE_EXTERNAL_STORAGE（引导页只为 Android 9- 申请过，
    // 所以 Android 10 上大概率还没给，必须在这里补一个入口）
    var legacyStorageReady by remember {
        mutableStateOf(!PermissionState.engineStoragePermissionPending(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesReady = PermissionState.allFilesAccessGranted()
                legacyStorageReady = !PermissionState.engineStoragePermissionPending(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val downloadDir = remember(allFilesReady, legacyStorageReady) { GopeedEngine.resolveDownloadDir(context) }
    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        legacyStorageReady = !PermissionState.engineStoragePermissionPending(context)
    }

    var busy by remember { mutableStateOf(false) }
    var soBytes by remember { mutableStateOf(0L) }
    var engineVersion by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var taskName by remember { mutableStateOf("") }
    var task by remember { mutableStateOf<GopeedEngine.TaskView?>(null) }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refreshSoInfo() {
        val so = GopeedEngine.soFile(context)
        soBytes = if (so.isFile) so.length() else 0L
    }

    fun refreshFiles() {
        val list = downloadDir.listFiles()?.filter { it.isFile } ?: emptyList()
        files = list.sortedByDescending { it.lastModified() }.take(8)
            .map { "${it.name}　${formatSize(it.length())}" }
    }

    /** 统一跑一个会阻塞的引擎动作：抢占 busy、清掉上次错误、把异常原文显示出来 */
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            try {
                block()
            } catch (e: Throwable) {
                failure = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            action {
                val bytes = withContext(Dispatchers.IO) { GopeedEngine.installFromAar(context, uri) }
                refreshSoInfo()
                notice = "引擎已导入：libgojni.so ${formatSize(bytes)}"
                failure = GopeedEngine.lastError
            }
        }
    }

    // 进页面先确认引擎文件在不在；引擎跑起来后补一次核心版本
    LaunchedEffect(engineState) {
        refreshSoInfo()
        refreshFiles()
        if (engineState == GopeedEngine.State.RUNNING && engineVersion.isBlank()) {
            engineVersion = runCatching {
                withContext(Dispatchers.IO) { GopeedEngine.engineVersion() }
            }.getOrDefault("")
        }
    }

    // 任务进度轮询：跑到 done / error 就停
    LaunchedEffect(task?.id) {
        val id = task?.id ?: return@LaunchedEffect
        while (isActive) {
            val view = withContext(Dispatchers.IO) {
                runCatching { GopeedEngine.taskStatus(id) }.getOrNull()
            }
            if (view != null) {
                task = view
                refreshFiles()
                if (view.status == "done" || view.status == "error") break
            }
            delay(1000)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Gopeed 下载引擎", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 引擎状态 ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("引擎状态", style = MaterialTheme.typography.titleMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        Text(gopeedStateLabel(engineState), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        if (soBytes > 0) {
                            "引擎文件：${formatSize(soBytes)}（${GopeedEngine.soFile(context).absolutePath}）"
                        } else {
                            "还没有导入引擎文件（需要 libgopeed-<abi>.aar）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (engineVersion.isNotBlank()) {
                        Text(
                            "核心版本：$engineVersion",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "下载目录：${downloadDir.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 引擎写公共目录需要的权限：Android 11+ 是「所有文件访问」，10- 是运行时存储权限
                    val needAllFiles = PermissionState.allFilesAccessRequired() && !allFilesReady
                    val needLegacyStorage = PermissionState.engineStoragePermissionPending(context)
                    Text(
                        when {
                            needAllFiles ->
                                "存储权限：「所有文件访问」未授权，现在只能下到应用私有目录" +
                                    "（Android 11+ 的文件管理器也看不到那里）"
                            needLegacyStorage -> "存储权限：还没授予存储权限，现在只能下到应用私有目录"
                            else -> "存储权限：已就绪，下载直接落到公共 Download/${GopeedEngine.PUBLIC_DIR_NAME}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (needAllFiles || needLegacyStorage) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (needAllFiles || needLegacyStorage) {
                        TextButton(onClick = {
                            if (needAllFiles) {
                                PermissionState.openAllFilesAccessSettings(context)
                            } else {
                                storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(if (needAllFiles) "去开启「所有文件访问」" else "授予存储权限")
                        }
                    }
                    Text(
                        "说明：Gopeed 是原生核心，只能按真实文件系统路径写文件（写不了 SAF 的 content:// 目录）。" +
                            "公共目录要「所有文件访问」（仅 Android 11+ 需要，且必须用户手动开启）；" +
                            "拿不到权限时自动退回应用外部私有目录，功能不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "排查：导入/加载/启动/调用全链路都打了日志，可用 adb logcat -s GopeedEngine 抓取。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                            },
                            enabled = !busy && engineState != GopeedEngine.State.RUNNING
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(if (soBytes > 0) "重新导入 AAR" else "导入 AAR")
                        }
                        if (engineState == GopeedEngine.State.RUNNING) {
                            Button(onClick = {
                                action {
                                    withContext(Dispatchers.IO) { GopeedEngine.stop() }
                                    notice = "引擎已停止"
                                    failure = GopeedEngine.lastError
                                }
                            }) {
                                Icon(Icons.Outlined.Power, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("停止引擎")
                            }
                        } else {
                            Button(
                                onClick = {
                                    action {
                                        val port = withContext(Dispatchers.IO) { GopeedEngine.start(context, downloadDir) }
                                        engineVersion = withContext(Dispatchers.IO) {
                                            runCatching { GopeedEngine.engineVersion() }.getOrDefault("")
                                        }
                                        notice = "引擎已启动（进程内 API 端口 $port，没有监听 TCP）"
                                    }
                                },
                                enabled = !busy && soBytes > 0
                            ) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("启动引擎")
                            }
                        }
                    }
                    if (soBytes > 0 && engineState != GopeedEngine.State.RUNNING) {
                        TextButton(onClick = {
                            action {
                                withContext(Dispatchers.IO) { GopeedEngine.uninstall(context) }
                                refreshSoInfo()
                                notice = "已删除引擎文件（本进程里已加载的库要到重启应用才真正卸载）"
                                failure = GopeedEngine.lastError
                            }
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("删除引擎文件")
                        }
                    }
                    if (notice.isNotBlank()) {
                        Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    failure?.let { text ->
                        // 用可选择文本，方便把原始报错整段复制出来贴到 issue 里
                        SelectionContainer {
                            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (gopeedNeedsRestart(text)) {
                            Text(
                                "引擎桥接类一旦初始化失败，JVM 会在本进程内永久记住这次失败：" +
                                    "请到「最近任务」划掉本应用（或系统设置里强行停止）后重新打开，再点一次启动。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // ---------- 测试下载 ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("测试下载", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("下载链接") },
                        placeholder = { Text("http/https 直链（引擎还支持 BT / 磁力 / ed2k 等）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { url = TEST_URL_ALIYUN }) { Text("填入约 20 MB 测试文件") }
                        TextButton(onClick = { url = TEST_URL_CLOUDFLARE }) { Text("填入 10 MB 测速文件") }
                    }
                    Button(
                        onClick = {
                            action {
                                val target = url.trim()
                                val id = withContext(Dispatchers.IO) { GopeedEngine.createTask(target, downloadDir) }
                                taskName = target
                                val first = withContext(Dispatchers.IO) {
                                    runCatching { GopeedEngine.taskStatus(id) }.getOrNull()
                                }
                                task = first ?: GopeedEngine.TaskView(id, "", "ready", 0L, 0L, 0L)
                                refreshFiles()
                                notice = "任务已创建：$id"
                            }
                        },
                        enabled = !busy && soBytes > 0 &&
                            engineState == GopeedEngine.State.RUNNING && url.isNotBlank()
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("创建下载任务")
                    }
                }
            }

            // ---------- 当前任务 ----------
            val current = task
            if (current != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("当前任务", style = MaterialTheme.typography.titleMedium)
                        Text(taskName, style = MaterialTheme.typography.bodyMedium)
                        Text("状态：${gopeedTaskLabel(current.status)}", style = MaterialTheme.typography.bodySmall)
                        val fraction = if (current.total > 0L) {
                            (current.downloaded.toFloat() / current.total).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${formatSize(current.downloaded)} / " +
                                (if (current.total > 0L) formatSize(current.total) else "大小未知") +
                                "　·　${formatSize(current.speed)}/s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (current.status == "pause") {
                                OutlinedButton(onClick = {
                                    action {
                                        withContext(Dispatchers.IO) { GopeedEngine.continueTask(current.id) }
                                        notice = "已继续"
                                    }
                                }) {
                                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("继续")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        action {
                                            withContext(Dispatchers.IO) { GopeedEngine.pauseTask(current.id) }
                                            notice = "已暂停"
                                        }
                                    },
                                    enabled = current.status == "running" || current.status == "wait" ||
                                        current.status == "ready"
                                ) {
                                    Icon(Icons.Outlined.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("暂停")
                                }
                            }
                            OutlinedButton(onClick = {
                                action {
                                    withContext(Dispatchers.IO) { GopeedEngine.deleteTask(current.id) }
                                    task = null
                                    refreshFiles()
                                    notice = "任务已删除"
                                }
                            }) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("删除任务")
                            }
                        }
                    }
                }
            }

            // ---------- 目录里的真实文件（交叉验证下载确实落盘） ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "下载目录里的文件",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { refreshFiles() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                    if (files.isEmpty()) {
                        Text(
                            "（空）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        files.forEach { item ->
                            Text(
                                item,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 引擎状态的中文描述 */
private fun gopeedStateLabel(state: GopeedEngine.State): String = when (state) {
    GopeedEngine.State.NOT_INSTALLED -> "未导入引擎"
    GopeedEngine.State.INSTALLED -> "已导入，未启动"
    GopeedEngine.State.RUNNING -> "运行中"
}

/**
 * 是否需要「杀进程重开」。
 *
 * 引擎桥接类（go.* / com.gopeed.*）的静态初始化一旦抛错，JVM 会把该类标记为初始化失败并永久记住，
 * 同一进程内再怎么点都只会得到 NoClassDefFoundError，必须重启进程。
 */
private fun gopeedNeedsRestart(message: String): Boolean =
    message.contains("libgojni.so") || message.contains("NoClassDefFoundError") ||
        message.contains("Libgopeed") || message.contains("go.Seq")

/** 任务状态的中文描述（键名对照 Gopeed 的 download.Status） */
private fun gopeedTaskLabel(status: String): String = when (status) {
    "ready" -> "待开始"
    "running" -> "下载中"
    "wait" -> "排队等待"
    "pause" -> "已暂停"
    "error" -> "出错"
    "done" -> "已完成"
    else -> status
}
