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
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolunteerActivism
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.annotation.StringRes
import com.yunx.app.R
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.backup.AuthCrypto
import com.yunx.app.data.download.DownloadDebugLog
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.DownloadSaver
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.YunXLoading
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.ListGroupPos
import com.yunx.app.ui.theme.LocaleController
import com.yunx.app.ui.theme.ThemeController
import com.yunx.app.ui.theme.listGroupShape
import com.yunx.app.util.AppLinks
import com.yunx.app.util.AppStrings
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.LogExporter
import com.yunx.app.util.StorageDirs
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
private data class ThreadPlatform(val platform: String, @StringRes val label: Int)

private val threadPlatforms = listOf(
    ThreadPlatform(DownloadPlatform.QUARK, R.string.platform_quark),
    ThreadPlatform(DownloadPlatform.UC, R.string.platform_uc),
    ThreadPlatform(DownloadPlatform.XUNLEI, R.string.platform_xunlei),
    ThreadPlatform(DownloadPlatform.BAIDU, R.string.platform_baidu),
    ThreadPlatform(DownloadPlatform.C139, R.string.platform_c139),
    ThreadPlatform(DownloadPlatform.PAN123, R.string.platform_pan123),
    ThreadPlatform(DownloadPlatform.PAN115, R.string.platform_pan115),
    ThreadPlatform(DownloadPlatform.GUANGYA, R.string.platform_guangya),
    ThreadPlatform(DownloadPlatform.ILANZOU, R.string.platform_ilanzou),
    ThreadPlatform(DownloadPlatform.LANZOU, R.string.platform_lanzou),
)

/** 跳转系统「应用通知」设置页（Android 8+ 通用入口；失败时退回应用详情页）。跨文件复用（下载设置页） */
internal fun openNotificationSettings(context: Context) {
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
        SnackbarController.show(AppStrings.get(R.string.snack_no_browser_link_copied))
    }
}

/**
 * 设置页：下载线程数设置 + 主题外观 + 检查更新 + 日志与认证信息。
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
    /** 「链接唤起」行的容器变换源侧修饰符（同上，由 MainScreen 构造） */
    linkOpenRowModifier: Modifier = Modifier,
    /** 「语言」行的容器变换源侧修饰符（同上，由 MainScreen 构造） */
    languageRowModifier: Modifier = Modifier,
    /** 「下载设置」行的容器变换源侧修饰符（同上，由 MainScreen 构造） */
    downloadSettingsRowModifier: Modifier = Modifier,
    /** 「网络与更新」行的容器变换源侧修饰符（同上，由 MainScreen 构造） */
    networkUpdateRowModifier: Modifier = Modifier,
    onThemeClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSupportClick: () -> Unit,
    /** 打开下载引擎页（两套下载器切换、导入内核、引擎启停与状态） */
    onGopeedClick: () -> Unit,
    /** 打开「链接唤起」页（深链接注册项的逐个开关） */
    onLinkOpenClick: () -> Unit,
    /** 打开「语言」页（界面语言单选列表） */
    onLanguageClick: () -> Unit,
    /** 打开「下载设置」页（并发 / 限速 / 重试 / 后台下载） */
    onDownloadSettingsClick: () -> Unit,
    /** 打开「网络与更新」页（预发布 / GitHub 镜像 / 网络代理） */
    onNetworkUpdateClick: () -> Unit,
    backupManager: AuthBackupManager,
    /** 手动检查更新（弹窗与下载逻辑都由 MainScreen 统一持有，设置页不再自己实现一份） */
    onCheckUpdate: () -> Unit,
    /** 开发调试菜单的「显示检查更新弹窗」：直接用 MainScreen 已拿到的真实 Release 弹窗，不发请求、不比较版本 */
    onPreviewUpdateSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showThreadsDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    // 认证信息导出弹窗（AES 加密 + 导出范围）
    var showExportAuthDialog by remember { mutableStateOf(false) }
    // 认证信息导入：加密文件内容（非空时弹解密密码框）
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
    // 下载调试开关（开发调试菜单里）：开启后下载页长按任务可查看「调试信息」；关闭会清空日志，故需二次确认
    var downloadDebugOn by remember { mutableStateOf(settingsRepo.downloadDebug) }
    var showDisableDownloadDebugConfirm by remember { mutableStateOf(false) }
    // 全量 SHA-256 校验开关（开发调试菜单里）：默认关，开启后保存文件时顺带算整文件摘要写进调试日志
    var fullShaOn by remember { mutableStateOf(settingsRepo.fullFileSha256) }
    // 下载引擎（内置分片下载器 / Gopeed 引擎）：本身在独立页面里改，这里只读来渲染副标题
    var engineChoice by remember { mutableStateOf(settingsRepo.downloadEngine) }
    // 引擎模式下「下载保存目录」这一行的语义/副标题不同，而且会隐藏若干「只对内置分片下载器有意义」的设置项，
    // 所以这个判断要早于下面几个回调（目录选择器的回调里就要用），声明在这里
    val engineOn = engineChoice == SettingsRepository.ENGINE_GOPEED
    // 下载策略 / 取链方式 / 系统适配等重项已移到「下载设置」二级页，本页只保留常用入口
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 下载引擎在独立页面里切换，返回设置页时同步副标题
                engineChoice = settingsRepo.downloadEngine
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                    SnackbarController.show(AppStrings.get(R.string.snack_gopeed_dir_updated, dir.absolutePath))
                } else {
                    val fallback = engineDirPath.ifBlank { StorageDirs.defaultDownloadPath() }
                    engineDirInput = path?.absolutePath ?: fallback
                    showEngineDirDialog = true
                    SnackbarController.show(
                        if (path == null) {
                            AppStrings.get(R.string.snack_engine_dir_no_real_path)
                        } else {
                            AppStrings.get(R.string.snack_dir_not_writable_hint)
                        }
                    )
                }
            } else {
                // 持久授权：应用重启后仍可写（API19+；Android 10/11+ 分区存储必需）
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                settingsRepo.downloadDirUri = uri.toString()
                downloadDirUri = uri.toString()
                SnackbarController.show(AppStrings.get(R.string.snack_download_dir_updated))
            }
        }
    }
    // 导入认证信息文件选择器：选择后先判断是否加密备份，加密则弹密码框
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
                        SnackbarController.show(AppStrings.get(R.string.snack_read_file_failed))
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
                            SnackbarController.show(AppStrings.get(R.string.snack_import_failed, e.message ?: ""))
                            return@launch
                        }
                        SnackbarController.show(AppStrings.get(R.string.snack_auth_imported, count))
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
        SectionLabel(stringResource(R.string.section_download))
        // 引擎切换在独立页面里做（那里上下两段各一个按钮），这里只显示当前用的是哪个
        SettingsItem(
            icon = Icons.Outlined.SwapHoriz,
            shape = listGroupShape(ListGroupPos.FIRST),
            title = stringResource(R.string.settings_download_engine),
            description = stringResource(
                if (engineOn) R.string.settings_download_engine_desc_gopeed
                else R.string.settings_download_engine_desc_builtin
            ),
            modifier = engineRowModifier,
            onClick = onGopeedClick
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.Layers,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = stringResource(R.string.settings_download_threads),
            description = stringResource(R.string.settings_download_threads_desc),
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
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = stringResource(R.string.settings_download_dir),
            description = when {
                engineOn && engineDirPath.isNotBlank() ->
                    stringResource(R.string.settings_download_dir_desc_engine, engineDirPath)
                engineOn -> stringResource(
                    R.string.settings_download_dir_desc_engine_default,
                    StorageDirs.defaultDownloadPath()
                )
                downloadDirUri != null -> stringResource(
                    R.string.settings_download_dir_desc_custom,
                    DownloadSaver.safDirDisplay(downloadDirUri!!)
                )
                else -> stringResource(
                    R.string.settings_download_dir_desc_default,
                    StorageDirs.defaultDownloadPath()
                )
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
                        SnackbarController.show(AppStrings.get(R.string.snack_folder_picker_unavailable))
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
                            SnackbarController.show(AppStrings.get(R.string.snack_download_dir_reset))
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_restore_default),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                null
            }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        // 并发 / 限速 / 失败重试 / 免转存取链 / 锁屏保持 / 通知进度 / 多账号下载等重项
        // 都收进了「下载设置」二级页，本页「下载」组只保留最常用的三行 + 这一个入口。
        SettingsItem(
            icon = Icons.Outlined.Tune,
            shape = listGroupShape(ListGroupPos.LAST),
            title = stringResource(R.string.settings_download_settings),
            description = stringResource(R.string.settings_download_settings_desc),
            modifier = downloadSettingsRowModifier,
            onClick = onDownloadSettingsClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel(stringResource(R.string.section_appearance))
        SettingsItem(
            icon = Icons.Outlined.Palette,
            shape = listGroupShape(ListGroupPos.FIRST),
            title = stringResource(R.string.settings_theme_entry),
            description = stringResource(R.string.settings_theme_entry_desc),
            // 容器变换的源：点击后这一行会长成「主题与外观」整页（key 需与 MainScreen 一致）
            modifier = themeRowModifier,
            onClick = onThemeClick
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        // 语言：进入二级页选择界面语言（下拉框难以做到完美，改用二级页）
        SettingsItem(
            icon = Icons.Outlined.Language,
            shape = listGroupShape(ListGroupPos.LAST),
            title = stringResource(R.string.settings_language),
            description = stringResource(
                R.string.settings_language_desc,
                stringResource(LocaleController.language.labelRes)
            ),
            modifier = languageRowModifier,
            onClick = onLanguageClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel(stringResource(R.string.section_general))
        SettingsItem(
            icon = Icons.Outlined.Image,
            shape = listGroupShape(ListGroupPos.FIRST),
            title = stringResource(R.string.settings_thumbnails),
            description = stringResource(
                if (ThemeController.cloudThumbnails) R.string.settings_thumbnails_desc_on
                else R.string.settings_thumbnails_desc_off
            ),
            onClick = {
                ThemeController.setCloudThumbnails(context, !ThemeController.cloudThumbnails)
            },
            checked = ThemeController.cloudThumbnails,
            trailing = {
                Switch(checked = ThemeController.cloudThumbnails, onCheckedChange = null)
            }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        SettingsItem(
            icon = Icons.Outlined.ContentPaste,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = stringResource(R.string.settings_clipboard_detect),
            description = stringResource(
                if (ThemeController.clipboardSuggestEnabled) R.string.settings_clipboard_detect_desc_on
                else R.string.settings_clipboard_detect_desc_off
            ),
            onClick = {
                ThemeController.setClipboardSuggestEnabled(
                    context,
                    !ThemeController.clipboardSuggestEnabled
                )
            },
            checked = ThemeController.clipboardSuggestEnabled,
            trailing = {
                Switch(checked = ThemeController.clipboardSuggestEnabled, onCheckedChange = null)
            }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        SettingsItem(
            icon = Icons.Outlined.SystemUpdate,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = stringResource(R.string.settings_check_update),
            description = stringResource(R.string.settings_check_update_desc),
            onClick = onCheckUpdate
        )

        Spacer(modifier = Modifier.height(ListGroupGap))

        // 网络与更新：预发布版、GitHub 镜像、网络代理都收进这个二级页
        SettingsItem(
            icon = Icons.Outlined.Cloud,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = stringResource(R.string.settings_network_update),
            description = stringResource(R.string.settings_network_update_desc),
            modifier = networkUpdateRowModifier,
            onClick = onNetworkUpdateClick
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.Article,
            shape = listGroupShape(ListGroupPos.LAST),
            title = stringResource(R.string.settings_export_log),
            description = stringResource(R.string.settings_export_log_desc),
            onClick = { showLogDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel(stringResource(R.string.section_auth_backup))
        SettingsItem(
            icon = Icons.Outlined.Backup,
            shape = listGroupShape(ListGroupPos.FIRST),
            title = stringResource(R.string.settings_export_auth),
            description = stringResource(R.string.settings_export_auth_desc),
            onClick = { showExportAuthDialog = true }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.Restore,
            shape = listGroupShape(ListGroupPos.LAST),
            title = stringResource(R.string.settings_import_auth),
            description = stringResource(R.string.settings_import_auth_desc),
            onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel(stringResource(R.string.section_link_open))
        SettingsItem(
            icon = Icons.Outlined.Link,
            shape = listGroupShape(ListGroupPos.SINGLE),
            title = stringResource(R.string.settings_link_open),
            description = stringResource(R.string.settings_link_open_desc),
            modifier = linkOpenRowModifier,
            onClick = onLinkOpenClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel(stringResource(R.string.section_about))
        SettingsItem(
            icon = Icons.Outlined.Info,
            shape = listGroupShape(ListGroupPos.FIRST),
            title = stringResource(R.string.settings_about_app),
            description = stringResource(R.string.settings_about_app_desc),
            modifier = aboutRowModifier,
            onClick = onAboutClick,
            onLongClick = { showDevMenu = true } // 长按打开隐藏开发调试菜单
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.VolunteerActivism,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = stringResource(R.string.settings_support_dev),
            description = stringResource(R.string.settings_support_dev_desc),
            modifier = supportRowModifier,
            onClick = onSupportClick
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.Groups,
            shape = listGroupShape(ListGroupPos.MIDDLE),
            title = stringResource(R.string.settings_qq_group),
            description = stringResource(R.string.settings_qq_group_desc, AppLinks.QQ_GROUP),
            // 优先拉起 QQ 群卡片；设备上没装 QQ 时退回复制群号，保证入口永远可用
            onClick = {
                if (!AppLinks.openQQGroup(context)) {
                    copyToClipboard(context, AppLinks.QQ_GROUP)
                    SnackbarController.show(AppStrings.get(R.string.snack_qq_not_installed, AppLinks.QQ_GROUP))
                }
            },
            onLongClick = {
                copyToClipboard(context, AppLinks.QQ_GROUP)
                SnackbarController.show(AppStrings.get(R.string.snack_qq_group_copied, AppLinks.QQ_GROUP))
            }
        )

        Spacer(modifier = Modifier.height(ListGroupGap))
        SettingsItem(
            icon = Icons.Outlined.Code,
            shape = listGroupShape(ListGroupPos.LAST),
            title = stringResource(R.string.settings_github_repo),
            description = stringResource(R.string.settings_github_repo_desc, AppLinks.GITHUB_REPO_DISPLAY),
            onClick = { openUrl(context, AppLinks.GITHUB_REPO) }
        )
    }

    // Gopeed 下载目录手输弹窗：SAF 反解不到真实路径（第三方 provider / 选择器不可用）时的兜底。
    // 校验（建目录 + 试写探针）统一交给 GopeedEngine.prepareDownloadDir，失败原文直接提示、不关弹窗。
    if (showEngineDirDialog) {
        AlertDialog(
            onDismissRequest = { showEngineDirDialog = false },
            title = { Text(stringResource(R.string.dialog_engine_dir_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(
                            R.string.dialog_engine_dir_message,
                            StorageDirs.defaultDownloadPath()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = engineDirInput,
                        onValueChange = { engineDirInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dialog_engine_dir_label)) },
                        placeholder = { Text(StorageDirs.defaultDownloadPath()) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dir = runCatching { GopeedEngine.prepareDownloadDir(engineDirInput) }
                        .getOrElse { e ->
                            SnackbarController.show(e.message ?: AppStrings.get(R.string.snack_dir_not_writable))
                            return@TextButton
                        }
                    settingsRepo.engineDownloadDir = dir.absolutePath
                    engineDirPath = dir.absolutePath
                    showEngineDirDialog = false
                    SnackbarController.show(AppStrings.get(R.string.snack_gopeed_dir_set, dir.absolutePath))
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEngineDirDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 导出日志方式选择弹窗
    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text(stringResource(R.string.dialog_log_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.dialog_log_choose),
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
                                    SnackbarController.show(AppStrings.get(R.string.snack_log_shared))
                                } else {
                                    SnackbarController.show(AppStrings.get(R.string.snack_log_export_failed))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dialog_log_share))
                    }
                    TextButton(
                        onClick = {
                            showLogDialog = false
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    LogExporter.saveToDownloads(context)
                                }
                                SnackbarController.show(
                                    if (ok) AppStrings.get(R.string.snack_log_saved)
                                    else AppStrings.get(R.string.snack_log_save_failed)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dialog_log_save))
                    }
                    TextButton(
                        onClick = {
                            showLogDialog = false
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    LogExporter.clearLogcat()
                                }
                                SnackbarController.show(
                                    if (ok) AppStrings.get(R.string.snack_log_cleared)
                                    else AppStrings.get(R.string.snack_log_clear_failed)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dialog_log_clear))
                    }
                    // 诊断模式开着时额外提供 zip：把 diagnostic_logs 下的分模块日志一次带走
                    if (settingsRepo.diagnosticMode) {
                        TextButton(
                            onClick = {
                                showLogDialog = false
                                scope.launch {
                                    val zip = withContext(Dispatchers.IO) { LogExporter.exportDiagnosticZip(context) }
                                    if (zip != null && LogExporter.share(context, zip)) {
                                        SnackbarController.show(AppStrings.get(R.string.snack_diag_log_shared, zip.name))
                                    } else {
                                        SnackbarController.show(AppStrings.get(R.string.snack_diag_log_empty))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.dialog_log_export_zip))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 隐藏开发调试菜单（长按「关于云析」打开）
    if (showDevMenu) {
        AlertDialog(
            onDismissRequest = { showDevMenu = false },
            title = { Text(stringResource(R.string.dialog_dev_title)) },
            text = {
                Column {
                    Button(
                        onClick = {
                            showDevMenu = false
                            // 调试用途：直接拿 MainScreen 已获取到的真实 Release 弹出更新弹窗（不再发请求、不比较版本号）
                            onPreviewUpdateSheet()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.dialog_dev_show_update)) }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 诊断模式：开启后 db / crypto / download / webview / network / operation 六个模块
                    // 的详细日志写进私有目录 diagnostic_logs（按模块分文件），关闭时一行都不写。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.dialog_dev_diagnostic),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (diagnosticOn) {
                                    stringResource(R.string.dialog_dev_diagnostic_on, context.packageName)
                                } else {
                                    stringResource(R.string.dialog_dev_diagnostic_off)
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
                                SnackbarController.show(
                                    if (on) AppStrings.get(R.string.snack_diagnostic_on)
                                    else AppStrings.get(R.string.snack_diagnostic_off)
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 下载调试：开启后按下载任务记录事件流（主要变更 / 错误节点），下载页长按任务可进调试信息页。
                    // ★ 关闭会清空全部日志，所以不直接关，先弹二次确认（见下方 AlertDialog）。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.dialog_dev_download_debug),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (downloadDebugOn) {
                                    stringResource(R.string.dialog_dev_download_debug_on)
                                } else {
                                    stringResource(R.string.dialog_dev_download_debug_off)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = downloadDebugOn,
                            onCheckedChange = { on ->
                                if (on) {
                                    downloadDebugOn = true
                                    ThemeController.setDownloadDebug(context, true)
                                    DownloadDebugLog.setEnabled(true)
                                    SnackbarController.show(AppStrings.get(R.string.snack_download_debug_on))
                                } else {
                                    // 关闭会清空全部调试日志：先二次确认再真正关闭
                                    showDisableDownloadDebugConfirm = true
                                }
                            }
                        )
                    }

                    // 全量 SHA-256 只在「下载调试」开启时才有意义（摘要只写进调试日志），
                    // 所以调试关着时整行隐藏；关掉下载调试会把它一并强制关闭，下次要手动重新开。
                    if (downloadDebugOn) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // 全量 SHA-256（默认关）：保存文件时顺带算整文件摘要并写进下载调试日志。
                        // 与合并复用同一遍 IO，所以不额外读盘，只多一点 CPU —— 大文件想省 CPU 就关着。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.dialog_dev_full_sha),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = if (fullShaOn) {
                                        stringResource(R.string.dialog_dev_full_sha_on)
                                    } else {
                                        stringResource(R.string.dialog_dev_full_sha_off)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = fullShaOn,
                                onCheckedChange = { on ->
                                    fullShaOn = on
                                    settingsRepo.fullFileSha256 = on
                                    SnackbarController.show(
                                        if (on) AppStrings.get(R.string.snack_full_sha_on)
                                        else AppStrings.get(R.string.snack_full_sha_off)
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 导出入口（zip 名 yunx_diagnostic_logs_yyyyMMdd_HHmmss.zip，导出前会先 flush）
                    TextButton(
                        onClick = {
                            showDevMenu = false
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) { LogExporter.exportDiagnosticZip(context) }
                                if (zip != null && LogExporter.share(context, zip)) {
                                    SnackbarController.show(AppStrings.get(R.string.snack_diag_log_shared, zip.name))
                                } else {
                                    SnackbarController.show(AppStrings.get(R.string.snack_diag_log_empty))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.dialog_log_export_zip)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDevMenu = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    // 关闭「下载调试」的二次确认：关闭会清空已记录的全部调试日志，且不可恢复
    if (showDisableDownloadDebugConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableDownloadDebugConfirm = false },
            title = { Text(stringResource(R.string.dialog_disable_debug_title)) },
            text = { Text(stringResource(R.string.dialog_disable_debug_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisableDownloadDebugConfirm = false
                    downloadDebugOn = false
                    ThemeController.setDownloadDebug(context, false)
                    // 关闭即清空：DownloadDebugLog.setEnabled(false) 内部会 clear()
                    DownloadDebugLog.setEnabled(false)
                    // 全量 SHA-256 依赖下载调试（摘要只写调试日志）：一并强制关闭，且不记忆，
                    // 下次重新开启下载调试后需要用户手动再开一次。
                    fullShaOn = false
                    settingsRepo.fullFileSha256 = false
                    SnackbarController.show(AppStrings.get(R.string.snack_download_debug_off))
                }) { Text(stringResource(R.string.action_close_and_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDownloadDebugConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 线程数选择弹窗（按平台）
    if (showThreadsDialog) {
        AlertDialog(
            onDismissRequest = { showThreadsDialog = false },
            title = { Text(stringResource(R.string.dialog_threads_title)) },
            text = {
                // 横屏/小屏时内容超高可滚动，避免按钮被挤出屏幕
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.dialog_threads_hint),
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
                                text = stringResource(item.label),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (isXunlei) stringResource(R.string.dialog_threads_fixed)
                                else stringResource(R.string.dialog_threads_count, current),
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
                TextButton(onClick = { showThreadsDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 单个平台线程数选择（二级弹窗）
    if (showPlatformThreadDialog) {
        val current = settingsRepo.downloadThreadsFor(selectedThreadPlatform.platform)
        AlertDialog(
            onDismissRequest = { showPlatformThreadDialog = false },
            title = { Text(stringResource(R.string.dialog_platform_threads_title, stringResource(selectedThreadPlatform.label))) },
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
                TextButton(onClick = { showPlatformThreadDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 导出认证信息弹窗（AES 加密密码 + 导出范围）
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
                            SnackbarController.show(AppStrings.get(R.string.snack_export_failed))
                            return@launch
                        }
                        val encrypted = true
                        val saved = withContext(Dispatchers.IO) {
                            backupManager.saveToDownloads(context, content, encrypted)
                        }
                        SnackbarController.show(
                            if (saved) {
                                if (encrypted) AppStrings.get(R.string.snack_auth_exported_encrypted)
                                else AppStrings.get(R.string.snack_auth_exported)
                            } else {
                                AppStrings.get(R.string.snack_export_failed)
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
                                SnackbarController.show(AppStrings.get(R.string.snack_decrypt_failed))
                                return@launch
                            } catch (e: Exception) {
                                SnackbarController.show(AppStrings.get(R.string.snack_import_failed, e.message ?: ""))
                                return@launch
                            }
                            SnackbarController.show(AppStrings.get(R.string.snack_auth_imported, count))
                        } finally {
                            isImporting = false
                        }
                    }
                }
            }
        )
    }

    // 导出/导入处理中：转圈加载弹窗（PBKDF2 派生密钥耗时较长，避免用户以为界面卡死）
    if (isExporting) OperationLoadingDialog(stringResource(R.string.loading_exporting_auth))
    if (isImporting) OperationLoadingDialog(stringResource(R.string.loading_importing_auth))
}

/** 导出认证弹窗：AES 加密密码 + 导出范围（仅已登录 / 全部绑定） */
@Composable
private fun ExportAuthDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String, onlyLoggedIn: Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var onlyLoggedIn by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_export_auth_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.dialog_export_auth_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dialog_export_auth_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Text(
                    text = stringResource(R.string.dialog_export_scope),
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
                    Text(stringResource(R.string.dialog_export_scope_logged_in), style = MaterialTheme.typography.bodyMedium)
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
                    Text(stringResource(R.string.dialog_export_scope_all), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password, onlyLoggedIn) },
                enabled = password.length >= 8
            ) { Text(stringResource(R.string.action_export)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        title = { Text(stringResource(R.string.dialog_import_auth_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.dialog_import_auth_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dialog_import_auth_password)) },
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
            ) { Text(stringResource(R.string.action_decrypt_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    checked: Boolean? = null,
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
            .then(
                if (checked != null) Modifier.toggleable(
                    value = checked,
                    role = Role.Switch,
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    onValueChange = { onClick() }
                ) else Modifier.combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    onClick = onClick,
                    onLongClick = onLongClick
                )
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
            text = stringResource(R.string.dialog_threads_count, value),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
