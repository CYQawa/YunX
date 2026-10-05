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

package com.yunx.app.data.download

import java.net.URLDecoder

/**
 * 磁力链接（`magnet:?xt=urn:btih:...`）的识别与信息提取。
 *
 * 为什么是纯字符串实现、不用 `android.net.Uri`：
 * ① 磁力链接不是 URL —— 它没有 authority，`?` 后面是一串 `&` 连接的参数（`dn` 里还可能带空格），
 *    Android 的 Uri 会把它拆得七零八落；
 * ② JVM 单测里 `android.net.Uri` 是空壳（`unitTests.isReturnDefaultValues = true` ⇒ 方法全返回
 *    null/0），用 Uri 写的解析逻辑**根本测不了**（见 Agent.md §4）。这里只用 JDK 的 URLDecoder。
 *
 * 只做「够用」的解析：识别 scheme、取 `dn`（显示名）、取 `xt=urn:btih:` 的 info hash。
 * 真正的种子名与文件列表由 Gopeed 引擎解析出元数据后给出（见 GopeedEngine.taskDetail），不在这里猜。
 */
object MagnetLink {

    private const val SCHEME = "magnet:"

    /** 磁力的 info hash：`xt=urn:btih:<40 位 hex 或 32 位 base32>` */
    private val BTIH = Regex("(?i)xt=urn:btih:([A-Za-z0-9]+)")

    /** `dn` 里的换行/制表符：显示名要进通知栏与列表，先压成空格 */
    private val BLANK_CHARS = Regex("[\\r\\n\\t]+")

    /** 显示名上限：`dn` 可能很长（有的站点把简介都塞进去），截断后 UI 与通知栏才不会被撑爆 */
    private const val MAX_NAME_CHARS = 80

    /** 是不是磁力链接（大小写不敏感，允许前后空白；只有 `magnet:` 光杆不算） */
    fun isMagnet(url: String): Boolean {
        val text = url.trim()
        return text.length > SCHEME.length && text.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)
    }

    /**
     * 任务的显示名：优先 `dn=`（磁力自带的显示名），没有就用 info hash 前 8 位占位。
     *
     * ★ 只用于「元数据到手前」的界面/通知显示：磁力在解析出元数据之前拿不到真正的种子名，
     *   下完后会用引擎给的真实名字覆盖（见 DownloadManager.completeEngineTask）。
     */
    fun displayName(url: String): String {
        val dn = param(url, "dn").replace(BLANK_CHARS, " ").trim()
        if (dn.isNotEmpty()) return dn.take(MAX_NAME_CHARS)
        val hash = infoHash(url)
        return if (hash.length >= 8) "磁力_${hash.take(8)}" else "磁力任务"
    }

    /** info hash（统一大写，取不到返回空串）；仅用于显示与日志，不参与去重 */
    fun infoHash(url: String): String =
        BTIH.find(url)?.groupValues?.get(1)?.uppercase().orEmpty()

    /** 取第一个 `key=value` 参数的值（`dn` 理论上唯一，出现多次也只认第一个） */
    private fun param(url: String, key: String): String {
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return ""
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (!part.substring(0, eq).equals(key, ignoreCase = true)) continue
            return decode(part.substring(eq + 1)).trim()
        }
        return ""
    }

    /**
     * 磁力参数是 percent-encoding（空格写作 `%20`）：这里把 `+` 按**字面**处理，
     * 真正的 `+`（文件名里很常见）才不会被当成空格吃掉。
     */
    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)
}
