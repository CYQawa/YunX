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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公众号图片提取的纯逻辑单测（不联网、不碰 `org.json`）。
 *
 * 三个用例对应实测遇到的三种页面形态：普通图文（`data-src`）、图片消息（`cdn_url:` 列表）、
 * 以及「页面内联 JS 里也有 `data-src="..."` 模板串」这个坑。
 */
class WechatMpParserTest {

    @Test
    fun `普通图文的 data-src 会被升级成 0 原图`() {
        val html = """
            <div id="js_content">
              <img data-src="https://mmbiz.qpic.cn/mmbiz_png/AAA/640?wx_fmt=png" />
              <img data-src="https://mmbiz.qpic.cn/mmbiz_jpg/BBB/0?wx_fmt=jpeg" />
            </div>
        """.trimIndent()
        val urls = WechatMpParser.extractImageUrls(html)
        assertEquals(2, urls.size)
        assertTrue(urls[0].endsWith("/0?wx_fmt=png"))
        assertTrue(urls[0].contains("mmbiz_png/AAA"))
        assertTrue(urls[1].endsWith("/0?wx_fmt=jpeg"))
    }

    @Test
    fun `图片消息（图集）从 cdn_url 列表取图`() {
        val html = """
            <script>
              window.cgiDataNew = { picture_page_info_list: [
                { cdn_url: 'https://mmbiz.qpic.cn/sz_mmbiz_jpg/CCC/640?wx_fmt=jpeg' },
                { cdn_url: 'https://mmbiz.qpic.cn/sz_mmbiz_jpg/DDD/0?wx_fmt=jpeg' }
              ] };
            </script>
        """.trimIndent()
        val urls = WechatMpParser.extractImageUrls(html)
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.contains("sz_mmbiz_jpg") })
        assertTrue(urls.all { it.contains("/0?wx_fmt=jpeg") })
    }

    @Test
    fun `页面内联 JS 里的 data-src 模板串不会被当成图片`() {
        val html = """
            <script>var k=function(e){return '<img data-src="'.concat(r,'">'};</script>
            <div id="js_content">
              <img data-src="https://mmbiz.qpic.cn/mmbiz_png/EEE/640?wx_fmt=png" />
            </div>
        """.trimIndent()
        val urls = WechatMpParser.extractImageUrls(html)
        assertEquals(1, urls.size)
        assertTrue(urls.single().contains("mmbiz_png/EEE"))
        assertFalse(urls.single().contains("concat"))
    }

    @Test
    fun `同一张图的缩略与原始规格只保留一份`() {
        val html = """
            <div id="js_content">
              <img data-src="https://mmbiz.qpic.cn/mmbiz_jpg/FFF/640?wx_fmt=jpeg" />
              <img data-src="https://mmbiz.qpic.cn/mmbiz_jpg/FFF/0?wx_fmt=jpeg" />
            </div>
        """.trimIndent()
        val urls = WechatMpParser.extractImageUrls(html)
        assertEquals(1, urls.size)
        assertTrue(urls.single().contains("/0?wx_fmt=jpeg"))
    }

    @Test
    fun `水印版整图（watermark_info）不会被当成正文图片`() {
        val html = """
            <script>
              window.cgiDataNew = {
                picture_page_info_list: [
                  { cdn_url: 'https://mmbiz.qpic.cn/sz_mmbiz_png/AAA/0?wx_fmt=png', width: '1289' },
                  { cdn_url: 'https://mmbiz.qpic.cn/sz_mmbiz_png/BBB/0?wx_fmt=png', width: '1289' }
                ],
                watermark_info: { cdn_url: 'https://mmbiz.qpic.cn/sz_mmbiz_png/WATERMARKED/0?wx_fmt=png' },
                share_cover: { }
              };
            </script>
        """.trimIndent()
        val urls = WechatMpParser.extractImageUrls(html)
        assertEquals(2, urls.size)
        assertFalse(urls.any { it.contains("WATERMARKED") })
    }

    @Test
    fun `封面两个尺寸都能取到`() {
        val html = """
            <script>
              var msg_cdn_url = "https://mmbiz.qpic.cn/sz_mmbiz_jpg/WIDE/0?wx_fmt=jpeg";
              window.cgiDataNew = { cdn_url_235_1: '', cdn_url_1_1: 'https://mmbiz.qpic.cn/sz_mmbiz_jpg/SQUARE/0?wx_fmt=jpeg' };
            </script>
        """.trimIndent()
        val covers = WechatMpParser.extractCoverUrls(html)
        assertEquals(2, covers.size)
        assertTrue(covers[0].contains("WIDE"))
        assertTrue(covers[1].contains("SQUARE"))
    }

    @Test
    fun `没有图片的页面返回空列表`() {
        assertTrue(WechatMpParser.extractImageUrls("<html><body>纯文字</body></html>").isEmpty())
    }
}
