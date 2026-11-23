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

import com.yunx.app.R
import com.yunx.app.data.download.DownloadPathPolicy
import com.yunx.app.util.AppStrings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
        else -> throw VideoParseException(
            VideoParseErrorKind.UNSUPPORTED,
            AppStrings.get(R.string.em_weibo_unsupported_kind, link.kind)
        )
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
                    AppStrings.get(R.string.em_weibo_blocked, response.status)
                )

                404, 410 -> VideoParseException(
                    VideoParseErrorKind.NOT_FOUND,
                    AppStrings.get(R.string.em_weibo_not_found_code, response.status)
                )

                else -> VideoParseException(
                    VideoParseErrorKind.UNKNOWN,
                    AppStrings.get(R.string.em_weibo_fetch_failed, response.status)
                )
            }
        }

        val contentType = response.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value.orEmpty()
        // 状态 200 但 content-type 不是 JSON，说明被风控换成了验证页/网关页
        if (!contentType.contains("application/json")) {
            throw VideoParseException(
                VideoParseErrorKind.BLOCKED,
                AppStrings.get(R.string.em_weibo_not_json, contentType)
            )
        }

        val json = runCatching { JSONObject(response.body) }.getOrElse {
            throw VideoParseException(
                VideoParseErrorKind.UNKNOWN,
                AppStrings.get(R.string.em_weibo_parse_failed),
                it
            )
        }
        val data = json.optJSONObject("data")
            ?: throw VideoParseException(
                VideoParseErrorKind.NOT_FOUND,
                AppStrings.get(R.string.em_weibo_not_found)
            )
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
        val text = VideoHttp.postForm(
            reqUrl,
            form,
            headers,
            AppStrings.get(R.string.em_what_weibo_video)
        )

        val json = runCatching { JSONObject(text) }.getOrElse {
            throw VideoParseException(
                VideoParseErrorKind.UNKNOWN,
                AppStrings.get(R.string.em_weibo_video_parse_failed),
                it
            )
        }
        val data = json.optJSONObject("data")?.optJSONObject("Component_Play_Playinfo")
            ?: throw VideoParseException(
                VideoParseErrorKind.NOT_FOUND,
                AppStrings.get(R.string.em_weibo_video_empty)
            )

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
    private suspend fun buildWeiboData(
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
        // ★ 动图（Live Photo）：`type == "livephoto"` 的条目另有 `videoSrc` —— 那是「会动的那一半」
        //   （`https://video.weibo.com/media/play?livephoto=<.mov>` 中转链，实测可下；直连
        //   `livephoto.us.sinaimg.cn` 反而 403，所以必须原样用它）。下载时由 `MotionPhotoWriter`
        //   把它与静图合成为一张「动态照片」，见 `VideoMedia.motionUrl`。
        val pics = data.optJSONArray("pics")
        if (pics != null) {
            val picObjects = (0 until pics.length()).mapNotNull { pics.optJSONObject(it) }
            // ★ 并行探测：原来是「一张接一张」串行探测，9 图就是 9 轮往返，解析明显变慢。
            //   这里全部并发，总耗时≈最慢的那一张。
            val picks = coroutineScope {
                picObjects.map { pic -> async { pic to resolveBestPic(pic, mediaHeaders) } }.awaitAll()
            }
            var imageIndex = 0
            for ((pic, pick) in picks) {
                if (pick.url.isBlank()) continue
                val motionUrl = pic.str("videoSrc")
                imageIndex += 1
                medias += VideoMedia(
                    url = pick.url,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "${base}_$imageIndex.jpg",
                    // 探测到的真实体积一并带上：下载页能显示准确进度，不必再等服务器给 Content-Length
                    size = if (pick.size > 0) pick.size else -1L,
                    headers = mediaHeaders,
                    quality = if (motionUrl.isNotBlank()) {
                        AppStrings.get(R.string.em_quality_motion)
                    } else {
                        AppStrings.get(R.string.em_quality_original)
                    },
                    motionUrl = motionUrl
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
    // ---------------------------- 图片「原图」解析 ----------------------------

    /** 一张图的最终选择：url + 探测到的字节数（-1 = 未知） */
    private data class PicPick(val url: String, val size: Long)

    /**
     * 取一张微博图片**最清晰**的地址。
     *
     * ★ 为什么不能只用 `pics[].large.url`：微博图床同一张图（同一个 pid）有多个规格，
     *   形如 `https://wx1.sinaimg.cn/<规格>/<pid>.jpg`，规格从低到高大致是
     *   `thumbnail` → `bmiddle` → `mw690` → `large` → `mw2000` → `orj1080`。
     *   `large` 只是「大图」，客户端点「下载原图」拿到的那一档往往比它大 ——
     *   这就是「解析出来的图比微博客户端下载的分辨率低」的原因。
     *
     * 做法：把 JSON 给的地址（largest / large / url）当作基准，把规格段分别改写成
     * `orj1080` / `mw2000` / `large`，**并行 HEAD 比 Content-Length 取最大的一档**。
     *
     * ★ 为什么不能「第一个成功就用」（2026-11 的教训）：图床对**不存在的规格**可能返回
     *   `200 + 一张 HTML 错误页`，先到先得会把这张 HTML 当成"原图"存下来 —— 用户看到的就是
     *   「下载的不是原图」。所以必须比体积，并且排除 `Content-Type` 不是图片类型的响应。
     * ★ 速度靠并发 + 2.5s 超时保证：3 个候选同时探，正常几十毫秒；
     *   每张图之间也并发（见上方 `pics` 的 `map { async { ... } }`），9 图总耗时≈单张。
     */
    private suspend fun resolveBestPic(pic: JSONObject, headers: Map<String, String>): PicPick {
        val direct = listOfNotNull(
            pic.optJSONObject("largest")?.str("url")?.takeIf { it.isNotBlank() },
            pic.optJSONObject("large")?.str("url")?.takeIf { it.isNotBlank() },
            pic.str("url").takeIf { it.isNotBlank() }
        ).map(::httpsUrl)
        if (direct.isEmpty()) return PicPick("", -1L)

        val candidates = LinkedHashSet<String>()
        // 与第一版一致：把 JSON 里给的每个地址都当基准改写（同一 pid 改写后字符串相同，
        // LinkedHashSet 会自动去重，实际候选通常只有 3~4 个），不会漏掉高清规格。
        for (baseUrl in direct) {
            for (spec in listOf("orj1080", "mw2000", "large")) {
                val rewritten = rewriteSize(baseUrl, spec)
                if (rewritten != baseUrl) candidates += rewritten
            }
            candidates += baseUrl
        }

        val probed = coroutineScope {
            candidates.map { url -> async { url to sizeOf(url, headers) } }.awaitAll()
        }
        // 只在「探测成功（>0）且确实是图片」的候选里挑体积最大的；全失败则退回 JSON 原始地址
        val best = probed.filter { it.second > 0 }.maxByOrNull { it.second }
        return if (best != null) PicPick(best.first, best.second) else PicPick(direct.first(), -1L)
    }

    /**
     * 把微博图床地址的「规格段」换掉：`https://wx1.sinaimg.cn/<规格>/<pid>.jpg` → `.../<newSize>/<pid>.jpg`。
     * 解析不出「host + 规格 + 文件名」三段结构时原样返回（不做任何猜测）。
     */
    private fun rewriteSize(url: String, size: String): String {
        // 常见形态：https://wx1.sinaimg.cn/<规格>/<pid>.jpg
        Regex("""^(https?://[^/]+)/[^/]+/(.+)$""").find(url)?.let {
            return "${it.groupValues[1]}/$size/${it.groupValues[2]}"
        }
        // 少数图片没有规格段（https://wx1.sinaimg.cn/<pid>.jpg）：在文件名前插入规格，
        // 命中就是更清晰的版本，不命中（404）会被 sizeOf 判成 -1，自动退回原地址
        Regex("""^(https?://[^/]+)/([^/?]+\.[A-Za-z0-9]+)(\?.*)?$""").find(url)?.let {
            return "${it.groupValues[1]}/$size/${it.groupValues[2]}${it.groupValues[3]}"
        }
        return url
    }

    /**
     * HEAD 取字节数。以下情况一律返回 -1（不参与「谁更大」的比较）：
     * 非 2xx、**`Content-Type` 不是图片类型**（图床对不存在的规格会返回 200+HTML 错误页）、
     * 拿不到 `Content-Length`、超过 2.5 秒、网络异常。
     */
    private suspend fun sizeOf(url: String, headers: Map<String, String>): Long = try {
        withTimeoutOrNull(2_500L) {
            val r = VideoHttp.request(url, method = "HEAD", headers = headers)
            if (r.status !in 200..299) {
                -1L
            } else {
                val contentType = r.headers.entries
                    .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                    ?.value?.lowercase()
                // ★ 关键：200 但返回 HTML/JSON 的一律不算图片 —— 否则会选中"假原图"
                if (contentType != null && !contentType.startsWith("image/")) {
                    -1L
                } else {
                    r.headers.entries
                        .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
                        ?.value?.trim()?.toLongOrNull() ?: -1L
                }
            }
        } ?: -1L
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        -1L
    }

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
