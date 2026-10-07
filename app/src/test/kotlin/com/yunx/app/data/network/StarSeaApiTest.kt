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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 星海中文口令解析的**纯逻辑**单测。
 *
 * ★ 只测不碰网络/JSON 的部分（[StarSeaApi.looksLikeCommand] / [StarSeaApi.shareLinkFor]）：
 *   工程开了 `unitTests.isReturnDefaultValues = true`，JVM 单测里 android.jar 的 `org.json`
 *   是空壳（`JSONObject` 只会返回 null），任何依赖 JSON 解析的断言都测不了（见 Agent.md §4）。
 */
class StarSeaApiTest {

    @Test
    fun `短中文口令按口令处理`() {
        assertTrue(StarSeaApi.looksLikeCommand("绝对有效 咐置松君杂货铺叩苓"))
        assertTrue(StarSeaApi.looksLikeCommand("张三丰资源"))
        assertTrue(StarSeaApi.looksLikeCommand("U_C1234567890"))
    }

    @Test
    fun `空串_超长_或本身就是链接时不当口令`() {
        assertFalse(StarSeaApi.looksLikeCommand(""))
        assertFalse(StarSeaApi.looksLikeCommand("   "))
        // 超长：多半是整段文案，不该拿去打第三方接口
        assertFalse(StarSeaApi.looksLikeCommand("令".repeat(StarSeaConstants.MAX_COMMAND_LENGTH + 1)))
        // 本身就是 URL：说明是本地没认出来的链接，问口令服务也没用
        assertFalse(StarSeaApi.looksLikeCommand("https://example.com/s/abc"))
        assertFalse(StarSeaApi.looksLikeCommand("看这个 HTTP://example.com/s/abc"))
    }

    @Test
    fun `pwdId 合成 App 现有链路认得的分享链接`() {
        assertEquals("https://pan.quark.cn/s/81c7b58ff633", StarSeaApi.shareLinkFor("qk", "81c7b58ff633"))
        assertEquals("https://drive.uc.cn/s/81c7b58ff633", StarSeaApi.shareLinkFor("uc", "81c7b58ff633"))
        // 平台大小写不敏感，未知平台按夸克兜底
        assertEquals("https://drive.uc.cn/s/abc", StarSeaApi.shareLinkFor("UC", "abc"))
        assertEquals("https://pan.quark.cn/s/abc", StarSeaApi.shareLinkFor("", "abc"))
    }

    @Test
    fun `合成出来的分享链接必须能被本地解析器认出来`() {
        // 这一步是整条链路的关键：合成链接认不出来，「口令 → 本地链路」就断了
        val quark = ShareLinkParser.parse(StarSeaApi.shareLinkFor("qk", "81c7b58ff633"))
        assertEquals(SharePlatform.QUARK, quark?.platform)
        assertEquals("81c7b58ff633", quark?.shareId)

        val uc = ShareLinkParser.parse(StarSeaApi.shareLinkFor("uc", "abcdef123456"))
        assertEquals(SharePlatform.UC, uc?.platform)
        assertEquals("abcdef123456", uc?.shareId)
    }
}
