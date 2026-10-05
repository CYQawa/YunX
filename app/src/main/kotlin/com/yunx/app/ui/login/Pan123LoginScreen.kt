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

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yunx.app.data.network.Pan123Constants
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.YunXWavyLoading
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.ui.viewmodel.Pan123AccountViewModel
import kotlinx.coroutines.launch

/** 登录方式：0=网页登录（WebView 提取 authorToken）、1=账号密码（原生 sign_in 接口） */
private const val MODE_WEB = 0
private const val MODE_PASSWORD = 1

/**
 * 读取网页 localStorage 里 authorToken 的 JS（自动检测与「保存」按钮共用）。
 * 返回值用 encodeURIComponent 包一层，避免 JWT 里的字符破坏 evaluateJavascript 的 JSON 回参解析。
 */
private val READ_AUTHOR_TOKEN_JS: String =
    "(function(){try{var v=localStorage.getItem('" + Pan123Constants.LOCAL_STORAGE_TOKEN_KEY + "');" +
        "return v===null?'':encodeURIComponent(v)}catch(e){return ''}})()"

/**
 * 123 云盘登录页，两条路并存：
 *
 * - **网页登录**：WebView 打开官网个人盘主页 [Pan123Constants.WEB_LOGIN_URL]，由用户手动登录
 *   （验证码/扫码由官网处理）。登录成功后网页 SPA 把 Bearer JWT 写入当前域 localStorage
 *   （键名 authorToken），本页自动轮询提取并经 user/info 校验后落库。
 * - **账号密码**：直接打 123 的原生登录接口（`user.123pan.cn/api/user/sign_in`），不用开网页。
 *   触发风控（滑块/验证码）时会提示改走网页登录——那条路不受这套风控限制。
 *
 * 两条路拿到的都是同一种 authorToken，落库后行为完全一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pan123LoginScreen(
    viewModel: Pan123AccountViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    // 手动输入 Token 弹窗状态
    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    var isSavingManual by remember { mutableStateOf(false) }

    // 账号密码登录表单状态
    var mode by rememberSaveable { mutableIntStateOf(MODE_WEB) }
    var account by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }

    // 登录教程弹窗：进入页面即展示一次
    var showTutorial by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showTutorial = true }

    // WebView 按需创建：只在「网页登录」这一档里才建，账号密码模式不必白占一个 WebView
    var webView by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(mode) {
        if (mode == MODE_WEB && webView == null) {
            webView = buildWebView(context) { loading -> isLoading = loading }
        }
    }

    // 页面销毁时释放 WebView
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    // 系统返回键 → 返回主页（保存/登录中禁用）
    BackHandler(enabled = !isSaving && !isSavingManual && !isLoggingIn) { onBack() }

    // 自动登录检测：网页登录完成（authorToken 写入 localStorage）即自动提取并校验登录，无需手动点「保存」
    rememberWebLoginAutoDetect(
        sampleCredential = { webView?.evaluateJsEncoded(READ_AUTHOR_TOKEN_JS) ?: "" },
        isPlausible = { it.isNotBlank() },
        validateAndSave = { viewModel.saveToken(it) },
        // 账号密码模式下暂停检测：用户正在用另一条路登录，别被网页里残留的旧登录态抢先写库
        isPaused = { mode != MODE_WEB || isSaving || isSavingManual || showTokenDialog },
        onInFlightChange = { isSaving = it },
        onAutoSaved = onSaved
    )

    // 全局 Snackbar 宿主
    val snackbarHostState = rememberGlobalSnackbarHostState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("123云盘登录", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { if (!isSaving && !isSavingManual && !isLoggingIn) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 「保存」与「粘贴」只对网页登录有意义，账号密码模式下不显示，避免误点
                    if (mode == MODE_WEB) {
                        IconButton(
                            onClick = { if (!isSaving && !isSavingManual) showTokenDialog = true },
                            enabled = !isSaving && !isSavingManual
                        ) {
                            Icon(
                                Icons.Outlined.ContentPaste,
                                contentDescription = "手动输入 Token",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isSaving = true
                                    try {
                                        // 提取网页 localStorage 的 authorToken 作为登录凭证
                                        val token = webView?.evaluateJsEncoded(READ_AUTHOR_TOKEN_JS).orEmpty()
                                        val saved = if (token.isBlank()) false else viewModel.saveToken(token)
                                        if (saved) {
                                            SnackbarController.show("登录成功")
                                            onSaved()
                                        } else {
                                            SnackbarController.show("未检测到登录态，请先完成登录")
                                        }
                                    } finally {
                                        // 必须 finally：抛异常时按钮要能恢复可点
                                        isSaving = false
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
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    onClick = { mode = MODE_WEB },
                    selected = mode == MODE_WEB
                ) { Text("网页登录") }
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    onClick = { mode = MODE_PASSWORD },
                    selected = mode == MODE_PASSWORD
                ) { Text("账号密码") }
            }

            if (mode == MODE_WEB) {
                // weight(1f)：让网页区域吃掉「除去上方分段控件」剩下的高度。
                // 用 fillMaxSize 会把分段控件的高度也算进来，Column 不滚动 ⇒ 网页底部被裁掉一条
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    webView?.let { wv ->
                        AndroidView(factory = { wv }, modifier = Modifier.fillMaxSize())
                    }
                    if (isLoading || webView == null) {
                        YunXWavyLoading(modifier = Modifier.fillMaxWidth())
                    }
                }
            } else {
                AccountPasswordForm(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    account = account,
                    onAccountChange = { account = it },
                    password = password,
                    onPasswordChange = { password = it },
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                    error = loginError,
                    busy = isLoggingIn,
                    onSubmit = {
                        scope.launch {
                            loginError = null
                            isLoggingIn = true
                            try {
                                val error = viewModel.login(account, password)
                                if (error == null) {
                                    SnackbarController.show("登录成功")
                                    onSaved()
                                } else {
                                    loginError = error
                                }
                            } finally {
                                isLoggingIn = false
                            }
                        }
                    }
                )
            }
        }
    }

    // 登录教程弹窗（网页登录的说明；账号密码模式用不到）
    if (showTutorial && mode == MODE_WEB) {
        AlertDialog(
            onDismissRequest = { showTutorial = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text("登录教程") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "1. 在下方网页中登录 123 云盘账号（支持账号密码 / 短信验证码）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. 登录完成后将自动检测并登录，无需手动操作",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. 若自动登录未触发，可点右上角「保存」手动提取（读取网页 localStorage 的 authorToken）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "4. 或点击「粘贴」图标，手动粘贴 Token",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "5. Token 长期有效，失效后需重新登录",
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

    // 手动输入 Token 弹窗
    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSavingManual) showTokenDialog = false },
            title = { Text("手动输入 Token") },
            text = {
                Column {
                    Text(
                        text = "登录 123 云盘网页后，复制其 localStorage 中的 authorToken 值粘贴到这里（可用浏览器开发者工具查看）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴 authorToken…") },
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
                            try {
                                val saved = viewModel.saveToken(tokenInput.trim())
                                if (saved) {
                                    SnackbarController.show("登录成功")
                                    showTokenDialog = false
                                    onSaved()
                                } else {
                                    SnackbarController.show("Token 无效，请检查是否为完整的 authorToken")
                                }
                            } finally {
                                isSavingManual = false
                            }
                        }
                    },
                    enabled = tokenInput.isNotBlank() && !isSavingManual
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
                    onClick = { if (!isSavingManual) showTokenDialog = false },
                    enabled = !isSavingManual
                ) { Text("取消") }
            }
        )
    }
}

/**
 * 账号密码登录表单（123 原生接口）。
 * 错误就地展示不弹 Snackbar：登录失败的原因（密码错 / 太频繁 / 要过验证）需要一直看得见，
 * 用户照着改才有意义，一闪而过的提示反而让人不知道该干什么。
 */
@Composable
private fun AccountPasswordForm(
    modifier: Modifier = Modifier,
    account: String,
    onAccountChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    error: String?,
    busy: Boolean,
    onSubmit: () -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "账号密码登录",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
        Text(
            text = "直接使用 123 云盘账号登录，不需要打开网页；若提示需要安全验证，请改用「网页登录」",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = account,
            onValueChange = onAccountChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("手机号 / 邮箱") },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVisible) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (passwordVisible) KeyboardType.Text else KeyboardType.Password
            ),
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = account.isNotBlank() && password.isNotEmpty() && !busy
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("登录")
            }
        }
    }
}

/**
 * 构造 123 网页登录用的 WebView。
 * 桌面 UA：yun.123pan.cn 个人盘是桌面 SPA，移动 UA 会跳到不完整的移动版页面。
 */
private fun buildWebView(context: Context, onLoadingChange: (Boolean) -> Unit): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true   // 123 云盘把登录态（authorToken）存在 localStorage，必须开启
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NARROW_COLUMNS
        setInitialScale(0)
        settings.userAgentString = Pan123Constants.WEB_UA
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onLoadingChange(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onLoadingChange(false)
                // 强制覆盖页面 viewport：适配屏幕宽度 + 允许双指缩放（桌面版页面无 viewport 或限制缩放时生效）
                view?.evaluateJavascript(
                    "(function(){var m=document.querySelector('meta[name=\"viewport\"]');" +
                        "var c='width=device-width,initial-scale=1.0,maximum-scale=5.0,user-scalable=yes';" +
                        "if(m){m.setAttribute('content',c);}else{var n=document.createElement('meta');n.name='viewport';n.content=c;document.head.appendChild(n);}" +
                        "window.dispatchEvent(new Event('resize'));})()",
                    null
                )
            }
        }
        webChromeClient = WebChromeClient()
        loadUrl(Pan123Constants.WEB_LOGIN_URL)
    }
