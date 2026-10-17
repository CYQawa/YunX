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
 * 微博解析器（移植自参考实现 `maibot-platform-parser/core/parsers/weibo.py`）。
 *
 * 三种入参分别对应参考实现的三条路径：
 * - `status`：wid（如 `P5kWdcfDe`）→ 手机站 JSON 接口 `m.weibo.cn/statuses/show`；
 * - `fid`（如 `1034:5145615399845897`）→ 视频组件接口 `h5.video.weibo.com/api/component`；
 * - `tv`：链接里的 `mid`（纯数字）先 `mid2id` 转成 id，再走 `status` 那条接口。
 */
object WeiboParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.WEIBO

    private const val REFERER = "https://weibo.com/"

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult = when (link.kind) {
        "status" -> parseWeiboStatus(link, link.id, cookie)
        // tv 链接的 id 是 mid：先 mid → id，再按 status 处理
        "tv" -> parseWeiboStatus(link, mid2id(link.id), cookie)
        "fid" -> parseVideoFid(link, cookie)
        else -> throw VideoParseException(VideoParseErrorKind.UNSUPPORTED, "不支持的微博链接形态：${link.kind}")
    }

    /**
     * 按 wid 拉手机站 JSON（无 Cookie + 伪装 XHR + 不跟随重定向）。
     */
    private suspend fun parseWeiboStatus(
        link: DetectedVideoLink,
        weiboId: String,
        cookie: String
    ): VideoParseResult {
        val headers = buildMap {
            put("accept", "application/json, text/plain, */*")
            put("referer", "https://m.weibo.cn/detail/$weiboId")
            put("origin", "https://m.weibo.cn")
            put("x-requested-with", "XMLHttpRequest")
            put("mweibo-pwa", "1")
            put("sec-fetch-site", "same-origin")
            put("sec-fetch-mode", "cors")
            put("sec-fetch-dest", "empty")
            put("User-Agent", VideoHttp.UA_CHROME)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        // 加一个时间戳参数，减少被缓存/规则命中的概率（参考实现同款）
        val url = "https://m.weibo.cn/statuses/show?id=$weiboId&_=${System.currentTimeMillis()}"

        // ★ 参考实现的关键点：**不跟随重定向**。跟随的话会被 302 到登录/验证页，既拿不到 JSON
        //   又会把 Cookie 顺带发给二跳域名；不跟随才能稳定拿到接口响应（或被风控明确拒绝）。
        val response = VideoHttp.request(url, headers = headers, followRedirects = false)
        if (response.status != 200) {
            throw when (response.status) {
                403, 418, 429 -> VideoParseException(
                    VideoParseErrorKind.BLOCKED,
                    "微博被风控拦截（HTTP ${response.status}），可更换 UA/Referer 或稍后重试"
                )

                404, 410 -> VideoParseException(
                    VideoParseErrorKind.NOT_FOUND, "微博内容不存在或已删除（HTTP ${response.status}）"
                )

                else -> VideoParseException(
                    VideoParseErrorKind.UNKNOWN, "微博数据获取失败（HTTP ${response.status}）"
                )
            }
        }

        val contentType = response.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value.orEmpty()
        // 状态 200 但 content-type 不是 JSON，说明被风控换成了验证页/网关页
        if (!contentType.contains("application/json")) {
            throw VideoParseException(
                VideoParseErrorKind.BLOCKED,
                "微博接口返回的不是 JSON（content-type: $contentType），多半被风控拦截"
            )
        }

        val json = runCatching { JSONObject(response.body) }.getOrElse {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "微博返回数据解析失败", it)
        }
        val data = json.optJSONObject("data")
            ?: throw VideoParseException(VideoParseErrorKind.NOT_FOUND, "微博内容不存在或已删除")
        return buildWeiboData(link, data, cookie, allowRetweet = true)
    }

    /**
     * 按 fid 拉视频组件接口。
     */
    private suspend fun parseVideoFid(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val reqUrl = "https://h5.video.weibo.com/api/component?page=/show/${link.id}"
        val headers = buildMap {
            put("Referer", "https://h5.video.weibo.com/show/${link.id}")
            put("User-Agent", VideoHttp.UA_CHROME)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        // 参考实现把整段 JSON 塞在名为 data 的表单字段里：`data={"Component_Play_Playinfo":{"oid":"..."}}`
        val form = mapOf("data" to """{"Component_Play_Playinfo":{"oid":"${link.id}"}}""")
        val text = VideoHttp.postForm(reqUrl, form, headers, "微博视频")

        val json = runCatching { JSONObject(text) }.getOrElse {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "微博视频接口返回数据解析失败", it)
        }
        val data = json.optJSONObject("data")?.optJSONObject("Component_Play_Playinfo")
            ?: throw VideoParseException(VideoParseErrorKind.NOT_FOUND, "微博视频数据为空（作品可能已删除）")

        val mediaHeaders = mediaHeaders(cookie)
        val title = data.str("title")
        val base = fileBase(title, link)
        val cover = httpsUrl(data.str("cover_image"))

        val medias = mutableListOf<VideoMedia>()
        // urls 是「码率 → 直链」的字典，第一条码率最高（stream_url 才是最低码率兜底）
        val urls = data.optJSONObject("urls")
        // org.json 的 keys() 是 Java 迭代器，Kotlin 侧要先转成序列才能 firstOrNull()
        val firstKey = urls?.let { obj -> obj.keys().asSequence().firstOrNull() }
        val videoUrl = if (urls != null && firstKey != null) {
            httpsUrl(urls.str(firstKey))
        } else {
            httpsUrl(data.str("stream_url"))
        }
        if (videoUrl.isNotBlank()) {
            medias += VideoMedia(
                url = videoUrl,
                kind = VideoMediaKind.VIDEO,
                fileName = "$base.mp4",
                headers = mediaHeaders
            )
        }

        return VideoParseResult(
            platform = platform,
            title = title,
            author = data.optJSONObject("reward")?.optJSONObject("user")?.str("name").orEmpty(),
            avatar = data.optJSONObject("reward")?.optJSONObject("user")?.str("profile_image_url").orEmpty(),
            cover = cover,
            text = cleanFidText(data.str("text")),
            timestampSeconds = parseRealDate(data.str("real_date")),
            url = link.url,
            medias = medias
        )
    }

    /**
     * 按参考实现 `build_weibo_data` 组装结果。
     *
     * 转发微博：本项目的 [VideoParseResult] 没有嵌套字段（参考实现是构造成 `repost` 递归嵌套），
     * 这里退化成「外层**没有任何媒体**时，直接用一层被转发内容作为结果」，只取一层以避免无限递归。
     */
    private fun buildWeiboData(
        link: DetectedVideoLink,
        data: JSONObject,
        cookie: String,
        allowRetweet: Boolean
    ): VideoParseResult {
        val mediaHeaders = mediaHeaders(cookie)
        val pageInfo = data.optJSONObject("page_info")
        val title = pageInfo?.str("title").orEmpty()
        val base = fileBase(title, link)

        val medias = mutableListOf<VideoMedia>()
        val videoUrl = weiboVideoUrl(pageInfo)
        if (videoUrl.isNotBlank()) {
            medias += VideoMedia(
                url = videoUrl,
                kind = VideoMediaKind.VIDEO,
                fileName = "$base.mp4",
                headers = mediaHeaders
            )
        }
        // 图片取九宫格原图：pics[].large.url
        val pics = data.optJSONArray("pics")
        if (pics != null) {
            var imageIndex = 0
            for (i in 0 until pics.length()) {
                val url = pics.optJSONObject(i)?.optJSONObject("large")?.str("url").orEmpty()
                if (url.isBlank()) continue
                imageIndex += 1
                medias += VideoMedia(
                    url = url,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "${base}_$imageIndex.jpg",
                    headers = mediaHeaders,
                    quality = "原图"
                )
            }
        }

        if (medias.isEmpty() && allowRetweet) {
            val retweeted = data.optJSONObject("retweeted_status")
            if (retweeted != null) return buildWeiboData(link, retweeted, cookie, allowRetweet = false)
        }

        val user = data.optJSONObject("user")
        val userId = user?.optLong("id", 0L) ?: 0L
        val bid = data.str("bid")

        return VideoParseResult(
            platform = platform,
            title = title,
            author = user?.str("screen_name").orEmpty(),
            avatar = user?.str("profile_image_url").orEmpty(),
            cover = pageInfo?.optJSONObject("page_pic")?.str("url").orEmpty(),
            text = cleanStatusText(data.str("text")),
            timestampSeconds = parseWeiboTime(data.str("created_at")),
            url = if (userId > 0 && bid.isNotBlank()) "https://weibo.com/$userId/$bid" else link.url,
            medias = medias
        )
    }

    /** `page_info.urls` 里 mp4_720p → mp4_hd → mp4_ld 取第一个非空（视频是单文件 mp4 直链） */
    private fun weiboVideoUrl(pageInfo: JSONObject?): String {
        val urls = pageInfo?.optJSONObject("urls") ?: return ""
        val value = urls.str("mp4_720p_mp4")
            .ifBlank { urls.str("mp4_hd_mp4") }
            .ifBlank { urls.str("mp4_ld_mp4") }
        return httpsUrl(value)
    }

    /** 微博正文里的 `&lt;br /&gt;` 换成换行，再去掉其余 HTML 标签（参考实现 text_content 原样移植） */
    private fun cleanStatusText(raw: String): String =
        raw.replace("<br />", "\n").replace(Regex("<[^>]*>"), "")

    /** fid 接口的正文：先去标签，再去掉多余空行（参考实现 parse_fid 原样移植） */
    private fun cleanFidText(raw: String): String =
        raw.replace(Regex("<[^>]*>"), "").replace("\n\n", "").trim()

    /** `Thu Oct 02 14:39:33 +0800 2025` → 秒级时间戳（Locale.US，否则英文月份解析不出来） */
    private fun parseWeiboTime(createdAt: String): Long {
        if (createdAt.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).parse(createdAt)?.time
        }.getOrNull()?.div(1000) ?: 0L
    }

    /** fid 接口的 `real_date`：能是毫秒/秒时间戳，也可能是 `yyyy-MM-dd HH:mm:ss` 这类字符串 */
    private fun parseRealDate(value: String): Long {
        if (value.isBlank()) return 0L
        if (value.all { it.isDigit() }) {
            val number = value.toLongOrNull() ?: return 0L
            // 13 位毫秒 → 秒；10 位已经是秒
            return if (number > 1_000_000_000_000L) number / 1000 else number
        }
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            val millis = runCatching { SimpleDateFormat(pattern, Locale.US).parse(value)?.time }.getOrNull()
            if (millis != null) return millis / 1000
        }
        return 0L
    }

    /** 微博直链常写成 `//f.video.weibocdn.com/...`，补上协议（参考实现手工加 `https:`） */
    private fun httpsUrl(value: String): String = when {
        value.isBlank() -> ""
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:$value"
        else -> "https:$value"
    }

    /** base62 编码（字母表与参考实现一致：0-9 a-z A-Z） */
    private fun base62Encode(number: Long): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (number == 0L) return "0"
        var value = number
        val builder = StringBuilder()
        while (value > 0) {
            builder.insert(0, alphabet[(value % 62).toInt()])
            value /= 62
        }
        return builder.toString()
    }

    /**
     * 微博 mid → id（参考实现 `_mid2id` 原样移植）：先整体反转，每 7 位切一段、段内再反转，
     * 每段按 base62 编码，非尾段不足 4 位左侧补零，最后把段序反转后拼接。
     */
    private fun mid2id(mid: String): String {
        val reversed = mid.reversed()
        if (reversed.isEmpty()) return ""
        val size = (reversed.length + 6) / 7 // ceil(len / 7)
        val chunks = mutableListOf<String>()
        for (i in 0 until size) {
            val end = minOf((i + 1) * 7, reversed.length)
            val piece = reversed.substring(i * 7, end).reversed()
            var encoded = base62Encode(piece.toLong())
            if (i < size - 1 && encoded.length < 4) {
                encoded = "0".repeat(4 - encoded.length) + encoded
            }
            chunks += encoded
        }
        return chunks.reversed().joinToString("")
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
