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

package com.yunx.app.util

import android.content.Intent

/**
 * 深链接 intent 的解析（纯逻辑，可单测）。
 *
 * 只负责「从 intent 里取出待处理的文本」，不碰网络也不碰组件状态：
 * 拿到的文本是分享链接、中文口令还是一整句带链接的文案都行 —— 后面统一交给解析链路
 * （[com.yunx.app.data.network.ShareLinkParser] / GitHub / 迅雷与星海口令兜底）自己认。
 *
 * ★ 一律不用 `android.net.Uri`：JVM 单测里它是空壳（见 Agent.md §4），所以手写串解析。
 */
object DeepLinks {

    /** 自有协议 scheme：`yunx://resolve?url=<编码后的分享链接>&pwd=<提取码>` */
    private const val SCHEME_PREFIX = "yunx://"

    /** `yunx://` 里承载真实分享链接的参数名 */
    private const val PARAM_URL = "url"

    /** `yunx://` 里可选的提取码参数名 */
    private const val PARAM_PWD = "pwd"

    /**
     * 从 intent 的三要素里取出待解析文本；null = 这个 intent 不是「链接唤起 / 分享 / 选中文本解析」。
     *
     * @param action    `Intent.action`
     * @param data      `Intent.dataString`（ACTION_VIEW：点链接 / yunx:// / magnet:）
     * @param extraText 文本类 action 带过来的文本：
     *                  ACTION_SEND 用 `EXTRA_TEXT`，ACTION_PROCESS_TEXT 用 `EXTRA_PROCESS_TEXT`（选中文本）
     */
    fun textFrom(action: String?, data: String?, extraText: String?): String? = when (action) {
        Intent.ACTION_VIEW -> viewText(data)
        // 分享 / 选中文本解析：文本本身就是待解析内容（可能是链接、中文口令，或一整句带链接的文案）
        Intent.ACTION_SEND, Intent.ACTION_PROCESS_TEXT ->
            extraText?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }

    /** ACTION_VIEW 的 data：`yunx://` 要拆出真实链接，其余（https / magnet:）原样返回 */
    private fun viewText(data: String?): String? {
        val raw = data?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.startsWith(SCHEME_PREFIX, ignoreCase = true)) return raw
        // 自有协议：url 参数里才是真正的分享链接（URL 编码过）
        val url = queryParam(raw, PARAM_URL)?.let(::urlDecode)?.trim().orEmpty()
        if (url.isEmpty()) return null
        // 提取码可以放在单独的 pwd 参数里：拼回链接（解析链路会从 ?pwd= 里认出来）
        val pwd = queryParam(raw, PARAM_PWD)?.let(::urlDecode)?.trim().orEmpty()
        if (pwd.isEmpty() || url.contains("pwd=", ignoreCase = true)) return url
        return url + if (url.contains('?')) "&pwd=$pwd" else "?pwd=$pwd"
    }

    /** 取查询参数（取第一个同名参数；值已编码，所以不担心值里出现裸 &） */
    private fun queryParam(data: String, name: String): String? {
        val query = data.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun urlDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
