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
import androidx.compose.material.icons.outlined.Refresh
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
 * - Gopeed 引擎：内置 gomobile 核心，**必须按真实文件路径落盘**，所以这一段还带着内核导入、内核状态、
 *   下载目录与存储权限的引导；**没导入内核时主按钮直接就是「导入内核」**。
 *
 * **三条交互口径**（都是用户反馈后定的，改之前先读这里）：
 * 1. **没有存储权限就不给切到 Gopeed**：11+ 要有「所有文件访问」、10- 要有运行时存储权限；
 *    缺权限时主按钮变成「先授予存储权限」（点它去授权，而不是切完再报错），`chooseEngine` 里还有一道硬拦截。
 * 2. **不提供「启动引擎 / 停止引擎」**：只在**已切到 Gopeed**时给一个「重启引擎」（stop + start，stop 幂等）；
 *    内置下载器模式下引擎本来就不该在跑，也就不给任何引擎操作（只剩「删除内核」）。
 *    **切回内置不停引擎**：切换只决定"新任务由谁执行"，在跑的引擎任务不受影响（与本页顶部那句说明一致）；
 *    唯一还需要停一下的地方是「删除内核」（`uninstall` 不允许运行中删），那里自己会停。
 * 3. **内核状态进页面先与文件对齐**：`GopeedEngine.syncInstalledState`（`state` 只在导入/启动/停止/卸载时
 *    被写过，进程重启后是 NOT_INSTALLED，选内置下载器时启动流程不会碰引擎）。
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

    // 引擎只能按真实文件路径落盘 → 「切到 Gopeed」必须先拿到存储权限（Android 11+ 是系统设置里的
    // 「所有文件访问」，Android 10- 是运行时存储权限）。三个值提到这里算，是因为 chooseEngine() 的
    // 硬拦截、主按钮的文案/行为都要用，不能只留在 Gopeed 段内部。
    val needAllFiles = PermissionState.allFilesAccessRequired() && !allFilesReady
    val needLegacyStorage = PermissionState.engineStoragePermissionPending(context)
    val storageBlocked = needAllFiles || needLegacyStorage

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
        if (value == SettingsRepository.ENGINE_GOPEED && storageBlocked) {
            // 硬拦截：引擎只能按真实路径落盘，存储权限没到位就不给切。
            // （主按钮那边已经把它导成「先授予存储权限」，正常点不到这里；这里是防漏网）
            notice = "还没有存储权限，不能切换到 Gopeed 引擎"
            return
        }
        settings.downloadEngine = value
        engineChoice = value
        if (value == SettingsRepository.ENGINE_GOPEED) {
            notice = "已切换到 Gopeed 引擎"
            if (installed && !running) {
                action {
                    withContext(Dispatchers.IO) { GopeedEngine.start(context, downloadDir) }
                    engineVersion = withContext(Dispatchers.IO) {
                        runCatching { GopeedEngine.engineVersion() }.getOrDefault("")
                    }
                    failure = GopeedEngine.lastError
                }
            }
        } else {
            // 切回内置**不**停引擎：切换只决定"新任务由谁执行"，已经在跑的引擎任务不受影响
            // （页面顶部就是这么写的）。它们跑完后引擎会空转着，直到进程结束或用户点「重启引擎」。
            // 这也是 UI 里不再有「启动/停止引擎」的原因：唯一还需要停一下的地方是「删除内核」
            // （uninstall 不允许在运行中删），那里自己会把引擎停掉。
            notice = "已切换到内置分片下载器"
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

    /** 申请引擎落盘需要的存储权限：11+ 去系统设置开「所有文件访问」，10- 弹运行时权限 */
    fun requestStoragePermission() {
        if (needAllFiles) {
            PermissionState.openAllFilesAccessSettings(context)
        } else {
            storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // 进页面先把「内核在不在」这条内存状态与真实文件对齐：GopeedEngine.state 只在导入/启动/停止/卸载时
    // 被写过，进程重启后是 NOT_INSTALLED；选内置下载器时启动流程根本不会碰引擎，
    // 不同步的话就会显示成「未导入内核」，而内核明明还在（用户报的 bug）。
    LaunchedEffect(Unit) { GopeedEngine.syncInstalledState(context) }

    // 内核文件大小；引擎跑起来后补一次核心版本
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
                                "内置 gomobile 核心：多连接分片且自带断点续传；" +
                                    "它是原生核心，只能按真实文件路径落盘（默认公共 Download 目录，" +
                                    "可在设置 →「下载保存目录」里自定义）。",
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

                            // 内核就绪后才出现的细节：下载目录 + 存储权限
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
                                    Text(
                                        when {
                                            needAllFiles ->
                                                "存储权限：「所有文件访问」未授权，现在只能下到应用私有目录" +
                                                    "（Android 11+ 的文件管理器也看不到那里）"
                                            needLegacyStorage ->
                                                "存储权限：还没授予存储权限，现在只能下到应用私有目录"
                                            else ->
                                                "存储权限：已就绪，引擎可直接写进上面的真实目录"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (storageBlocked) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    if (storageBlocked) {
                                        TextButton(onClick = { requestStoragePermission() }) {
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
                                icon = when {
                                    !installed -> Icons.Outlined.Add
                                    storageBlocked -> Icons.Outlined.FolderOpen
                                    else -> Icons.Outlined.SwapHoriz
                                },
                                label = when {
                                    !installed -> "导入内核"
                                    engineOn -> "使用中"
                                    // 没存储权限就不给切：按钮直接变成权限入口（点它去开权限，而不是切完再报错）
                                    storageBlocked -> "先授予存储权限"
                                    else -> "切换到此引擎"
                                },
                                enabled = !busy && (!installed || !engineOn),
                                onClick = {
                                    when {
                                        !installed -> importLauncher.launch(
                                            arrayOf("application/octet-stream", "application/zip", "*/*")
                                        )
                                        storageBlocked -> requestStoragePermission()
                                        else -> chooseEngine(SettingsRepository.ENGINE_GOPEED)
                                    }
                                }
                            )

                            if (installed) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // 引擎操作只在「正在用 Gopeed」时给：内置下载器模式下引擎本来就不该在跑，
                                    // 更不该让用户去启动/停止它（用户反馈：没切到 Gopeed 却能点「启动引擎」）。
                                    // 而且只留「重启」——都切到 Gopeed 了，单独「停止引擎」这个动作没有意义；
                                    // 引擎卡住时能自救的才是重启（停止 + 再启动，stop 是幂等的）。
                                    if (engineOn) {
                                        TextButton(onClick = {
                                            action {
                                                withContext(Dispatchers.IO) {
                                                    GopeedEngine.stop()
                                                    GopeedEngine.start(context, downloadDir)
                                                }
                                                engineVersion = withContext(Dispatchers.IO) {
                                                    runCatching { GopeedEngine.engineVersion() }
                                                        .getOrDefault("")
                                                }
                                                notice = "引擎已重启"
                                                failure = GopeedEngine.lastError
                                            }
                                        }) {
                                            Icon(
                                                Icons.Outlined.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text("重启引擎")
                                        }
                                    }
                                    TextButton(onClick = {
                                        action {
                                            withContext(Dispatchers.IO) {
                                                // uninstall 不允许在运行中删（Go core 还占着 .so），先停掉
                                                GopeedEngine.stop()
                                                GopeedEngine.uninstall(context)
                                            }
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
