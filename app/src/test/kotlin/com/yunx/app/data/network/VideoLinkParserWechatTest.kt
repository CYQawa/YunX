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

import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.network.video.VideoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 微信相关链接的识别单测。
 *
 * 只测 [VideoLinkParser] 的纯正则逻辑（不碰网络、不碰 `org.json`）：
 * 公众号文章、视频号分享页、视频号/公众号媒体直链，以及「不要误伤其它平台」的回归。
 */
class VideoLinkParserWechatTest {

    @Test
    fun `公众号短链识别为 article`() {
        val link = VideoLinkParser.parse("https://mp.weixin.qq.com/s/RVRo0sOLJMMc56NB4_c-gQ")
        assertEquals(VideoPlatform.WECHAT_MP, link?.platform)
        assertEquals("article", link?.kind)
        assertEquals("AbCdEf123456", link?.id)
    }

    @Test
    fun `公众号长链（__biz 形态）识别为 article`() {
        val link = VideoLinkParser.parse(
            "https://mp.weixin.qq.com/s?__biz=MzA5MTI2MzUxMA==&mid=2650000001&idx=1&sn=abcdef123456"
        )
        assertEquals(VideoPlatform.WECHAT_MP, link?.platform)
        assertEquals("article", link?.kind)
    }

    @Test
    fun `视频号分享链识别为 sph`() {
        val link = VideoLinkParser.parse("https://weixin.qq.com/sph/AbCdEf123456")
        assertEquals(VideoPlatform.WECHAT_MP, link?.platform)
        assertEquals("sph", link?.kind)
        assertEquals("AbCdEf123456", link?.id)
    }

    @Test
    fun `视频号预览页识别为 sph`() {
        val link = VideoLinkParser.parse(
            "https://channels.weixin.qq.com/finder-preview/pages/sph?id=AbCdEf123456"
        )
        assertEquals(VideoPlatform.WECHAT_MP, link?.platform)
        assertEquals("sph", link?.kind)
        assertEquals("AbCdEf123456", link?.id)
    }

    @Test
    fun `视频号签名直链识别为 direct`() {
        val link = VideoLinkParser.parse(
            "https://finder.video.qq.com/251/20302/stodownload?encfilekey=ABC123&token=xyz&sign=abc"
        )
        assertEquals(VideoPlatform.WECHAT_MP, link?.platform)
        assertEquals("direct", link?.kind)
    }

    @Test
    fun `公众号图床直链识别为 direct`() {
        val link = VideoLinkParser.parse("https://mmbiz.qpic.cn/sz_mmbiz_jpg/xxxx/0?wx_fmt=jpeg")
        assertEquals(VideoPlatform.WECHAT_MP, link?.platform)
        assertEquals("direct", link?.kind)
    }

    @Test
    fun `其它平台链接不受影响`() {
        assertEquals(
            VideoPlatform.WEIBO,
            VideoLinkParser.parse("https://weibo.com/1234567890/P5kWdcfDe")?.platform
        )
        assertEquals(
            VideoPlatform.BILIBILI,
            VideoLinkParser.parse("https://www.bilibili.com/video/BV1xx411c7mD")?.platform
        )
        assertNull(VideoLinkParser.parse("https://example.com/nothing"))
    }
}
