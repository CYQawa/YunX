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

/**
 * 识别结果：一条链接属于哪个平台、是哪种资源。
 *
 * [kind] 用平台自己的词（`video` / `note` / `slides` / `short` / `status` / `fid` / `artwork` / `ac`），
 * 解析器按它决定走哪条解析路径。[isShortLink] 为 true 时 [id] 为空 —— 短链要先跟随重定向拿到
 * 最终地址，再由 [VideoLinkParser] 重新识别（这一步统一在 `VideoParseRepository` 里做）。
 */
data class DetectedVideoLink(
    val platform: VideoPlatform,
    val kind: String,
    val id: String,
    val url: String,
    val isShortLink: Boolean = false
)

/**
 * 从用户粘贴的任意文本里识别视频平台链接。
 *
 * 与 [com.yunx.app.data.network.ShareLinkParser] 同构（先抓 URL 再按平台正则顺序匹配），
 * 但两套识别是**分开**的：网盘链接与视频链接互不干扰，解析页先试 GitHub、再试视频、
 * 最后才是网盘，任何一个命中就分流到对应页面。
 *
 * 正则全部照搬参考实现 `maibot-platform-parser` 的 `core/parsers` 目录（已逐条核对），
 * 只做两处收紧：
 * 1. **不识别裸的数字 ID**（参考实现允许「一段 18-20 位数字就是抖音」）。那样会把网盘提取码、
 *    迅雷口令里的数字全吃进来，误判代价远大于少支持一种输入；
 * 2. 短链只认固定域名前缀，不做泛化。
 */
object VideoLinkParser {

    /** 与网盘解析一致的 URL 抓取：从整段分享文案里取第一个 http(s) 链接 */
    private val urlRegex = Regex("""https?://[^\s]+""")

    /** 需要去掉的尾标点（中文文案里链接后面常跟句号/逗号/括号） */
    private const val TRAILING = "。，,；;)]}\"'、！？…"

    private data class Rule(val platform: VideoPlatform, val kind: String, val regex: Regex)

    /** 顺序即优先级：短链优先于长链（短链命中后不会再被长链规则抢走） */
    private val rules = listOf(
        // ---------- 抖音 ----------
        Rule(VideoPlatform.DOUYIN, "short", Regex("""v\.douyin\.com/[A-Za-z0-9_\-]+""", RegexOption.IGNORE_CASE)),
        Rule(VideoPlatform.DOUYIN, "short", Regex("""jx\.douyin\.com/[A-Za-z0-9_\-]+""", RegexOption.IGNORE_CASE)),
        Rule(
            VideoPlatform.DOUYIN, "video",
            Regex("""(?:www\.)?douyin\.com/(video|note)/(\d{10,})""", RegexOption.IGNORE_CASE)
        ),
        Rule(
            VideoPlatform.DOUYIN, "video",
            Regex("""iesdouyin\.com/share/(?:slides|video|note)/(\d{10,})""", RegexOption.IGNORE_CASE)
        ),
        Rule(
            VideoPlatform.DOUYIN, "video",
            Regex("""m\.douyin\.com/share/(?:slides|video|note)/(\d{10,})""", RegexOption.IGNORE_CASE)
        ),
        Rule(
            VideoPlatform.DOUYIN, "video",
            Regex("""jingxuan\.douyin\.com/m/(?:slides|video|note)/(\d{10,})""", RegexOption.IGNORE_CASE)
        ),
        // ---------- 小红书 ----------
        Rule(VideoPlatform.XHS, "short", Regex("""xhslink\.(?:com|cn)/[A-Za-z0-9._?%&+=/#@\-]+""", RegexOption.IGNORE_CASE)),
        Rule(
            VideoPlatform.XHS, "note",
            Regex("""xiaohongshu\.com/(?:explore|discovery/item|discovery)/([0-9a-zA-Z]{10,})""", RegexOption.IGNORE_CASE)
        ),
        // ---------- 快手 ----------
        Rule(VideoPlatform.KUAISHOU, "short", Regex("""v\.kuaishou\.com/[A-Za-z\d._?%&+\-=/#]+""", RegexOption.IGNORE_CASE)),
        Rule(
            VideoPlatform.KUAISHOU, "photo",
            Regex("""(?:www\.)?kuaishou\.com/(?:short-video|fw/photo|fw/long-video)/([A-Za-z\d]+)""", RegexOption.IGNORE_CASE)
        ),
        // 分享码形态：https://www.kuaishou.com/f/XxxXxx（入口页，解析器会先跟随一次重定向）
        Rule(
            VideoPlatform.KUAISHOU, "photo",
            Regex("""(?:www\.)?kuaishou\.com/f/([A-Za-z\d]+)""", RegexOption.IGNORE_CASE)
        ),
        Rule(
            VideoPlatform.KUAISHOU, "photo",
            Regex("""(?:v\.m\.)?chenzhongtech\.com/fw/(?:photo|long-video)/([A-Za-z\d]+)""", RegexOption.IGNORE_CASE)
        ),
        // ---------- 微博 ----------
        Rule(
            VideoPlatform.WEIBO, "tv",
            Regex("""weibo\.com/tv/show/\d{4}:\d+\?mid=(\d+)""", RegexOption.IGNORE_CASE)
        ),
        Rule(
            VideoPlatform.WEIBO, "fid",
            Regex("""video\.weibo\.com/show\?fid=(\d+:\d+)""", RegexOption.IGNORE_CASE)
        ),
        Rule(
            VideoPlatform.WEIBO, "status",
            Regex("""weibo\.cn/(?:status|statuses|detail)/([0-9a-zA-Z]+)""", RegexOption.IGNORE_CASE)
        ),
        Rule(
            VideoPlatform.WEIBO, "status",
            Regex("""weibo\.com/\d+/([0-9a-zA-Z]{6,})""", RegexOption.IGNORE_CASE)
        ),
        // ---------- AcFun ----------
        Rule(VideoPlatform.ACFUN, "ac", Regex("""acfun\.cn/.*(?:ac=|/ac)(\d+)""", RegexOption.IGNORE_CASE)),
        // ---------- 哔哩哔哩 ----------
        Rule(VideoPlatform.BILIBILI, "short", Regex("""b23\.tv/[A-Za-z\d]+""", RegexOption.IGNORE_CASE)),
        Rule(VideoPlatform.BILIBILI, "short", Regex("""bili2233\.cn/[A-Za-z\d]+""", RegexOption.IGNORE_CASE)),
        Rule(
            VideoPlatform.BILIBILI, "video",
            Regex("""bilibili\.com/video/(BV[0-9a-zA-Z]{10}|av\d+)""", RegexOption.IGNORE_CASE)
        ),
        // ---------- Pixiv ----------
        Rule(
            VideoPlatform.PIXIV, "artwork",
            Regex("""pixiv\.net/(?:[a-z]{2}/)?artworks/(\d+)""", RegexOption.IGNORE_CASE)
        ),
        Rule(
            VideoPlatform.PIXIV, "artwork",
            Regex("""pixiv\.net/member_illust\.php\?[^\s]*illust_id=(\d+)""", RegexOption.IGNORE_CASE)
        )
    )

    /**
     * 识别文本里的视频链接；识别不到返回 null。
     *
     * ★ 只在**链接**上做匹配（不看链接外的文案）：抖音/快手的分享文案里常带一堆数字，
     *   拿整段文本匹配必然误判。
     */
    fun parse(text: String): DetectedVideoLink? {
        val url = urlRegex.find(text.trim())?.value?.trimEnd(*TRAILING.toCharArray()) ?: return null
        for (rule in rules) {
            val match = rule.regex.find(url) ?: continue
            // 短链没有独立 id：它的资源标识要等重定向之后才有
            val id = match.groupValues.drop(1).lastOrNull { it.isNotBlank() }.orEmpty()
            val isShort = rule.kind == "short"
            return DetectedVideoLink(
                platform = rule.platform,
                kind = if (isShort) "short" else rule.kind,
                id = if (isShort) "" else id,
                url = if (url.startsWith("http")) url else "https://$url",
                isShortLink = isShort
            )
        }
        return null
    }

    /** 剪贴板建议用：这段文本里有没有可解析的视频链接 */
    fun looksLikeVideoLink(text: String): Boolean = parse(text) != null
}
