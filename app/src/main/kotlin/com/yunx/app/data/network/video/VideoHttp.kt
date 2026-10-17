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

import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * 视频解析各平台共用的 HTTP 小工具。
 *
 * 存在的意义是把三件容易写错、又十个解析器都要做的事收在一处：
 * 1. **统一走 `HttpClients.apiClient()`**（共享超时与代理设置，不要在解析器里 new client）；
 * 2. **把 HTTP 状态码翻译成 [VideoParseException]**：403/418/429 是各平台风控的常态
 *    （微博、小红书匿名请求尤其常见），必须变成「请稍后重试」而不是裸异常或静默失败；
 * 3. **短链展开**：抖音/小红书/快手/微博/B站都有 `v.douyin.com` 这类短链，先取 Location
 *    再重新识别，是每个平台都要的一步。
 */
object VideoHttp {

    /** 桌面 Chrome UA：多数站点的分享页用桌面 UA 返回完整 SSR（小红书/快手/微博） */
    const val UA_CHROME =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    /** iPhone UA：抖音分享页走 iOS UA（与参考实现一致，返回的 SSR 结构最稳） */
    const val UA_IPHONE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    /** 一次请求的原始响应（需要读 Location / Set-Cookie / Content-Range 时用） */
    data class RawResponse(
        val status: Int,
        val body: String,
        val headers: Map<String, String>,
        val finalUrl: String
    )

    /**
     * 发一次请求。
     *
     * @param followRedirects false 用于「只取重定向目标」（短链展开）
     */
    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        followRedirects: Boolean = true,
        clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
    ): RawResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).headers(buildHeaders(headers))
        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post(body ?: FormBody.Builder().build())
            else -> builder.method(method.uppercase(), body)
        }
        val client = clientProvider().newBuilder().followRedirects(followRedirects).build()
        val response: Response = try {
            client.newCall(builder.build()).execute()
        } catch (e: java.io.IOException) {
            throw VideoParseException(VideoParseErrorKind.NETWORK, "请求失败：$url", e)
        }
        response.use {
            RawResponse(
                status = it.code,
                body = runCatching { it.body?.string().orEmpty() }.getOrDefault(""),
                headers = buildMap {
                    it.headers.names().forEach { name -> put(name, it.header(name).orEmpty()) }
                },
                finalUrl = it.request.url.toString()
            )
        }
    }

    /** 把 Map 转成 OkHttp 的 Headers（不用 `Headers.of(map)`：该重载在不同 OkHttp 版本里不一致） */
    private fun buildHeaders(headers: Map<String, String>): okhttp3.Headers =
        okhttp3.Headers.Builder().apply { headers.forEach { (k, v) -> add(k, v) } }.build()

    /** 断言请求成功；失败按状态码分类抛 [VideoParseException] */
    fun requireSuccess(response: RawResponse, what: String) {
        if (response.status in 200..299) return
        throw when (response.status) {
            // 各平台风控：微博/小红书/抖音都会用这几个码挡匿名请求
            403, 418, 429 -> VideoParseException(
                VideoParseErrorKind.BLOCKED, "$what 被风控拦截（HTTP ${response.status}）"
            )

            404, 410 -> VideoParseException(
                VideoParseErrorKind.NOT_FOUND, "$what 不存在（HTTP ${response.status}）"
            )

            else -> VideoParseException(
                VideoParseErrorKind.UNKNOWN, "$what 请求失败（HTTP ${response.status}）"
            )
        }
    }

    /** GET 并断言成功，返回响应体文本 */
    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        what: String = "解析",
        clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
    ): String {
        val response = request(url, headers = headers, clientProvider = clientProvider)
        requireSuccess(response, what)
        return response.body
    }

    /** GET 并断言成功，解析为 JSON 对象 */
    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        what: String = "解析",
        clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
    ): JSONObject {
        val text = getText(url, headers, what, clientProvider)
        return runCatching { JSONObject(text) }.getOrElse {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "$what 返回的不是合法 JSON", it)
        }
    }

    /** POST 表单并断言成功 */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        what: String = "解析",
        clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
    ): String {
        val formBody = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val response = request(url, method = "POST", headers = headers, body = formBody, clientProvider = clientProvider)
        requireSuccess(response, what)
        return response.body
    }

    /**
     * 展开短链：只知道一次重定向（与参考实现一致，逐跳重试由调用方决定）。
     *
     * 拿不到 Location 时返回 null，由调用方决定报错还是当成最终地址继续。
     */
    suspend fun expandRedirect(
        url: String,
        headers: Map<String, String> = emptyMap(),
        clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
    ): String? {
        val response = request(
            url = url,
            headers = headers,
            followRedirects = false,
            clientProvider = clientProvider
        )
        // 3xx：Location 就是目标；2xx：说明这个短链本身就是终址（有些短链会直接返回内容）
        if (response.status in 300..399) {
            return response.headers.entries.firstOrNull { it.key.equals("Location", ignoreCase = true) }?.value
        }
        return if (response.finalUrl != url) response.finalUrl else null
    }

    /**
     * 从 HTML 里抠出形如 `window.XXX = {...}</script>` 的内嵌 JSON 文本。
     *
     * 抖音/小红书/快手/AcFun 都是这个套路，差异只在变量名与引号转义，所以统一在这里做：
     * - [key] 传形如 `window._ROUTER_DATA` 的变量名（内部转义成正则字面量）；
     * - [stopAt] 传结束标记（一般 `</script>`）。
     */
    fun extractInlineJson(html: String, key: String, stopAt: String = "</script>"): String? {
        val pattern = Regex(Regex.escape(key) + """\s*=\s*(.*?)""" + Regex.escape(stopAt), RegexOption.DOT_MATCHES_ALL)
        return pattern.find(html)?.groupValues?.getOrNull(1)?.trim()
    }

    /** 把 `\\"` 还原成 `"`（AcFun 的内嵌 JSON 会被转义一层，参考实现也是手工还原） */
    fun unescapeQuotes(text: String): String = text.replace("\\\"", "\"").replace("\\\\/", "/")

    /**
     * 把解析出的直链归一化成 https —— **必须做，否则直链在 OkHttp 层就被系统拦下**。
     *
     * ★ 实测（2026-10）：小红书 SSR 里的图片直链是**明文 http**
     *   （`http://sns-webpic-qc.xhscdn.com/...`，见诊断日志 `download.log` 的 `url=`），
     *   而本项目 `network_security_config.xml` 的 `base-config` 全局禁止明文，OkHttp 会直接抛
     *   `CLEARTEXT communication to ... not permitted by network security policy` ——
     *   表现就是「在线预览」和「直接下载」**双双失败**（Coil 与下载器都走 OkHttp，所以一起倒）。
     *
     * ★ 为什么可以直接升级：这些 CDN 同域都提供 https（网页本身就是 https 页面在引用它们）；
     *   升级只改变本来就用不了的明文链，对已经是 https 的链零影响。协议相对地址（`//host/...`）
     *   也一并按 https 补全 —— OkHttp 不认没有 scheme 的 URL。
     */
    fun secureUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }
}
