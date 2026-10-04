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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.data.gopeed.KernelProvisioner
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.components.YunXWavyLoading
import com.yunx.app.ui.components.YunXWavyProgress
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
@OptIn(ExperimentalLayoutApi::class)
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

    // ---------- 内核的云端获取 ----------
    // 阶段状态全在 KernelProvisioner 单例里（下载跑在它自己的 scope 上，切页/退后台都不影响），
    // 这里只收集它渲染那个**不可关闭**的进度弹窗。
    val provisionPhase by KernelProvisioner.phase.collectAsState()
    /** 「导入内核」按钮的下拉菜单（来源：云端 / 本地 AAR） */
    var showKernelMenu by remember { mutableStateOf(false) }
    /** 非 null = 云端下载的底部弹窗正在显示（已拿到 Release 且已按本机 ABI 挑好包） */
    var cloudRelease by remember { mutableStateOf<KernelProvisioner.KernelRelease?>(null) }
    /** 「GitHub 下载」的二级菜单：直链 / 镜像站 */
    var showGithubDialog by remember { mutableStateOf(false) }

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

    /**
     * 点「从云端下载」：先问 GitHub 要内核仓库的最新 Release（这期间进度弹窗显示「正在获取内核版本」），
     * 拿到并且**本机 ABI 的包在里面**才弹底部弹窗；否则弹窗直接显示失败原因。
     */
    fun fetchCloudRelease() {
        if (provisionPhase !is KernelProvisioner.Phase.Idle) return
        scope.launch {
            // 失败原因由 KernelProvisioner 写进 phase（Failed），界面照常弹那个弹窗显示，这里不用再抄一遍
            KernelProvisioner.loadRelease().onSuccess { cloudRelease = it }
        }
    }

    /** 选好来源后开下：下载 + 校验 + 导入 + 清理全程由 KernelProvisioner 推进，界面只跟着 phase 走 */
    fun startKernelDownload(source: KernelProvisioner.Source) {
        val release = cloudRelease ?: return
        cloudRelease = null
        showGithubDialog = false
        KernelProvisioner.start(context, release, source)
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

    // 云端内核导入完成：刷新内核信息/状态，并把「要不要重启应用」带进提示行
    // （本进程已加载过旧库时 dlopen 卸载不掉，页面留一句说明，别让用户以为新内核已经生效）
    LaunchedEffect(provisionPhase) {
        val done = provisionPhase as? KernelProvisioner.Phase.Done ?: return@LaunchedEffect
        refreshSoInfo()
        GopeedEngine.syncInstalledState(context)
        notice = "内核已导入：libgojni.so ${formatSize(done.bytes)}" +
            (if (GopeedEngine.pendingRestartForNewKernel) "　·　重启应用后生效" else "")
        failure = GopeedEngine.lastError
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

                            // 没导入内核时这个按钮是「导入内核」，点它弹来源菜单（云端 / 本地 AAR）；
                            // 菜单要贴着按钮长，所以包一层 Box 当锚点
                            Box {
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
                                            !installed -> showKernelMenu = true
                                            storageBlocked -> requestStoragePermission()
                                            else -> chooseEngine(SettingsRepository.ENGINE_GOPEED)
                                        }
                                    }
                                )
                                KernelSourceMenu(
                                    expanded = showKernelMenu,
                                    onDismiss = { showKernelMenu = false },
                                    onCloud = {
                                        showKernelMenu = false
                                        fetchCloudRelease()
                                    },
                                    onLocal = {
                                        showKernelMenu = false
                                        importLauncher.launch(
                                            arrayOf("application/octet-stream", "application/zip", "*/*")
                                        )
                                    }
                                )
                            }

                            if (installed) {
                                // 引擎操作行：重启引擎（仅 Gopeed 模式）/ 删除内核。
                                // 用 FlowRow 而不是 Row：窄屏上两个带图标的文字按钮也可能贴边，
                                // 让它自己折行，别硬挤成溢出。
                                // ★ 这里**没有**「更新内核」入口（用户要求去掉）：已导入状态下要换内核，
                                //   得先「删除内核」再回到主按钮的「导入内核」走来源菜单。
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
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

    // ---------- 云端内核：底部弹窗（选来源）+ GitHub 二级菜单 + 不可关闭的进度弹窗 ----------

    // ① 底部弹窗：只在「已拿到 Release」时显示；说明里命中网盘链接才有「网盘自动下载」
    cloudRelease?.let { release ->
        KernelCloudSheet(
            release = release,
            onPan = { startKernelDownload(KernelProvisioner.Source.PAN) },
            onGithub = { showGithubDialog = true },
            onDismiss = { cloudRelease = null }
        )
    }

    // ② GitHub 二级菜单：直链 / 镜像站（镜像站失败会自动回退直链）
    if (showGithubDialog) {
        KernelGithubDialog(
            onDirect = { startKernelDownload(KernelProvisioner.Source.GITHUB) },
            onMirror = { startKernelDownload(KernelProvisioner.Source.GITHUB_MIRROR) },
            onDismiss = { showGithubDialog = false }
        )
    }

    // ③ 进度弹窗：网上取包到导入完成的全过程都在这里，**点空白/返回键都关不掉**
    //（只有「取消」和终态的「关闭」能走），免得用户手滑把 25MB 的下载甩掉
    KernelProgressDialog(
        phase = provisionPhase,
        onCancel = { KernelProvisioner.cancel() },
        onDismiss = { KernelProvisioner.reset() }
    )
}

/**
 * 「导入内核」的来源菜单：从云端下载（GitHub Release，按本机 ABI 自动挑包）或本地 AAR。
 * 只挂在未导入时的那个主按钮上 —— 已导入状态下**没有**再导入的入口（用户要求去掉「更新内核」），
 * 要换内核得先「删除内核」再走这里。
 */
@Composable
private fun KernelSourceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCloud: () -> Unit,
    onLocal: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("从云端下载") },
            leadingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
            onClick = onCloud
        )
        DropdownMenuItem(
            text = { Text("导入本地 AAR") },
            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
            onClick = onLocal
        )
    }
}

/**
 * 云端内核的底部弹窗（层级对齐「发现新版本」那个 UpdateSheet）：
 * 主按钮「网盘自动下载」**只在 Release 说明里命中网盘链接时**出现，否则只有 GitHub 一条路。
 * 两个通道都会按本机 ABI 自动挑包（`libgopeed-<abi>.aar`），下载完自动导入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KernelCloudSheet(
    release: KernelProvisioner.KernelRelease,
    onPan: () -> Unit,
    onGithub: () -> Unit,
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
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "下载 Gopeed 内核",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "版本 ${release.tagName}　·　本机 ${GopeedEngine.preferredAbi()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = release.assetName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (release.assetSize > 0L) {
                            "大小 ${formatSize(release.assetSize)}"
                        } else {
                            "大小未知"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "下载完成后自动解包导入，临时包不落到 Download 目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ① 主按钮：说明里有「[网盘下载](…)」才给网盘通道
            if (release.panUrl != null) {
                Button(
                    onClick = onPan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("网盘自动下载")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ② 次按钮：GitHub 下载（再选直链 / 镜像站）
            if (release.panUrl != null) {
                OutlinedButton(
                    onClick = onGithub,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GitHub 下载")
                }
            } else {
                Button(
                    onClick = onGithub,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GitHub 下载")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** GitHub 通道的二级菜单：直链 / 镜像站（镜像站失效时下载器会自动回退直连） */
@Composable
private fun KernelGithubDialog(
    onDirect: () -> Unit,
    onMirror: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GitHub 下载") },
        text = {
            Column {
                Button(onClick = onDirect, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("GitHub 直链下载")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onMirror, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Outlined.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("GitHub 镜像站下载")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/**
 * 内核下载的进度弹窗：**故意做成关不掉的**（`dismissOnBackPress`/`dismissOnClickOutside` 全 false）——
 * 25MB 的下载不该被一次误触甩掉；要中断只能按「取消」，那会清干净地把任务停掉。
 * 阶段文案跟着 [KernelProvisioner.Phase] 走，终态（完成/失败）给一个「关闭」。
 */
@Composable
private fun KernelProgressDialog(
    phase: KernelProvisioner.Phase,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    if (phase is KernelProvisioner.Phase.Idle) return

    val done = phase as? KernelProvisioner.Phase.Done
    val failed = phase as? KernelProvisioner.Phase.Failed
    val downloading = phase as? KernelProvisioner.Phase.Downloading
    val merging = phase as? KernelProvisioner.Phase.Merging
    val finished = done != null || failed != null

    AlertDialog(
        onDismissRequest = { /* 故意空着：点空白/返回键都不关，见上面的说明 */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Text(
                when {
                    done != null -> "内核已导入"
                    failed != null -> "内核下载失败"
                    else -> "正在获取内核"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = when (phase) {
                        is KernelProvisioner.Phase.FetchingRelease -> "正在获取最新版本…"
                        is KernelProvisioner.Phase.Resolving -> "正在解析下载地址…"
                        is KernelProvisioner.Phase.Downloading -> "正在分片下载…"
                        is KernelProvisioner.Phase.Merging -> "正在合并分片…"
                        is KernelProvisioner.Phase.Importing -> "正在解包导入…"
                        is KernelProvisioner.Phase.Done ->
                            "${phase.fileName} 已解出 libgojni.so ${formatSize(phase.bytes)}"
                        is KernelProvisioner.Phase.Failed -> phase.message
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                when {
                    downloading != null -> {
                        // 总大小未知时给不确定进度条，别拿 0% 骗人
                        val known = downloading.total > 0L
                        val fraction = if (known) {
                            (downloading.downloaded.toFloat() / downloading.total).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        if (known) {
                            YunXWavyProgress(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        } else {
                            YunXWavyLoading(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text = buildString {
                                if (known) {
                                    append("${(fraction * 100).toInt()}%　")
                                    append("${formatSize(downloading.downloaded)} / ${formatSize(downloading.total)}")
                                } else {
                                    append(formatSize(downloading.downloaded))
                                }
                                if (downloading.speedBps > 0L) {
                                    append("　·　${formatSize(downloading.speedBps)}/s")
                                }
                                append("　·　${downloading.chunks} 分片")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    merging != null -> {
                        YunXWavyProgress(
                            progress = { (merging.percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Text(
                            text = "合并中 ${merging.percent}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    phase is KernelProvisioner.Phase.Importing -> {
                        YunXWavyLoading(modifier = Modifier.fillMaxWidth())
                    }
                    !finished -> {
                        YunXWavyLoading(modifier = Modifier.fillMaxWidth())
                    }
                }

                if (done != null) {
                    Text(
                        text = if (GopeedEngine.pendingRestartForNewKernel) {
                            "内核文件已换新，但本进程已经加载过旧内核（dlopen 进来的库卸载不掉），" +
                                "需要重启应用才会用上新内核。"
                        } else {
                            "内核文件已就位，下次启动引擎就会用它。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (finished) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                TextButton(onClick = onCancel) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
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
