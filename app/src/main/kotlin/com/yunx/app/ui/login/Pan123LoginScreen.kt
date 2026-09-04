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
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHost
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yunx.app.data.network.Pan123Constants
import com.yunx.app.ui.viewmodel.Pan123AccountViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 123 云盘登录页（网页登录方案，与夸克/百度等一致）：
 * - WebView 打开官网个人盘主页 [Pan123Constants.WEB_LOGIN_URL]，由用户手动登录（验证码/扫码由官网处理）；
 * - 登录成功后网页 SPA 会把 Bearer JWT 写入当前域 localStorage，键名 authorToken（旧账号密码登录的 data.token 同源同形）；
 * - 右上角「保存」/自动登录检测均从 localStorage 提取该值，经 user/info 接口校验后落库。
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

    // 登录教程弹窗：进入页面即展示一次
    var showTutorial by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showTutorial = true }

    val webView = remember {
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
            // 桌面 UA：yun.123pan.cn 个人盘是桌面 SPA；移动 UA 会跳到不完整的移动版页面
            settings.userAgentString = Pan123Constants.WEB_UA
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    isLoading = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    isLoading = false
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
    }

    // 页面销毁时释放 WebView
    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    // 系统返回键 → 返回主页（保存中禁用）
    BackHandler(enabled = !isSaving && !isSavingManual) { onBack() }

    // 自动登录检测：网页登录完成（authorToken 写入 localStorage）即自动提取并校验登录，无需手动点「保存」
    rememberWebLoginAutoDetect(
        sampleCredential = { webView.readLocalStorageValue(Pan123Constants.LOCAL_STORAGE_TOKEN_KEY) },
        isPlausible = { it.isNotBlank() },
        validateAndSave = { viewModel.saveToken(it) },
        isPaused = { isSaving || isSavingManual || showTokenDialog },
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
                    IconButton(onClick = { if (!isSaving && !isSavingManual) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
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
                                // 提取网页 localStorage 的 authorToken 作为登录凭证
                                val token = webView.readLocalStorageValue(Pan123Constants.LOCAL_STORAGE_TOKEN_KEY)
                                val saved = if (token.isBlank()) false else viewModel.saveToken(token)
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
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                            val saved = viewModel.saveToken(tokenInput.trim())
                            isSavingManual = false
                            if (saved) {
                                SnackbarController.show("登录成功")
                                showTokenDialog = false
                                onSaved()
                            } else {
                                SnackbarController.show("Token 无效，请检查是否为完整的 authorToken")
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
 * 读取当前 WebView 页面 localStorage 中 [key] 的值（123 云盘登录态存于 authorToken）。
 * ⚠️ 依赖 123 站点私有实现（键名 authorToken / 值为裸 JWT），官网改版可能失效——失效时用户可走「粘贴 Token」兜底。
 * JS 侧用 encodeURIComponent 包一层返回，避免 JWT 特殊字符干扰 evaluateJavascript 的 JSON 返回值解析；
 * 页面未就绪 / 跨域（如还停留在登录跳转中间页）时返回空串，由调用方继续轮询。
 */
private suspend fun WebView.readLocalStorageValue(key: String): String =
    withTimeoutOrNull(2_000) {
        suspendCancellableCoroutine { cont ->
            try {
                evaluateJavascript(
                    "(function(){try{var v=localStorage.getItem('" + key + "');" +
                        "return v===null?'':encodeURIComponent(v)}catch(e){return ''}})()"
                ) { result ->
                    val raw = result?.trim() ?: ""
                    val value = when {
                        raw.isEmpty() || raw == "\"\"" || raw == "null" -> ""
                        raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"") ->
                            raw.substring(1, raw.length - 1)
                        else -> raw
                    }
                    val decoded = if (value.isBlank()) "" else
                        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault("")
                    if (cont.isActive) cont.resume(decoded)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume("")
            }
        }
    } ?: ""
