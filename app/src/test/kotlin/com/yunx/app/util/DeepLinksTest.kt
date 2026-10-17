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

package com.yunx.app.util

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 深链接 intent 解析的纯逻辑单测（不碰网络、不碰组件状态）。
 * 覆盖三种进来方式：点链接（VIEW）、分享（SEND）、选中文本解析（PROCESS_TEXT）。
 *
 * ★ 只用字符串常量当 action，不去读 `Intent` 实例：JVM 单测里 android.jar 是空壳
 *   （`unitTests.isReturnDefaultValues = true`，见 Agent.md §4），构造 Intent 拿不到真实数据；
 *   `Intent.ACTION_VIEW` 这类**编译期常量**会被 kotlinc 内联，所以这里直接写字面量更稳。
 */
class DeepLinksTest {

    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"

    @Test
    fun `点网盘分享链接原样返回`() {
        val url = "https://pan.quark.cn/s/abc123"
        assertEquals(url, DeepLinks.textFrom(view, url, null))
    }

    @Test
    fun `磁力链接原样返回（整串含参数）`() {
        val magnet = "magnet:?xt=urn:btih:abcdef0123&dn=%E6%B5%8B%E8%AF%95"
        assertEquals(magnet, DeepLinks.textFrom(view, magnet, null))
    }

    @Test
    fun `自有协议拆出 url 参数并把 pwd 拼回链接`() {
        val encoded = URLEncoder.encode("https://pan.quark.cn/s/abc123", "UTF-8")
        val data = "yunx://resolve?url=$encoded&pwd=1234"
        assertEquals("https://pan.quark.cn/s/abc123?pwd=1234", DeepLinks.textFrom(view, data, null))
    }

    @Test
    fun `自有协议链接里已带 pwd 时不再重复拼接`() {
        val encoded = URLEncoder.encode("https://pan.quark.cn/s/abc?pwd=9999", "UTF-8")
        val data = "yunx://resolve?url=$encoded&pwd=1234"
        assertEquals("https://pan.quark.cn/s/abc?pwd=9999", DeepLinks.textFrom(view, data, null))
    }

    @Test
    fun `自有协议只有 url 参数也认`() {
        val encoded = URLEncoder.encode("https://drive.uc.cn/s/xyz", "UTF-8")
        assertEquals("https://drive.uc.cn/s/xyz", DeepLinks.textFrom(view, "yunx://resolve?url=$encoded", null))
    }

    @Test
    fun `自有协议缺少 url 参数返回 null`() {
        assertNull(DeepLinks.textFrom(view, "yunx://resolve?pwd=1234", null))
        assertNull(DeepLinks.textFrom(view, "yunx://resolve", null))
    }

    @Test
    fun `分享过来的文本去掉首尾空白后原样返回`() {
        assertEquals(
            "看这个 https://pan.quark.cn/s/abc 提取码: 1234",
            DeepLinks.textFrom(send, null, "  看这个 https://pan.quark.cn/s/abc 提取码: 1234  ")
        )
    }

    @Test
    fun `选中文本解析取 EXTRA_PROCESS_TEXT 那份文本`() {
        val processText = "android.intent.action.PROCESS_TEXT"
        // 选中文本可能是中文口令，也可能是链接
        assertEquals("绝对有效 咐置松君杂货铺叩苓", DeepLinks.textFrom(processText, null, "绝对有效 咐置松君杂货铺叩苓"))
        assertEquals("https://pan.quark.cn/s/abc", DeepLinks.textFrom(processText, null, " https://pan.quark.cn/s/abc "))
        assertNull(DeepLinks.textFrom(processText, null, "   "))
        // 选中文本只看 extras，不认 data（这条防止误把 intent 里的 data 当成待解析文本）
        assertNull(DeepLinks.textFrom(processText, "https://pan.quark.cn/s/abc", null))
    }

    @Test
    fun `无关 intent 一律不处理`() {
        assertNull(DeepLinks.textFrom(null, null, null))
        assertNull(DeepLinks.textFrom("android.intent.action.MAIN", null, null))
        assertNull(DeepLinks.textFrom(view, null, null))
        assertNull(DeepLinks.textFrom(view, "   ", null))
        assertNull(DeepLinks.textFrom(send, null, ""))
        assertNull(DeepLinks.textFrom(send, null, null))
    }
}
