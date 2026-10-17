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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pixiv 插画/漫画解析，产出每页的**原图直链**。
 *
 * 数据来源（两个公开 ajax 接口）：
 * 1. `ajax/illust/{id}` 取标题/作者/创建时间/作品类型；
 * 2. `ajax/illust/{id}/pages` 取每一页的 `urls.original`（原图）。
 *
 * ★ **匿名也能解析，但只能拿到一部分作品**（实测 2026-10）：游客身份请求上面两个接口，公开作品返回的
 *   `body` 里 `urls.original` 就是完整原图直链（`i.pximg.net/img-original/...`）；而 R18 / 受限作品
 *   以及其余部分作品**必须登录**才拿得到（服务端会返回 `error`，见 [parse]）。
 *   所以本解析器**匿名也会跑**（比直接拦掉友好），但平台仍标成「需要登录」—— 想拿全量就得登录。
 *   别在解析入口把空 Cookie 直接拦掉。
 *
 * ★ 已知限制（都是刻意不做，而非遗漏）：
 * - **动图（ugoira）不支持**：它是 zip 包 + 帧时长表，要合成 GIF/PDF 得靠图像处理。Android 侧本期不做，
 *   直接给出明确提示而不是产出一个打不开的文件；
 * - **小说不支持**（`novel/` 识别不到，也不在本期范围）；
 * - **不做任何内容过滤**：能否看到 R18 取决于账号在 Pixiv 侧的设置，接口不返回时这里也无从得知。
 *
 * ★ `i.pximg.net` 有防盗链：下载请求**必须**带 `Referer: https://www.pixiv.net/`，否则一律 403。
 */
object PixivParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.PIXIV

    private const val REFERER = "https://www.pixiv.net/"

    private fun apiHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", REFERER)
        put("Accept", "application/json")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** 原图 CDN 只校验 Referer；带上 Cookie 无害且对个别受限作品有帮助 */
    private fun mediaHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", REFERER)
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /**
     * 登录态校验：**行为探测**，不看 Cookie 名字。
     *
     * ★ 这里踩过一个坑（与小红书同款）：最初按「Cookie 里有 `PHPSESSID`」判定，但实测（2026-10）
     *   未登录访问 `https://www.pixiv.net/` 就会收到 `Set-Cookie: PHPSESSID=...`（游客会话令牌）。
     *   于是「打开登录页 → 服务端立刻种下游客 PHPSESSID → 被判定成已登录 → 自动保存并关闭登录页」，
     *   用户根本没机会登录 —— 现象就是「点了登录却没有登录页，账号页还显示已登录」。
     *
     * ★ 判定依据来自实测：未登录请求 `/dashboard/works`（我的作品）→ **302 到
     *   `http://www.pixiv.net/?return_to=%2Fdashboard%2Fworks`**（带 `return_to` 回跳参数）。
     *   注意**不是**跳 `accounts.pixiv.net`（那是登录页本身，别把它当判据）。
     *
     * ★ **必须 `followRedirects = false`**：上面那个 302 的目标是**明文 http**，而本项目
     *   `network_security_config` 全局禁止明文 —— 跟随它会被 OkHttp 直接抛
     *   `CLEARTEXT communication ... not permitted`，于是「探测失败就放行」的兜底会把游客判成已登录
     *   （表现就是「依然被游客令牌干扰」）。只看 302 的 `Location` 就够了，根本不需要跳过去。
     *
     * ★ 探测本身失败（网络不通 / 被墙 / 超时）时**一律不拦**（返回空串）：把有效 Cookie 判死的后果
     *   是用户永远存不上账号（保存入口被这个校验挡住），比放行严重得多 —— 真有问题会在解析那一步
     *   拿到服务端明确的报错（见 [parse] 的错误映射）。
     *
     * @return 昵称（Pixiv 网页端没有轻量接口可取，恒为空串 → 界面显示「已登录」）；未登录返回 null
     */
    override suspend fun validateCookie(cookie: String): String? {
        if (cookie.isBlank()) return null
        val response = runCatching {
            VideoHttp.request(
                url = "https://www.pixiv.net/dashboard/works",
                headers = apiHeaders(cookie),
                followRedirects = false
            )
        }.getOrNull() ?: return ""
        // 2xx = 正常返回了页面内容 = 已登录；3xx 才需要看跳到哪
        if (response.status !in 300..399) return ""
        val location = response.headers.entries
            .firstOrNull { it.key.equals("Location", ignoreCase = true) }
            ?.value
            .orEmpty()
        val loginWall = location.contains("return_to=") ||
            location.contains("/login") ||
            location.contains("accounts.pixiv.net")
        return if (loginWall) null else ""
    }

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val info = VideoHttp.getJson(
            "https://www.pixiv.net/ajax/illust/${link.id}",
            apiHeaders(cookie),
            "Pixiv 作品信息"
        )
        if (info.optBoolean("error")) {
            val message = info.optString("message")
            // 服务端用同一个 error 字段表达「作品不存在」「登录态失效」「受限作品」，
            // 按文案 + 「这次有没有带 Cookie」粗分一下，给可操作的提示而不是含糊的解析失败
            val needsLogin = message.contains("login", ignoreCase = true) ||
                message.contains("session", ignoreCase = true)
            val kind = when {
                message.contains("not found", ignoreCase = true) -> VideoParseErrorKind.NOT_FOUND
                needsLogin || cookie.isBlank() -> VideoParseErrorKind.UNSUPPORTED
                else -> VideoParseErrorKind.BLOCKED
            }
            val hint = when {
                needsLogin -> "Pixiv 登录态已失效，请到「账号」页重新粘贴 Cookie"
                kind == VideoParseErrorKind.UNSUPPORTED ->
                    "该作品需要登录后才能解析（多为 R18 或受限作品），请在「账号」页粘贴浏览器里的 Cookie"
                else -> "Pixiv 作品信息返回错误：$message"
            }
            throw VideoParseException(kind, hint)
        }

        val body = info.optJSONObject("body")
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "Pixiv 作品信息缺少 body")

        // illustType: 0 插画 / 1 漫画 / 2 动图（ugoira）
        if (body.optInt("illustType") == 2) {
            throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                "Pixiv 动图（ugoira）需要合成 GIF，当前版本暂不支持"
            )
        }

        val pages = VideoHttp.getJson(
            "https://www.pixiv.net/ajax/illust/${link.id}/pages",
            apiHeaders(cookie),
            "Pixiv 原图列表"
        ).optJSONArray("body")

        val title = body.optString("title")
        val fileBase = DownloadPathPolicy.sanitizeName(title).take(80).ifBlank { "pixiv_${link.id}" }

        val medias = buildList {
            if (pages != null) {
                for (i in 0 until pages.length()) {
                    val original = pages.optJSONObject(i)?.optJSONObject("urls")?.optString("original").orEmpty()
                    if (original.isBlank()) continue
                    add(
                        VideoMedia(
                            url = original,
                            kind = VideoMediaKind.IMAGE,
                            fileName = "${fileBase}_${i + 1}.${extensionOf(original)}",
                            headers = mediaHeaders(cookie),
                            quality = "原图"
                        )
                    )
                }
            }
            // 兜底：pages 拿不到时用作品信息里的首图（至少能下一张，而不是整条失败）
            if (isEmpty()) {
                val original = body.optJSONObject("urls")?.optString("original").orEmpty()
                if (original.isNotBlank()) {
                    add(
                        VideoMedia(
                            url = original,
                            kind = VideoMediaKind.IMAGE,
                            fileName = "$fileBase.${extensionOf(original)}",
                            headers = mediaHeaders(cookie),
                            quality = "原图"
                        )
                    )
                }
            }
        }

        if (medias.isEmpty()) {
            throw VideoParseException(VideoParseErrorKind.NOT_FOUND, "Pixiv 未返回任何可下载的原图")
        }

        return VideoParseResult(
            platform = platform,
            title = title,
            author = body.optString("userName"),
            cover = medias.first().url,
            text = body.optString("description"),
            timestampSeconds = parseCreateDate(body.optString("createDate")),
            url = "https://www.pixiv.net/artworks/${link.id}",
            medias = medias
        )
    }

    /** `2024-01-01T12:00:00+09:00` 这种带时区的 ISO 时间 → 秒级时间戳 */
    private fun parseCreateDate(text: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(text)?.time?.div(1000) ?: 0L
    }.getOrDefault(0L)

    /** 从原图地址里取扩展名（Pixiv 有 jpg / png / gif，不能一律当 jpg） */
    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "")
        return if (ext.isNotBlank() && ext.length <= 4) ext.lowercase() else "jpg"
    }
}
