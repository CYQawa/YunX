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
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.backup.AuthCrypto
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.DownloadSaver
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.ListGroupPos
import com.yunx.app.ui.theme.ThemeController
import com.yunx.app.ui.theme.listGroupShape
import com.yunx.app.util.AppLinks
import com.yunx.app.util.LogExporter
import com.yunx.app.util.StorageDirs
import com.yunx.app.ui.components.YunXLoading
import com.yunx.app.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 可选的下载线程数档位（最高 512，与 `SettingsRepository.MAX_DOWNLOAD_THREADS` 一致）。
 * 真实并发 = min(所选档位, `DownloadManager.MAX_INFLIGHT_CHUNKS`)：后者按最大堆预算推导并封顶 512，
 * 与本档位上限一致 ⇒ 选 512 就是 512 路（真实值见日志 `runTask:` 行的 actualWorkers）。
 * 下载客户端已固定 HTTP/1.1，每路只占一条连接 + 64KB 读缓冲（见 Agent.md §5.1.1）；
 * 但并发越高越容易撞上 CDN 的同 IP 连接数上限与手机链路瓶颈 —— 调高不一定更快，看诊断日志的总速。
 */
private val threadOptions = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512)

/** 按平台下载线程数设置项 */
private data class ThreadPlatform(val platform: String, val label: String)

private val threadPlatforms = listOf(
    ThreadPlatform(DownloadPlatform.QUARK, "夸克网盘"),
    ThreadPlatform(DownloadPlatform.UC, "UC 网盘"),
    ThreadPlatform(DownloadPlatform.XUNLEI, "迅雷网盘"),
    ThreadPlatform(DownloadPlatform.BAIDU, "百度网盘"),
    ThreadPlatform(DownloadPlatform.C139, "139 网盘"),
    ThreadPlatform(DownloadPlatform.PAN123, "123 云盘"),
    ThreadPlatform(DownloadPlatform.PAN115, "115 网盘"),
    ThreadPlatform(DownloadPlatform.GUANGYA, "光鸭云盘"),
    ThreadPlatform(DownloadPlatform.ILANZOU, "蓝奏云优享版"),
    ThreadPlatform(DownloadPlatform.LANZOU, "蓝奏云"),
)

/** 跳转系统「应用通知」设置页（Android 8+ 通用入口；失败时退回应用详情页） */
private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }
    }
}

/** 复制文本到剪贴板（写剪贴板不受「自动识别剪贴板」开关约束，见 Agent.md §3.22） */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("yunx_contact", text))
}

/** 用系统浏览器打开链接：没有可用浏览器时退化成复制链接，不静默失败 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        copyToClipboard(context, url)
        SnackbarController.show("未找到可用浏览器，链接已复制")
    }
}

/**
 * 设置页：下载线程数设置 + 主题外观 + 检查更新 + 日志与网盘认证。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    /**
     * 容器变换（Container Transform）源侧修饰符：由 MainScreen 在 SharedTransitionLayout 作用域内构造
     * （设置页自己拿不到那个作用域），分别挂到「主题与外观 / 关于云析 / 支持开发 / 下载引擎」这四行上。
     */
    themeRowModifier: Modifier = Modifier,
    aboutRowModifier: Modifier = Modifier,
    supportRowModifier: Modifier = Modifier,
    engineRowModifier: Modifier = Modifier,
    onThemeClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSupportClick: () -> Unit,
    /** 打开下载引擎页（两套下载器切换、导入内核、引擎启停与状态） */
    onGopeedClick: () -> Unit,
    backupManager: AuthBackupManager,
    /** 手动检查更新（弹窗与下载逻辑都由 MainScreen 统一持有，设置页不再自己实现一份） */
    onCheckUpdate: () -> Unit,
    /** 开发调试菜单的「显示检查更新弹窗」：直接用 MainScreen 已拿到的真实 Release 弹窗，不发请求、不比较版本 */
    onPreviewUpdateSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showThreadsDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    // 网盘认证导出弹窗（AES 加密 + 导出范围）
    var showExportAuthDialog by remember { mutableStateOf(false) }
    // 网盘认证导入：加密文件内容（非空时弹解密密码框）
    var pendingImportContent by remember { mutableStateOf<String?>(null) }
    var showImportAuthDialog by remember { mutableStateOf(false) }
    // 导出/导入处理中（PBKDF2 21万次迭代派生密钥，偶发 1~3s，期间显示加载弹窗）
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    // 按平台线程数：二级弹窗当前选择的平台
    var selectedThreadPlatform by remember { mutableStateOf(threadPlatforms.first()) }
    var showPlatformThreadDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 下载保存目录：内置下载器存 SAF tree Uri；Gopeed 引擎只能按真实路径写，所以另存一份路径字符串
    val settingsRepo = remember { SettingsRepository(context) }
    var downloadDirUri by remember { mutableStateOf(settingsRepo.downloadDirUri) }
    var engineDirPath by remember { mutableStateOf(settingsRepo.engineDownloadDir) }
    var showEngineDirDialog by remember { mutableStateOf(false) }
    var engineDirInput by remember { mutableStateOf("") }
    var showDevMenu by remember { mutableStateOf(false) }
    // 诊断模式开关（开发调试菜单里）：本地状态驱动 UI，运行态在 DiagnosticLog 里（改完立刻生效）
    var diagnosticOn by remember { mutableStateOf(settingsRepo.diagnosticMode) }
    // 下载引擎（内置分片下载器 / Gopeed 引擎）：本身在独立页面里改，这里只读来渲染副标题
    var engineChoice by remember { mutableStateOf(settingsRepo.downloadEngine) }
    // 引擎模式下「下载保存目录」这一行的语义/副标题不同，而且会隐藏若干「只对内置分片下载器有意义」的设置项，
    // 所以这个判断要早于下面几个回调（目录选择器的回调里就要用），声明在这里
    val engineOn = engineChoice == SettingsRepository.ENGINE_GOPEED
    // 网络与下载策略（本地状态驱动 UI，同时同步 SharedPreferences）
    var maxConcurrent by remember { mutableStateOf(settingsRepo.maxConcurrentDownloads) }
    var speedLimitBps by remember { mutableStateOf(settingsRepo.downloadSpeedLimit) }
    var retryCount by remember { mutableStateOf(settingsRepo.downloadRetryCount) }
    // 夸克取链方式：免转存（默认，不写入网盘）↔ 转存（先存临时目录再取链）
    var quarkNoSave by remember { mutableStateOf(settingsRepo.quarkNoSaveDownload) }
    var showConcurrencyDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showRetryDialog by remember { mutableStateOf(false) }
    // GitHub 下载镜像前缀：null/空 = 使用内置默认（UpdateChecker.MIRROR_PREFIX）
    var githubMirror by remember { mutableStateOf(settingsRepo.githubMirrorPrefix) }
    var showMirrorDialog by remember { mutableStateOf(false) }
    // 网络代理：本地状态驱动副标题，弹窗内使用临时变量编辑
    var proxyEnabled by remember { mutableStateOf(settingsRepo.proxyEnabled) }
    var proxyHost by remember { mutableStateOf(settingsRepo.proxyHost) }
    var proxyPort by remember { mutableStateOf(settingsRepo.proxyPort.toString()) }
    var showProxyDialog by remember { mutableStateOf(false) }
    // 用户体验与系统适配：锁屏保持下载 / 通知栏速度
    var keepLocked by remember { mutableStateOf(settingsRepo.keepDownloadWhenLocked) }
    var showSpeed by remember { mutableStateOf(settingsRepo.notificationShowSpeed) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    // 通知是否可用（areNotificationsEnabled 不是 Compose 状态源，手动提升为状态，
    // 权限回调/从系统设置返回时刷新，保证副标题文案即时同步）
    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                // 下载引擎在独立页面里切换，返回设置页时同步副标题
                engineChoice = settingsRepo.downloadEngine
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 通知权限（Android 13+）：未授权时点击「通知栏下载进度」先申请，授权后生效
    val notifyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 回调后立即刷新通知可用状态（副标题同步）
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (granted) {
            showSpeed = true
            settingsRepo.notificationShowSpeed = true
        }
    }
    val dirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            if (engineOn) {
                // Gopeed 引擎要的是**真实路径**：本机存储（外部存储 provider）能反解出来，
                // 第三方 provider（网盘/Drive 之类）反解不了 → 退到手输弹窗，绝不把 content:// 当路径用
                val path = GopeedEngine.realPathFromTreeUri(uri)
                val dir = path?.let {
                    runCatching { GopeedEngine.prepareDownloadDir(it.absolutePath) }.getOrNull()
                }
                if (dir != null) {
                    settingsRepo.engineDownloadDir = dir.absolutePath
                    engineDirPath = dir.absolutePath
                    SnackbarController.show("Gopeed 下载目录已更新：${dir.absolutePath}")
                } else {
                    val fallback = engineDirPath.ifBlank { StorageDirs.defaultDownloadPath() }
                    engineDirInput = path?.absolutePath ?: fallback
                    showEngineDirDialog = true
                    SnackbarController.show(
                        if (path == null) {
                            "这个位置拿不到真实路径，请手动输入目录"
                        } else {
                            "这个目录不可写，请手动输入或先授予「所有文件访问」"
                        }
                    )
                }
            } else {
                // 持久授权：应用重启后仍可写（API19+；Android 10/11+ 分区存储必需）
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                settingsRepo.downloadDirUri = uri.toString()
                downloadDirUri = uri.toString()
                SnackbarController.show("下载保存目录已更新")
            }
        }
    }
    // 导入网盘认证文件选择器：选择后先判断是否加密备份，加密则弹密码框
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                try {
                    val text = runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                    if (text == null) {
                        SnackbarController.show("读取文件失败")
                        return@launch
                    }
                    if (AuthCrypto.isEncrypted(text)) {
                        // 加密备份：关闭加载弹窗，弹解密密码框（解密在确认后执行）
                        pendingImportContent = text
                        showImportAuthDialog = true
                    } else {
                        // 明文备份：直接导入
                        val count = runCatching {
                            withContext(Dispatchers.IO) { backupManager.importJson(text, context) }
                        }.getOrElse { e ->
                            SnackbarController.show("导入失败：${e.message}")
                            return@launch
                        }
                        SnackbarController.show("已恢复 $count 个平台的认证信息")
                    }
                } finally {
                    isImporting = false
                }
            }
        }
    }

    // 列表组：同一分组内的行首尾相接（只留 1dp 发丝缝，圆角由 listGroupShape 按首/中/末分段给），
    // 分组之间仍用 24dp 间距隔开。
    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionLabel("下载")
        // 引擎切换在独立页面里做（那里上下两段各一个按钮），这里只显示当前用的是哪个
        SettingsItem(
            icon = Icons.Outlined.SwapHoriz,
            shape = listGroupShape(ListGroupPos.FIRST),
            title = "下载引擎",
            description = if (engineOn) {
                "Gopeed 引擎（内置 gomobile 核心，按真实路径落盘）"
            } else {
                "内置分片下载器（默认，走 SAF/MediaStore 保存）"
            },
            modifier = engineRowModifier,
            onClick = onGopeedClick
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.Tune,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "下载线程数",
            description = "按网盘分别设置分片并发数（默认 32，最高 512）",
            onClick = { showThreadsDialog = true }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        // 下载保存目录（两种下载器共用这一行，但存的东西不一样）：
        // - 内置分片下载器：SAF tree Uri（写 content://，走 MediaStore/SAF，Android 10+ 免存储权限）；
        // - Gopeed 引擎：**真实文件路径**（原生核心写不了 content://），所以从 SAF 选的目录里反解路径，
        //   反解不到（第三方 provider）或选择器不可用时退到手输弹窗（见 Agent.md §3.33）。
        // 系统选择器被卸载或禁用时 launch 抛 ActivityNotFoundException（#90）：
        // 内置下载器只能提示恢复选择器；引擎模式则直接给手输兜底。
        val dirCustomized = if (engineOn) engineDirPath.isNotBlank() else downloadDirUri != null
        SettingsItem(
            icon = Icons.Outlined.FolderOpen,
            // 这一行在两种引擎下都是**组内中间行**：它后面还有「免转存下载 / 锁屏后保持下载 /
            // 通知栏下载进度」，整个「下载」组一直到最后的通知栏那行才收尾（LAST）。
            // ★ 别按当前引擎改成 LAST —— 引擎模式下折叠掉的只是"只对内置有意义"的那几项，
            //   组并没有结束；组尾圆角会变成"圆了底又接着下一行"的怪样子（用户报过）。
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "下载保存目录",
            description = when {
                engineOn && engineDirPath.isNotBlank() -> "Gopeed：$engineDirPath"
                engineOn -> "Gopeed 默认 ${StorageDirs.defaultDownloadPath()}（点击自定义）"
                else -> downloadDirUri?.let { "已自定义：${DownloadSaver.safDirDisplay(it)}" }
                    ?: "默认 ${StorageDirs.defaultDownloadPath()}（点击自定义）"
            },
            onClick = {
                try {
                    dirLauncher.launch(null)
                } catch (_: ActivityNotFoundException) {
                    if (engineOn) {
                        // SAF 不可用：引擎模式退到手输路径
                        engineDirInput = engineDirPath.ifBlank { StorageDirs.defaultDownloadPath() }
                        showEngineDirDialog = true
                    } else {
                        SnackbarController.show("无法打开文件夹选择器，请恢复或启用系统文件选择器后重试")
                    }
                }
            },
            trailing = if (dirCustomized) {
                {
                    TextButton(
                        onClick = {
                            if (engineOn) {
                                engineDirPath = ""
                                settingsRepo.engineDownloadDir = ""
                            } else {
                                downloadDirUri = null
                                settingsRepo.downloadDirUri = null
                            }
                            SnackbarController.show("已恢复默认下载目录")
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "恢复默认",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                null
            }
        )

        // ★ 组内 3dp 发丝缝必须留在这个 AnimatedVisibility **外面**：折叠时里面的间距会一起消失，
        //   引擎模式下这一行就会和下面的「免转存下载」贴在一起（用户报的"边距错误"）。
        Spacer(modifier = Modifier.height(ListGroupGap))

        // 最大同时下载任务数：**两条下载路径共用这一个上限**，所以不能跟着下面的 AnimatedVisibility 一起折叠
        // （内置分片下载器是自己的并发闸门；Gopeed 引擎侧下发成配置顶层的 maxRunning，由引擎自己排队）
        SettingsItem(
            icon = Icons.Outlined.Layers,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "最大同时下载任务数",
            description = "同时下载 $maxConcurrent 个任务，超出的排队等待（内置下载器与 Gopeed 引擎共用）",
            onClick = { showConcurrencyDialog = true }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        // 引擎模式下隐藏「只对内置分片下载器有意义」的设置（引擎不支持限速；重试是引擎内部自己的策略）
        AnimatedVisibility(visible = !engineOn, enter = expandVertically(), exit = shrinkVertically()) {
            Column {
        // 网络与下载策略
        SettingsItem(
            icon = Icons.Outlined.Speed,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "下载速度限制",
            description = speedLimitText(speedLimitBps),
            onClick = { showSpeedDialog = true }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        SettingsItem(
            icon = Icons.Outlined.Refresh,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "失败自动重试",
            description = if (retryCount == 0) "失败后不自动重试" else "失败后自动重试 $retryCount 次（断点续传）",
            onClick = { showRetryDialog = true }
        )

            Spacer(modifier = Modifier.height(ListGroupGap))
            }
        }

        // 取链方式：免转存（默认）↔ 转存。只影响夸克 —— 其分享文件可以「用分享凭证直接换直链」，
        // 不必先转存进用户网盘；关掉后回到「转存到 YunX临时转存 再取链」的老流程。
        SettingsItem(
            icon = Icons.Outlined.SwapHoriz,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "免转存下载",
            description = if (quarkNoSave) {
                "夸克：解析出直链后直接下载，不把文件转存到自己的网盘"
            } else {
                "夸克：先转存到临时目录再取链（下载完成后自动清理）"
            },
            onClick = {
                quarkNoSave = !quarkNoSave
                settingsRepo.quarkNoSaveDownload = quarkNoSave
            },
            trailing = { Switch(checked = quarkNoSave, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        // 用户体验与系统适配：锁屏保持下载 / 通知栏进度样式
        // （这两项对引擎任务同样生效：引擎任务走同一套前台服务、WakeLock 与通知通道，所以不隐藏）
        SettingsItem(
            icon = Icons.Outlined.Power,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "锁屏后保持下载",
            description = "开启后下载时获取 WakeLock 维持网络，并可加入「忽略电池优化」白名单",
            onClick = {
                keepLocked = !keepLocked
                settingsRepo.keepDownloadWhenLocked = keepLocked
                if (keepLocked) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm?.isIgnoringBatteryOptimizations(context.packageName) != true) {
                        showBatteryDialog = true
                    }
                }
            },
            trailing = { Switch(checked = keepLocked, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        SettingsItem(
            icon = Icons.Outlined.Notifications,
            shape = listGroupShape(ListGroupPos.LAST),
            title = "通知栏下载进度",
            description = when {
                // 任意版本：系统通知被禁用（Android 13+ 未授权/低版本被系统或用户关闭）时提示去开启
                !notificationsEnabled ->
                    "通知未开启，下载通知将不可见（点击去开启）"
                showSpeed -> "完整通知：进度条 + 下载速度"
                else -> "仅显示通知（隐藏下载速度）"
            },
            onClick = {
                // Android 13+ 未授权：先申请运行时权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (!notificationsEnabled) {
                    // 任意版本：系统通知被禁用时引导去系统设置开启
                    openNotificationSettings(context)
                } else {
                    showSpeed = !showSpeed
                    settingsRepo.notificationShowSpeed = showSpeed
                }
            },
            trailing = { Switch(checked = showSpeed, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("外观")
        SettingsItem(
            icon = Icons.Outlined.Palette,
            title = "主题与外观",
            description = "主题色、动态色彩与深色模式",
            // 容器变换的源：点击后这一行会长成「主题与外观」整页（key 需与 MainScreen 一致）
            modifier = themeRowModifier,
            onClick = onThemeClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("通用")
        SettingsItem(
            icon = Icons.Outlined.ContentPaste,
            shape = listGroupShape(ListGroupPos.FIRST),
            title = "自动识别剪贴板",
            description = if (ThemeController.clipboardSuggestEnabled) {
                "复制分享链接后自动提示解析"
            } else {
                "已关闭，应用不再读取剪贴板"
            },
            onClick = {
                ThemeController.setClipboardSuggestEnabled(
                    context,
                    !ThemeController.clipboardSuggestEnabled
                )
            },
            trailing = {
                Switch(checked = ThemeController.clipboardSuggestEnabled, onCheckedChange = null)
            }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        SettingsItem(
            icon = Icons.Outlined.SystemUpdate,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "检查更新",
            description = "检查 GitHub 是否有新版本可用",
            onClick = onCheckUpdate
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        SettingsItem(
            icon = Icons.Outlined.SystemUpdate,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "接受预发布版更新",
            description = if (ThemeController.acceptPrereleaseUpdate) {
                "检查更新时包含 GitHub Pre-release（可能不稳定）"
            } else {
                "只接收正式版更新"
            },
            onClick = {
                ThemeController.setAcceptPrereleaseUpdate(
                    context,
                    !ThemeController.acceptPrereleaseUpdate
                )
            },
            trailing = {
                Switch(checked = ThemeController.acceptPrereleaseUpdate, onCheckedChange = null)
            }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        // GitHub 下载镜像：自定义前缀，留空使用内置默认镜像
        SettingsItem(
            icon = Icons.Outlined.Cloud,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "GitHub 下载镜像",
            description = githubMirror?.takeIf { it.isNotBlank() }
                ?.let { "已自定义：$it" }
                ?: "默认：${UpdateChecker.MIRROR_PREFIX}",
            onClick = { showMirrorDialog = true }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        // 网络代理：HTTP 代理（Clash/v2ray 等本地代理），未启用时直连
        SettingsItem(
            icon = Icons.Outlined.Security,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "网络代理",
            description = if (proxyEnabled && proxyHost.isNotBlank()) {
                "已启用：$proxyHost:$proxyPort"
            } else {
                "未启用（直连）"
            },
            onClick = { showProxyDialog = true }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.Article,
            shape = listGroupShape(ListGroupPos.LAST),
            title = "导出日志",
            description = "导出崩溃日志与应用信息，便于排查问题",
            onClick = { showLogDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("网盘认证")
        SettingsItem(
            icon = Icons.Outlined.Backup,
            shape = listGroupShape(ListGroupPos.FIRST),
            title = "导出网盘认证",
            description = "使用至少 8 位口令加密 Cookie/JWT 后导出",
            onClick = { showExportAuthDialog = true }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.Restore,
            shape = listGroupShape(ListGroupPos.LAST),
            title = "导入网盘认证",
            description = "选择加密或明文的认证备份文件，恢复网盘登录",
            onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("关于")
        SettingsItem(
            icon = Icons.Outlined.Info,
            shape = listGroupShape(ListGroupPos.FIRST),
            title = "关于云析",
            description = "版本信息、支持平台与技术说明",
            modifier = aboutRowModifier,
            onClick = onAboutClick,
            onLongClick = { showDevMenu = true } // 长按打开隐藏开发调试菜单
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.VolunteerActivism,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "支持开发",
            description = "微信扫码捐赠，支持项目持续维护",
            modifier = supportRowModifier,
            onClick = onSupportClick
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.Groups,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = "QQ 交流群",
            description = "群号 ${AppLinks.QQ_GROUP}（点击加入群聊，长按复制群号）",
            // 优先拉起 QQ 群卡片；设备上没装 QQ 时退回复制群号，保证入口永远可用
            onClick = {
                if (!AppLinks.openQQGroup(context)) {
                    copyToClipboard(context, AppLinks.QQ_GROUP)
                    SnackbarController.show("未安装 QQ，群号已复制：${AppLinks.QQ_GROUP}")
                }
            },
            onLongClick = {
                copyToClipboard(context, AppLinks.QQ_GROUP)
                SnackbarController.show("群号已复制：${AppLinks.QQ_GROUP}")
            }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.Code,
            shape = listGroupShape(ListGroupPos.LAST),
            title = "GitHub 仓库",
            description = "${AppLinks.GITHUB_REPO_DISPLAY} · 查看源码与反馈问题",
            onClick = { openUrl(context, AppLinks.GITHUB_REPO) }
        )
    }

    // Gopeed 下载目录手输弹窗：SAF 反解不到真实路径（第三方 provider / 选择器不可用）时的兜底。
    // 校验（建目录 + 试写探针）统一交给 GopeedEngine.prepareDownloadDir，失败原文直接提示、不关弹窗。
    if (showEngineDirDialog) {
        AlertDialog(
            onDismissRequest = { showEngineDirDialog = false },
            title = { Text("自定义 Gopeed 下载目录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Gopeed 是原生下载核心，只能按真实文件路径落盘，读不了系统文件选择器给的目录。" +
                            "请填一个绝对路径，例如 ${StorageDirs.defaultDownloadPath()}/YunX。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = engineDirInput,
                        onValueChange = { engineDirInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("目录路径") },
                        placeholder = { Text(StorageDirs.defaultDownloadPath()) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dir = runCatching { GopeedEngine.prepareDownloadDir(engineDirInput) }
                        .getOrElse { e ->
                            SnackbarController.show(e.message ?: "这个目录不可写")
                            return@TextButton
                        }
                    settingsRepo.engineDownloadDir = dir.absolutePath
                    engineDirPath = dir.absolutePath
                    showEngineDirDialog = false
                    SnackbarController.show("Gopeed 下载目录已设为 ${dir.absolutePath}")
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEngineDirDialog = false }) { Text("取消") }
            }
        )
    }

    // 导出日志方式选择弹窗
    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("导出日志") },
            text = {
                Column {
                    Text(
                        text = "选择日志导出方式：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            showLogDialog = false
                            scope.launch {
                                val file = withContext(Dispatchers.IO) { LogExporter.export(context) }
                                if (file != null && LogExporter.share(context, file)) {
                                    SnackbarController.show("日志已分享")
                                } else {
                                    SnackbarController.show("导出日志失败")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("分享日志（发送到其他应用）")
                    }
                    TextButton(
                        onClick = {
                            showLogDialog = false
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    LogExporter.saveToDownloads(context)
                                }
                                SnackbarController.show(if (ok) "已保存到下载目录" else "保存失败")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存到下载目录")
                    }
                    TextButton(
                        onClick = {
                            showLogDialog = false
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    LogExporter.clearLogcat()
                                }
                                SnackbarController.show(if (ok) "日志缓存已清空" else "清空失败")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("清空日志缓存（logcat -c）")
                    }
                    // 诊断模式开着时额外提供 zip：把 diagnostic_logs 下的分模块日志一次带走
                    if (settingsRepo.diagnosticMode) {
                        TextButton(
                            onClick = {
                                showLogDialog = false
                                scope.launch {
                                    val zip = withContext(Dispatchers.IO) { LogExporter.exportDiagnosticZip(context) }
                                    if (zip != null && LogExporter.share(context, zip)) {
                                        SnackbarController.show("诊断日志已打包分享（${zip.name}）")
                                    } else {
                                        SnackbarController.show("暂时没有可导出的诊断日志")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("导出诊断日志（zip）")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) { Text("取消") }
            }
        )
    }

    // 隐藏开发调试菜单（长按「关于云析」打开）
    if (showDevMenu) {
        AlertDialog(
            onDismissRequest = { showDevMenu = false },
            title = { Text("开发调试") },
            text = {
                Column {
                    Button(
                        onClick = {
                            showDevMenu = false
                            // 调试用途：直接拿 MainScreen 已获取到的真实 Release 弹出更新弹窗（不再发请求、不比较版本号）
                            onPreviewUpdateSheet()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("显示检查更新弹窗") }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 诊断模式：开启后 db / crypto / download / webview / network / operation 六个模块
                    // 的详细日志写进私有目录 diagnostic_logs（按模块分文件），关闭时一行都不写。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("诊断模式", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (diagnosticOn) {
                                    "详细日志写入 Android/data/${context.packageName}/files/diagnostic_logs"
                                } else {
                                    "关闭：不写任何诊断日志"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = diagnosticOn,
                            onCheckedChange = { on ->
                                diagnosticOn = on
                                settingsRepo.diagnosticMode = on
                                // 动态生效：DiagnosticLog.setEnabled 会立刻起/停写线程，关闭时先 flush
                                DiagnosticLog.setEnabled(context, on)
                                SnackbarController.show(if (on) "诊断模式已开启" else "诊断模式已关闭")
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 导出入口（zip 名 yunx_diagnostic_logs_yyyyMMdd_HHmmss.zip，导出前会先 flush）
                    TextButton(
                        onClick = {
                            showDevMenu = false
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) { LogExporter.exportDiagnosticZip(context) }
                                if (zip != null && LogExporter.share(context, zip)) {
                                    SnackbarController.show("诊断日志已打包分享（${zip.name}）")
                                } else {
                                    SnackbarController.show("暂时没有可导出的诊断日志")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("导出诊断日志（zip）") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDevMenu = false }) { Text("关闭") }
            }
        )
    }

    // 线程数选择弹窗（按平台）
    if (showThreadsDialog) {
        AlertDialog(
            onDismissRequest = { showThreadsDialog = false },
            title = { Text("下载线程数") },
            text = {
                // 横屏/小屏时内容超高可滚动，避免按钮被挤出屏幕
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "按网盘分别设置分片并发数；线程数不是越多越好，适当调整",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    threadPlatforms.forEach { item ->
                        val current = settingsRepo.downloadThreadsFor(item.platform)
                        val isXunlei = item.platform == DownloadPlatform.XUNLEI
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isXunlei) {
                                    selectedThreadPlatform = item
                                    showPlatformThreadDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (isXunlei) "固定 8 线程" else "$current 线程",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isXunlei) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            if (!isXunlei) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThreadsDialog = false }) { Text("取消") }
            }
        )
    }

    // 单个平台线程数选择（二级弹窗）
    if (showPlatformThreadDialog) {
        val current = settingsRepo.downloadThreadsFor(selectedThreadPlatform.platform)
        AlertDialog(
            onDismissRequest = { showPlatformThreadDialog = false },
            title = { Text("${selectedThreadPlatform.label}线程数") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    threadOptions.chunked(2).forEach { rowValues ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            rowValues.forEach { value ->
                                RadioThreadRow(
                                    value = value,
                                    threads = current,
                                    onSelect = { v ->
                                        settingsRepo.setDownloadThreads(selectedThreadPlatform.platform, v)
                                        showPlatformThreadDialog = false
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // 奇数个时补空占位，保持两列对齐
                            if (rowValues.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlatformThreadDialog = false }) { Text("取消") }
            }
        )
    }

    // 导出网盘认证弹窗（AES 加密密码 + 导出范围）
    if (showExportAuthDialog) {
        ExportAuthDialog(
            onDismiss = { showExportAuthDialog = false },
            onConfirm = { password, onlyLoggedIn ->
                showExportAuthDialog = false
                isExporting = true
                scope.launch {
                    try {
                        val content = runCatching {
                            withContext(Dispatchers.IO) { backupManager.export(password, onlyLoggedIn, context) }
                        }.getOrNull()
                        if (content == null) {
                            SnackbarController.show("导出失败")
                            return@launch
                        }
                        val encrypted = true
                        val saved = withContext(Dispatchers.IO) {
                            backupManager.saveToDownloads(context, content, encrypted)
                        }
                        SnackbarController.show(
                            if (saved) {
                                if (encrypted) "已加密导出到下载目录" else "已导出到下载目录"
                            } else {
                                "导出失败"
                            }
                        )
                    } finally {
                        isExporting = false
                    }
                }
            }
        )
    }

    // 导入加密备份弹窗（解密密码）
    if (showImportAuthDialog) {
        ImportAuthDialog(
            onDismiss = {
                showImportAuthDialog = false
                pendingImportContent = null
            },
            onConfirm = { password ->
                showImportAuthDialog = false
                val content = pendingImportContent
                pendingImportContent = null
                if (content != null) {
                    isImporting = true
                    scope.launch {
                        try {
                            val count = try {
                                withContext(Dispatchers.IO) { backupManager.import(content, password, context) }
                            } catch (e: javax.crypto.AEADBadTagException) {
                                SnackbarController.show("密码错误，解密失败")
                                return@launch
                            } catch (e: Exception) {
                                SnackbarController.show("导入失败：${e.message}")
                                return@launch
                            }
                            SnackbarController.show("已恢复 $count 个平台的认证信息")
                        } finally {
                            isImporting = false
                        }
                    }
                }
            }
        )
    }

    // 导出/导入处理中：转圈加载弹窗（PBKDF2 派生密钥耗时较长，避免用户以为界面卡死）
    if (isExporting) OperationLoadingDialog("正在导出认证…")
    if (isImporting) OperationLoadingDialog("正在导入认证…")

    // 最大同时下载任务数
    if (showConcurrencyDialog) {
        val options = listOf(1, 2, 3, 5, 8)
        AlertDialog(
            onDismissRequest = { showConcurrencyDialog = false },
            title = { Text("最大同时下载任务数") },
            text = {
                // 横屏/小屏时内容超高可滚动，避免按钮被挤出屏幕
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { v ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = maxConcurrent == v,
                                onClick = {
                                    maxConcurrent = v
                                    settingsRepo.maxConcurrentDownloads = v
                                    showConcurrencyDialog = false
                                    // 引擎自己不读这个设置，只在启动时同步一次，所以这里立刻补一次下发：
                                    // 调大时顺手唤醒引擎里已在排队的任务；调小不打断正在下载的（跑完自然收敛）。
                                    scope.launch {
                                        val applied = runCatching {
                                            withContext(Dispatchers.IO) { GopeedEngine.applyMaxRunning(context) }
                                        }.getOrNull()
                                        // null = 引擎没在跑（下次启动会带上新值），不算失败；引擎在跑却拿不到值才是失败
                                        if (applied == null &&
                                            GopeedEngine.state.value == GopeedEngine.State.RUNNING
                                        ) {
                                            SnackbarController.show("下载引擎并发上限下发失败，重启引擎后生效")
                                        }
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("同时下载 $v 个任务", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConcurrencyDialog = false }) { Text("取消") }
            }
        )
    }

    // 下载速度限制：预设档位 + 自定义（KB/s）
    if (showSpeedDialog) {
        val presets = listOf(0L, 1L * 1024 * 1024, 2L * 1024 * 1024, 5L * 1024 * 1024, 10L * 1024 * 1024)
        // 弹窗内临时选择（不立即写设置）：null=未操作，-1=自定义，其余=预设值
        var tempSelected by remember { mutableStateOf<Long?>(null) }
        // 自定义输入：打开时若当前是自定义档位，带出原值（重新打开保留）
        var customKb by remember {
            mutableStateOf(
                if (speedLimitBps > 0 && speedLimitBps !in presets) (speedLimitBps / 1024).toString() else ""
            )
        }
        val effective = tempSelected ?: speedLimitBps
        // 自定义选中态：显式识别「-1=自定义」哨兵；未操作时按当前值是否为自定义档位判断
        val isCustom = when {
            tempSelected == -1L -> true
            tempSelected == null -> speedLimitBps > 0 && speedLimitBps !in presets
            else -> false
        }
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("下载速度限制") },
            text = {
                // 横屏/小屏时内容超高可滚动，避免按钮被挤出屏幕
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    presets.forEach { v ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !isCustom && effective == v,
                                onClick = { tempSelected = v }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (v == 0L) "不限速" else speedLimitText(v),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    // 自定义档位：点击单选即可选中（进入自定义模式）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isCustom,
                            onClick = {
                                tempSelected = -1L
                                // 当前已是自定义值时带出原值，便于修改
                                if (speedLimitBps > 0 && speedLimitBps !in presets && customKb.isBlank()) {
                                    customKb = (speedLimitBps / 1024).toString()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = customKb,
                            onValueChange = {
                                customKb = it.filter(Char::isDigit).take(6)
                                // 输入即视为选择自定义
                                tempSelected = -1L
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("自定义 KB/s") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 以当前选中项为准：选自定义则应用输入；选预设则应用预设值
                        if (isCustom) {
                            val kb = customKb.toLongOrNull()?.coerceAtLeast(1L)
                            if (kb != null) {
                                speedLimitBps = kb * 1024
                                settingsRepo.downloadSpeedLimit = kb * 1024
                            }
                            // 自定义输入为空：保持原值
                        } else if (tempSelected != null) {
                            val v = tempSelected ?: speedLimitBps
                            speedLimitBps = v
                            settingsRepo.downloadSpeedLimit = v
                        }
                        // 未做任何选择：保持当前值
                        showSpeedDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showSpeedDialog = false }) { Text("取消") }
            }
        )
    }

    // 失败自动重试次数
    if (showRetryDialog) {
        val options = listOf(0, 1, 2, 3, 5, 8, 10)
        AlertDialog(
            onDismissRequest = { showRetryDialog = false },
            title = { Text("失败自动重试") },
            text = {
                // 横屏/小屏时内容超高可滚动，避免按钮被挤出屏幕
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { v ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = retryCount == v,
                                onClick = {
                                    retryCount = v
                                    settingsRepo.downloadRetryCount = v
                                    showRetryDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (v == 0) "不自动重试" else "失败后自动重试 $v 次",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRetryDialog = false }) { Text("取消") }
            }
        )
    }

    // 锁屏保持下载：引导加入「忽略电池优化」白名单
    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("保持后台下载") },
            text = {
                Text(
                    text = "为确保障屏后下载不中断，建议将云析加入「忽略电池优化」白名单。是否前往系统设置？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatteryDialog = false
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                ) { Text("前往设置") }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) { Text("暂不") }
            }
        )
    }

    // GitHub 下载镜像前缀设置弹窗（留空 = 使用内置默认镜像）
    if (showMirrorDialog) {
        // 弹窗内临时输入：打开时带出当前已保存的自定义前缀（无则空）
        var mirrorInput by remember { mutableStateOf(githubMirror ?: "") }
        AlertDialog(
            onDismissRequest = { showMirrorDialog = false },
            title = { Text("GitHub 下载镜像") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = mirrorInput,
                        onValueChange = { mirrorInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("镜像前缀 URL") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true
                    )
                    Text(
                        text = "留空使用默认镜像 ${UpdateChecker.MIRROR_PREFIX}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { mirrorInput = "" }) {
                        Text("恢复默认")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val raw = mirrorInput.trim()
                        if (raw.isBlank()) {
                            // 空：恢复内置默认镜像
                            settingsRepo.githubMirrorPrefix = null
                            githubMirror = null
                            showMirrorDialog = false
                            SnackbarController.show("已恢复默认镜像")
                        } else if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                            // 必须是 http/https 开头，否则报错不保存
                            SnackbarController.show("镜像前缀需以 http:// 或 https:// 开头")
                        } else {
                            // 规范化：统一以 / 结尾，拼接原直链时不会粘连
                            val normalized = if (raw.endsWith("/")) raw else "$raw/"
                            settingsRepo.githubMirrorPrefix = normalized
                            githubMirror = normalized
                            showMirrorDialog = false
                            SnackbarController.show("GitHub 镜像已更新")
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showMirrorDialog = false }) { Text("取消") }
            }
        )
    }

    // 网络代理设置弹窗（HTTP 代理；未启用时直连）
    if (showProxyDialog) {
        // 弹窗内临时变量：取消时不回写已保存值
        var tempEnabled by remember { mutableStateOf(proxyEnabled) }
        var tempHost by remember { mutableStateOf(proxyHost) }
        var tempPort by remember { mutableStateOf(proxyPort) }
        AlertDialog(
            onDismissRequest = { showProxyDialog = false },
            title = { Text("网络代理") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "启用代理",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = tempEnabled, onCheckedChange = { tempEnabled = it })
                    }
                    OutlinedTextField(
                        value = tempHost,
                        onValueChange = { tempHost = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("代理主机地址（如 127.0.0.1）") },
                        singleLine = true,
                        enabled = tempEnabled
                    )
                    OutlinedTextField(
                        value = tempPort,
                        onValueChange = { tempPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("代理端口（如 7890）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = tempEnabled
                    )
                    Text(
                        text = "代理用于加速 GitHub 等海外资源；不启用时所有请求直连。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tempEnabled) {
                            val host = tempHost.trim()
                            val port = tempPort.toIntOrNull()
                            when {
                                // 校验失败仅提示，不关闭弹窗
                                host.isBlank() ->
                                    SnackbarController.show("请填写代理主机地址")
                                port == null || port !in 1..65535 ->
                                    SnackbarController.show("代理端口需为 1-65535 之间的数字")
                                else -> {
                                    settingsRepo.proxyEnabled = true
                                    settingsRepo.proxyHost = host
                                    settingsRepo.proxyPort = port
                                    HttpClients.setProxy(host, port)
                                    proxyEnabled = true
                                    proxyHost = host
                                    proxyPort = port.toString()
                                    showProxyDialog = false
                                    SnackbarController.show("代理已启用：$host:$port")
                                }
                            }
                        } else {
                            // 关闭代理：恢复直连
                            settingsRepo.proxyEnabled = false
                            HttpClients.setProxy(null, 0)
                            proxyEnabled = false
                            showProxyDialog = false
                            SnackbarController.show("已关闭代理，恢复直连")
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showProxyDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 导出网盘认证弹窗：AES 加密密码 + 导出范围（仅已登录 / 全部绑定） */
@Composable
private fun ExportAuthDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String, onlyLoggedIn: Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var onlyLoggedIn by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出网盘认证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "设置至少 8 位密码对认证文件进行 AES 加密。密码请务必牢记，丢失无法找回。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("加密密码（至少 8 位）") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Text(
                    text = "导出范围",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = onlyLoggedIn,
                        onClick = { onlyLoggedIn = true }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("仅导出当前已登录的网盘", style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !onlyLoggedIn,
                        onClick = { onlyLoggedIn = false }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出全部绑定的网盘", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password, onlyLoggedIn) },
                enabled = password.length >= 8
            ) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 导入加密备份弹窗：输入解密密码 */
@Composable
private fun ImportAuthDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入网盘认证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "该备份文件已加密，请输入导出时设置的密码进行解密。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("解密密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank()
            ) { Text("解密并导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 操作处理中弹窗：转圈加载 + 提示文案，禁止关闭（防止中途取消导致导入/导出状态不一致） */
@Composable
private fun OperationLoadingDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(message) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                YunXLoading()
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    /** 长按回调（隐藏菜单等）；null 时不启用长按 */
    onLongClick: (() -> Unit)? = null,
    /** 自定义尾部内容（如「恢复默认」操作）；null 时显示默认 ChevronRight */
    trailing: @Composable (() -> Unit)? = null,
    /** 列表组分段圆角：同一分组内的行传 listGroupShape(...)；默认四角整圆（单行分组） */
    shape: Shape = MaterialTheme.shapes.large,
    /** 容器变换源侧修饰符（sharedBounds）；普通行不传 */
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 线程数单选行（用于弹窗两列布局，每行占半宽） */
@Composable
private fun RadioThreadRow(
    value: Int,
    threads: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = threads == value,
            onClick = { onSelect(value) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$value 线程",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/** 速度限制展示文案：0=不限速；>=1MB/s 显示 MB/s，否则 KB/s */
private fun speedLimitText(bps: Long): String {
    if (bps <= 0) return "不限速"
    return if (bps >= 1024 * 1024) {
        val mb = bps / (1024.0 * 1024.0)
        if (mb >= 10) String.format("%.0f MB/s", mb) else String.format("%.1f MB/s", mb)
    } else {
        "${bps / 1024} KB/s"
    }
}
