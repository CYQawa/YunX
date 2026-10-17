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
import com.yunx.app.util.LogRedactor
import org.json.JSONArray
import org.json.JSONObject

/**
 * 快手解析器（视频 + 图集）。
 *
 * - **平台**：快手（`VideoPlatform.KUAISHOU`）。
 * - **数据来源手法**：请求作品页 SSR，从 HTML 里抠出 `window.INIT_STATE`；它是一张
 *   「作品 id → 作品数据」的映射，字段语义见参考实现 `kuaishou.py` 的 `Photo`/`Atlas`。
 * - **是否需要登录**：不需要；带上账号 Cookie 更稳，所以 [parse] 的 `cookie` 非空时会一并带上。
 * - **产出形态**：视频命中 → 单条 [VideoMediaKind.VIDEO]（mp4 单文件直链）；图集命中 → 多条
 *   [VideoMediaKind.IMAGE]（由图集 CDN + 路由拼出）。二者可同时存在。
 *
 * 已知限制：完全依赖 `window.INIT_STATE` 的结构，快手改版即失效；CDN 直链有时效性，
 * 解析后应尽快下载。
 */
object KuaishouParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.KUAISHOU

    private const val TAG = "YunX"

    private const val DEFAULT_REFERER = "https://v.kuaishou.com/"

    /**
     * 取作品页的真实地址：跟随一次重定向（拿不到 Location 就退回原地址）。
     *
     * 三种链接形态里只有 `v.m.chenzhongtech.com/fw/photo/<id>` 本身就是作品页，另外两种
     * （`v.kuaishou.com/<码>`、`www.kuaishou.com/short-video|f/<码>`）都是入口页，必须先跳一次。
     */
    private suspend fun resolvePageUrl(url: String, headers: Map<String, String>): String {
        val normalized = if (url.startsWith("http")) url else "https://$url"
        return VideoHttp.expandRedirect(normalized, headers)?.takeIf { it.isNotBlank() } ?: normalized
    }

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        // ★ UA 与 Referer 必须与参考实现一致（iOS UA + v.kuaishou.com）：拿桌面 UA 去请求时，
        //   快手返回的是「桌面 SPA 页面」，里面**根本没有** window.INIT_STATE，
        //   表现为「作品可能已删除或页面改版」这种误导性报错。
        val headers = buildMap {
            put("User-Agent", VideoHttp.UA_IPHONE)
            put("Referer", DEFAULT_REFERER)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        // ★ 先跟随一次重定向再请求真实作品页：`www.kuaishou.com/short-video/<id>` 与
        //   `www.kuaishou.com/f/<分享码>` 都是**入口页**，移动端作品页（v.m.chenzhongtech.com/fw/photo/…）
        //   才是带 SSR 数据的那一页。参考实现对三种链接形态也都是先 get_redirect_url 再请求。
        // 长视频页（/fw/long-video/）返回结构不同，参考实现统一改按 /fw/photo/ 请求
        val pageUrl = resolvePageUrl(link.url, headers).replace("/fw/long-video/", "/fw/photo/")

        val response = VideoHttp.request(pageUrl, headers = headers)
        VideoHttp.requireSuccess(response, "快手作品页")
        val initJson = VideoHttp.extractInlineJson(response.body, "window.INIT_STATE")
            ?: run {
                // 这类失败几乎都来自「拿到了非作品页」（入口页 / 验证页 / 改版），
                // 记下真实请求地址与响应长度，便于下次定位，而不是只留一句“页面改版”
                Log.w(
                    TAG,
                    "快手未找到 window.INIT_STATE：url=${LogRedactor.url(pageUrl)} " +
                        "status=${response.status} bodyLen=${response.body.length}"
                )
                throw VideoParseException(
                    VideoParseErrorKind.UNKNOWN,
                    "快手页面里没有 window.INIT_STATE（作品可能已删除或页面改版）"
                )
            }
        val photo = findPhoto(initJson)

        val title = jsonString(photo, "caption")
        val videoUrl = firstUrl(photo.optJSONArray("mainMvUrls"))
        val coverUrl = firstUrl(photo.optJSONArray("coverUrls")).orEmpty()
        val imageUrls = atlasImageUrls(photo.optJSONObject("ext_params")?.optJSONObject("atlas"))

        val medias = mutableListOf<VideoMedia>()
        if (videoUrl != null) {
            medias += VideoMedia(
                url = videoUrl,
                kind = VideoMediaKind.VIDEO,
                fileName = videoFileName(link.id, title),
                headers = headers
            )
        }
        imageUrls.forEachIndexed { index, url ->
            medias += VideoMedia(
                url = url,
                kind = VideoMediaKind.IMAGE,
                fileName = imageFileName(link.id, title, index + 1),
                headers = headers
            )
        }

        return VideoParseResult(
            platform = platform,
            title = title,
            // 昵称里常见的 U+3164 是占位空白字符，参考实现也会先剥掉
            author = jsonString(photo, "userName").replace("\u3164", "").trim(),
            avatar = jsonString(photo, "headUrl"),
            cover = coverUrl,
            timestampSeconds = photo.optLong("timestamp", 0L) / 1000,
            url = link.url,
            medias = medias
        )
    }

    /**
     * 从 `window.INIT_STATE` 里取出第一个含作品数据的 value。
     *
     * 参考实现把这段 JSON 解码成 `dict[str, TusjohData]`（以作品 id 为 key），再取**第一个
     * `photo` 非空**的 value；org.json 的 [JSONObject] 保持插入顺序，所以这里同样遍历所有 key、
     * 返回第一个 `photo` 非空的对象，无需按 id 精确定位（链接里虽然有 id，但 value 的 key
     * 并不总是那个 id）。
     */
    private fun findPhoto(initJson: String): JSONObject {
        val initState = try {
            JSONObject(initJson)
        } catch (e: Exception) {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "window.INIT_STATE 不是合法 JSON", e)
        }
        for (key in initState.keys()) {
            val photo = initState.optJSONObject(key)?.optJSONObject("photo")
            if (photo != null) return photo
        }
        throw VideoParseException(VideoParseErrorKind.UNKNOWN, "window.INIT_STATE 里没有作品数据")
    }

    /**
     * 图集直链：由 CDN 域名 + 路由拼出（参考实现 `Atlas.img_urls`）。
     *
     * 参考实现对 `cdnList` 随机取一条，这里固定取第一条以保证结果可复现。
     */
    private fun atlasImageUrls(atlas: JSONObject?): List<String> {
        atlas ?: return emptyList()
        val cdn = atlas.optJSONArray("cdnList")?.optJSONObject(0)?.let { jsonString(it, "cdn") }.orEmpty()
        val routes = atlas.optJSONArray("list") ?: return emptyList()
        if (cdn.isBlank()) return emptyList()
        return (0 until routes.length())
            .map { routes.optString(it) }
            .filter { it.isNotBlank() }
            .map { route -> "https://$cdn/$route" }
    }

    /** 从 CdnUrl 数组里取第一条有 url 的（参考实现随机取一条，这里固定第一条） */
    private fun firstUrl(nodes: JSONArray?): String? {
        if (nodes == null) return null
        for (i in 0 until nodes.length()) {
            val url = nodes.optJSONObject(i)?.let { jsonString(it, "url") }.orEmpty()
            if (url.isNotBlank()) return url
        }
        return null
    }

    /** 兜底名里的 id：短链一路跳过来时 [DetectedVideoLink.id] 可能为空，用时间戳避免重名 */
    private fun fallbackName(id: String): String =
        "kuaishou_${id.ifBlank { System.currentTimeMillis().toString(36) }}"

    private fun videoFileName(id: String, title: String): String = "${safeTitle(title, fallbackName(id))}.mp4"

    private fun imageFileName(id: String, title: String, index: Int): String =
        "${safeTitle(title, fallbackName(id))}_$index.jpg"

    /** 清洗标题里的非法文件名字符并截断（清洗交给共享的 [DownloadPathPolicy]） */
    private fun safeTitle(title: String, fallback: String): String =
        DownloadPathPolicy.sanitizeName(title).take(80).ifBlank { fallback }

    /** 与参考实现的 Optional 字段对齐：缺失或 JSON `null` 一律当成空串 */
    private fun jsonString(node: JSONObject, key: String): String =
        if (node.isNull(key)) "" else node.optString(key, "")
}
