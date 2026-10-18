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

package com.yunx.app.ui.login

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.YunXWavyLoading
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.ui.viewmodel.VideoAccountViewModel
import com.yunx.app.util.DiagnosticLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视频平台通用登录页：WebView 打开平台官网，登录完成后自动提取 Cookie 并保存。
 *
 * ★ 只写一个页面而不是「每个平台一个」：6 个平台的登录动作完全同构（打开官网 → 用户登录 →
 *   读整域 Cookie → 交给解析器校验），差异只有 [VideoPlatform] 里的地址与 Cookie 域。
 * ★ **没有网页登录页**的平台（[VideoPlatform.manualCookieOnly]）在这里被转交给
 *   [ManualCookieLoginScreen]：整页只做手动填 Cookie，一个 WebView 都不建。
 *
 * ★ 之所以把 cookieDomain 补成完整 URL 再读 Cookie：Android 的 `CookieManager.getCookie()`
 *   入参是 **URL**（需要 scheme），而 [VideoPlatform.cookieDomain] 存的是裸域（如 `bilibili.com`），
 *   直接传会读不到任何 Cookie，自动检测就永远不会触发。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoLoginScreen(
    platform: VideoPlatform,
    viewModel: VideoAccountViewModel,
    /** true = 「添加账号」：这次登录落成新的一行，不覆盖当前账号 */
    newAccount: Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    // ★ 没有网页登录页的平台（`loginUrl` 为空、只支持手动填 Cookie，见 VideoPlatform.manualCookieOnly）：
    //   立刻交给专用页面，**一个 WebView 都不创建** —— 不加载网页、不碰 WebView 的 Cookie 库。
    if (platform.manualCookieOnly) {
        ManualCookieLoginScreen(platform, viewModel, newAccount, onBack, onSaved)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    // 主文档加载失败的原因（非空时盖一张错误卡片）：网络不通/被墙在部分平台是常态，必须让用户看见
    var loadError by remember { mutableStateOf<String?>(null) }
    /**
     * 「游客基线」Cookie：页面刚打开时服务端会先种一批**游客**凭证
     * （小红书会自己种下游客态 `web_session`），它们只说明「有过会话」。
     *
     * ★ 自动保存必须等到 **Cookie 相比基线发生了变化**才去校验：否则登录页刚打开就被判成已登录、
     *   自动保存并关闭，用户根本没机会登录（现象就是「点了登录却没有登录页，账号页还显示已登录」）。
     * ★ 这只是**省掉一次无意义的网络校验**的廉价前置条件，真正的判定仍由各解析器的 `validateCookie`
     *   负责；基线没取到时不做限制（不阻塞登录）。
     */
    var guestCookie by remember(platform) { mutableStateOf<String?>(null) }
    var baselineCaptured by remember(platform) { mutableStateOf(false) }

    // 手动输入 Cookie 弹窗状态
    var showCookieDialog by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }
    var isSavingManual by remember { mutableStateOf(false) }

    // 登录教程弹窗：进入页面即展示一次
    var showTutorial by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showTutorial = true }

    // 读 WebView 登录态的地址（补 scheme，见类注释）
    val cookieUrl = remember(platform) {
        if (platform.cookieDomain.startsWith("http")) platform.cookieDomain
        else "https://${platform.cookieDomain}"
    }

    /**
     * 实际写进 WebView 的 UA = 平台模板 + **内核真实的 Chrome 大版本号**。
     *
     * ★ 各平台的 UA 模板里 Chrome 版本是写死的（131），而设备内核可能已经升到更高版本；不改的话，
     *   服务端一探测就能看出「UA 说 131、内核却是 155」的伪装指纹，登录页风控会因此降分。
     *   见 [engineChromeMajor] / [withEngineChromeVersion]。
     * ★ 用 remember 缓存：`WebSettings.getDefaultUserAgent` 首次调用会初始化 WebView 提供方，偏慢。
     */
    val loginUserAgent = remember(platform) {
        engineChromeMajor(context)
            ?.let { platform.loginUserAgent.withEngineChromeVersion(it) }
            ?: platform.loginUserAgent
    }

    // 以 platform 为 key：同一个组合位置换平台时不能沿用上一个平台的 WebView 与已加载页面
    val webView = remember(platform) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            settings.setSupportZoom(true)          // 支持缩放
            settings.builtInZoomControls = true    // 启用内置缩放机制（双指缩放）
            settings.displayZoomControls = false   // 隐藏屏幕上的缩放按钮（只保留手势）

            settings.useWideViewPort = true        // 支持 viewport 标签
            settings.loadWithOverviewMode = true

            // ★ 允许第三方 Cookie：登录页里嵌的 reCAPTCHA / SSO 都是第三方 iframe，WebView 默认拦掉它们的
            //   Cookie 会让验证码流程悄悄失败（拿不到有效 token，提交时只报一句通用的服务器错误）。
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            // ★ UA 由平台决定（`VideoPlatform.loginUserAgent`），并把 Chrome 大版本换成内核真实版本：
            //   默认桌面 Chrome —— 小红书/B站 的桌面登录页才是可用那一版（小红书移动端没有登录入口）。
            settings.userAgentString = loginUserAgent
            // ★ 客户端提示必须跟着这条 UA 一起改：只改 UA 不改 Sec-CH-UA，服务端拿到的就是
            //   「UA=Chrome、client hints=Android WebView」这种自相矛盾的指纹，登录页风控直接拒绝。
            settings.alignClientHintsWith(loginUserAgent)
            settings.suppressRequestedWithHeader()
            // 诊断模式下的网络嗅探（默认不注入，见 installLoginNetworkSniffer）
            installLoginNetworkSniffer()
            webViewClient = object : DiagnosticWebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    DiagnosticLog.webview("page_started", url)
                    isLoading = true
                    loadError = null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    DiagnosticLog.webview("page_finished", url)
                    isLoading = false
                    // 小红书这类「窄屏会把登录表单裁掉」的平台：把页面 viewport 覆盖成桌面宽度，
                    // 让整页（含确认按钮）都渲染出来，再交给用户双指缩放 / 左右拖动（见 loginViewportWidth）
                    platform.loginViewportWidth?.let { width ->
                        view?.evaluateJavascript(desktopViewportScript(width), null)
                    }
                    // 首次加载完成后再等一会儿取「游客基线」Cookie：游客凭证常由页面 JS 种下，
                    // 加载完成的瞬间可能还没写完（只取一次，见 guestCookie 的说明）
                    if (!baselineCaptured) {
                        baselineCaptured = true
                        scope.launch {
                            delay(GUEST_COOKIE_BASELINE_DELAY_MS)
                            guestCookie = CookieManager.getInstance().getCookie(cookieUrl).orEmpty()
                        }
                    }
                }

                /**
                 * 主文档加载失败要说出来。
                 *
                 * ★ 只认主文档（[WebResourceRequest.isForMainFrame]）：子资源（CDN 图、验证码 iframe）失败
                 *   不该弹错误卡片 —— 登录页普遍会加载第三方脚本，用子资源失败做判断会误报。
                 * ★ 需要代理才能访问的站点直连必然失败，以前只留一个转圈或白屏，用户根本不知道发生了什么；
                 *   这里给出「可能需要代理」并配一个重试按钮。
                 */
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame != true) return
                    DiagnosticLog.webview(
                        "page_error",
                        request.url?.toString(),
                        summary = "code=${error?.errorCode} desc=${error?.description}"
                    )
                    isLoading = false
                    loadError = when (error?.errorCode) {
                        WebViewClient.ERROR_HOST_LOOKUP -> "域名解析失败，通常是网络不通"
                        WebViewClient.ERROR_CONNECT,
                        WebViewClient.ERROR_TIMEOUT -> "连接超时，通常是网络不通"
                        else -> error?.description?.toString().orEmpty().ifBlank { "未知错误" }
                    }
                }
            }
            // 诊断版 WebChromeClient：把页面 JS 控制台里的警告/错误落进诊断日志 + logcat。
            // 登录失败的真因常只出现在控制台里（WebViewClient 的加载回调看不到）。
            webChromeClient = DiagnosticWebChromeClient()
            loadUrl(platform.loginUrl)
        }
    }

    // 自动登录检测：网页内登录完成（整域 Cookie 出现并通过解析器校验）即自动保存；右上角「保存」作手动兜底
    rememberWebLoginAutoDetect(
        sampleCredential = { CookieManager.getInstance().getCookie(cookieUrl).orEmpty() },
        // 凭证必须与「游客基线」不同：游客 Cookie 非空但不是登录态（见 guestCookie 的说明）。
        // 基线尚未取到时不做限制（否则会阻塞登录）。
        isPlausible = { it.isNotBlank() && it != guestCookie },
        validateAndSave = { viewModel.save(platform, it, newAccount) != null },
        isPaused = { isSaving || isSavingManual || showCookieDialog },
        onInFlightChange = { isSaving = it },
        onAutoSaved = onSaved
    )

    // 页面销毁时释放 WebView
    DisposableEffect(platform) {
        onDispose { webView.destroy() }
    }

    // 系统返回键 → 返回上一页（保存中禁用）
    BackHandler(enabled = !isSaving && !isSavingManual) { onBack() }

    // 全局 Snackbar 宿主
    val snackbarHostState = rememberGlobalSnackbarHostState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text("${platform.displayName} 登录", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = { if (!isSaving && !isSavingManual) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 刷新：登录流程里的失败（验证码 token 失效、页面状态过期）平台自己会提示「请刷新网页」，
                    // 得给用户一个能刷的入口 —— 以前只能返回再进来
                    IconButton(
                        onClick = {
                            if (!isSaving && !isSavingManual) {
                                loadError = null
                                isLoading = true
                                webView.reload()
                            }
                        },
                        enabled = !isSaving && !isSavingManual
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "刷新网页",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 手动输入 Cookie
                    IconButton(
                        onClick = { if (!isSaving && !isSavingManual) showCookieDialog = true },
                        enabled = !isSaving && !isSavingManual
                    ) {
                        Icon(
                            Icons.Outlined.ContentPaste,
                            contentDescription = "手动输入 Cookie",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                val cookie = CookieManager.getInstance().getCookie(cookieUrl).orEmpty()
                                val saved = viewModel.save(platform, cookie, newAccount) != null
                                isSaving = false
                                if (saved) {
                                    SnackbarController.show("登录成功")
                                    onSaved()
                                } else {
                                    SnackbarController.show("未检测到登录态，请先完成登录")
                                }
                            }
                        },
                        enabled = !isSaving && !isSavingManual
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("保存")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
            if (isLoading) {
                // 首次加载白屏时给个波浪进度提示（深色模式下同样可见）
                YunXWavyLoading(modifier = Modifier.fillMaxWidth())
            }
            // 主文档加载失败：给一张能看懂、能重试的卡片（网络不通 / 被墙是常态）
            loadError?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(28.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "网页加载失败",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "若这个平台在本地访问不了（例如被墙的境外站点），请先连上代理再重试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                loadError = null
                                isLoading = true
                                webView.loadUrl(platform.loginUrl)
                            }
                        ) { Text("重试") }
                    }
                }
            }
        }
    }

    // 登录教程弹窗
    if (showTutorial) {
        AlertDialog(
            onDismissRequest = { showTutorial = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text("登录教程") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "1. 在下方网页中登录${platform.displayName}账号",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. 登录成功后会自动检测并保存，无需手动操作",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. 若没有自动保存，点右上角「保存」；也可点「粘贴」图标手动输入 Cookie",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "4. Cookie 会加密保存在本机，失效后需重新登录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTutorial = false }) { Text("知道了") }
            }
        )
    }

    // 手动输入 Cookie 弹窗
    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSavingManual) showCookieDialog = false },
            title = { Text("手动输入 Cookie") },
            text = {
                Column {
                    Text(
                        text = "从浏览器或抓包工具复制完整的 Cookie 后粘贴到这里",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴 Cookie…") },
                        minLines = 4,
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isSavingManual = true
                            val saved = viewModel.save(platform, normalizePastedCookie(cookieInput), newAccount) != null
                            isSavingManual = false
                            if (saved) {
                                SnackbarController.show("登录成功")
                                showCookieDialog = false
                                onSaved()
                            } else {
                                SnackbarController.show("Cookie 无效或已过期，请重新复制")
                            }
                        }
                    },
                    enabled = cookieInput.isNotBlank() && !isSavingManual
                ) {
                    if (isSavingManual) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("保存")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!isSavingManual) showCookieDialog = false },
                    enabled = !isSavingManual
                ) { Text("取消") }
            }
        )
    }
}

/**
 * 取「游客基线」Cookie 前的等待时长。
 *
 * 页面 `onPageFinished` 只保证文档加载完，游客凭证常常是**页面 JS 起来之后**才种下的
 * （小红书就是这种），所以等一下再取，否则基线会是空的、白白失去这道闸门。
 */
private const val GUEST_COOKIE_BASELINE_DELAY_MS = 2_500L

/**
 * 把页面自带的 viewport 覆盖成**桌面宽度**，并放开缩放。
 *
 * ★ 抄的是百度网盘登录页那套（`BaiduLoginScreen` 的 `onPageFinished`）：有些登录页把自己的 viewport
 *   钉死成 `maximum-scale=1, user-scalable=no`，WebView 的 `setSupportZoom` 就完全失效；而它们的表单
 *   又是**固定宽度容器**，比手机屏幕宽的部分会被 `overflow-x: hidden` 直接裁掉 —— 既缩不了也滚不到。
 *   覆盖成桌面宽度后，页面按桌面布局渲染（小红书还会因此从「只有继续按钮」的窄屏变体切到桌面双栏
 *   登录页），WebView 因 `loadWithOverviewMode` 自动缩放成整页可见，用户可双指放大 + 左右拖动。
 *
 * 末尾手动派发一次 `resize`：这类页面（Vue SPA）是按**布局宽度**选变体的，改了 viewport 要让它
 * 的监听器重算一遍，否则会停在窄屏变体上。
 */
private fun desktopViewportScript(width: Int): String =
    "(function(){var c='width=$width,minimum-scale=0.2,maximum-scale=5.0,user-scalable=yes';" +
        "var m=document.querySelector('meta[name=\"viewport\"]');" +
        "if(m){m.setAttribute('content',c);}else{var n=document.createElement('meta');" +
        "n.name='viewport';n.content=c;document.head.appendChild(n);}" +
        "window.dispatchEvent(new Event('resize'));})()"

/**
 * 「手动填写 Cookie」登录页：给**没有网页登录页**的平台用（[VideoPlatform.manualCookieOnly]，目前是 Pixiv）。
 *
 * ★ 为什么单开一个页面、而不是给 WebView 版加个开关：这类平台的网页登录**必然失败**（Pixiv 的登录页
 *   带 reCAPTCHA 风控，二段验证提交会被服务端在验码之前就拒掉，页面显示「服务器错误。请刷新网页。」，
 *   详见 [VideoPlatform.manualCookieOnly] 的说明）。所以这里**一个 WebView 都不建**：不加载网页、
 *   不碰 WebView 的 Cookie 库，只让用户把浏览器里的 Cookie 粘进来。
 * ★ 校验与落库走的仍是**与网页登录完全相同**的那条路（`VideoAccountViewModel.save` 内部会调解析器的
 *   `validateCookie`），所以「粘进来的 Cookie 有没有效」由解析器说了算，这里不做第二套判断。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualCookieLoginScreen(
    platform: VideoPlatform,
    viewModel: VideoAccountViewModel,
    newAccount: Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    // 系统返回键 → 返回上一页（保存中禁用）
    BackHandler(enabled = !isSaving) { onBack() }

    val snackbarHostState = rememberGlobalSnackbarHostState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text("${platform.displayName} 登录", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = { if (!isSaving) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // 先把「为什么没有网页登录」讲清楚，否则用户会以为功能坏了
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "为什么没有网页登录",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${platform.displayName} 的登录页带有风控验证（reCAPTCHA），在 App 内置网页里" +
                            "无法完成登录，因此这里不再提供网页登录，改为直接粘贴 Cookie。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Cookie",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("粘贴 Cookie…") },
                minLines = 4,
                maxLines = 12
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "获取方法：在电脑浏览器登录 ${platform.displayName} 后打开开发者工具（F12）→ " +
                    "Application / 存储 → Cookies，复制该站点下的整条 Cookie。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        val saved = viewModel.save(platform, normalizePastedCookie(input), newAccount) != null
                        isSaving = false
                        if (saved) {
                            SnackbarController.show("登录成功")
                            onSaved()
                        } else {
                            SnackbarController.show("Cookie 无效或已过期，请重新复制")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = input.isNotBlank() && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("保存")
                }
            }
        }
    }
}

/**
 * 把用户粘贴的 Cookie 文本整理成请求头可用的 `k=v; k2=v2` 形式；整理不出来时原样返回。
 *
 * 兼容的输入：
 * 1. 标准请求头 `Cookie: a=b; c=d`（带 `Cookie:` 前缀，常见于从抓包工具复制整行 Headers）；
 * 2. 纯 `a=b; c=d`；
 * 3. JSON 对象 `{"a":"b","c":"d"}`（浏览器 Cookie 导出 / 剪贴板整理工具的常见格式）；
 * 4. JSON 数组 `[{"name":"a","value":"b",...},...]`（EditThisCookie 类插件的导出格式）。
 *
 * ★ JSON 格式必须先转成 `k=v` 再拼接：否则整段 JSON 会被原样塞进 Cookie 头，服务端不认，
 *   校验必然失败（表现就是「Cookie 无效或已过期」）。
 */
private fun normalizePastedCookie(raw: String): String {
    val withoutPrefix = raw.trim()
        .replace(Regex("^cookie\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        .trim()
    val pairs = cookiePairsFromJson(withoutPrefix)
    if (!pairs.isNullOrEmpty()) return pairs.joinToString("; ")
    return withoutPrefix
}

/** 尝试把 JSON 格式的 Cookie 解析成 `name=value` 列表；不是可解析的 JSON 时返回 null */
private fun cookiePairsFromJson(text: String): List<String>? {
    val trimmed = text.trim()
    // JSON 对象：{"name":"value",...}
    if (trimmed.startsWith("{")) {
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return buildList {
            obj.keys().forEach { key ->
                cookieScalarText(obj.opt(key))?.let { add("$key=$it") }
            }
        }
    }
    // JSON 数组：[{"name":"...","value":"...",...},...]
    if (trimmed.startsWith("[")) {
        val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isBlank()) continue
                cookieScalarText(item.opt("value"))?.let { add("$name=$it") }
            }
        }
    }
    return null
}

/** Cookie 值只接受标量（字符串/数字/布尔）：null、嵌套对象、数组都跳过 */
private fun cookieScalarText(value: Any?): String? = when (value) {
    is String -> value
    is Number -> value.toString()
    is Boolean -> if (value) "true" else "false"
    else -> null
}
