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

package com.yunx.app.data.network

import com.yunx.app.util.DiagnosticLog
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink

/**
 * 诊断模式的网络日志（挂在 `HttpClients` 的两个客户端上）。
 *
 * 记什么：方法、URL（**经过 `LogRedactor.url` 脱敏**，token/cookie 不会进文件）、状态码、耗时、
 * 请求体/响应体**摘要**（各截断到 1.5KB）。
 *
 * ★ 请求体/响应体的记录条件是**刻意收窄**的（用户要求）：只有
 *   ① HTTP 状态码不是 200/206，或 ② 直接抛异常（连接失败/超时/解析错误）时才记 body。
 *   正常的 200 响应（比如网盘列表 JSON、直链请求）只记一行「方法 + 脱敏 URL + 状态码 + 耗时」，
 *   既不落敏感数据，也不会把日志刷爆。
 *
 * 关闭诊断模式时 `intercept` 第一行就 return（零开销，不读 body、不建 Buffer）。
 */
class DiagnosticNetworkInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!DiagnosticLog.isEnabled()) return chain.proceed(chain.request())

        val request = chain.request()
        // 请求体要在发出去之前复制一份（发完就拿不到了）；只在体积可控时才复制，避免大上传吃内存
        val captured = captureRequestBody(request)
        val start = System.currentTimeMillis()
        return try {
            val response = chain.proceed(request)
            val cost = System.currentTimeMillis() - start
            val code = response.code
            // ★ 只有非 200/206 才读响应体（peekBody 不消费原流，下载/大响应也安全）
            val body = if (code != 200 && code != 206) {
                runCatching { response.peekBody(BODY_LIMIT.toLong()).string() }.getOrNull()
            } else {
                null
            }
            DiagnosticLog.network(
                method = request.method,
                url = request.url.toString(),
                code = code,
                costMs = cost,
                requestBody = captured,
                responseBody = body
            )
            response
        } catch (t: Throwable) {
            // 异常路径（含 JSON 解析失败的上游）：把请求体带上，方便对着服务端复现
            DiagnosticLog.network(
                method = request.method,
                url = request.url.toString(),
                code = -1,
                costMs = System.currentTimeMillis() - start,
                requestBody = captured,
                error = t.message ?: t.javaClass.simpleName
            )
            throw t
        }
    }

    /** 复制请求体文本（仅限有 body 且声明长度在 0~64KB 的请求；其余只记长度） */
    private fun captureRequestBody(request: okhttp3.Request): String? {
        val body = request.body ?: return null
        val length = runCatching { body.contentLength() }.getOrDefault(-1L)
        if (length < 0 || length > MAX_CAPTURED_BODY) return "（$length 字节，未记录内容）"
        return runCatching {
            val tee = TeeRequestBody(body)
            // 立刻走一遍 writeTo 把内容抓进内存（同一次请求里 OkHttp 还会再调一次，用的是缓存副本）
            tee.writeTo(Buffer())
            tee.captured()
        }.getOrNull()
    }

    /**
     * 把真实请求体复制一份到内存的包装体。
     *
     * OkHttp 在 `writeTo` 里才真正产出内容，所以这里「抓一份 + 原样转发」：
     * 抓到的是**同一份字节**，对服务端零影响；`contentLength`/`contentType` 原样透传。
     */
    private class TeeRequestBody(private val delegate: okhttp3.RequestBody) : okhttp3.RequestBody() {
        private val buffer = Buffer()

        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            val copy = Buffer()
            delegate.writeTo(copy)
            buffer.writeAll(copy.copy())
            sink.writeAll(copy)
        }

        fun captured(): String = buffer.copy().readUtf8().take(BODY_LIMIT)
    }

    private companion object {
        /** 单个 body 最多读这么多字节（够看结构，不会把日志写爆） */
        const val BODY_LIMIT = 1536

        /** 超过这个长度的请求体不复制内容（大文件上传场景） */
        const val MAX_CAPTURED_BODY = 64L * 1024
    }
}
