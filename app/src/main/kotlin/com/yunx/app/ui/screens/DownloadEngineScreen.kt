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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.resolve.formatSize
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.effectsFast
import com.yunx.app.util.PermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载引擎页（设置 → 下载引擎）：一张卡片里上下两段，对应两套下载器（圆角图标块 + 标题 + 小标签 +
 * 说明 + 整宽按钮，段间一条细分隔线）。字号/圆角/间距刻意压到与设置页同一档
 * （标题 titleMedium、说明 bodySmall、按钮 44dp / 14dp 圆角），避免整页显得笨重。
 *
 * **本页不做自己的入场动画**：它是由设置页「下载引擎」那一行做容器变换（Container Transform）
 * "长"出来的（源侧 sharedBounds 见 MainScreen，目标侧见 OverlayPage），再叠一层淡入/位移会和形变打架。
 *
 * - 内置分片下载器：项目自带（分片并发 + 断点续传，落盘走 SAF/MediaStore）；
 * - Gopeed 引擎：内置 gomobile 核心，**必须按真实文件路径落盘**，所以这一段还带着内核导入、引擎启停、
 *   内核状态、下载目录与「所有文件访问」权限的引导；**没导入内核时主按钮直接就是「导入内核」**。
 *
 * 切换结果写进 `SettingsRepository.downloadEngine`，`DownloadManager.enqueue` 按它分流（见 Agent.md §3.30）；
 * 选 Gopeed 且已导入内核时，应用启动会自动加载引擎（见 YunXApp.autoStartGopeedIfSelected）；
 * **删掉内核会自动切回内置下载器**，避免"设置说在用 Gopeed、实际跑的是内置下载器"这种不一致。
 */
@Composable
fun DownloadEngineScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val engineState by GopeedEngine.state.collectAsState()

    var engineChoice by remember { mutableStateOf(settings.downloadEngine) }
    var busy by remember { mutableStateOf(false) }
    var soBytes by remember { mutableStateOf(0L) }
    var engineVersion by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }

    // 「所有文件访问」（Android 11+）与运行时存储权限（Android 10-）：从系统设置返回时要刷新
    var allFilesReady by remember { mutableStateOf(PermissionState.allFilesAccessGranted()) }
    var legacyStorageReady by remember {
        mutableStateOf(!PermissionState.engineStoragePermissionPending(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesReady = PermissionState.allFilesAccessGranted()
                legacyStorageReady = !PermissionState.engineStoragePermissionPending(context)
                engineChoice = settings.downloadEngine
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val downloadDir = remember(allFilesReady, legacyStorageReady) { GopeedEngine.resolveDownloadDir(context) }

    val engineOn = engineChoice == SettingsRepository.ENGINE_GOPEED
    val installed = soBytes > 0L
    val running = engineState == GopeedEngine.State.RUNNING

    fun refreshSoInfo() {
        val so = GopeedEngine.soFile(context)
        soBytes = if (so.isFile) so.length() else 0L
    }

    /** 统一跑一个会阻塞的引擎动作：抢 busy、清掉上次错误、把异常原文显示出来 */
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

    /** 切换下载引擎：选 Gopeed 且内核已导入时顺手把引擎拉起来，省得用户再点一次 */
    fun chooseEngine(value: String) {
        settings.downloadEngine = value
        engineChoice = value
        notice = if (value == SettingsRepository.ENGINE_GOPEED) {
            "已切换到 Gopeed 引擎"
        } else {
            "已切换到内置分片下载器"
        }
        if (value == SettingsRepository.ENGINE_GOPEED && installed && !running) {
            action {
                withContext(Dispatchers.IO) { GopeedEngine.start(context, downloadDir) }
                engineVersion = withContext(Dispatchers.IO) {
                    runCatching { GopeedEngine.engineVersion() }.getOrDefault("")
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            action {
                val bytes = withContext(Dispatchers.IO) { GopeedEngine.installFromAar(context, uri) }
                refreshSoInfo()
                notice = "内核已导入：libgojni.so ${formatSize(bytes)}"
                failure = GopeedEngine.lastError
            }
        }
    }

    // Android 10- 走真实路径还需要运行时存储权限（引导页只为 Android 9- 申请过）
    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        legacyStorageReady = !PermissionState.engineStoragePermissionPending(context)
    }

    // 进页面确认内核在不在；引擎跑起来后补一次核心版本
    LaunchedEffect(engineState, soBytes) {
        refreshSoInfo()
        if (engineState == GopeedEngine.State.RUNNING && engineVersion.isBlank()) {
            engineVersion = runCatching {
                withContext(Dispatchers.IO) { GopeedEngine.engineVersion() }
            }.getOrDefault("")
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("下载引擎", style = MaterialTheme.typography.titleLarge) },
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
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // 本页不做自己的入场动画：它是由设置页那一行「长」出来的（容器变换，见 MainScreen 的
            // sharedBounds / OverlayPage），再叠一层淡入上移只会和形变打架。
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "下载引擎决定新任务由谁来执行，已存在的任务不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // ---------- 第 1 段：内置分片下载器 ----------
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            EngineSectionHeader(
                                icon = Icons.Outlined.Download,
                                title = "内置分片下载器",
                                badge = null,
                                highlighted = !engineOn
                            )
                            Text(
                                "项目自带的下载器：按网盘分片并发、断点续传，保存位置走系统 SAF / MediaStore" +
                                    "（Android 10+ 不需要任何存储权限）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            EngineActionButton(
                                selected = !engineOn,
                                icon = Icons.Outlined.SwapHoriz,
                                label = if (engineOn) "切换到此引擎" else "使用中",
                                enabled = engineOn,
                                onClick = { chooseEngine(SettingsRepository.ENGINE_BUILTIN) }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )

                        // ---------- 第 2 段：Gopeed 引擎 ----------
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            EngineSectionHeader(
                                icon = Icons.Outlined.SwapHoriz,
                                title = "Gopeed 引擎",
                                badge = "实验性",
                                highlighted = engineOn
                            )
                            Text(
                                "内置 gomobile 核心：HTTP / HLS / BT / 磁力 / ed2k，多连接分片且自带断点续传；" +
                                    "它是原生核心，只能按真实文件路径落盘（公共 Download/" +
                                    "${GopeedEngine.PUBLIC_DIR_NAME}）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                                Text(
                                    "引擎状态：",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                AnimatedContent(
                                    targetState = engineState,
                                    transitionSpec = {
                                        // 只是状态那一小段文字在换：透明度走 effects，小范围变化 → 用 Fast
                                        fadeIn(effectsFast()) togetherWith fadeOut(effectsFast())
                                    },
                                    label = "engineState"
                                ) { state ->
                                    Text(
                                        gopeedStateLabel(state),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (state == GopeedEngine.State.RUNNING) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            Text(
                                if (installed) {
                                    "内核：${formatSize(soBytes)}" +
                                        (if (engineVersion.isNotBlank()) "　·　核心版本 $engineVersion" else "")
                                } else {
                                    "内核：未导入（需要 libgopeed-<abi>.aar）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // 内核就绪后才出现的细节：下载目录 / 存储权限 / 引擎启停
                            AnimatedVisibility(
                                visible = installed,
                                enter = fadeIn(effectsDefault()),
                                exit = fadeOut(effectsFast())
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "下载目录：${downloadDir.absolutePath}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // 引擎写公共目录需要的权限：11+ 是「所有文件访问」，10- 是运行时存储权限
                                    val needAllFiles =
                                        PermissionState.allFilesAccessRequired() && !allFilesReady
                                    val needLegacyStorage =
                                        PermissionState.engineStoragePermissionPending(context)
                                    Text(
                                        when {
                                            needAllFiles ->
                                                "存储权限：「所有文件访问」未授权，现在只能下到应用私有目录" +
                                                    "（Android 11+ 的文件管理器也看不到那里）"
                                            needLegacyStorage ->
                                                "存储权限：还没授予存储权限，现在只能下到应用私有目录"
                                            else ->
                                                "存储权限：已就绪，下载直接落到公共 Download/" +
                                                    GopeedEngine.PUBLIC_DIR_NAME
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
                                                storagePermLauncher.launch(
                                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                                )
                                            }
                                        }) {
                                            Icon(
                                                Icons.Outlined.FolderOpen,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                if (needAllFiles) {
                                                    "去开启「所有文件访问」"
                                                } else {
                                                    "授予存储权限"
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            EngineActionButton(
                                selected = engineOn,
                                icon = if (installed) Icons.Outlined.SwapHoriz else Icons.Outlined.Add,
                                label = when {
                                    !installed -> "导入内核"
                                    engineOn -> "使用中"
                                    else -> "切换到此引擎"
                                },
                                enabled = !busy && (!installed || !engineOn),
                                onClick = {
                                    if (!installed) {
                                        importLauncher.launch(
                                            arrayOf("application/octet-stream", "application/zip", "*/*")
                                        )
                                    } else {
                                        chooseEngine(SettingsRepository.ENGINE_GOPEED)
                                    }
                                }
                            )

                            if (installed) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = {
                                        action {
                                            if (running) {
                                                withContext(Dispatchers.IO) { GopeedEngine.stop() }
                                                notice = "引擎已停止"
                                            } else {
                                                withContext(Dispatchers.IO) {
                                                    GopeedEngine.start(context, downloadDir)
                                                }
                                                engineVersion = withContext(Dispatchers.IO) {
                                                    runCatching { GopeedEngine.engineVersion() }
                                                        .getOrDefault("")
                                                }
                                                notice = "引擎已启动"
                                            }
                                            failure = GopeedEngine.lastError
                                        }
                                    }) {
                                        Icon(
                                            if (running) Icons.Outlined.Power else Icons.Outlined.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (running) "停止引擎" else "启动引擎")
                                    }
                                    TextButton(onClick = {
                                        action {
                                            withContext(Dispatchers.IO) { GopeedEngine.uninstall(context) }
                                            refreshSoInfo()
                                            // 内核没了就不能再用引擎下载：顺手切回内置，避免"设置说在用引擎、
                                            // 实际跑的是内置下载器"的错位（DownloadManager 也会因内核缺失回退）
                                            if (engineChoice == SettingsRepository.ENGINE_GOPEED) {
                                                settings.downloadEngine = SettingsRepository.ENGINE_BUILTIN
                                                engineChoice = SettingsRepository.ENGINE_BUILTIN
                                                notice = "内核已删除，已自动切回内置分片下载器"
                                            } else {
                                                notice = "内核已删除"
                                            }
                                            failure = GopeedEngine.lastError
                                        }
                                    }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("删除内核")
                                    }
                                }
                            }

                            if (notice.isNotBlank()) {
                                Text(
                                    notice,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            failure?.let { text ->
                                // 可选择文本：方便把原始报错整段复制出来
                                SelectionContainer {
                                    Text(
                                        text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                if (gopeedNeedsRestart(text)) {
                                    Text(
                                        "引擎桥接类一旦初始化失败，JVM 会在本进程内永久记住这次失败：" +
                                            "请到「最近任务」划掉本应用（或系统设置里强行停止）后重新打开，" +
                                            "再点一次启动。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 段头：圆角图标块 + 标题（+ 可选小标签）；当前启用的那一段图标底色跟着高亮。
 * 底色与图标色都走动画，切换引擎时能看出「选中态」变了。
 */
@Composable
private fun EngineSectionHeader(
    icon: ImageVector,
    title: String,
    badge: String?,
    highlighted: Boolean
) {
    val container by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = effectsDefault(),
        label = "engineIconContainer"
    )
    val tint by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = effectsDefault(),
        label = "engineIconTint"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(container),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (badge != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/**
 * 段里的整宽操作按钮：当前引擎用 tonal 按钮显示「使用中」（带对勾、不可点），
 * 另一段用描边按钮显示「切换到此引擎 / 导入内核」。
 */
@Composable
private fun EngineActionButton(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        FilledTonalButton(
            onClick = onClick,
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 引擎状态的中文描述 */
private fun gopeedStateLabel(state: GopeedEngine.State): String = when (state) {
    GopeedEngine.State.NOT_INSTALLED -> "未导入内核"
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
