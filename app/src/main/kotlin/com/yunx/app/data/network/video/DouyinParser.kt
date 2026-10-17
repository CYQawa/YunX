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
import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 抖音解析器（视频 + 图文/图集）。
 *
 * - **平台**：抖音（`VideoPlatform.DOUYIN`）。
 * - **数据来源手法**：请求 iesdouyin 分享页的 SSR，从 HTML 里抠出 `window._ROUTER_DATA`
 *   （抖音给移动端 Web 的预渲染数据，字段语义见参考实现 `douyin/video.py`），再对视频走
 *   `aweme/v1/play` 端点探测**无水印**直链。
 * - **是否需要登录**：不需要。但抖音要求先带上一枚匿名 `ttwid` Cookie，否则返回验证页
 *   （见 [ensureTtwid]）。
 * - **产出形态**：视频命中 → 单条 [VideoMediaKind.VIDEO] 直链；图文命中 → 多条
 *   [VideoMediaKind.IMAGE] 原图。二者互斥（与参考实现的 `if/elif` 一致）。
 *
 * 已知限制：
 * 1. `slides`（幻灯片）在参考实现里走独立的 `slidesinfo` 接口，本期未实现：识别层已把它归一化成
 *    `video`，这里会退化成按 `note` 分享页解析，可能拿不到数据；
 * 2. `ttwid` 用进程内缓存（见 [ensureTtwid]），该 ttwid 被风控拉黑后需重启进程才会重新注册。
 */
object DouyinParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.DOUYIN

    private const val TTWID_REGISTER_URL = "https://ttwid.bytedance.com/ttwid/union/register/"
    private const val IESDOUYIN_REFERER = "https://www.iesdouyin.com/"

    /** 探测无水印直链时依次尝试的清晰度（顺序与参考实现一致，最终取体积最大的一路） */
    private val PLAY_RATIOS = listOf("1080p", "720p", "540p", "360p")

    /** 从原始链接里还原分享页类型（识别层把 video/note/slides 都压成了 kind=video） */
    private val TYPE_REGEX = Regex("""/(?:share/)?(video|note|slides)/""")

    /** 从 play_addr 直链里反查 video_id 查询参数 */
    private val VIDEO_ID_REGEX = Regex("""[?&]video_id=([^&\s]+)""")

    /** 内容区间 `bytes 0-1/12345` 里的总长度 */
    private val CONTENT_RANGE_TOTAL_REGEX = Regex("""/(\d+)\s*$""")

    /**
     * 进程内缓存的匿名 ttwid。
     *
     * 之所以缓存而不每次解析都注册：ttwid 有效期较长，重复注册是白白多挨一次风控。
     * 并发下最坏情况是两个解析同时注册并互相覆盖缓存，结果等价、无副作用，故不加锁。
     */
    @Volatile
    private var cachedTtwid: String? = null

    private data class ResolvedMedia(val url: String, val size: Long, val headers: Map<String, String>)

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val shareUrl = buildShareUrl(link)
        val ttwid = ensureTtwid()
        // 抖音无需登录，cookie 正常为空；带上只是兼容万一上层塞了账号 Cookie 的情况
        val cookieHeader = if (cookie.isBlank()) ttwid else "$ttwid; $cookie"

        val html = VideoHttp.getText(
            url = shareUrl,
            headers = mapOf(
                "User-Agent" to VideoHttp.UA_IPHONE,
                "Referer" to IESDOUYIN_REFERER,
                "Cookie" to cookieHeader
            ),
            what = "抖音分享页"
        )

        val routerJson = VideoHttp.extractInlineJson(html, "window._ROUTER_DATA")
            ?: throw VideoParseException(
                VideoParseErrorKind.UNKNOWN,
                "抖音分享页里没有 window._ROUTER_DATA（作品可能已删除或页面改版）"
            )
        val data = extractVideoData(routerJson)
        val mediaHeaders = buildMediaHeaders(shareUrl)

        val title = data.optStringAny("desc")
        val medias = mutableListOf<VideoMedia>()
        var cover = ""

        val images = data.optArrayAny("images")
        val video = data.optObjectAny("video")
        if (images != null && images.length() > 0) {
            // 图文：参考实现对每张图随机取一条 url_list，这里固定取第一条以保证结果可复现
            for (i in 0 until images.length()) {
                val image = images.optJSONObject(i) ?: continue
                val url = firstUrl(image) ?: continue
                if (cover.isBlank()) cover = url
                medias += VideoMedia(
                    url = url,
                    kind = VideoMediaKind.IMAGE,
                    fileName = imageFileName(link.id, title, i + 1),
                    quality = "原图",
                    headers = mediaHeaders
                )
            }
        } else if (video != null) {
            val resolved = resolveVideo(video, mediaHeaders)
            if (resolved != null) {
                cover = firstUrl(video.optObjectAny("cover")).orEmpty()
                medias += VideoMedia(
                    url = resolved.url,
                    kind = VideoMediaKind.VIDEO,
                    fileName = videoFileName(link.id, title),
                    size = resolved.size,
                    headers = resolved.headers
                )
            }
        }

        val author = data.optObjectAny("author")
        return VideoParseResult(
            platform = platform,
            title = title,
            author = author?.optStringAny("nickname").orEmpty(),
            // 参考实现优先 avatar_thumb，退化到 avatar_medium
            avatar = firstUrl(author?.optObjectAny("avatar_thumb", "avatar_medium")).orEmpty(),
            cover = cover,
            timestampSeconds = data.optLongAny("create_time", "createTime"),
            url = link.url,
            medias = medias
        )
    }

    // ------------------------------------------------------------------ 分享页

    private fun buildShareUrl(link: DetectedVideoLink): String {
        // 只区分 note / video：slides 未实现，退化成 video 分享页（见类注释的已知限制）
        val ty = if (TYPE_REGEX.find(link.url)?.groupValues?.getOrNull(1) == "note") "note" else "video"
        return "https://www.iesdouyin.com/share/$ty/${link.id}/"
    }

    /**
     * 从 `window._ROUTER_DATA` 里取出作品对象。
     *
     * 字段名按参考实现的 msgspec 结构走：显式 `name=` 过的键是 camelCase（`videoInfoRes`、
     * `video_(id)/page`），其余字段直接用 Python 字段名（snake_case，如 `item_list`）。
     * 为容忍不同端点的 casing 差异，叶子键用 [optObjectAny]/[optArrayAny] 做候选匹配。
     */
    private fun extractVideoData(routerJson: String): JSONObject {
        val router = try {
            JSONObject(routerJson)
        } catch (e: Exception) {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "window._ROUTER_DATA 不是合法 JSON", e)
        }
        val loaderData = router.optJSONObject("loaderData")
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "window._ROUTER_DATA 里没有 loaderData")
        val page = loaderData.optJSONObject("video_(id)/page")
            ?: loaderData.optJSONObject("note_(id)/page")
            ?: throw VideoParseException(
                VideoParseErrorKind.UNKNOWN,
                "window._ROUTER_DATA 里既没有 video_(id)/page 也没有 note_(id)/page"
            )
        val itemList = page.optObjectAny("videoInfoRes", "video_info_res")
            ?.optArrayAny("item_list", "itemList")
        // 参考实现是 choice(item_list) 随机取一条，这里固定取第一条（通常也只有一条）
        return itemList?.optJSONObject(0)
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "videoInfoRes.item_list 为空")
    }

    // ------------------------------------------------------------------ 直链

    private suspend fun resolveVideo(video: JSONObject, mediaHeaders: Map<String, String>): ResolvedMedia? {
        val playAddr = video.optObjectAny("play_addr", "playAddr")
        extractPlayToken(playAddr)?.let { token ->
            // 优先走 play 端点：能同时拿到无水印直链与真实文件大小
            probePlay(token, mediaHeaders)?.let { return it }
        }
        // 回退：直接用 play_addr 直链，把 playwm 换成 play 去水印（参考实现的关键一步）
        val fallback = firstUrl(playAddr)?.replace("playwm", "play") ?: return null
        return ResolvedMedia(fallback, -1L, mediaHeaders)
    }

    /** 有 `uri` 用 `uri`，否则遍历 play_addr 直链从 `video_id` 查询参数里取（与参考实现一致） */
    private fun extractPlayToken(playAddr: JSONObject?): String? {
        val uri = playAddr?.optStringAny("uri").orEmpty()
        if (uri.isNotBlank()) return uri
        val urlList = playAddr?.optArrayAny("url_list", "urlList") ?: return null
        for (i in 0 until urlList.length()) {
            val videoId = VIDEO_ID_REGEX.find(urlList.optString(i))?.groupValues?.getOrNull(1)
            if (!videoId.isNullOrBlank()) return videoId
        }
        return null
    }

    /**
     * 逐个清晰度探测 play 端点，取**总文件体积最大**的一路。
     *
     * 各 ratio 指向同一视频的不同转码，体积大即更清晰；用 `Range: bytes=0-1` 只取 1 字节即可
     * 从 `Content-Range`/`Content-Length` 拿到总长。单路失败（超时/被拒）不影响其它清晰度。
     */
    private suspend fun probePlay(videoId: String, mediaHeaders: Map<String, String>): ResolvedMedia? {
        var best: ResolvedMedia? = null
        for (ratio in PLAY_RATIOS) {
            val url = "https://aweme.snssdk.com/aweme/v1/play/?video_id=$videoId&ratio=$ratio"
            val response = try {
                VideoHttp.request(url, headers = mediaHeaders + ("Range" to "bytes=0-1"))
            } catch (e: VideoParseException) {
                continue
            }
            if (response.status !in 200..299) continue
            val size = extractResponseSize(response.headers)
            if (size <= 0L) continue
            val candidate = ResolvedMedia(response.finalUrl, size, mediaHeaders)
            if (best == null || candidate.size > best.size) best = candidate
        }
        return best
    }

    private fun extractResponseSize(headers: Map<String, String>): Long {
        headerIgnoreCase(headers, "Content-Range")?.let { contentRange ->
            CONTENT_RANGE_TOTAL_REGEX.find(contentRange)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        }
        return headerIgnoreCase(headers, "Content-Length")?.trim()?.toLongOrNull() ?: 0L
    }

    private fun headerIgnoreCase(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** 媒体直链请求头：参考实现只带 iOS UA + Referer（不含 Cookie，直链是 CDN 静态文件） */
    private fun buildMediaHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to VideoHttp.UA_IPHONE,
        "Referer" to referer
    )

    // ------------------------------------------------------------------ ttwid

    private suspend fun ensureTtwid(): String {
        cachedTtwid?.let { return it }
        return registerTtwid().also { cachedTtwid = it }
    }

    /**
     * 注册匿名 ttwid。
     *
     * ★ 这是本解析器**唯一**不走 [VideoHttp.request] 的请求：ttwid 藏在响应的多条 `Set-Cookie`
     *   里，而 VideoHttp 把响应头拍平成「同名互相覆盖」的 Map，取不全所有 Set-Cookie。
     *   因此这里直接用底层 OkHttp 客户端读 `headers.values("Set-Cookie")`。
     */
    private suspend fun registerTtwid(): String = withContext(Dispatchers.IO) {
        val client = HttpClients.apiClient()
        val body = """{"region":"cn","aid":1768,"needFid":false,"service":"www.iesdouyin.com","union":true,"fid":""}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(TTWID_REGISTER_URL)
            .header("User-Agent", VideoHttp.UA_IPHONE)
            .header("Referer", IESDOUYIN_REFERER)
            .post(body)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw VideoParseException(VideoParseErrorKind.NETWORK, "ttwid 注册请求失败", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw VideoParseException(VideoParseErrorKind.UNKNOWN, "ttwid 注册失败（HTTP ${resp.code}）")
            }
            val registered = resp.headers.values("Set-Cookie").firstNotNullOfOrNull { extractTtwid(it) }
            val redirectUrl = try {
                JSONObject(resp.body?.string().orEmpty()).optString("redirect_url")
            } catch (e: Exception) {
                ""
            }
            // 参考实现会再请求一次 redirect_url 补全 union 注册；这里尽力而为，失败不影响本次解析
            val fromCallback = if (redirectUrl.isBlank()) null else try {
                callbackTtwid(client, redirectUrl)
            } catch (e: IOException) {
                null
            }
            fromCallback ?: registered
                ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "ttwid 注册未返回可用 Cookie")
        }
    }

    /** 跟随 register 返回的 `redirect_url` 再收一次 Set-Cookie（阻塞调用，仅在 IO 线程里用） */
    private fun callbackTtwid(client: OkHttpClient, url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", VideoHttp.UA_IPHONE)
            .header("Referer", IESDOUYIN_REFERER)
            .get()
            .build()
        return client.newBuilder().followRedirects(false).build().newCall(request).execute().use { resp ->
            resp.headers.values("Set-Cookie").firstNotNullOfOrNull { extractTtwid(it) }
        }
    }

    private fun extractTtwid(setCookie: String): String? =
        Regex("""ttwid=([^;]+)""").find(setCookie)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ 工具

    private fun firstUrl(node: JSONObject?): String? {
        val urlList = node?.optArrayAny("url_list", "urlList") ?: return null
        for (i in 0 until urlList.length()) {
            val url = urlList.optString(i)
            if (url.isNotBlank()) return url
        }
        return null
    }

    private fun videoFileName(id: String, title: String): String = "${safeTitle(title, "douyin_$id")}.mp4"

    private fun imageFileName(id: String, title: String, index: Int): String =
        "${safeTitle(title, "douyin_$id")}_$index.jpg"

    /** 清洗标题里的非法文件名字符并截断（清洗交给共享的 [DownloadPathPolicy]） */
    private fun safeTitle(title: String, fallback: String): String =
        DownloadPathPolicy.sanitizeName(title).take(80).ifBlank { fallback }
}

// ---------------------------------------------------------------------- JSON 取值
// 抖音分享页同一字段在不同端点上 casing 不一致（snake_case / camelCase 混用），
// 这里统一按「候选键」取值，避免因改版把整条解析打挂。

private fun JSONObject.optStringAny(vararg keys: String): String {
    for (key in keys) {
        if (isNull(key)) continue
        val value = optString(key, "")
        if (value.isNotBlank()) return value
    }
    return ""
}

private fun JSONObject.optLongAny(vararg keys: String): Long {
    for (key in keys) {
        if (!isNull(key)) return optLong(key, 0L)
    }
    return 0L
}

private fun JSONObject.optObjectAny(vararg keys: String): JSONObject? {
    for (key in keys) {
        if (isNull(key)) continue
        optJSONObject(key)?.let { return it }
    }
    return null
}

private fun JSONObject.optArrayAny(vararg keys: String): JSONArray? {
    for (key in keys) {
        if (isNull(key)) continue
        optJSONArray(key)?.let { return it }
    }
    return null
}
