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
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkParserTest {

    @Test
    fun parsesAllSupportedPlatforms() {
        val cases = listOf(
            "https://pan.quark.cn/s/Abc123?pwd=a1B2" to (SharePlatform.QUARK to "Abc123"),
            "https://drive.uc.cn/s/Abc123" to (SharePlatform.UC to "Abc123"),
            "https://pan.xunlei.com/s/Abc_123-xy" to (SharePlatform.XUNLEI to "Abc_123-xy"),
            "https://pan.baidu.com/s/1Abc_123-xy?pwd=9xYz" to (SharePlatform.BAIDU to "Abc_123-xy"),
            "https://yun.139.com/shareweb/#/w/i/Abc_123" to (SharePlatform.C139 to "Abc_123"),
            "https://www.123pan.com/s/2785Vv-T4Ded" to (SharePlatform.PAN123 to "2785Vv-T4Ded"),
            "https://115.com/s/swz54f736y2?password=y118" to (SharePlatform.PAN115 to "swz54f736y2")
        )

        cases.forEach { (text, expected) ->
            val parsed = ShareLinkParser.parse(text)!!
            assertEquals(expected.first, parsed.platform)
            assertEquals(expected.second, parsed.shareId)
        }
    }

    @Test
    fun explicitTextPasswordIsExtracted() {
        val parsed = ShareLinkParser.parse("链接 https://drive.uc.cn/s/Abc123 提取码：a1B2")!!
        assertEquals("a1B2", parsed.pwd)
    }

    @Test
    fun parsesPan115Forms() {
        // 链接 ?password= 直接带码
        val withQuery = ShareLinkParser.parse("https://115cdn.com/s/swz54f736y2?password=y118")!!
        assertEquals(SharePlatform.PAN115, withQuery.platform)
        assertEquals("swz54f736y2", withQuery.shareId)
        assertEquals("y118", withQuery.pwd)
        // 口令形式：/<share_code>-<code>/
        val command = ShareLinkParser.parse("https://115.com/swz54f736y2-y118/")!!
        assertEquals(SharePlatform.PAN115, command.platform)
        assertEquals("swz54f736y2", command.shareId)
        assertEquals("y118", command.pwd)
    }

    @Test
    fun rejectsUnrelatedUrl() {
        assertNull(ShareLinkParser.parse("https://example.com/s/Abc123"))
    }

    @Test
    fun parsesGuangyaAndLanzouWithoutMisjudgingILanzou() {
        // 光鸭：https://www.guangyapan.com/s/{shareId}
        val guangya = ShareLinkParser.parse("https://www.guangyapan.com/s/Share123")!!
        assertEquals(SharePlatform.GUANGYA, guangya.platform)
        assertEquals("Share123", guangya.shareId)

        // 蓝奏云域名族 lanzou*/lan[zs]o[ux]：子域、文件分享(i...)、文件夹分享(b...)与提取码
        val lanzouSub = ShareLinkParser.parse("https://wwx.lanzoui.com/iAbc123")!!
        assertEquals(SharePlatform.LANZOU, lanzouSub.platform)
        assertEquals("iAbc123", lanzouSub.shareId)
        val lanzouFolder = ShareLinkParser.parse("https://pan.lanzou.com/bFolder1?pwd=a1B2")!!
        assertEquals(SharePlatform.LANZOU, lanzouFolder.platform)
        assertEquals("bFolder1", lanzouFolder.shareId)
        assertEquals("a1B2", lanzouFolder.pwd)

        // 蓝奏云优享版：识别为 ILANZOU（不再误判为蓝奏云），分享路径 /s/<id>
        val ilanzou = ShareLinkParser.parse("https://www.ilanzou.com/s/Abc123")!!
        assertEquals(SharePlatform.ILANZOU, ilanzou.platform)
        assertEquals("Abc123", ilanzou.shareId)
        val ilanzouBare = ShareLinkParser.parse("https://ilanzou.com/s/Abc123")!!
        assertEquals(SharePlatform.ILANZOU, ilanzouBare.platform)
    }
}
