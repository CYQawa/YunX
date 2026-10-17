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
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yunx.app.util.DiagnosticLog

/**
 * 登录页 WebView 的诊断版客户端：**只记日志、不改变任何行为**（每个回调都先/后调 `super`）。
 *
 * 为什么做成基类而不是在每个页面里各插一行：8 个登录页（夸克/UC/百度/139/123/115/迅雷×2）各自
 * 匿名继承了 `WebViewClient`，想统一记「加载 URL、页面状态、错误」就得改 8 处 —— 那样下次再加网盘
 * 必然漏。改成 `object : DiagnosticWebViewClient()` 后，只要新页面也用它，日志自动就有了。
 *
 * 记的内容：`page_started` / `page_finished`（页面状态）、`url_override`（跳转目标）、
 * `page_error`（主框架加载失败，带 errorCode 与描述）、`http_error`（HTTP 非 2xx）。
 * URL 由 `DiagnosticLog.webview` 统一走 `LogRedactor.url` 脱敏，授权码/回调参数不会明文落盘。
 */
internal open class DiagnosticWebViewClient : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        DiagnosticLog.webview("page_started", url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        DiagnosticLog.webview("page_finished", url)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        DiagnosticLog.webview("url_override", request?.url?.toString())
        return super.shouldOverrideUrlLoading(view, request)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        DiagnosticLog.webview(
            "page_error",
            request?.url?.toString(),
            code = error?.errorCode ?: -1,
            summary = "desc=${error?.description} mainFrame=${request?.isForMainFrame}"
        )
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        DiagnosticLog.webview(
            "http_error",
            request?.url?.toString(),
            code = errorResponse?.statusCode ?: -1,
            summary = "mainFrame=${request?.isForMainFrame}"
        )
        // 主文档的 HTTP 错误同时写一份 logcat：诊断模式是用户的开关，排查问题时经常是关着的，
        // 而 logcat 用 adb 就能取。子资源（图片/脚本的 404 等）不写，否则刷屏。
        if (request?.isForMainFrame == true) {
            Log.w(TAG, "http_error ${errorResponse?.statusCode} ${request.url}")
        }
    }

    private companion object {
        const val TAG = "YunX-WebView"
    }
}

/**
 * 登录页 WebView 的**诊断版 WebChromeClient**：把页面的 JS 控制台消息（**只记 WARN / ERROR**，
 * 单条截断 200 字）落进诊断日志**并同时写 logcat**。
 *
 * ★ 为什么需要：登录页失败的真因往往只出现在页面内部的 JS/XHR 上（「验证码校验失败」「token 过期」
 *   「请求被风控拒绝」），WebViewClient 的加载回调**完全看不到**。注意 `onConsoleMessage` 属于
 *   **WebChromeClient**（不是 WebViewClient），所以单独立一个类，而不是塞进 [DiagnosticWebViewClient]。
 * ★ 只记警告与错误：info 级会把站点的常规输出也灌进日志；内容可能带 token，统一脱敏/截断。
 * ★ 除落盘外同时写 logcat：诊断模式是用户开关（排查时常常关着），logcat 用 adb 就能取。
 */
internal open class DiagnosticWebChromeClient : WebChromeClient() {

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        val level = message.messageLevel()
        if (level == ConsoleMessage.MessageLevel.ERROR || level == ConsoleMessage.MessageLevel.WARNING) {
            val text = message.message().take(200)
            DiagnosticLog.webview(
                "console",
                message.sourceId(),
                code = message.lineNumber(),
                summary = "level=$level msg=$text"
            )
            Log.w(TAG, "console[$level] ${message.sourceId()}:${message.lineNumber()} $text")
        }
        return super.onConsoleMessage(message)
    }

    private companion object {
        const val TAG = "YunX-WebView"
    }
}

/**
 * 关闭 WebView 自带的 `X-Requested-With: <应用包名>` 请求头。
 *
 * ⚠️ **在当前的 WebView 上它已经不起作用了**：Google 原计划从 WebView 里移除该头、改成按 allow list
 * 发送，但该计划**已被取消**（androidx.webkit 1.14.0 起 `REQUESTED_WITH_HEADER_ALLOW_LIST` 的
 * allowlist API 已被废弃），该头重新**默认发给所有请求**且无法通过公开 API 关闭。
 *
 * 实测确认：设成空 allow list 后，Pixiv 登录页的 XHR 仍然带着 `x-requested-with: com.yunx.app`。
 * 保留调用只是「不支持的版本上静默跳过、支持的版本上尽力而为」，不要再指望它能摘掉这个头。
 */
internal fun WebSettings.suppressRequestedWithHeader() {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(this, emptySet())
    }
}

/**
 * 取 WebView 内核**真实**的 Chrome 大版本号（如 `155`；拿不到返回 null）。
 *
 * ★ 为什么需要：登录页要把 UA 伪装成 Chrome（为了拿到与浏览器一致的页面与信任度），但版本号是
 *   **写死**在平台 UA 模板里的（`VideoHttp.UA_CHROME` 是 131），而设备内核可能早已升到 155。
 *   服务端只要做一次能力探测、或把 client hints 与已知版本对照，就能看出「UA 说 131、内核是 155」
 *   —— 这是一条典型的**伪装指纹**，登录页的风控会据此降分。真实浏览器永远不会出现这种版本错位。
 * ★ 取法：`WebSettings.getDefaultUserAgent()` 返回内核的真实 UA（形如
 *   `... Chrome/155.0.8059.43 Mobile Safari/537.36`），从里面抽出大版本号即可。
 *   首次调用会初始化 WebView 提供方、略慢，调用方需缓存（见 [withEngineChromeVersion]）。
 */
internal fun engineChromeMajor(context: android.content.Context): String? = runCatching {
    Regex("Chrome/(\\d+)").find(WebSettings.getDefaultUserAgent(context))?.groupValues?.get(1)
}.getOrNull()

/** 把 UA 里的 Chrome 版本改写成 `[major].0.0.0`（真实 Chrome UA 就是这个形态，不要写全版本号） */
internal fun String.withEngineChromeVersion(major: String): String =
    replace(Regex("Chrome/\\d+(?:\\.\\d+)*"), "Chrome/$major.0.0.0")

/**
 * 把 WebView 的**客户端提示**（`Sec-CH-UA` / `Sec-CH-UA-Mobile` / `Sec-CH-UA-Platform`，以及页面里的
 * `navigator.userAgentData`）对齐到刚设置的那条 UA。
 *
 * ★ 为什么必须做：Android WebView 默认发出的 client hints 里带着 `"Android WebView"` 品牌、平台写死
 *   Android。只要用 `WebSettings.userAgentString` 把 UA 改成别的（例如桌面 Chrome），服务端就会看到
 *   一份**自相矛盾的指纹**：「UA 说自己是 Chrome，client hints 却说自己是 WebView」——
 *   普通站点不在意，但登录页背后的风控会据此判定「不是浏览器环境」。
 *   （迅雷那边同理，见 `XunleiWebCredential.applyDesktopClientHints`。）
 * ★ 品牌顺序与版本号取自 [userAgent] 里的 Chrome 大版本：client hints 与 UA 的版本**不允许冲突**，
 *   写死会造成新的矛盾指纹。
 * ★ 依赖 WebView 的 `USER_AGENT_METADATA` 特性，不支持时静默跳过（页面回退到仅靠 UA 判定）。
 */
internal fun WebSettings.alignClientHintsWith(userAgent: String) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
    val major = Regex("Chrome/(\\d+)").find(userAgent)?.groupValues?.get(1) ?: "131"
    val full = "$major.0.0.0"
    val brandVersions = listOf(
        Triple("Google Chrome", major, full),
        Triple("Chromium", major, full),
        Triple("Not_A Brand", "24", "24.0.0.0")
    ).map { (brand, brandMajor, brandFull) ->
        UserAgentMetadata.BrandVersion.Builder()
            .setBrand(brand)
            .setMajorVersion(brandMajor)
            .setFullVersion(brandFull)
            .build()
    }
    val isAndroid = userAgent.contains("Android")
    val isIos = userAgent.contains("iPhone") || userAgent.contains("iPad")
    val metadata = UserAgentMetadata.Builder()
        .setBrandVersionList(brandVersions)
        .setFullVersion(full)
        .setPlatform(if (isAndroid) "Android" else if (isIos) "iOS" else "Windows")
        .setPlatformVersion(if (isAndroid) "14.0.0" else if (isIos) "17.0.0" else "10.0.0")
        .setArchitecture(if (isAndroid || isIos) "" else "x86")
        // 机型跟着 UA 走（UA 里写了 Pixel 8 就写 Pixel 8，两处保持一致）
        .setModel(if (isAndroid) "Pixel 8" else "")
        .setMobile(isAndroid || isIos || userAgent.contains("Mobile"))
        .setWow64(false)
        .build()
    WebSettingsCompat.setUserAgentMetadata(this, metadata)
}

/**
 * 在**诊断模式**下给登录页装一个网络嗅探脚本：把失败的 `fetch` / `XMLHttpRequest`
 * （状态码 ≥ 400，或网络层直接报错）以 `console.error` 输出，于是被 [DiagnosticWebChromeClient]
 * 收进诊断日志与 logcat。
 *
 * ★ 为什么需要：登录流程里真正失败的是页面内部的 XHR（提交表单、换取验证码 token），
 *   `WebViewClient` 的加载回调**看不到**它，`onReceivedHttpError` 也只在状态码非 2xx 时才来——
 *   服务端返回 200 但响应体是报错 JSON 这种最典型的失败就完全抓不到。本脚本补上这一环。
 * ★ 只在诊断模式开启时注入（`DiagnosticLog.isEnabled()`）：关掉时一行 JS 都不加，
 *   登录页行为与之前完全一致。
 * ★ 用 `addDocumentStartJavaScript` 在**文档开始前**注入，才能覆盖页面自己发起的第一批请求；
 *   作用于所有源（`"*"`），跨域的 reCAPTCHA iframe 里的请求也能记到。
 */
internal fun WebView.installLoginNetworkSniffer() {
    if (!DiagnosticLog.isEnabled()) return
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    WebViewCompat.addDocumentStartJavaScript(this, LOGIN_NET_SNIFFER_JS, setOf("*"))
}

/**
 * 嗅探脚本本体。全段包在 `try/catch` 里：任何异常都只吞掉，绝不影响登录页自身的脚本。
 * 只报「失败」（`fetch` 的 `ok === false` / XHR 的 `status >= 400`），正常请求不产生任何输出。
 * 响应体截断 400 字，避免把整页 HTML 灌进日志。
 */
private const val LOGIN_NET_SNIFFER_JS: String = """
(function(){
  try{
    if(window.__yxNetSniffer)return; window.__yxNetSniffer=1;
    function report(method,url,status,text){
      try{ console.error('YX-NET '+method+' '+url+' -> '+status+' '+String(text||'').slice(0,400)); }catch(e){}
    }
    var rawFetch=window.fetch;
    if(rawFetch){
      window.fetch=function(){
        var args=arguments;
        var url=(args[0]&&args[0].url)?args[0].url:String(args[0]||'');
        var p=rawFetch.apply(this,args);
        try{ p.then(function(r){
          if(r&&r.ok===false){
            try{ r.clone().text().then(function(t){report('fetch',url,r.status,t);},function(){report('fetch',url,r.status,'');}); }
            catch(e){ report('fetch',url,r.status,''); }
          }
        },function(e){ report('fetch',url,'ERR',e&&e.message); }); }catch(e){}
        return p;
      };
    }
    var rawOpen=XMLHttpRequest.prototype.open;
    var rawSend=XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open=function(m,u){
      try{ this.__yxM=m; this.__yxU=u; }catch(e){}
      return rawOpen.apply(this,arguments);
    };
    XMLHttpRequest.prototype.send=function(){
      var x=this;
      try{ x.addEventListener('loadend',function(){
        try{ if(x.status>=400){ report('xhr',x.__yxU,x.status,x.responseText); } }catch(e){}
      }); }catch(e){}
      return rawSend.apply(this,arguments);
    };
  }catch(e){}
})()
"""
