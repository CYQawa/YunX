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

package com.yunx.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.res.Configuration
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.data.repository.BaiduAccountRepository
import com.yunx.app.data.repository.BaiduResolveRepository
import com.yunx.app.data.repository.C139AccountRepository
import com.yunx.app.data.repository.C139ResolveRepository
import com.yunx.app.data.repository.Pan123AccountRepository
import com.yunx.app.data.repository.Pan123ResolveRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiAccountRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import com.yunx.app.ui.login.BaiduLoginScreen
import com.yunx.app.ui.login.C139LoginScreen
import com.yunx.app.ui.login.Pan123LoginScreen
import com.yunx.app.ui.login.QuarkLoginScreen
import com.yunx.app.ui.login.UCLoginScreen
import com.yunx.app.ui.login.XunleiLoginScreen
import com.yunx.app.ui.login.XunleiVerifyWebViewScreen
import com.yunx.app.ui.navigation.MainTab
import com.yunx.app.ui.screens.AboutScreen
import com.yunx.app.ui.screens.BookmarkScreen
import com.yunx.app.ui.screens.DownloadScreen
import com.yunx.app.ui.screens.DriveScreen
import com.yunx.app.ui.screens.OnboardingScreen
import com.yunx.app.ui.screens.ResolveScreen
import com.yunx.app.ui.screens.SettingsScreen
import com.yunx.app.ui.screens.SupportScreen
import com.yunx.app.ui.screens.ThemeScreen
import com.yunx.app.ui.screens.UpdateSheet
import com.yunx.app.ui.viewmodel.BaiduAccountViewModel
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.BookmarkViewModel
import com.yunx.app.ui.viewmodel.C139AccountViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.DownloadViewModel
import com.yunx.app.ui.viewmodel.DriveQuotaViewModel
import com.yunx.app.ui.viewmodel.Pan123AccountViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkAccountViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.UCAccountViewModel
import com.yunx.app.ui.viewmodel.XunleiAccountViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yunx.app.data.network.HttpClients
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.effectsFast

/**
 * 容器变换的共享 key：源（设置页那一行）与目标（叠加页）必须用同一个 key，形变才会发生。
 * 两处都在本文件里构造（源侧修饰符见 MainScreen 里 themeRowModifier 等，目标侧见 OverlayPage 的调用处）。
 */
internal const val OVERLAY_KEY_ABOUT = "overlay-about"
internal const val OVERLAY_KEY_SUPPORT = "overlay-support"
internal const val OVERLAY_KEY_THEME = "overlay-theme"

/** 收藏页从顶栏图标进入，没有"被点的那一项"，不做共享元素形变（普通淡入即可） */
internal const val OVERLAY_KEY_BOOKMARKS = "overlay-bookmarks"

/**
 * 主页框架：
 * - 顶部可折叠标题（MediumFlexibleTopAppBar，Expressive 柔性顶栏），切换 Tab 时标题文字随 Tab 变化，折叠状态不受影响；
 * - 导航 Tab（解析 / 网盘 / 下载 / 设置）：竖屏为底部导航条（ShortNavigationBar），横屏切换为侧边导航栏（NavigationRail）；
 * - 通过 SaveableStateHolder 保存各页面状态，切换 Tab 再切回来不会重置；
 * - 夸克登录页全屏覆盖展示。
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun MainScreen() {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Resolve) }
    var showQuarkLogin by rememberSaveable { mutableStateOf(false) }
    var showUCLogin by rememberSaveable { mutableStateOf(false) }
    var showXunleiLogin by rememberSaveable { mutableStateOf(false) }
    var showXunleiVerify by rememberSaveable { mutableStateOf(false) }
    var xunleiVerifyUrl by rememberSaveable { mutableStateOf("") }
    var xunleiVerifyDeviceId by rememberSaveable { mutableStateOf("") }
    var showBaiduLogin by rememberSaveable { mutableStateOf(false) }
    var showC139Login by rememberSaveable { mutableStateOf(false) }
    var showPan123Login by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showSupport by rememberSaveable { mutableStateOf(false) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    val saveableStateHolder = rememberSaveableStateHolder()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 横屏时使用侧边导航栏（NavigationRail），竖屏保持底部导航条（ShortNavigationBar）
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 首次启动引导页（context 声明后检测）
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("yunx_prefs", android.content.Context.MODE_PRIVATE)
        showOnboarding = !prefs.getBoolean("onboarding_shown", false)
    }

    // 更新检测：请求 GitHub 最新 Release（仓库无 Release / 网络失败则不提示，失败原因看 YunX-Update 日志）
    var showUpdateSheet by remember { mutableStateOf(false) }
    // 最近一次成功拿到的真实 Release：既用于「发现新版本」弹窗，也供设置页的开发调试入口直接预览
    var latestRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    LaunchedEffect(Unit) {
        when (val result = UpdateChecker.fetchLatestRelease()) {
            is UpdateChecker.CheckResult.Failure -> Unit // 启动检查不打扰用户，失败原因已由 UpdateChecker 打 E 级日志
            is UpdateChecker.CheckResult.Success -> {
                val release = result.release
                latestRelease = release
                val current = UpdateChecker.currentVersion(context)
                val prefs = context.getSharedPreferences("yunx_prefs", android.content.Context.MODE_PRIVATE)
                val ignored = prefs.getString("ignored_version", "")
                if (UpdateChecker.compareVersions(release.tagName, current) > 0 &&
                    release.tagName != ignored
                ) {
                    showUpdateSheet = true
                }
            }
        }
    }

    /**
     * 手动检查更新：与启动检查共用同一份状态和同一个 [UpdateSheet]（设置页不再自己实现一份弹窗）。
     * 失败时把 [UpdateChecker.CheckResult.Failure.reason] 直接显示出来，方便区分断网 / 限流 / 仓库无 Release。
     */
    val checkForUpdate: () -> Unit = {
        scope.launch {
            SnackbarController.show("正在检查更新…")
            when (val result = UpdateChecker.fetchLatestRelease()) {
                is UpdateChecker.CheckResult.Failure -> SnackbarController.show("检查更新失败：${result.reason}")
                is UpdateChecker.CheckResult.Success -> {
                    val release = result.release
                    latestRelease = release
                    if (UpdateChecker.compareVersions(release.tagName, UpdateChecker.currentVersion(context)) > 0) {
                        showUpdateSheet = true
                    } else {
                        SnackbarController.show("已是最新版本")
                    }
                }
            }
        }
    }

    /**
     * 开发调试入口「显示检查更新弹窗」：只用已经拿到的真实 Release 打开弹窗，
     * 不发网络请求、也不比较版本号（想预览就先在设置页联网检查一次更新）。
     */
    val previewUpdateSheet: () -> Unit = {
        if (latestRelease != null) {
            showUpdateSheet = true
        } else {
            SnackbarController.show("暂未获取到 Release 数据，请先联网检查一次更新")
        }
    }
    val api = remember { QuarkApi() }
    val ucApi = remember { UCApi() }
    val xunleiApi = remember { XunleiApi() }
    val baiduApi = remember { BaiduApi() }
    val c139Api = remember { C139Api() }
    val pan123Api = remember { Pan123Api() }
    val db = remember { AppDatabase.get(context) }
    val settings = remember { SettingsRepository(context) }
    val repository = remember {
        QuarkAccountRepository(db.quarkAccountDao(), api)
    }
    val ucRepository = remember {
        UCAccountRepository(db.ucAccountDao(), ucApi)
    }
    val xunleiRepository = remember {
        XunleiAccountRepository(db.xunleiAccountDao(), xunleiApi)
    }
    val baiduRepository = remember {
        BaiduAccountRepository(db.baiduAccountDao(), baiduApi)
    }
    val c139Repository = remember {
        C139AccountRepository(db.c139AccountDao())
    }
    val pan123Repository = remember {
        Pan123AccountRepository(db.pan123AccountDao(), pan123Api)
    }
    // 网盘认证备份：打包/恢复各平台凭证
    val backupManager = remember {
        AuthBackupManager(
            db.quarkAccountDao(),
            db.ucAccountDao(),
            db.xunleiAccountDao(),
            db.baiduAccountDao(),
            db.c139AccountDao(),
            db.pan123AccountDao()
        )
    }
    // 下载管理器：OkHttp 分片下载器 + Room 任务持久化 + 可配置线程数（设置页动态生效）
    // 下载客户端由全局 HttpClients 统一管理（大 Dispatcher 保障分片并发，不锁死 CDN host；
    // 并支持隐藏菜单「忽略 SSL 证书」开关，抓包调试时即时生效，无需重启）
    val downloadManager = remember {
        DownloadManager(
            context = context,
            dao = db.downloadTaskDao(),
            downloader = ChunkDownloader({ HttpClients.downloadClient() }),
            threadProvider = { platform -> settings.downloadThreadsFor(platform) },
            // 自定义下载保存目录（SAF tree Uri），设置页可选，动态生效
            saveDirProvider = { settings.downloadDirUri },
            // 网络与下载策略（设置页可调，动态生效）：并发任务数 / 全局限速 / 失败重试
            concurrencyProvider = { settings.maxConcurrentDownloads },
            speedLimitProvider = { settings.downloadSpeedLimit },
            retryCountProvider = { settings.downloadRetryCount },
            // 锁屏保持下载 / 通知栏速度开关
            keepWhenLockedProvider = { settings.keepDownloadWhenLocked },
            showSpeedProvider = { settings.notificationShowSpeed }
        )
    }
    // Android 9- 写公共 Download 需要 WRITE_EXTERNAL_STORAGE 运行时授权：
    // 下载完成保存前由 DownloadManager.storagePermissionProvider 触发动态申请，授权后自动继续保存
    var pendingStoragePermission by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingStoragePermission?.complete(granted)
        pendingStoragePermission = null
    }
    downloadManager.storagePermissionProvider = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true // Android 10+ MediaStore 无需存储权限
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            val deferred = CompletableDeferred<Boolean>()
            pendingStoragePermission = deferred
            withContext(Dispatchers.Main) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            deferred.await()
        }
    }
    val viewModel: QuarkAccountViewModel = viewModel(
        factory = QuarkAccountViewModel.Factory(repository)
    )
    val ucViewModel: UCAccountViewModel = viewModel(
        factory = UCAccountViewModel.Factory(ucRepository)
    )
    val xunleiViewModel: XunleiAccountViewModel = viewModel(
        factory = XunleiAccountViewModel.Factory(xunleiRepository)
    )
    val baiduViewModel: BaiduAccountViewModel = viewModel(
        factory = BaiduAccountViewModel.Factory(baiduRepository)
    )
    val c139ViewModel: C139AccountViewModel = viewModel(
        factory = C139AccountViewModel.Factory(c139Repository)
    )
    val pan123ViewModel: Pan123AccountViewModel = viewModel(
        factory = Pan123AccountViewModel.Factory(pan123Repository)
    )
    // 各平台「账号是否已登录」流：云盘浏览 VM 在启动期（未登录）init 加载会残留「请先登录…」错误态，
    // 首次登录成功后由 VM 监听该流自动重载根目录（见各 XxxCloudViewModel init）
    val quarkLoginState = remember { repository.observeAccount().map { it != null } }
    val ucLoginState = remember { ucRepository.observeAccount().map { it != null } }
    val xunleiLoginState = remember { xunleiRepository.observeAccount().map { it != null } }
    val baiduLoginState = remember { baiduRepository.observeAccount().map { it != null } }
    val c139LoginState = remember { c139Repository.observeAccount().map { it != null } }
    val pan123LoginState = remember { pan123Repository.observeAccount().map { it != null } }
    // 夸克云盘浏览：作为网盘 Tab 内容展示（非全屏），cookie 从数据库读取（避免 StateFlow 初始值为空的竞态）；
    // 下载前经 getFreshCookie 惰性刷新 __puus（修复 AlistGo/alist#830 下载 412）
    val quarkCloudViewModel: QuarkCloudViewModel = viewModel(
        factory = QuarkCloudViewModel.Factory(
            api,
            { repository.getFreshCookie() },
            downloadManager,
            loginState = quarkLoginState
        )
    )
    // UC 网盘云盘浏览：点击已登录的 UC 卡片打开（cookie 从数据库读取）；
    // 取链前经 getFreshCookie 惰性刷新 __puus（与夸克同源，修复取链/直链过期失败）
    val ucCloudViewModel: UCCoudViewModel = viewModel(
        factory = UCCoudViewModel.Factory(
            ucApi,
            { ucRepository.getFreshCookie() },
            downloadManager,
            loginState = ucLoginState
        )
    )
    // 迅雷 access_token 过期（401 unauthenticated）自动刷新：refresh_token 换新并持久化（对齐官方 /v1/auth/token 抓包）
    xunleiApi.refreshTokenProvider = { deviceId ->
        val acc = xunleiRepository.getAccount()
        if (acc == null || acc.refreshToken.isBlank()) null
        else xunleiApi.refreshToken(acc.refreshToken, deviceId)?.also { (at, nrt) ->
            xunleiRepository.updateTokens(at, nrt)
        }
    }
    // 迅雷云盘浏览：点击已登录的迅雷卡片打开（access_token/设备指纹/captcha 从数据库读取）
    val xunleiCloudViewModel: XunleiCloudViewModel = viewModel(
        factory = XunleiCloudViewModel.Factory(
            xunleiApi,
            { xunleiRepository.getAccount()?.accessToken },
            { xunleiRepository.getAccount()?.deviceId },
            { xunleiRepository.getAccount()?.captchaToken },
            downloadManager,
            loginState = xunleiLoginState
        )
    )
    // 百度网盘云盘浏览：点击已登录的百度卡片打开（cookie 从数据库读取）
    val baiduCloudViewModel: BaiduCloudViewModel = viewModel(
        factory = BaiduCloudViewModel.Factory(
            baiduApi,
            { baiduRepository.getAccount()?.cookie },
            downloadManager,
            loginState = baiduLoginState
        )
    )
    // 139 网盘云盘浏览：点击已登录的 139 卡片打开（cookie 从数据库读取）
    val c139CloudViewModel: C139CloudViewModel = viewModel(
        factory = C139CloudViewModel.Factory(
            c139Api,
            { c139Repository.getAccount()?.cookie },
            downloadManager,
            loginState = c139LoginState
        )
    )
    // 123 云盘浏览：点击已登录的 123 卡片打开（token 从数据库读取）
    val pan123CloudViewModel: Pan123CloudViewModel = viewModel(
        factory = Pan123CloudViewModel.Factory(
            pan123Api,
            { pan123Repository.getAccount()?.accessToken },
            downloadManager,
            loginState = pan123LoginState
        )
    )
    // 网盘空间详情：网盘页顶部「空间总览」展示 6 平台容量使用
    val driveQuotaViewModel: DriveQuotaViewModel = viewModel(
        factory = DriveQuotaViewModel.Factory(
            api, { repository.getAccount()?.cookie },
            ucApi, { ucRepository.getAccount()?.cookie },
            xunleiApi,
            { xunleiRepository.getAccount()?.accessToken },
            { xunleiRepository.getAccount()?.deviceId },
            { xunleiRepository.getAccount()?.captchaToken },
            baiduApi, { baiduRepository.getAccount()?.cookie },
            c139Api, { c139Repository.getAccount()?.cookie },
            pan123Api, { pan123Repository.getAccount()?.accessToken }
        )
    )
    val xunleiResolveRepository = remember {
        XunleiResolveRepository(
            api = xunleiApi,
            accountProvider = { xunleiRepository.getAccount()?.accessToken },
            deviceIdProvider = { xunleiRepository.getAccount()?.deviceId },
            captchaProvider = { xunleiRepository.getAccount()?.captchaToken },
            // token 过期（含导入恢复后旧 token 过期）自动用 refresh_token 刷新并持久化
            refreshProvider = {
                val acc = xunleiRepository.getAccount()
                if (acc == null || acc.refreshToken.isBlank()) null
                else xunleiApi.refreshToken(acc.refreshToken, acc.deviceId)?.also { (at, nrt) ->
                    xunleiRepository.updateTokens(at, nrt)
                }
            }
        )
    }
    val baiduResolveRepository = remember {
        BaiduResolveRepository(baiduApi)
    }
    val c139ResolveRepository = remember {
        C139ResolveRepository(c139Api)
    }
    val pan123ResolveRepository = remember {
        Pan123ResolveRepository(
            api = pan123Api,
            tokenProvider = { pan123Repository.getAccount()?.accessToken }
        )
    }
    val resolveViewModel: ResolveViewModel = viewModel(
        factory = ResolveViewModel.Factory(
            repository,
            QuarkResolveRepository(api),
            ucRepository,
            UCResolveRepository(ucApi),
            xunleiRepository,
            xunleiResolveRepository,
            baiduRepository,
            baiduResolveRepository,
            c139Repository,
            c139ResolveRepository,
            pan123Repository,
            pan123ResolveRepository,
            downloadManager,
            db.bookmarkDao()
        )
    )
    val downloadViewModel: DownloadViewModel = viewModel(
        factory = DownloadViewModel.Factory(downloadManager)
    )
    val bookmarkViewModel: BookmarkViewModel = viewModel(
        factory = BookmarkViewModel.Factory(db.bookmarkDao())
    )
    val quarkAccount by viewModel.quarkAccount.collectAsState()
    val ucAccount by ucViewModel.ucAccount.collectAsState()
    val xunleiAccount by xunleiViewModel.xunleiAccount.collectAsState()
    val baiduAccount by baiduViewModel.baiduAccount.collectAsState()
    val c139Account by c139ViewModel.c139Account.collectAsState()
    val pan123Account by pan123ViewModel.pan123Account.collectAsState()

    // 首次下载引导：锁屏保持下载默认开启，但新用户未加入「忽略电池优化」白名单 →引导一次
    var showBatteryGuide by remember { mutableStateOf(false) }
    var batteryGuideShown by remember { mutableStateOf(false) }

    // 解析页发起下载后，自动切换到「下载」Tab
    LaunchedEffect(resolveViewModel.downloadStarted) {
        if (resolveViewModel.downloadStarted) {
            currentTab = MainTab.Download
            resolveViewModel.consumeDownloadStarted()
        }
    }

    // 首次下载任务启动：锁屏保持下载默认开启但未豁免电池优化 →引导一次。
    // 监听任务状态而非 downloadStarted，覆盖解析页/网盘页/手动添加等所有下载入口。
    LaunchedEffect(Unit) {
        downloadViewModel.tasks.collect { tasks ->
            if (!batteryGuideShown && tasks.any {
                    it.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                        it.status == DownloadTaskEntity.STATUS_PENDING
                }
            ) {
                batteryGuideShown = true
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (settings.keepDownloadWhenLocked &&
                    pm?.isIgnoringBatteryOptimizations(context.packageName) != true
                ) {
                    showBatteryGuide = true
                }
            }
        }
    }

    // 首次启动引导页：全屏覆盖（优先级最高）
    if (showOnboarding) {
        OnboardingScreen(
            onFinish = {
                context.getSharedPreferences("yunx_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("onboarding_shown", true)
                    .apply()
                showOnboarding = false
            }
        )
        return
    }

    // 夸克登录页：全屏覆盖
    if (showQuarkLogin) {
        QuarkLoginScreen(
            viewModel = viewModel,
            onBack = { showQuarkLogin = false },
            onSaved = { showQuarkLogin = false }
        )
        return
    }

    // UC 登录页：全屏覆盖
    if (showUCLogin) {
        UCLoginScreen(
            viewModel = ucViewModel,
            onBack = { showUCLogin = false },
            onSaved = { showUCLogin = false }
        )
        return
    }

    // 迅雷登录页：全屏覆盖（账号+密码，可能触发短信验证）
    if (showXunleiLogin) {
        XunleiLoginScreen(
            viewModel = xunleiViewModel,
            onBack = { showXunleiLogin = false },
            onSaved = { showXunleiLogin = false },
            onVerify = { url, deviceId ->
                // 应用内验证：登录页让位，切到验证 WebView 全屏承载（不再跳外部浏览器）
                xunleiVerifyUrl = url
                xunleiVerifyDeviceId = deviceId
                showXunleiLogin = false
                showXunleiVerify = true
            }
        )
        return
    }

    // 迅雷验证页（应用内 WebView 承载验证面板）：全屏覆盖（兜底承载，核心验证仍走自有短信流）
    if (showXunleiVerify) {
        XunleiVerifyWebViewScreen(
            verifyUrl = xunleiVerifyUrl,
            deviceId = xunleiVerifyDeviceId,
            onResult = { success, _ ->
                showXunleiVerify = false
                showXunleiLogin = true // 回到登录页
                if (success) {
                    // 设备已验证受信任：自动重试密码登录（应直接成功并自动关闭登录页）
                    SnackbarController.show("验证完成，正在自动登录…")
                    xunleiViewModel.retryLoginAfterVerify()
                } else {
                    SnackbarController.show("验证未完成，请重试")
                }
            },
            onBack = {
                showXunleiVerify = false
                showXunleiLogin = true // 返回登录页短信步骤
            }
        )
        return
    }

    // 百度登录页：全屏覆盖（WebView 登录提取 Cookie）
    if (showBaiduLogin) {
        BaiduLoginScreen(
            viewModel = baiduViewModel,
            onBack = { showBaiduLogin = false },
            onSaved = { showBaiduLogin = false }
        )
        return
    }

    // 139 登录页：全屏覆盖（WebView 登录提取 Cookie）
    if (showC139Login) {
        C139LoginScreen(
            viewModel = c139ViewModel,
            onBack = { showC139Login = false },
            onSaved = { showC139Login = false }
        )
        return
    }

    // 123 登录页：全屏覆盖（WebView 打开官网登录，提取 localStorage 的 authorToken）
    if (showPan123Login) {
        Pan123LoginScreen(
            viewModel = pan123ViewModel,
            onBack = { showPan123Login = false },
            onSaved = { showPan123Login = false }
        )
        return
    }

    // 当前叠加页路由（null = 主界面）：容器变换用它当"源/目标"的共享 key
    val overlayRoute = when {
        showAbout -> OVERLAY_KEY_ABOUT
        showSupport -> OVERLAY_KEY_SUPPORT
        showTheme -> OVERLAY_KEY_THEME
        showBookmarks -> OVERLAY_KEY_BOOKMARKS
        else -> null
    }
    // 正在展示的叠加页路由：打开时更新，关闭时**保留**（退出动画要用它渲染那个页面）
    var shownRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(overlayRoute) {
        if (overlayRoute != null) shownRoute = overlayRoute
    }

    // 折叠标题状态提升到本层：跨页面共享，页面切换时折叠/展开状态保持不变
    // 用 exitUntilCollapsed（默认实现，含松手吸附）：滚动时标题先收起再滚内容；
    // 向上滚动回顶部过程中标题保持收起，只有列表到达最顶部后继续下拉（overscroll）才重新展开
    val topAppBarState = rememberTopAppBarState()
    // ★ flingAnimationSpec = null 是「切页后首次快速滑动，列表恰好卡在标题收起完毕处」的修复：
    //   material3 AppBar.kt 的 ExitUntilCollapsedScrollBehavior.onPostFling → settleAppBar 里，
    //   惯性开始时若顶栏尚未完全收起，顶栏会先用 flingAnimationSpec 做衰减动画、把惯性速度消耗在自己收起上，
    //   只把「剩余速度」还给列表；一次快速滑动的速度往往不够既收起标题又带动列表，于是列表停住不动。
    //   传 null 后：顶栏仍随滚动增量收起/展开、松手时吸附到位，但不再吞掉列表的惯性速度。
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        topAppBarState,
        flingAnimationSpec = null
    )

    // 全局 Snackbar 宿主（Material3，替换原 Toast 提示）
    val snackbarHostState = rememberGlobalSnackbarHostState()

    // ★ 容器变换（Container Transform）：源 = 主界面（设置页里被点的那一行），目标 = 叠加页。
    //   两者在同一个 SharedTransitionLayout 里、用同一个 key 的 sharedBounds 做形变：
    //   被点的卡片自己长成整页，行内内容淡出、页面内容在容器内淡入。
    //   ★ 主界面这个"源"用 AnimatedVisibility 承载：叠加页打开时它会被真正移出组合，
    //     而不是像以前那样常驻叠在下面 —— 那正是"卡片底色整片画不出来"的根因（见 OverlayPage 注释）。
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        // 底色放最外面：过渡期间主界面淡出、叠加页容器还在长大时，露出来的是页面底色而不是系统窗口的白色
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            AnimatedVisibility(
                visible = overlayRoute == null,
                enter = fadeIn(effectsDefault()),
                exit = fadeOut(effectsFast())
            ) {
                // 源侧共享元素修饰符：设置页那三行各自对应一个 key（见 SettingsScreen）。
                // ★ rememberSharedContentState 是 @Composable，必须在 composable 作用域里直接调用，
                //   不能包在普通 lambda 里延迟构造 —— 所以这里一次性建好三个传下去。
                val sourceScope = this
                val themeRowModifier = Modifier.sharedBounds(
                    rememberSharedContentState(OVERLAY_KEY_THEME),
                    animatedVisibilityScope = sourceScope
                )
                val aboutRowModifier = Modifier.sharedBounds(
                    rememberSharedContentState(OVERLAY_KEY_ABOUT),
                    animatedVisibilityScope = sourceScope
                )
                val supportRowModifier = Modifier.sharedBounds(
                    rememberSharedContentState(OVERLAY_KEY_SUPPORT),
                    animatedVisibilityScope = sourceScope
                )
                Box(modifier = Modifier.fillMaxSize()) {
                // 顶部可折叠标题（竖屏 / 横屏共用）：Expressive 的「中号柔性顶栏」
                val topBarContent: @Composable () -> Unit = {
                    // ★ 标题不要写死 style/fontWeight：柔性顶栏内部用 ProvideContentColorTextStyle 注入样式，
                    //   展开时取 headlineMedium、收起时取 titleLarge，并在这两档之间做字号形变；
                    //   这两个 token 都解析到 MaterialTheme.typography（即本项目 Type.kt 的 22sp / 18sp SemiBold），
                    //   自己再传 style 会覆盖注入值，柔性形变直接失效。
                    MediumFlexibleTopAppBar(
                        title = {
                            Text(text = currentTab.title)
                        },
                        actions = {
                            // 解析页标题右上角：收藏网盘链接入口
                            if (currentTab == MainTab.Resolve) {
                                IconButton(onClick = { showBookmarks = true }) {
                                    Icon(Icons.Outlined.Bookmarks, contentDescription = "收藏网盘链接")
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        // 柔性顶栏没有专用的 largeTopAppBarColors，用通用 topAppBarColors（同为 TopAppBarColors 类型）
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
                // ★ Material 3 Expressive 动效规格：MaterialTheme.motionScheme 是 @Composable 属性，只能在 composable 作用域读取；
                //   而 AnimatedContent 的 transitionSpec 是普通 lambda（非 @Composable），所以必须在这里先取好、再捕获进 lambda。
                //   （P4 全项目替换 tween 时遵循同一规则：规格取在 composable 里，transitionSpec / 回调里只用捕获值）
                val motionScheme = MaterialTheme.motionScheme
                val tabSlideSpec = motionScheme.defaultSpatialSpec<IntOffset>()
                val tabFadeSpec = motionScheme.defaultEffectsSpec<Float>()
                // Tab 内容区（竖屏 / 横屏共用）：每个页面独立保存状态，切换 Tab 再切回来不丢失；带 Material3 过渡动画（按 Tab 顺序决定方向）
                val tabContent: @Composable () -> Unit = {
                    AnimatedContent(
                        targetState = currentTab,
                        transitionSpec = {
                            // 根据 Tab 顺序决定滑动方向：向右切（新Tab在右边）→ 新页从右滑入；向左切反向
                            val forward = targetState.ordinal > initialState.ordinal
                            // 位移/尺寸走 spatial 弹簧，透明度/颜色走 effects 弹簧（替代原来的 tween(220)/tween(160)）
                            if (forward) {
                                (fadeIn(tabFadeSpec) + slideInHorizontally(tabSlideSpec) { it / 4 })
                                    .togetherWith(fadeOut(tabFadeSpec) + slideOutHorizontally(tabSlideSpec) { -it / 4 })
                            } else {
                                (fadeIn(tabFadeSpec) + slideInHorizontally(tabSlideSpec) { -it / 4 })
                                    .togetherWith(fadeOut(tabFadeSpec) + slideOutHorizontally(tabSlideSpec) { it / 4 })
                            }
                        },
                        label = "mainTab"
                    ) { tab ->
                        saveableStateHolder.SaveableStateProvider(tab) {
                            when (tab) {
                                MainTab.Resolve -> ResolveScreen(
                                    scrollBehavior,
                                    resolveViewModel,
                                    quarkCloudViewModel,
                                    xunleiCloudViewModel,
                                    baiduCloudViewModel,
                                    c139CloudViewModel,
                                    ucCloudViewModel,
                                    pan123CloudViewModel
                                )
                                MainTab.Drive -> DriveScreen(
                                    scrollBehavior = scrollBehavior,
                                    quarkAccount = quarkAccount,
                                    ucAccount = ucAccount,
                                    xunleiAccount = xunleiAccount,
                                    baiduAccount = baiduAccount,
                                    c139Account = c139Account,
                                    pan123Account = pan123Account,
                                    quarkCloudViewModel = quarkCloudViewModel,
                                    ucCloudViewModel = ucCloudViewModel,
                                    xunleiCloudViewModel = xunleiCloudViewModel,
                                    baiduCloudViewModel = baiduCloudViewModel,
                                    c139CloudViewModel = c139CloudViewModel,
                                    pan123CloudViewModel = pan123CloudViewModel,
                                    driveQuotaViewModel = driveQuotaViewModel,
                                    onQuarkLogin = { showQuarkLogin = true },
                                    onQuarkLogout = { viewModel.logout() },
                                    onDownloadStarted = { currentTab = MainTab.Download },
                                    onUCLogin = { showUCLogin = true },
                                    onUCLogout = { ucViewModel.logout() },
                                    onXunleiLogin = { showXunleiLogin = true },
                                    onXunleiLogout = { xunleiViewModel.logout() },
                                    onBaiduLogin = { showBaiduLogin = true },
                                    onBaiduLogout = { baiduViewModel.logout() },
                                    onC139Login = { showC139Login = true },
                                    onC139Logout = { c139ViewModel.logout() },
                                    onPan123Login = { showPan123Login = true },
                                    onPan123Logout = { pan123ViewModel.logout() }
                                )
                                MainTab.Download -> DownloadScreen(scrollBehavior, downloadViewModel)
                                MainTab.Settings -> SettingsScreen(
                                    scrollBehavior = scrollBehavior,
                                    themeRowModifier = themeRowModifier,
                                    aboutRowModifier = aboutRowModifier,
                                    supportRowModifier = supportRowModifier,
                                    onThemeClick = { showTheme = true },
                                    onAboutClick = { showAbout = true },
                                    onSupportClick = { showSupport = true },
                                    backupManager = backupManager,
                                    // 手动检查更新与开发调试预览都复用 MainScreen 的更新弹窗状态
                                    onCheckUpdate = checkForUpdate,
                                    onPreviewUpdateSheet = previewUpdateSheet
                                )
                            }
                        }
                    }
                }

                if (isLandscape) {
                    // 横屏：左侧侧边导航栏（NavigationRail）+ 右侧顶栏 & 内容
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // 竖屏由 Scaffold 提供主题背景；横屏手动布局需显式设置，否则露出窗口默认白色
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            MainNavigationRail(
                                currentTab = currentTab,
                                onTabSelected = { currentTab = it }
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                            ) {
                                topBarContent()
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    tabContent()
                                }
                            }
                        }
                        // 全局 Snackbar（横屏无底部栏，悬浮底部居中）
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                } else {
                    // 竖屏：Scaffold + 底部导航栏
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = { topBarContent() },
                        bottomBar = {
                            MainBottomBar(
                                currentTab = currentTab,
                                onTabSelected = { currentTab = it }
                            )
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            tabContent()
                        }
                    }
                }

                }
            }

            // 目标：叠加页（整屏 + 不透明底 + sharedBounds：从被点那一项长出来）
            AnimatedVisibility(
                visible = overlayRoute != null,
                enter = fadeIn(effectsDefault()),
                // ★ 退出时长必须≥ sharedBounds 形变的时长（默认 bounds 弹簧约 300ms）：
                //   AnimatedVisibility 的退出一结束就会把内容移出组合，页面提前消失 → 回收形变被截断，
                //   观感就是"退出一闪而过、像没做动画"。所以这里刻意用 300ms 的 tween 而不是 effectsFast(≈64ms)。
                exit = fadeOut(tween(durationMillis = 300))
            ) {
                val targetScope = this
                // ★ 读"正在展示的路由"而不是 overlayRoute：后者在点返回的瞬间就变 null 了，
                //   退出动画会因此没有内容可放（整段退出效果消失）。
                val route = shownRoute
                if (route != null) {
                    OverlayPage(
                        modifier = if (route == OVERLAY_KEY_BOOKMARKS) {
                            // 收藏页是从顶栏图标进来的，没有"被点的卡片"，不做形变
                            Modifier
                        } else {
                            Modifier.sharedBounds(
                                rememberSharedContentState(route),
                                animatedVisibilityScope = targetScope
                            )
                        }
                    ) {
                        when (route) {
                            OVERLAY_KEY_ABOUT -> AboutScreen(
                                onBack = { showAbout = false },
                                onPreviewOnboarding = {
                                    context.getSharedPreferences("yunx_prefs", android.content.Context.MODE_PRIVATE)
                                        .edit()
                                        .putBoolean("onboarding_shown", false)
                                        .apply()
                                    showAbout = false
                                    showOnboarding = true
                                }
                            )
                            OVERLAY_KEY_SUPPORT -> SupportScreen(onBack = { showSupport = false })
                            OVERLAY_KEY_THEME -> ThemeScreen(onBack = { showTheme = false })
                            else -> BookmarkScreen(
                                viewModel = bookmarkViewModel,
                                onBack = { showBookmarks = false },
                                onResolve = { link, pwd ->
                                    showBookmarks = false
                                    currentTab = MainTab.Resolve
                                    resolveViewModel.startResolve(link, pwd)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 首次下载引导：加入「忽略电池优化」白名单（锁屏保持下载生效的前提）
    if (showBatteryGuide) {
        AlertDialog(
            onDismissRequest = { showBatteryGuide = false },
            title = { Text("保持后台下载") },
            text = {
                Text(
                    text = "「锁屏后保持下载」已开启，但应用尚未加入「忽略电池优化」白名单，息屏后可能被系统中断下载。是否前往系统设置？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatteryGuide = false
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
                TextButton(onClick = { showBatteryGuide = false }) { Text("暂不") }
            }
        )
    }

    // 发现新版本（底部弹窗，覆盖在主页之上）：全应用唯一的更新弹窗实现，启动检查 / 手动检查 / 开发调试预览共用
    latestRelease?.let { release ->
        if (showUpdateSheet) {
            UpdateSheet(
                currentVersion = UpdateChecker.currentVersion(context),
                release = release,
                onDownload = {
                    showUpdateSheet = false
                    // 用内置下载功能下载更新 APK 到 Download 目录，并切到下载页
                    val apk = release.assets.firstOrNull { it.name.endsWith(".apk", true) }
                    if (apk != null) {
                        scope.launch {
                            downloadManager.enqueue(url = apk.downloadUrl, fileName = apk.name)
                            currentTab = MainTab.Download
                        }
                        SnackbarController.show("已加入下载，完成后点击「打开」即可安装")
                    } else {
                        SnackbarController.show("未找到 APK 下载链接")
                    }
                },
                onDownloadMirror = {
                    showUpdateSheet = false
                    // 镜像站下载：GitHub 直连慢/失败时走国内加速镜像
                    val apk = release.assets.firstOrNull { it.name.endsWith(".apk", true) }
                    if (apk != null) {
                        scope.launch {
                            downloadManager.enqueue(url = UpdateChecker.mirrorUrl(apk.downloadUrl), fileName = apk.name)
                            currentTab = MainTab.Download
                        }
                        SnackbarController.show("已通过镜像站加入下载，完成后点击「打开」即可安装")
                    } else {
                        SnackbarController.show("未找到 APK 下载链接")
                    }
                },
                // 网盘更新：Release 说明里的网盘链接直接丢给解析流程（与收藏页的「解析」同一路径）
                onNetdiskUpdate = { link ->
                    showUpdateSheet = false
                    currentTab = MainTab.Resolve
                    resolveViewModel.startResolve(link, null)
                },
                onLater = { showUpdateSheet = false },
                onIgnore = {
                    context.getSharedPreferences("yunx_prefs", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString("ignored_version", release.tagName)
                        .apply()
                    showUpdateSheet = false
                }
            )
        }
    }
}


/**
 * 叠加页容器（关于云析 / 支持开发 / 主题与外观 / 收藏）：整屏 + 不透明底色 + 可选的 sharedBounds 形变。
 *
 * ★ 它与主界面的关系是「互斥」而不是「叠加」：两者是同一个 SharedTransitionLayout 下两个
 *   `AnimatedVisibility`，叠加页打开时**主界面会被移出组合**。这一点是硬要求，不是洁癖——
 *   实测（多轮截图 + E 级日志）证明：只要主界面常驻叠在下面，这些页面里的 `Card` 底色就会整片
 *   画不出来（文字/图标/描边/分隔线都在，就是底色没了；把底色写死成亮绿也一样不画 ⇒ 与颜色无关），
 *   而且缺失区域的分界线会随滚动/折叠状态移动。改成互斥（源被移除）后完全正常。
 *   过渡期间两者会短暂共存（形变需要源的边界），这是可接受的：动画一结束源就被释放。
 *
 * ★ 底色仍要自己铺：M3E(material3 1.5.0-alpha18) 的 Scaffold 已不带底色
 *   （`ScaffoldDefaults` 只剩 `getContentWindowInsets`），页面不铺底就是透的。
 */
@Composable
private fun OverlayPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // sharedBounds 加在"底色之外"：形变中的容器自带不透明底色，过渡期间不会透出下层的窗口底色
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        content()
    }
}

/**
 * 底部导航条（竖屏）：4 个主 Tab（解析 / 网盘 / 下载 / 设置）。
 * 用 Expressive 的 ShortNavigationBar（选中项带形状指示器 + 弹簧动效，item 由组件内部按 EqualWeight 均分，
 * 不需要自己加 weight）。
 * ★ 高度：Expressive 规范高度是 64dp（NavigationBarTokens.ContainerHeight），比经典 NavigationBar 的
 *   TallContainerHeight（80dp）矮 16dp，产品上要求保持原高度。注意不能直接给 ShortNavigationBar 传
 *   Modifier.heightIn —— 它内部布局按 TopStart 对齐，撑高外层只会让 64dp 的内容贴顶。
 *   故外面套一层同色 Box 并居中：视觉上等价于原来的 80dp 导航栏，item 布局仍是 Expressive。
 *   三键导航机型上系统栏内边距会再叠加（经典版同样如此），因此会比 80dp 更高一点，属正常。
 */
@Composable
private fun MainBottomBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    val barColor = ShortNavigationBarDefaults.containerColor
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .background(barColor),
        contentAlignment = Alignment.Center
    ) {
        ShortNavigationBar(containerColor = barColor) {
            MainTab.values().forEach { tab ->
                ShortNavigationBarItem(
                    selected = currentTab == tab,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == tab) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.title
                        )
                    },
                    label = { Text(tab.title) }
                )
            }
        }
    }
}


/**
 * 侧边导航栏（横屏）：同 4 个主 Tab，未选中项只显示图标，节省横向空间。
 */
@Composable
private fun MainNavigationRail(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    NavigationRail {
        MainTab.values().forEach { tab ->
            NavigationRailItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = if (currentTab == tab) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.title
                    )
                },
                label = { Text(tab.title) },
                alwaysShowLabel = currentTab == tab
            )
        }
    }
}
