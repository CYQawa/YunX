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

/**
 * 哔哩哔哩视频解析：**单文件低清**（`fnval=1` 的 durl 直链）。
 *
 * 数据来源（都是公开的 web 接口，未登录也能用）：
 * 1. `x/web-interface/view` 取 bvid/aid/cid/标题/作者/封面/时长；
 * 2. `x/player/playurl?fnval=1&platform=html5&high_quality=1` 取**单文件** `durl[0]`。
 *
 * ★ 为什么刻意用 `fnval=1` 而不是默认的 DASH：DASH 是音视频**分离**的两路流，需要 ffmpeg 或
 *   自研 mux 才能合并成一个可播放文件，Android 侧没有这个能力（参考实现靠 ffmpeg）。
 *   `fnval=1` 换来的是「能直接下载播放」的单个 mp4/flv，代价是清晰度偏低（未登录尤其明显）。
 *   DASH 合并留作 TODO：等有了 Android 侧的 mux 方案再开高清。
 *
 * ★ 已知限制：
 * - 只解析**第一个分P**（多 P 视频后续可按 `pages[]` 展开）；
 * - 番剧/课程（`bangumi/play`、`cheese/play`）不在识别范围内，需要完全不同的接口与登录态；
 * - 若 B 站把 `playurl` 也改成必须 WBI 签名，此接口需要换成带签名版本（见 Agent.md 的 TODO）。
 */
object BilibiliParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.BILIBILI

    private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
    private const val PLAY_URL = "https://api.bilibili.com/x/player/playurl"
    private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"

    /** 页面/接口请求头：Referer 必须是 bilibili.com，否则会被风控挡 */
    private fun apiHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", "https://www.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /**
     * 下载直链的请求头。
     *
     * ★ 必须带 `Referer`：B 站 CDN（upos-*.bilivideo.com）会校验来源，缺了直接 403；
     *   带 Cookie 则是为了让登录用户拿到更高清晰度（未登录会退到最低档）。
     */
    private fun mediaHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", "https://www.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /**
     * 登录态校验：`nav` 接口顺带返回用户名，可以直接作为昵称存下来。
     *
     * ★ `isLogin` 是 JSON 布尔值（`"isLogin":true`），必须用 `optBoolean` 读：
     *   Android 的 org.json 不会把 Boolean 转成 int，`optInt("isLogin")` 恒得 0，
     *   会让所有合法 Cookie 都被判「校验不通过」（网页登录与手动填写一起失败）。
     */
    override suspend fun validateCookie(cookie: String): String? {
        if (cookie.isBlank()) return null
        val json = runCatching { VideoHttp.getJson(NAV_URL, apiHeaders(cookie), "B站登录态校验") }.getOrNull()
            ?: return null
        val data = json.optJSONObject("data") ?: return null
        if (!data.optBoolean("isLogin")) return null
        return data.optString("uname")
    }

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val viewUrl = if (link.id.startsWith("BV", ignoreCase = true)) {
            "$VIEW_URL?bvid=${link.id}"
        } else {
            "$VIEW_URL?aid=${link.id.removePrefix("av")}"
        }
        val view = VideoHttp.getJson(viewUrl, apiHeaders(cookie), "B站视频信息")
        if (view.optInt("code") != 0) {
            throw VideoParseException(
                VideoParseErrorKind.NOT_FOUND,
                "B站视频信息接口返回 code=${view.optInt("code")} msg=${view.optString("message")}"
            )
        }
        val data = view.optJSONObject("data")
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "B站视频信息缺少 data 字段")

        val bvid = data.optString("bvid")
        val cid = data.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid") ?: data.optLong("cid")
        if (bvid.isBlank() || cid <= 0L) {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "B站视频信息缺少 bvid/cid")
        }

        val title = data.optString("title")
        val fileBase = fileNameBase(title, "bilibili_$bvid")

        // ① 已登录才试 DASH 高清：音视频分离的两路流，下载时由 DashMuxer 合轨。
        //    ★ 未登录不试：匿名拿到的 DASH 往往也是最低清，不值得为它引入更长、更易失败的合轨链路。
        if (cookie.isNotBlank()) {
            dashQuality(bvid, cid, cookie)?.let { dash ->
                return buildResult(
                    data = data,
                    bvid = bvid,
                    media = VideoMedia(
                        url = dash.videoUrl,
                        kind = VideoMediaKind.VIDEO,
                        fileName = "$fileBase.mp4",
                        headers = mediaHeaders(cookie),
                        quality = "${qualityLabel(dash.quality)}（分轨，下载后合轨）",
                        audioUrl = dash.audioUrl,
                        audioHeaders = mediaHeaders(cookie)
                    )
                )
            }
        }

        // ② 退回单文件 durl（未登录 / DASH 不可用）：能直接下载播放，代价是清晰度偏低
        val play = VideoHttp.getJson(
            "$PLAY_URL?bvid=$bvid&cid=$cid&qn=80&fnval=1&fnver=0&fourk=1&platform=html5&high_quality=1",
            apiHeaders(cookie),
            "B站取直链"
        )
        val playData = play.optJSONObject("data")
        val durl = playData?.optJSONArray("durl")
        if (durl == null || durl.length() == 0) {
            throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                "B站既未返回单文件直链、也未返回可合轨的分轨流" +
                    "（code=${play.optInt("code")} msg=${play.optString("message")}）：该视频可能是番剧/付费内容"
            )
        }
        val first = durl.optJSONObject(0)
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "B站 durl 为空")
        val url = first.optString("url")
        if (url.isBlank()) {
            throw VideoParseException(VideoParseErrorKind.BLOCKED, "B站直链为空（可能需要登录或已被风控）")
        }

        return buildResult(
            data = data,
            bvid = bvid,
            media = VideoMedia(
                url = httpsUrl(url),
                kind = VideoMediaKind.VIDEO,
                fileName = "$fileBase.mp4",
                size = first.optLong("size", -1L),
                headers = mediaHeaders(cookie),
                quality = qualityLabel(playData.optInt("quality"))
            )
        )
    }

    /** 组装解析结果（DASH 与单文件两条路径共用，避免字段各写一遍） */
    private fun buildResult(data: JSONObject, bvid: String, media: VideoMedia): VideoParseResult =
        VideoParseResult(
            platform = platform,
            title = data.optString("title"),
            author = data.optJSONObject("owner")?.optString("name").orEmpty(),
            avatar = data.optJSONObject("owner")?.optString("face").orEmpty(),
            cover = httpsUrl(data.optString("pic")),
            text = data.optString("desc"),
            timestampSeconds = data.optLong("pubdate"),
            url = "https://www.bilibili.com/video/$bvid",
            medias = listOf(media)
        )

    /** DASH 分轨直链（视频轨 + 音轨 + 清晰度编号） */
    private data class DashStream(val videoUrl: String, val audioUrl: String, val quality: Int)

    /**
     * 取 DASH 分轨直链：视频轨取清晰度最高的一路、音轨取码率最高的一路。
     *
     * ★ `fnval=4048` 是「DASH + HDR + 4K + 杜比」的组合位；`qn=120` 表示「能给多高给多高」，
     *   实际返回哪几档由账号权限与版权决定。任何一步不对（没有 dash 字段、两轨缺一）
     *   都返回 null，由调用方退回单文件路径 —— 宁可低清可用，也不要在这里抛错。
     */
    private suspend fun dashQuality(bvid: String, cid: Long, cookie: String): DashStream? {
        val json = runCatching {
            VideoHttp.getJson(
                "$PLAY_URL?bvid=$bvid&cid=$cid&qn=120&fnval=4048&fnver=0&fourk=1",
                apiHeaders(cookie),
                "B站取 DASH 直链"
            )
        }.getOrNull() ?: return null
        val dash = json.optJSONObject("data")?.optJSONObject("dash") ?: return null
        val videos = dash.optJSONArray("video") ?: return null
        val audios = dash.optJSONArray("audio") ?: return null

        // 数组通常已按清晰度降序，但显式比较更稳（不同端返回顺序不一致过）
        val bestVideo = (0 until videos.length())
            .mapNotNull { videos.optJSONObject(it) }
            .maxByOrNull { it.optInt("id") } ?: return null
        val bestAudio = (0 until audios.length())
            .mapNotNull { audios.optJSONObject(it) }
            .maxByOrNull { it.optInt("id") } ?: return null

        // 直链字段名有两种（baseUrl / base_url），且可能被下发成 JSON null（optString 会得到 "null"）
        val videoUrl = bestVideo.dashUrl() ?: return null
        val audioUrl = bestAudio.dashUrl() ?: return null
        return DashStream(videoUrl = videoUrl, audioUrl = audioUrl, quality = bestVideo.optInt("id"))
    }

    private fun JSONObject.dashUrl(): String? =
        (optString("baseUrl").takeIf { it.isNotBlank() } ?: optString("base_url"))
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { httpsUrl(it) }

    /** B站清晰度编号 → 展示文案（只列常见的几档，其余原样显示编号） */
    private fun qualityLabel(qn: Int): String = when (qn) {
        6, 7 -> "240P"
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        74 -> "720P60"
        80 -> "1080P"
        112 -> "1080P+"
        116 -> "1080P60"
        120 -> "4K"
        else -> if (qn > 0) "画质 $qn" else ""
    }

    /** 接口偶尔给 `//` 开头的协议相对地址 */
    private fun httpsUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    /** 文件名：标题清洗后截断，标题为空时用兜底名 */
    private fun fileNameBase(title: String, fallback: String): String {
        val cleaned = DownloadPathPolicy.sanitizeName(title).take(80)
        return cleaned.ifBlank { fallback }
    }
}
