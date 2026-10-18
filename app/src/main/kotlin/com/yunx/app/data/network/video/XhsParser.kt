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

import android.util.Log
import com.yunx.app.data.download.DownloadPathPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小红书笔记解析器（移植自参考实现 `maibot-platform-parser/core/parsers/xhs.py`）。
 *
 * 不走私有 API，直接 GET 分享页，再从 HTML 内嵌的 `window.__INITIAL_STATE__` 里取数据。
 *
 * ★ **需要登录**：实测（2026-10）小红书网页端的笔记页对未登录会话一律拦截 —— explore 路径 302 到
 *   `/login`、discovery 路径 302 到 `/website-login/captcha`，两种页面里都没有 `__INITIAL_STATE__`。
 *   所以 `VideoPlatform.XHS` 标了 `loginUrl`，解析时用账号页保存的 Cookie（`web_session`）。
 *
 * ★ 分享链接里的 `xsec_token` **必须原样带上**：小红书按请求校验这个令牌，缺了或过期都会落到
 *   登录页（参考实现的正则也要求链接必须带查询串）。这里从 [DetectedVideoLink.url] 里取出来，
 *   重建两条路径的地址时都带上。
 *
 * ★ 分享页有两种形态，内嵌 JSON 的结构**完全不同**：
 *   - `/explore/<id>`：`note.noteDetailMap[<id>].note`（无时间戳字段，图片用 `urlDefault`，作者名用 `nickname`）；
 *   - `/discovery/item/<id>`：`noteData.data.noteData`（有 `time`，图片用 `url`（带水印），作者名用 `nickName`），
 *     另有 `noteData.normalNotePreloadData.imagesList`（无水印但只有一张，参考实现只拿它当视频封面）。
 *   参考实现是先请求 explore、失败再落到 discovery；这里保持同样的顺序与各自的请求头。
 */
object XhsParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.XHS

    private const val TAG = "YunX"

    /** 分享页请求头（参考实现 xhs 的 `headers`，桌面 UA 才会下发完整的 SSR 数据） */
    private const val ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8," +
            "application/signed-exchange;v=b3;q=0.7"

    private const val XHS_HOME = "https://www.xiaohongshu.com"

    /** 登录态探测地址：未登录访问首页会被 302 到 /login（与解析时同一道门，口径一致） */
    private const val EXPLORE_PROBE_URL = "$XHS_HOME/explore"

    private const val REFERER = "$XHS_HOME/"

    /** 最终地址命中这些片段 = 被登录墙/风控拦下（不是作品被删） */
    private val LOGIN_WALL_MARKERS = listOf("/login", "website-login", "captcha")

    /** 清晰度优先级：h265 无水印，h264 有水印（参考实现的取值顺序即此，注释见 xhs.py） */
    private val streamOrder = listOf(
        "h265" to "H.265",
        "h264" to "H.264",
        "av1" to "AV1",
        "h266" to "H.266"
    )

    /**
     * 登录态校验：**行为探测**，不按 Cookie 名字判断。
     *
     * ★ 这里踩过一个坑：最初按「Cookie 里有 `web_session=`」判定，结果**没登录也被认成已登录**。
     *   原因是小红书网页端在 JS 启动后会自己种一个**游客态的 `web_session`** ——
     *   curl 直接请求登录页只下发 `acw_tc`/`abRequestId`，但 WebView 里跑完 JS 就多出 `web_session`。
     *   也就是说 `web_session` 只能说明「浏览器有过会话」，不能说明「用户已登录」。
     *
     * ★ 因此改成和解析同口径的探测：带 Cookie 请求首页 `/explore` ——
     *   未登录会被 302 到 `/login`（实测），登录态则正常返回内容页。
     *   （`/api/sns/web/v2/user/me` 这类「我是谁」接口已被网关拦下，返回 create invoker failed，用不了。）
     *
     * @return 昵称（Web 端没有稳定接口可取，这里恒为空串 → 界面显示「已登录」）；未登录返回 null
     */
    override suspend fun validateCookie(cookie: String): String? {
        if (cookie.isBlank()) return null
        val response = runCatching {
            VideoHttp.request(EXPLORE_PROBE_URL, headers = exploreHeaders(cookie))
        }.getOrElse { error ->
            Log.w(TAG, "小红书登录态探测失败（按未登录处理）：${error.message}")
            return null
        }
        if (LOGIN_WALL_MARKERS.any { response.finalUrl.contains(it) }) return null
        return ""
    }

    /**
     * 解析入口：照搬参考实现的两条路径 —— **先 explore（桌面 UA）、失败再 discovery（iOS UA + XHR 头）**。
     *
     * ★ 为什么两条都要试：小红书对两条路径的风控策略不同（实测未登录时一条跳 /login、另一条跳
     *   /website-login/captcha），带登录态后可能出现「这条出数据、那条仍被拦」的情况。
     * ★ 「要求登录」不再重试第二条：那是同一道门，换路径也过不去，直接给出可操作的提示。
     */
    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val explore = tryOf { parseExplorePage(link, cookie) }
        explore.getOrNull()?.let { return it }
        val exploreError = explore.exceptionOrNull()
        if (exploreError is VideoParseException && exploreError.kind == VideoParseErrorKind.UNSUPPORTED) {
            throw exploreError
        }

        val discovery = tryOf { parseDiscoveryPage(link, cookie) }
        discovery.getOrNull()?.let { return it }
        val discoveryError = discovery.exceptionOrNull()
        if (discoveryError is VideoParseException && discoveryError.kind == VideoParseErrorKind.UNSUPPORTED) {
            throw discoveryError
        }
        throw discoveryError as? VideoParseException
            ?: exploreError as? VideoParseException
            ?: VideoParseException(VideoParseErrorKind.UNKNOWN, "小红书解析失败", discoveryError ?: exploreError)
    }

    /** explore 形态：重建 `/explore/<id>?<原查询串>`（参考实现 parse_explore，用桌面 UA） */
    private suspend fun parseExplorePage(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val html = fetchPage("$XHS_HOME/explore/${link.id}${queryOf(link)}", exploreHeaders(cookie))
        val state = extractInitialState(html)
        val note = state.optJSONObject("note")
            ?.optJSONObject("noteDetailMap")
            ?.optJSONObject(link.id)
            ?.optJSONObject("note")
            ?: throw VideoParseException(VideoParseErrorKind.NOT_FOUND, "小红书页面里没有该笔记数据")
        return buildFromExplore(note, link, cookie)
    }

    /** discovery 形态：重建 `/discovery/item/<id>?<原查询串>`（参考实现 parse_discovery，用 iOS UA + XHR 头） */
    private suspend fun parseDiscoveryPage(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val html = fetchPage("$XHS_HOME/discovery/item/${link.id}${queryOf(link)}", discoveryHeaders(cookie))
        return buildFromDiscovery(extractInitialState(html), link, cookie)
    }

    /**
     * 取分享页并**先把「被登录墙拦住」识别出来**。
     *
     * ★ 这一步是这次修复的关键：未登录时小红书会把请求 302 到 `/login` 或 `/website-login/captcha`，
     *   页面里没有 `__INITIAL_STATE__` —— 老实现会把这种情况报成「内容不存在或已删除」，把用户
     *   引到完全错误的方向（以为作品被删了）。现在按最终地址判断，给出「请先登录小红书」。
     */
    private suspend fun fetchPage(url: String, headers: Map<String, String>): String {
        val response = VideoHttp.request(url, headers = headers)
        VideoHttp.requireSuccess(response, "小红书笔记")
        val finalUrl = response.finalUrl
        if (LOGIN_WALL_MARKERS.any { finalUrl.contains(it) }) {
            throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                "小红书要求登录后才能查看笔记内容，请先到「账号」页登录小红书"
            )
        }
        return response.body
    }

    /**
     * 抠出 `window.__INITIAL_STATE__` 并解析成 JSON。
     *
     * ★ 关键坑：这段 JSON 里混有裸的 `undefined`（JS 字面量，不是合法 JSON），直接丢给 `JSONObject`
     *   会抛 `JSONException`。参考实现的做法是先把 `undefined` 整体替换成 `null` 再解析，这里照做。
     */
    private fun extractInitialState(html: String): JSONObject {
        val raw = VideoHttp.extractInlineJson(html, "window.__INITIAL_STATE__")
            ?: throw VideoParseException(
                VideoParseErrorKind.UNKNOWN,
                "小红书页面里没有笔记数据（可能被风控拦截或页面改版）"
            )
        return runCatching { JSONObject(raw.replace("undefined", "null")) }.getOrElse {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "小红书页面数据解析失败", it)
        }
    }

    /** 原分享链接的查询串（含 `xsec_token`）：小红书**必须**带上它，否则笔记页会被登录墙拦下 */
    private fun queryOf(link: DetectedVideoLink): String =
        link.url.substringAfter('?', "").takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()

    /** explore 路径的请求头（参考实现用桌面 `headers` + accept） */
    private fun exploreHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("accept", ACCEPT)
        put("Referer", REFERER)
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** discovery 路径的请求头（参考实现用 `ios_headers` + origin / XHR / sec-fetch-*） */
    private fun discoveryHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_IPHONE)
        put("accept", ACCEPT)
        put("Referer", REFERER)
        put("origin", XHS_HOME)
        put("x-requested-with", "XMLHttpRequest")
        put("sec-fetch-site", "same-origin")
        put("sec-fetch-mode", "cors")
        put("sec-fetch-dest", "empty")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** 把异常收成 [Result]，但**协程取消必须原样抛出**（否则取消会被吞成一次「解析失败」） */
    private inline fun <T> tryOf(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun buildFromExplore(
        note: JSONObject,
        link: DetectedVideoLink,
        cookie: String
    ): VideoParseResult {
        val mediaHeaders = mediaHeaders(cookie)
        val title = note.str("title")
        val base = fileBase(title, link)

        // explore 的图片列表字段是 urlDefault（原图）
        val images = imageUrls(note.optJSONArray("imageList")) { it.str("urlDefault").ifBlank { it.str("url") } }
        val video = pickVideo(note)

        val medias = mutableListOf<VideoMedia>()
        var cover = ""
        if (video != null) {
            // 参考实现用首图当视频封面
            cover = images.firstOrNull().orEmpty()
            medias += VideoMedia(
                url = video.url,
                kind = VideoMediaKind.VIDEO,
                fileName = "$base.mp4",
                headers = mediaHeaders,
                quality = video.quality
            )
        } else if (isVideoNote(note)) {
            // ★ 是视频笔记却没取到直链：**明确报错，不要退回图片** —— 把封面图当成作品图返回
            //   比报错更误导（用户会以为这条笔记本来就只解析出图）。
            throw VideoParseException(
                VideoParseErrorKind.UNKNOWN,
                "小红书视频笔记没有取到视频直链（笔记里有 video 字段，但 media.stream 里没有可用地址）"
            )
        } else {
            images.forEachIndexed { index, url ->
                medias += VideoMedia(
                    url = url,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "${base}_${index + 1}.jpg",
                    headers = mediaHeaders,
                    quality = "原图"
                )
            }
        }

        return VideoParseResult(
            platform = platform,
            title = title,
            author = note.optJSONObject("user")?.str("nickname").orEmpty(),
            avatar = note.optJSONObject("user")?.str("avatar").orEmpty(),
            cover = cover,
            text = note.str("desc"),
            // note.time 是毫秒
            timestampSeconds = note.optLong("time", 0L) / 1000,
            url = link.url,
            medias = medias
        )
    }

    private fun buildFromDiscovery(
        state: JSONObject,
        link: DetectedVideoLink,
        cookie: String
    ): VideoParseResult {
        val noteRoot = state.optJSONObject("noteData")
            ?: throw VideoParseException(VideoParseErrorKind.NOT_FOUND, "小红书分享链接失效或内容已删除")
        // discovery 的数据比 explore 多嵌一层：noteData.data.noteData
        val note = noteRoot.optJSONObject("data")?.optJSONObject("noteData")
            ?: throw VideoParseException(VideoParseErrorKind.NOT_FOUND, "小红书页面里没有笔记数据（内容可能已删除）")

        val mediaHeaders = mediaHeaders(cookie)
        val title = note.str("title")
        val base = fileBase(title, link)

        // 笔记图带水印；preload 的图无水印但只有一张，参考实现只拿它当视频封面
        val noteImages = imageUrls(note.optJSONArray("imageList")) { it.str("url") }
        val preloadImages = imageUrls(noteRoot.optJSONObject("normalNotePreloadData")?.optJSONArray("imagesList")) {
            it.str("urlSizeLarge").ifBlank { it.str("url") }
        }

        val video = pickVideo(note)

        val medias = mutableListOf<VideoMedia>()
        var cover = ""
        if (video != null) {
            cover = (preloadImages.ifEmpty { noteImages }).firstOrNull().orEmpty()
            medias += VideoMedia(
                url = video.url,
                kind = VideoMediaKind.VIDEO,
                fileName = "$base.mp4",
                headers = mediaHeaders,
                quality = video.quality
            )
        } else {
            noteImages.forEachIndexed { index, url ->
                medias += VideoMedia(
                    url = url,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "${base}_${index + 1}.jpg",
                    headers = mediaHeaders,
                    quality = "原图"
                )
            }
        }

        return VideoParseResult(
            platform = platform,
            title = title,
            // discovery 形态的昵称字段是大写 N 的 nickName
            author = note.optJSONObject("user")?.str("nickName").orEmpty(),
            avatar = note.optJSONObject("user")?.str("avatar").orEmpty(),
            cover = cover,
            text = note.str("desc"),
            timestampSeconds = note.optLong("time", 0L) / 1000,
            url = link.url,
            medias = medias
        )
    }

    private data class VideoPick(val url: String, val quality: String)

    /**
     * 这条笔记是不是**视频笔记**。
     *
     * ★ 判据是「有没有 `video` 对象」，**不是** `type == "video"`：`type` 在不同路径/版本下取值不一致，
     *   拿它当闸门一旦判错就会静默退化成只解析封面图（用户看到的就是「只解析了图片、没有视频」）。
     */
    private fun isVideoNote(note: JSONObject): Boolean = note.optJSONObject("video") != null

    /** 取视频直链：`<note>.video.media.stream` 按 h265 → h264 → av1 → h266 取第一条 masterUrl */
    private fun pickVideo(note: JSONObject): VideoPick? {
        val stream = note.optJSONObject("video")?.optJSONObject("media")?.optJSONObject("stream") ?: return null
        for ((key, label) in streamOrder) {
            val first = stream.optJSONArray(key)?.optJSONObject(0) ?: continue
            // 字段名历史上出现过两种写法，都认
            val url = first.str("masterUrl").ifBlank { first.str("master_url") }
            if (url.isNotBlank()) return VideoPick(url, label)
        }
        return null
    }

    private fun imageUrls(array: JSONArray?, pick: (JSONObject) -> String): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val url = pick(obj)
            if (url.isNotBlank()) result += url
        }
        return result
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
