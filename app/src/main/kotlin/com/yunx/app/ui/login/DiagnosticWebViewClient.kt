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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
    }
}
