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

import android.webkit.WebView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** 读取网页登录态的超时：页面卡住/还在跳转时不能让轮询协程永远挂在这里 */
private const val JS_EVAL_TIMEOUT_MS = 2_000L

/**
 * 在 WebView 里执行一段 JS 并取回字符串结果（用于「从网页 localStorage / Cookie 里掏登录态」）。
 *
 * 约定：脚本自己 `return encodeURIComponent(需要的字符串)`，拿不到时 `return ''`。
 * 为什么要让 JS 侧先 encodeURIComponent：`evaluateJavascript` 的回调值是一段 **JSON 文本**，
 * token 里的引号、反斜杠、换行都会把 JSON 弄坏（JWT 通常还好，JSON 原文必然带引号）。
 * 先百分号编码就只剩 `[A-Za-z0-9%]`，再怎么解都不会错位；这里负责剥引号 + 解码。
 *
 * 页面未就绪、跨域、脚本抛异常等一律返回空串，由调用方决定「继续轮询」还是「提示用户」。
 */
internal suspend fun WebView.evaluateJsEncoded(script: String): String = withTimeoutOrNull(JS_EVAL_TIMEOUT_MS) {
    suspendCancellableCoroutine { cont ->
        try {
            evaluateJavascript(script) { result ->
                if (cont.isActive) cont.resume(decodeJsCallback(result))
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume("")
        }
    }
} ?: ""

/** 解析 `evaluateJavascript` 的回调文本：去掉 JSON 引号后做百分号解码 */
private fun decodeJsCallback(result: String?): String {
    val raw = result?.trim().orEmpty()
    if (raw.isEmpty() || raw == "\"\"" || raw == "null" || raw == "undefined") return ""
    val unquoted = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
        raw.substring(1, raw.length - 1)
    } else {
        raw
    }
    if (unquoted.isBlank()) return ""
    return runCatching { java.net.URLDecoder.decode(unquoted, "UTF-8") }.getOrDefault("")
}
