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

package com.yunx.app.data.network

import java.net.URLEncoder

/**
 * 迅雷中文口令（如「张三丰资源」）→ 分享链接 + 提取码。
 *
 * 口令本质上是「分享链接 + 提取码」的打包形式：把口令当关键词打 shoulei 的搜索跳转接口，
 * 响应 `ext.kouling_type == "share_page"` 时 `location` 就是带 `pwd` 的分享页地址
 * （提取码是明文，不需要再调别的接口）；没有对应资源时只返回 `search_url`（搜索引擎结果页）。
 * 本对象只做纯逻辑（判定 / 拼 URL / 解 location），网络请求见 [XunleiApi.parseKouling]。
 */
object XunleiKouling {

    /** 口令 → 分享链接的跳转接口 */
    const val JUMP_API = "https://api-shoulei-ssl.xunlei.com/xlppc.searcher.api/jump"

    /**
     * 接口来源标识：服务端按渠道校验，缺了拿不到 `share_page`。
     * UA 需带 Thunder/TBC 标识（普通浏览器 UA 会走到搜索页而非分享页）。
     */
    const val ORIGIN = "https://sl-m-ssl.xunlei.com"
    const val REFERER = "https://sl-m-ssl.xunlei.com/"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/108.0.5359.215 Safari/537.36 TBC/1.3.3.999 Thunder/12.4.4.3740"

    /** 渠道参数（前端固定值，抓包所得） */
    private const val TN = "15007414_5_dg"
    private const val SRC = "lm"
    private const val LS = "sm3016480"
    private const val LM_EXTEND = "ctype:31"

    /** 口令长度上限：正常口令是短文本，过长说明用户粘的是整段文案，不该当口令去打接口 */
    private const val MAX_LENGTH = 64

    /** 口令两侧常见的装饰符号（引号 / 书名号 / 括号 / 标点） */
    private const val DECORATIONS = "「」『』【】《》〈〉“”‘’\"'`()（）[]{}<>.,，。;；:：!！?？、|"

    private val shareIdRegex = Regex("""pan\.xunlei\.com/s/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val pwdRegex = Regex("""[?&]pwd=([A-Za-z0-9]+)""")

    /** 去掉两侧空白与装饰符号，得到干净的口令文本 */
    fun normalize(text: String): String = text.trim().trim(*DECORATIONS.toCharArray()).trim()

    /**
     * 是否值得按口令去解析：非空、不太长、正文只含汉字/字母/数字、且至少有汉字。
     * 带空格或标点的多半是整段分享文案，纯英文/纯数字也不是中文口令 —— 这类都不打接口，
     * 免得白跑一趟还把「无对应资源」当结果。调用方还需先确认它不是分享链接
     * （[ShareLinkParser.parse] 返回 null）。
     */
    fun looksLikeKouling(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty() || t.length > MAX_LENGTH) return false
        if (!t.all { it.isLetterOrDigit() }) return false
        return t.any { it.code in 0x4E00..0x9FFF }
    }

    /** 拼 jump 请求 URL：`wd`（中文口令）必须 URL 编码 */
    fun buildJumpUrl(keyword: String): String {
        val wd = URLEncoder.encode(keyword, "UTF-8")
        return "$JUMP_API?noredirect=1&t=20&wd=$wd&tn=$TN&src=$SRC&ls=$LS&lm_extend=$LM_EXTEND"
    }

    /**
     * 从响应 `location` 里取干净的分享链接：`https://pan.xunlei.com/s/<share_id>?pwd=<pwd>`，
     * 丢掉 `channel`/`content`/`from`/`share_userid`/`wd` 等业务参数（它们对解析无用）。
     * 不是迅雷分享页（如口令无资源时的搜索引擎地址）返回 null。
     */
    fun shareUrlFromLocation(location: String?): String? {
        val loc = location?.takeIf { it.isNotBlank() } ?: return null
        val shareId = shareIdRegex.find(loc)?.groupValues?.getOrNull(1) ?: return null
        val pwd = pwdRegex.find(loc)?.groupValues?.getOrNull(1)
        return if (pwd.isNullOrBlank()) {
            "https://pan.xunlei.com/s/$shareId"
        } else {
            "https://pan.xunlei.com/s/$shareId?pwd=$pwd"
        }
    }
}
