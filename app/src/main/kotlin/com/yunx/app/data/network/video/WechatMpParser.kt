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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 微信：**公众号文章**与**媒体直链**。
 *
 * 三条入口（由 [VideoLinkParser] 的 kind 区分）：
 * 1. `article` —— `mp.weixin.qq.com/s/...` 文章页：抓 HTML，取正文图片（`data-src`）、封面（`msg_cdn_url`）与
 *    文章内嵌视频；图片一律升级成 `/0` 原图（**公众号图片本身没有水印**，`/640` 只是缩略规格）。
 * 2. `direct` —— `finder.video.qq.com/.../stodownload?...&sign=...`（视频号签名直链）、`mpvideo.qpic.cn`、
 *    `mmbiz.qpic.cn`：**不需要任何登录态**，带 UA/Referer 直接入队下载（2026-11 实测：不带 Cookie 也 200）。
 * 3. `sph` —— `weixin.qq.com/sph/...` 视频号分享链接：**拿不到视频**。
 *    预览页（`channels.weixin.qq.com/finder-preview/pages/sph`）只对外给封面图，视频直链要靠内部接口 +
 *    `sign` 签名；实测「扫码登录视频号助手」后再调预览接口，返回里仍然只有封面（无 `20302`、无 `sign`、
 *    无 `decodeKey`）。所以这里如实报错，让用户改用直链，而不是假装解析成功。
 */
object WechatMpParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.WECHAT_MP

    /** 微信对非浏览器 UA 更严格；移动 Chrome UA 实测能直接拿到文章正文 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val articleHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to "https://mp.weixin.qq.com/"
    )

    /** 图片/视频直链要带的头：公众号图床与视频号 CDN 都按 Referer 判来源 */
    private val mpMediaHeaders = mapOf("User-Agent" to UA, "Referer" to "https://mp.weixin.qq.com/")
    private val finderMediaHeaders = mapOf("User-Agent" to UA, "Referer" to "https://channels.weixin.qq.com/")

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult = when (link.kind) {
        "article" -> parseArticle(link)
        "direct" -> parseDirect(link)
        else -> throw VideoParseException(
            VideoParseErrorKind.UNSUPPORTED,
            AppStrings.get(R.string.er_wechat_sph_unsupported)
        )
    }

    // ---------------------------------------------------------------- 公众号文章

    private suspend fun parseArticle(link: DetectedVideoLink): VideoParseResult {
        val html = VideoHttp.getText(link.url, articleHeaders)

        val title = firstGroup(html, TITLE_JS).ifBlank { firstGroup(html, TITLE_H1) }
            .ifBlank { firstGroup(html, TITLE_OG) }.trim()
        val author = firstGroup(html, AUTHOR_JS).ifBlank { firstGroup(html, AUTHOR_DOM) }.trim()
        val cover = firstGroup(html, COVER_JS).ifBlank { firstGroup(html, COVER_OG) }
        val timestamp = firstGroup(html, CTIME_JS).toLongOrNull() ?: 0L

        val base = DownloadPathPolicy.sanitizeName(title).take(40)
            .ifBlank { "wechat_${link.id}" }

        val medias = mutableListOf<VideoMedia>()

        // ① 正文图片（抽成纯函数，便于单测）
        // ② 封面两个尺寸：长条（2.35:1）+ 方图（1:1），按用户要求都放进列表
        val imageUrls = LinkedHashSet<String>()
        imageUrls += extractCoverUrls(html)
        imageUrls += extractImageUrls(html)

        // 封面也算一张可下载的图：界面上没有单独的封面位（`VideoParseResult.cover` 目前没有 UI 消费方），
        // 直接把两个尺寸（长条 + 方图）放进媒体列表，用户既能"看到"也能下载。

        // 归一化成 /0 原图后再去重：同一张图在页面里可能同时出现缩略与原始两种规格
        val normalized = LinkedHashSet<String>(imageUrls)

        var index = 0
        for (url in normalized) {
            index += 1
            medias += VideoMedia(
                url = url,
                kind = VideoMediaKind.IMAGE,
                fileName = "${base}_$index.${imageExtension(url)}",
                headers = mpMediaHeaders,
                quality = AppStrings.get(R.string.em_quality_original)
            )
        }

        // ② 文章内嵌视频（有的排版是 <video src>，有的是 mpvideo 直链）
        val videoUrls = LinkedHashSet<String>()
        VIDEO_TAG.findAll(html).forEach { videoUrls += it.groupValues[1] }
        MPVIDEO.findAll(html).forEach { videoUrls += it.groupValues[1] }
        var videoIndex = 0
        for (raw in videoUrls) {
            val url = raw.replace("&amp;", "&")
            if (!url.startsWith("http")) continue
            videoIndex += 1
            medias += VideoMedia(
                url = url,
                kind = VideoMediaKind.VIDEO,
                fileName = "${base}_video_$videoIndex.mp4",
                headers = mpMediaHeaders
            )
        }

        if (medias.isEmpty()) {
            // 用 UNSUPPORTED 而不是 NOT_FOUND：后者的文案固定是「内容不存在、已删除或不可见」，
            // 而这里更常见的情况是「文章还在，但确实没有图片/视频」，不能误导用户以为文章被删了。
            throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                AppStrings.get(R.string.er_wechat_no_media)
            )
        }

        return VideoParseResult(
            platform = platform,
            title = title,
            author = author,
            avatar = "",
            cover = cover,
            text = "",
            timestampSeconds = timestamp,
            url = link.url,
            medias = medias
        )
    }

    // ---------------------------------------------------------------- 直链

    private fun parseDirect(link: DetectedVideoLink): VideoParseResult {
        val url = link.url.replace("&amp;", "&")
        val isImage = url.contains("mmbiz.qpic.cn")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val headers = if (url.contains("finder.video.qq.com")) finderMediaHeaders else mpMediaHeaders

        val media = if (isImage) {
            VideoMedia(
                url = url,
                kind = VideoMediaKind.IMAGE,
                fileName = "wx_$stamp.${imageExtension(url)}",
                headers = headers,
                quality = AppStrings.get(R.string.em_quality_original)
            )
        } else {
            VideoMedia(
                url = url,
                kind = VideoMediaKind.VIDEO,
                fileName = "wx_$stamp.mp4",
                headers = headers
            )
        }

        return VideoParseResult(
            platform = platform,
            title = if (isImage) "微信图片直链" else "微信视频直链",
            url = url,
            medias = listOf(media)
        )
    }

    /**
     * **纯函数**：从文章 HTML 里抽图片地址（已归一化成 `/0` 原图并去重）。
     *
     * 覆盖三种页面形态（都在实测里遇到过）：
     * a) 普通图文 —— 正文 img 懒加载，真地址在 `data-src`；必须限定 `http` 开头，
     *    否则会把页面内联 JS 里的 `data-src="'.concat(...)"` 模板串当成图片地址；
     * b) 图片消息（图集）—— 没有 `#js_content`，图片在 `picture_page_info_list` 的 `cdn_url:` 里；
     * c) 兜底 —— 整页所有 `mmbiz.qpic.cn` 地址（含表情/装饰图，只在 a、b 都为空时用）。
     */
    internal fun extractImageUrls(html: String): List<String> {
        val raw = LinkedHashSet<String>()
        // a) 普通图文：正文 img 懒加载，真地址在 data-src（限定 http 开头，避开内联 JS 的模板串）。
        //    ★ 必须先试正文：有些图文页里也带一个 `picture_page_info_list`（页面其他模块用），
        //      若先取图集块会把正文几十张图漏掉。
        val contentAt = html.indexOf("id=\"js_content\"").let { if (it >= 0) it else 0 }
        IMG_DATA_SRC.findAll(html.substring(contentAt)).forEach { raw += it.groupValues[1] }
        // b) 图片消息（图集）：只在 picture_page_info_list 块里取 `cdn_url`。
        //    ★ 不能全页匹配 `cdn_url:` —— 页面里还有 `watermark_info.cdn_url`（**已经烧好水印的整图**）、
        //      `share_cover`、头像、`320.wxapp.tc.qq.com` 等；全抓的结果就是「一张图变四张、其中一张带水印」。
        if (raw.isEmpty()) {
            val picBlock = section(html, "picture_page_info_list", listOf("share_cover", "watermark_info"))
            if (picBlock != null) {
                CDN_URL_IN_PICTURE.findAll(picBlock).forEach { raw += it.groupValues[1] }
            }
        }
        // c) 兜底：整页 mmbiz 图床；先把水印块摘掉，避免又把带水印的整图收进来
        if (raw.isEmpty()) {
            val wmBlock = section(html, "watermark_info", listOf("spot_product_info", "share_cover"))
            val clean = if (wmBlock != null) html.replace(wmBlock, "") else html
            ANY_MMBIZ.findAll(clean).forEach { raw += it.value }
        }
        return raw.map(::originalImage).filter { it.startsWith("http") }.distinct()
    }

    /**
     * **纯函数**：两个尺寸的封面 —— 长条（2.35:1）与方图（1:1）。
     *
     * 字段来源（实测 2026-11）：
     * - 长条：`msg_cdn_url`（普通图文）/ `cdn_url_235_1` / 页面级 `cdn_url`
     * - 方图：`cdn_url_1_1`
     */
    internal fun extractCoverUrls(html: String): List<String> {
        val out = LinkedHashSet<String>()
        val wide = firstGroup(html, COVER_235_1)
            .ifBlank { firstGroup(html, COVER_JS) }
            .ifBlank { firstGroup(html, COVER_OG) }
            .ifBlank { firstGroup(html, COVER_CDN_PLAIN) }
        if (wide.isNotBlank()) out += originalImage(wide)
        val square = firstGroup(html, COVER_1_1)
        if (square.isNotBlank()) out += originalImage(square)
        return out.filter { it.startsWith("http") }
    }

    /** 截取从 `key` 开始的一段（遇到 `until` 里任一标记即止，最长 60k 字符） */
    private fun section(html: String, key: String, until: List<String>): String? {
        val start = html.indexOf(key)
        if (start < 0) return null
        var end = minOf(html.length, start + 60_000)
        for (mark in until) {
            val i = html.indexOf(mark, start + key.length)
            if (i >= start + key.length && i < end) end = i
        }
        return html.substring(start, end)
    }

    // ---------------------------------------------------------------- 工具

    private fun firstGroup(text: String, regex: Regex): String =
        regex.find(text)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")?.replace("&quot;", "\"") ?: ""

    /**
     * 缩略规格 → 原图：`.../640?wx_fmt=png` → `.../0?wx_fmt=png`。
     *
     * ★ 只改「纯数字规格段」，不动 `sz_mmbiz`/`mmbiz` 这类路径前缀；`wx_fmt` 参数原样保留
     * （它决定返回格式，缺了会 404）。
     */
    internal fun originalImage(url: String): String =
        url.replace("&amp;", "&").replace(Regex("""/(?:640|750|1080)(\?|$)"""), "/0$1")

    /** 扩展名按 `wx_fmt` 判断；拿不到就按 jpg（微信自己也常把 jpeg 参数配 PNG/WebP 返回，见 §3.49 备注） */
    private fun imageExtension(url: String): String {
        val fmt = Regex("""[?&]wx_fmt=([A-Za-z0-9]+)""").find(url)?.groupValues?.getOrNull(1)?.lowercase()
        return when (fmt) {
            "png" -> "png"
            "gif" -> "gif"
            "webp" -> "webp"
            else -> "jpg"
        }
    }

    private val TITLE_JS = Regex("""var\s+msg_title\s*=\s*['"]([^'"]*)""")
    private val TITLE_H1 = Regex("""class="js_title_inner"[^>]*>([\s\S]{0,200}?)</""")
    private val TITLE_OG = Regex("""property="og:title"\s+content="([^"]*)""")
    private val AUTHOR_JS = Regex("""var\s+nickname\s*=\s*['"]([^'"]*)""")
    private val AUTHOR_DOM = Regex("""id="js_name"[^>]*>([\s\S]{0,80}?)</""")
    private val COVER_JS = Regex("""var\s+msg_cdn_url\s*=\s*['"]([^'"]+)""")
    private val COVER_OG = Regex("""property="og:image"\s+content="([^"]+)"""")
    private val CTIME_JS = Regex("""var\s+ct\s*=\s*['"]?(\d{9,13})""")
    private val IMG_DATA_SRC = Regex("""data-src="(https?://[^"]+)"""")
    /** 图片消息（图集）里的图片清单：`picture_page_info_list: [ { cdn_url: 'https://…' } ]` */
    private val CDN_URL_IN_PICTURE = Regex("""cdn_url:\s*'(https?://[^']+)'""")
    /** 封面：长条 2.35:1 */
    private val COVER_235_1 = Regex("""cdn_url_235_1:\s*'(https?://[^']+)'""")
    private val COVER_CDN_PLAIN = Regex("""\bcdn_url:\s*'(https?://[^']+)'""")
    /** 封面：方图 1:1 */
    private val COVER_1_1 = Regex("""cdn_url_1_1:\s*'(https?://[^']+)'""")
    private val ANY_MMBIZ = Regex("""https?://mmbiz\.qpic\.cn/[^"'\\\s)]+""")
    private val VIDEO_TAG = Regex("""<video[^>]+src="([^"]+)"""")
    private val MPVIDEO = Regex("""(https?://mpvideo\.qpic\.cn/[^"'\\\s]+)""")
}
