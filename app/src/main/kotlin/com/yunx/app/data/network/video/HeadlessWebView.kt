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

package com.yunx.app.data.network.video

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yunx.app.ui.login.alignClientHintsWith
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.LogRedactor
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 「隐藏 WebView」解析通道：**让 JS 跑起来**，再把结果取回来。
 *
 * ★ 为什么需要它：有些站点（目前是抖音）把数据放在了纯 HTTP 拿不到的地方 ——
 *   1) 页面先给一张 `byted_acrawler` 的 JS 挑战页，要用 `__ac_nonce` 算出 `__ac_signature` 写回 Cookie
 *      再 `location.reload()`，之后才是真正的页面；
 *   2) 真正的页面里数据也**不在任何 SSR 全局变量里**（`RENDER_DATA` / `SSR_RENDER_DATA` 只有应用配置），
 *      而是**渲染进 DOM** 的。
 *   这两步都只有「真的把页面跑一遍」才能过，所以这里用 WebView 当执行环境。
 *
 * ★ 工作方式：把 WebView **挂到当前 Activity 的窗口上**（1×1、全透明，肉眼不可见），
 *   然后轮询 [readyCheckJs]，直到它返回 `true` 或超时，再执行 [extractJs] 把结果取回来，最后销毁。
 *   **必须挂上窗口**：不挂的话 `requestAnimationFrame` 之类不会触发、`document.visibilityState`
 *   也不是 visible，页面的初始化脚本可能就此卡住（挑战脚本尤其容易踩这个）。
 *
 * ★ 取回值的约定（与 `WebView.evaluateJavascript` 一致）：[extractJs] 应当**直接返回对象**
 *   （不要 `JSON.stringify`）—— 回调拿到的是它的 JSON 文本；返回 `null` / `undefined` 时得到 "null"。
 *   [readyCheckJs] 应当返回布尔值（回调拿到 "true" / "false"）。
 *
 * ★ 线程：`WebView` 只能在创建它的线程上用，所以整段都跑在主线程；轮询间隔用 `delay` 让出，
 *   不会卡 UI。**不要在 IO 线程调用**（内部会自己切到主线程）。
 */
object HeadlessWebView {

    /** 当前处于前台的 Activity（挂 WebView 用）；只在进程内保存弱引用，不泄漏 */
    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null

    /** 在 `YunXApp.onCreate` 里调一次，之后 [loadAndExtract] 才能拿到 Activity */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }
        })
    }

    /**
     * 打开 [url] 并按 [readyCheckJs] 轮询，就绪后执行 [extractJs]。
     *
     * @return 就绪并取到的 JSON 文本；超时、页面报错或拿不到 Activity 时返回 null。
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun loadAndExtract(
        url: String,
        userAgent: String,
        readyCheckJs: String,
        extractJs: String,
        timeoutMs: Long = 25_000L,
        pollIntervalMs: Long = 350L,
        /** 调用方提供的「文档开始前」脚本，按顺序注入（如把页面自己的取数响应存下来的钩子） */
        extraDocumentStartJs: List<String> = emptyList()
    ): String? = withContext(Dispatchers.Main.immediate) {
        val activity = resumedActivity?.get()
        if (activity == null) {
            DiagnosticLog.warn(DiagnosticLog.NETWORK, "headless_no_activity", "url=${com.yunx.app.util.LogRedactor.url(url)}")
            return@withContext null
        }
        val started = System.currentTimeMillis()
        val webView = WebView(activity)
        activity.addContentView(webView, ViewGroup.LayoutParams(1, 1))
        // 1×1 + 全透明：既满足「必须挂上窗口」，又完全不占视觉（alpha 不影响 WebView 对可见性的判定）
        webView.alpha = 0f
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.userAgentString = userAgent
        // ★ 客户端提示必须跟着 UA 一起改：只改 UA 不改 `Sec-CH-UA`，服务端看到的是
        //   「UA 说 Windows Chrome、client hints 却说 Android WebView」这种自相矛盾的指纹。
        //   实测（2026-10）不这么做时，抖音虽然在 WebView 里过了 acrawler 挑战、页面也挂起来了，
        //   但服务端**不给直出作品详情**（内容区一直停在「视频数据加载中」），只能退回客户端 XHR，
        //   而那些 XHR 要签名、拿回来是空响应。对齐后 UA / 平台 / 机型三处自洽。
        //   （helper 与登录页共用，见 VideoLoginScreen；数据层引用它只是为了复用同一套口径。）
        webView.settings.alignClientHintsWith(userAgent)
        // ★ `navigator.platform` 也必须与 UA 一致。Android WebView 的 `navigator.platform` 恒为
        //   `"Linux armv8l"`，而我们对外声称是 Windows Chrome —— 页面自己会把这个不一致序列化进请求参数
        //   （实测抖音的 `pc_libra_divert=Linux`，而真实浏览器里是 `Windows`），风控据此判定「环境被伪装」：
        //   服务端**不给直出作品详情**，页面退回客户端 XHR，而那些 XHR 的响应体是空的
        //   （`aweme/v1/web/aweme/detail/` → HTTP 200 + len=0）。
        //   `WebSettings` 没有改它的 API，只能在文档开始前注入脚本覆盖 getter。
        val platformName = when {
            userAgent.contains("Windows") -> "Win32"
            userAgent.contains("Mac OS X") -> "MacIntel"
            else -> null
        }
        if (platformName != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val platformJs = "(function(){try{Object.defineProperty(navigator,'platform'," +
                "{get:function(){return '$platformName';},configurable:true});}catch(e){}})()"
            WebViewCompat.addDocumentStartJavaScript(webView, platformJs, setOf("*"))
        }
        // 调用方脚本同样必须在页面自身脚本之前跑（否则钩子挂不上已经发生过的请求）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            extraDocumentStartJs.forEach { script ->
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
                DiagnosticLog.webview("headless_error", request.url?.toString(), code = error?.errorCode)
            }
        }
        try {
            webView.loadUrl(url)
            val deadline = started + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                delay(pollIntervalMs)
                if (webView.evaluate(readyCheckJs) == "true") {
                    val extracted = webView.evaluate(extractJs)
                    DiagnosticLog.event(
                        DiagnosticLog.NETWORK,
                        "headless_done",
                        "costMs=${System.currentTimeMillis() - started} len=${extracted?.length ?: 0}"
                    )
                    return@withContext extracted
                }
            }
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK,
                "headless_timeout",
                "costMs=${System.currentTimeMillis() - started} url=${LogRedactor.url(url)}"
            )
            null
        } finally {
            runCatching { webView.stopLoading() }
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
            runCatching { webView.destroy() }
        }
    }

    /**
     * 执行一段 JS 并等它回调，返回**解码后的**值。
     *
     * ★ 必须带超时：页面若正好在导航，`evaluateJavascript` 的回调可能**永远不来**，
     *   轮询循环会卡死在这里（外面的大循环就形同虚设）。
     * ★ 回调里先判 `isActive`：超时那一轮之后回调才到的话，直接 resume 已取消的续体会抛异常。
     */
    private suspend fun WebView.evaluate(script: String): String? =
        withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { cont ->
                evaluateJavascript(script, ValueCallback { value ->
                    if (cont.isActive) cont.resume(value)
                })
            }
        }?.let(::decodeJsonValue)

    /**
     * `evaluateJavascript` 回传的是 **JSON 文本**，不是原始值：JS 返回字符串 `'true'` 时，
     * 回调拿到的是**带引号的 `"true"`**。
     *
     * ★ 踩过的坑：就绪判据一直拿 `"true"` 去跟 `true` 比，于是**永远不就绪** —— 页面明明已经把
     *   数据拿到了（诊断快照里能看见），却每次都走到超时。这里统一解码：字符串解回原值，
     *   对象/数组原样返回 JSON 文本（取数脚本返回的就是对象，调用方自己 `JSONObject` 解析）。
     */
    private fun decodeJsonValue(raw: String): String? {
        if (raw == "null") return null
        return runCatching {
            when (val value = org.json.JSONTokener(raw).nextValue()) {
                is String -> value
                JSONObject.NULL -> null
                else -> raw
            }
        }.getOrDefault(raw)
    }
}
