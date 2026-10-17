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

import com.yunx.app.data.download.DownloadPathPolicy
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * AcFun 解析器（移植自参考实现 `maibot-platform-parser/core/parsers/acfun.py`）。
 *
 * 作品页带 `ajaxpipe=1` 时会直接把 `window.videoInfo` 内嵌在返回的 HTML 里，从中取到
 * `currentVideoInfo.ksPlayJson`（一段**再嵌一层的 JSON 字符串**），其 `adaptationSet[0].representation`
 * 是各清晰度的 m3u8 直链数组。
 *
 * 产出 `isHls = true` 的 [VideoMedia]：m3u8 分片合并不在这里做，交给现有的 HLS 下载路径。
 */
object AcFunParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.ACFUN

    private const val REFERER = "https://www.acfun.cn/"

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val url = "https://www.acfun.cn/v/ac${link.id}?quickViewId=videoInfo_new&ajaxpipe=1"
        val headers = buildMap {
            put("User-Agent", VideoHttp.UA_CHROME)
            put("Referer", REFERER)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val html = VideoHttp.getText(url, headers, "AcFun 视频")

        val raw = VideoHttp.extractInlineJson(html, "window.videoInfo")
            ?: throw VideoParseException(
                VideoParseErrorKind.NOT_FOUND, "AcFun 视频信息解析失败（页面里没有 videoInfo，可能已删除）"
            )
        // 这段内嵌 JSON 被转义了两层：参考实现先还原 `\\"` → `\"`，再把 `\"` → `"`，否则 JSONObject 解析不了
        val jsonText = VideoHttp.unescapeQuotes(raw.replace("\\\\\"", "\\\""))
        val info = runCatching { JSONObject(jsonText) }.getOrElse {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "AcFun 视频信息解析失败", it)
        }

        val title = info.str("title")
        val description = info.str("description")
        val m3u8Url = pickM3u8(info)
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "AcFun 未取到视频流地址")

        return VideoParseResult(
            platform = platform,
            title = title,
            author = info.optJSONObject("user")?.str("name").orEmpty(),
            // 参考实现没取封面，这里顺带带上 videoInfo 里的 coverUrl（取不到则为空）
            cover = info.str("coverUrl"),
            text = description,
            timestampSeconds = parseCreateTime(info.str("createTime")),
            url = link.url,
            medias = listOf(
                VideoMedia(
                    url = m3u8Url,
                    kind = VideoMediaKind.VIDEO,
                    fileName = "${fileBase(title, link)}.mp4",
                    isHls = true,
                    headers = mediaHeaders(cookie),
                    quality = "720P"
                )
            )
        )
    }

    /**
     * 取某一档 m3u8 地址。
     *
     * 参考实现读 `currentVideoInfo.ksPlayJson.adaptationSet[0].representation`，直链数组按 4K → 360P
     * 降序排列，固定取第 4 档（≈720P）；档位不足 4 个时退回首档（画质最高），避免直接抛错。
     */
    private fun pickM3u8(info: JSONObject): String? {
        val current = info.optJSONObject("currentVideoInfo") ?: return null
        // ksPlayJson 正常是字符串（要再解析一次），个别版本直接给对象，两种都兜住
        val ksPlay = current.optJSONObject("ksPlayJson")
            ?: runCatching { JSONObject(current.str("ksPlayJson")) }.getOrNull()
            ?: return null
        val representations = ksPlay.optJSONArray("adaptationSet")
            ?.optJSONObject(0)
            ?.optJSONArray("representation") ?: return null
        val urls = (0 until representations.length()).mapNotNull { i ->
            representations.optJSONObject(i)?.str("url")?.takeIf { it.isNotBlank() }
        }
        return urls.getOrNull(3) ?: urls.firstOrNull()
    }

    /** `createTime` 一般是 `2024-12-1`；万一某天变成毫秒/秒时间戳也能兜住 */
    private fun parseCreateTime(value: String): Long {
        if (value.isBlank()) return 0L
        if (value.all { it.isDigit() }) {
            val number = value.toLongOrNull() ?: return 0L
            return if (number > 1_000_000_000_000L) number / 1000 else number
        }
        // SimpleDateFormat 默认宽松解析，`2024-12-1` 用 `yyyy-MM-dd` 也能过
        return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)?.time }
            .getOrNull()?.div(1000) ?: 0L
    }

    private fun mediaHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", REFERER)
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** 文件名主干：清洗非法字符并截断；标题为空时兜底为「平台_id」 */
    private fun fileBase(title: String, link: DetectedVideoLink): String {
        val cleaned = DownloadPathPolicy.sanitizeName(title).take(80)
        return cleaned.ifBlank { "${platform.displayName}_${link.id}" }
    }
}

/**
 * `JSONObject.optString` 遇到 JSON 里的显式 `null` 会返回字符串 "null"（Android org.json 的实现
 * 是 `String.valueOf(JSONObject.NULL)`），直接当字段值用会把 "null" 当成有效内容，这里统一过滤。
 */
private fun JSONObject.str(key: String): String =
    optString(key).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
