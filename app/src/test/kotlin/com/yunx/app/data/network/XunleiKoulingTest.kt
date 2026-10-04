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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XunleiKoulingTest {

    /** 接口原始 location（实测口令「张三丰资源」的真实响应，去掉其它字段） */
    private val realLocation =
        "https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1" +
            "?channel=complete_birdkey_by_plate" +
            "&content=%E5%BC%A0%E4%B8%89%E4%B8%B0%E8%B5%84%E6%BA%90" +
            "&from=BHO%2Fpaste%2Fkouling&pwd=nw45&share_userid=478416968" +
            "&wd=%E5%BC%A0%E4%B8%89%E4%B8%B0%E8%B5%84%E6%BA%90"

    @Test
    fun parsesRealKoulingResponseToShareLink() {
        val url = XunleiKouling.shareUrlFromLocation(realLocation)!!
        assertEquals("https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1?pwd=nw45", url)

        // 解析结果必须能被通用分享链接解析器吃下（口令 → 链接 → share_id + 提取码 的完整链路）
        val parsed = ShareLinkParser.parse(url)!!
        assertEquals(SharePlatform.XUNLEI, parsed.platform)
        assertEquals("VOEs0DLEAfUV9o-JOAqrzgZmA1", parsed.shareId)
        assertEquals("nw45", parsed.pwd)
    }

    @Test
    fun locationWithoutPwdKeepsBareShareUrl() {
        val url = XunleiKouling.shareUrlFromLocation(
            "https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1?channel=xxx&from=BHO"
        )!!
        assertEquals("https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1", url)

        val parsed = ShareLinkParser.parse(url)!!
        assertEquals(SharePlatform.XUNLEI, parsed.platform)
        assertEquals("VOEs0DLEAfUV9o-JOAqrzgZmA1", parsed.shareId)
        assertNull(parsed.pwd)
    }

    @Test
    fun rejectsNonXunleiAndBlankLocation() {
        // 无对应资源时只给 search_url，location 不是分享页 → 视为口令无效
        assertNull(XunleiKouling.shareUrlFromLocation("https://www.so.com/s?q=%E5%BC%A0%E4%B8%89"))
        assertNull(XunleiKouling.shareUrlFromLocation(""))
        assertNull(XunleiKouling.shareUrlFromLocation(null))
    }

    @Test
    fun acceptsChineseKoulingText() {
        assertTrue(XunleiKouling.looksLikeKouling("张三丰资源"))
        assertTrue(XunleiKouling.looksLikeKouling("【张三丰资源】"))
        assertTrue(XunleiKouling.looksLikeKouling("  张三丰资源  "))
        assertTrue(XunleiKouling.looksLikeKouling("「张三丰资源」"))
    }

    @Test
    fun rejectsNonKoulingText() {
        // 已是分享链接：不走口令解析
        assertFalse(XunleiKouling.looksLikeKouling("https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1"))
        // 不含汉字（纯英文/纯数字/纯符号）
        assertFalse(XunleiKouling.looksLikeKouling("xunlei-kouling"))
        assertFalse(XunleiKouling.looksLikeKouling("12345678"))
        assertFalse(XunleiKouling.looksLikeKouling(""))
        assertFalse(XunleiKouling.looksLikeKouling("   "))
        // 内含空白（整段文案，不该当口令打接口）
        assertFalse(XunleiKouling.looksLikeKouling("张三丰 资源"))
        assertFalse(XunleiKouling.looksLikeKouling("口令：张三丰资源"))
        // 过长
        assertFalse(XunleiKouling.looksLikeKouling("张三丰资源".repeat(20)))
    }

    @Test
    fun normalizeStripsDecorations() {
        assertEquals("张三丰资源", XunleiKouling.normalize("  【张三丰资源】 "))
        assertEquals("张三丰资源", XunleiKouling.normalize("“张三丰资源”"))
    }

    @Test
    fun jumpUrlCarriesEncodedKeywordAndChannelParams() {
        val url = XunleiKouling.buildJumpUrl("张三丰资源")
        assertTrue(url.startsWith(XunleiKouling.JUMP_API))
        assertTrue(url.contains("wd=%E5%BC%A0%E4%B8%89%E4%B8%B0%E8%B5%84%E6%BA%90"))
        assertTrue(url.contains("noredirect=1"))
        assertTrue(url.contains("tn=15007414_5_dg"))
        assertTrue(url.contains("lm_extend=ctype:31"))
    }
}
