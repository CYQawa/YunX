package com.yunx.app.ui.screens
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Speed
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.backup.AuthCrypto
import com.yunx.app.data.download.DownloadSaver
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.ui.SnackbarController
import com.yunx.app.util.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 可选的下载线程数（最高 512） */
private val threadOptions = listOf(1, 2, 4, 8, 16, 32)

/**
 * 设置页：下载线程数设置 + 主题外观 + 检查更新 + 日志与网盘认证。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    downloadThreads: Int,
    onThreadsChange: (Int) -> Unit,
    onThemeClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSupportClick: () -> Unit,
    backupManager: AuthBackupManager,
    /** 用应用内置下载器下载更新 APK（URL + 文件名），由 MainScreen 注入 DownloadManager */
    onDownloadUpdateApk: (url: String, fileName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showThreadsDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    // 检查更新结果（非空时弹更新对话框）
    var updateRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    // 网盘认证导出弹窗（AES 加密 + 导出范围）
    var showExportAuthDialog by remember { mutableStateOf(false) }
    // 网盘认证导入：加密文件内容（非空时弹解密密码框）
    var pendingImportContent by remember { mutableStateOf<String?>(null) }
    var showImportAuthDialog by remember { mutableStateOf(false) }
    // 本地状态：修改后立即刷新 UI，同时同步外部保存值
    var threads by remember { mutableStateOf(downloadThreads) }
    LaunchedEffect(downloadThreads) { threads = downloadThreads }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 下载保存目录（SAF）：本地状态驱动 UI 刷新，同时同步 SharedPreferences
    val settingsRepo = remember { SettingsRepository(context) }
    var downloadDirUri by remember { mutableStateOf(settingsRepo.downloadDirUri) }
    var showDevMenu by remember { mutableStateOf(false) }
    // 网络与下载策略（本地状态驱动 UI，同时同步 SharedPreferences）
    var maxConcurrent by remember { mutableStateOf(settingsRepo.maxConcurrentDownloads) }
    var speedLimitBps by remember { mutableStateOf(settingsRepo.downloadSpeedLimit) }
    var retryCount by remember { mutableStateOf(settingsRepo.downloadRetryCount) }
    var showConcurrencyDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showRetryDialog by remember { mutableStateOf(false) }
    // 用户体验与系统适配：锁屏保持下载 / 通知栏速度
    var keepLocked by remember { mutableStateOf(settingsRepo.keepDownloadWhenLocked) }
    var showSpeed by remember { mutableStateOf(settingsRepo.notificationShowSpeed) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    // 通知权限（Android 13+）：未授权时点击「通知栏下载进度」先申请，授权后生效
    val notifyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showSpeed = true
            settingsRepo.notificationShowSpeed = true
        }
    }
    val dirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久授权：应用重启后仍可写（API19+；Android 10/11+ 分区存储必需）
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            settingsRepo.downloadDirUri = uri.toString()
            downloadDirUri = uri.toString()
            SnackbarController.show("下载保存目录已更新")
        }
    }
    // 导入网盘认证文件选择器：选择后先判断是否加密备份，加密则弹密码框
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (text == null) {
                    SnackbarController.show("读取文件失败")
                    return@launch
                }
                if (AuthCrypto.isEncrypted(text)) {
                    // 加密备份：弹解密密码框
                    pendingImportContent = text
                    showImportAuthDialog = true
                } else {
                    // 明文备份：直接导入
                    val count = runCatching {
                        withContext(Dispatchers.IO) { backupManager.importJson(text) }
                    }.getOrElse { e ->
                        SnackbarController.show("导入失败：${e.message}")
                        return@launch
                    }
                    SnackbarController.show("已恢复 $count 个平台的认证信息")
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionLabel("下载")
        SettingsItem(
            icon = Icons.Outlined.Tune,
            title = "下载线程数",
            description = "当前 $threads 线程（分片并发）",
            onClick = { showThreadsDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 下载保存目录：系统文件夹选择器（SAF，适配各 Android 版本分区存储）；
        // 已自定义时卡片右侧内嵌「恢复默认」操作（不单独外露按钮）
        SettingsItem(
            icon = Icons.Outlined.FolderOpen,
            title = "下载保存目录",
            description = downloadDirUri?.let { "已自定义：${DownloadSaver.safDirDisplay(it)}" }
                ?: "系统默认 Download（点击自定义）",
            onClick = { dirLauncher.launch(null) },
            trailing = if (downloadDirUri != null) {
                {
                    TextButton(
                        onClick = {
                            downloadDirUri = null
                            settingsRepo.downloadDirUri = null
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

        Spacer(modifier = Modifier.height(8.dp))

        // 网络与下载策略
        SettingsItem(
            icon = Icons.Outlined.Layers,
            title = "最大同时下载任务数",
            description = "同时下载 $maxConcurrent 个任务（限制后台并发，避免占满带宽）",
            onClick = { showConcurrencyDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Speed,
            title = "下载速度限制",
            description = speedLimitText(speedLimitBps),
            onClick = { showSpeedDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Refresh,
            title = "失败自动重试",
            description = if (retryCount == 0) "失败后不自动重试" else "失败后自动重试 $retryCount 次（断点续传）",
            onClick = { showRetryDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 用户体验与系统适配：锁屏保持下载 / 通知栏进度样式
        SettingsItem(
            icon = Icons.Outlined.Power,
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

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Notifications,
            title = "通知栏下载进度",
            description = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED ->
                    "未授予通知权限，下载通知将不可见（点击申请）"
                showSpeed -> "完整通知：进度条 + 下载速度"
                else -> "仅显示通知（隐藏下载速度）"
            },
            onClick = {
                // Android 13+ 未授权：先申请通知权限，授权后自动开启完整通知
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            onClick = onThemeClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("通用")
        SettingsItem(
            icon = Icons.Outlined.SystemUpdate,
            title = "检查更新",
            description = "检查 GitHub 是否有新版本可用",
            onClick = {
                scope.launch {
                    SnackbarController.show("正在检查更新…")
                    val release = runCatching { UpdateChecker.fetchLatestRelease() }.getOrNull()
                    val current = UpdateChecker.currentVersion(context)
                    if (release == null) {
                        SnackbarController.show("检查更新失败，请检查网络")
                    } else if (UpdateChecker.compareVersions(release.tagName, current) > 0) {
                        updateRelease = release
                    } else {
                        SnackbarController.show("已是最新版本")
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.Article,
            title = "导出日志",
            description = "导出崩溃日志与应用信息，便于排查问题",
            onClick = { showLogDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("网盘认证")
        SettingsItem(
            icon = Icons.Outlined.Backup,
            title = "导出网盘认证",
            description = "使用至少 8 位口令加密 Cookie/JWT 后导出",
            onClick = { showExportAuthDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.Restore,
            title = "导入网盘认证",
            description = "选择加密或明文的认证备份文件，恢复网盘登录",
            onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("关于")
        SettingsItem(
            icon = Icons.Outlined.Info,
            title = "关于云析",
            description = "版本信息、支持平台与技术说明",
            onClick = onAboutClick,
            onLongClick = { showDevMenu = true } // 长按打开隐藏开发调试菜单
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.VolunteerActivism,
            title = "支持开发",
            description = "微信扫码捐赠，支持项目持续维护",
            onClick = onSupportClick
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
                            // 调试用途：直接弹出更新弹窗（不判断是否已是最新版），预览弹窗 UI
                            scope.launch {
                                val release = runCatching { UpdateChecker.fetchLatestRelease() }.getOrNull()
                                updateRelease = release ?: UpdateChecker.Release(
                                    tagName = "v1.2.4（预览）",
                                    body = "这是调试预览弹窗，用于查看更新弹窗 UI（含镜像站下载按钮）。",
                                    assets = emptyList(),
                                    publishedAt = ""
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("显示检查更新弹窗") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDevMenu = false }) { Text("关闭") }
            }
        )
    }

    // 检查更新结果弹窗（发现新版本时展示，下载走系统浏览器）
    updateRelease?.let { release ->
        UpdateDialog(
            currentVersion = UpdateChecker.currentVersion(context),
            release = release,
            onDownload = {
                updateRelease = null
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", true) }
                if (apk != null) {
                    onDownloadUpdateApk(apk.downloadUrl, apk.name)
                    SnackbarController.show("已加入下载 ${apk.name}")
                } else {
                    SnackbarController.show("未找到 APK 下载链接")
                }
            },
            onDownloadMirror = {
                updateRelease = null
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", true) }
                if (apk != null) {
                    onDownloadUpdateApk(UpdateChecker.mirrorUrl(apk.downloadUrl), apk.name)
                    SnackbarController.show("已通过镜像站加入下载 ${apk.name}")
                } else {
                    SnackbarController.show("未找到 APK 下载链接")
                }
            },
            onLater = { updateRelease = null },
            onIgnore = {
                context.getSharedPreferences("yunx_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("ignored_version", release.tagName)
                    .apply()
                updateRelease = null
            }
        )
    }

    // 线程数选择弹窗
    if (showThreadsDialog) {
        AlertDialog(
            onDismissRequest = { showThreadsDialog = false },
            title = { Text("下载线程数") },
            text = {
                Column {
                    Text(
                        text = "线程数越多，分片并行下载越快（需服务器支持 Range）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 两列网格：10 个选项 5 行一屏可见，无需滑动就知道有哪些档位；
                    // 仍保留限高 + 滚动，横屏/矮屏时兜底
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
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
                                        threads = threads,
                                        onSelect = { v ->
                                            threads = v
                                            onThreadsChange(v)
                                            showThreadsDialog = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                // 奇数个时补空占位，保持两列对齐
                                if (rowValues.size == 1) Spacer(modifier = Modifier.weight(1f))
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

    // 导出网盘认证弹窗（AES 加密密码 + 导出范围）
    if (showExportAuthDialog) {
        ExportAuthDialog(
            onDismiss = { showExportAuthDialog = false },
            onConfirm = { password, onlyLoggedIn ->
                showExportAuthDialog = false
                scope.launch {
                    val content = runCatching {
                        withContext(Dispatchers.IO) { backupManager.export(password, onlyLoggedIn) }
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
                    scope.launch {
                        val count = try {
                            withContext(Dispatchers.IO) { backupManager.import(content, password) }
                        } catch (e: javax.crypto.AEADBadTagException) {
                            SnackbarController.show("密码错误，解密失败")
                            return@launch
                        } catch (e: Exception) {
                            SnackbarController.show("导入失败：${e.message}")
                            return@launch
                        }
                        SnackbarController.show("已恢复 $count 个平台的认证信息")
                    }
                }
            }
        )
    }

    // 最大同时下载任务数
    if (showConcurrencyDialog) {
        val options = listOf(1, 2, 3, 5, 8)
        AlertDialog(
            onDismissRequest = { showConcurrencyDialog = false },
            title = { Text("最大同时下载任务数") },
            text = {
                Column {
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                Column {
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
    trailing: @Composable (() -> Unit)? = null
) {
    val shape = MaterialTheme.shapes.large
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
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
