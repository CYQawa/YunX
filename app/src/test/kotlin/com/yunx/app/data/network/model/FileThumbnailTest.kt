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

package com.yunx.app.data.network.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 列表缩略图信息构造单测。
 *
 * 重点只有一条：**http 地址必须被拒**。应用全局禁止明文，http 图片必然加载失败，
 * 放过去的结果是每行留一个空框再多一次注定失败的请求。
 */
class FileThumbnailTest {

    @Test
    fun `https 地址保留请求头`() {
        val headers = mapOf("User-Agent" to "ua", "Referer" to "https://example.com/")
        val thumbnail = FileThumbnail.of(" https://img.example.com/a.jpg ", headers)
        assertEquals("https://img.example.com/a.jpg", thumbnail?.url)
        assertEquals(headers, thumbnail?.headers)
    }

    @Test
    fun `没有请求头时默认空表`() {
        val thumbnail = FileThumbnail.of("https://img.example.com/a.jpg")
        assertEquals(emptyMap<String, String>(), thumbnail?.headers)
    }

    @Test
    fun `非 https 或空地址返回 null`() {
        assertNull(FileThumbnail.of("http://img.example.com/a.jpg"))
        assertNull(FileThumbnail.of(""))
        assertNull(FileThumbnail.of("   "))
        // 相对路径/非法协议同样不能当缩略图
        assertNull(FileThumbnail.of("/a.jpg"))
        assertNull(FileThumbnail.of("data:image/png;base64,AAAA"))
    }

    @Test
    fun `账号请求头只发给平台自己的接口域名`() {
        val public = mapOf("Referer" to "https://pan.quark.cn/")
        val auth = public + ("Cookie" to "puus=secret")
        // 自家缩略图接口：带 Cookie
        assertEquals(
            auth,
            FileThumbnail.of(
                url = "https://drive-pc.quark.cn/1/clouddrive/file/thumbnail?fid=1",
                headers = public,
                authHost = "drive-pc.quark.cn",
                authHeaders = auth
            )?.headers
        )
        // 域名大小写不影响判定
        assertEquals(
            auth,
            FileThumbnail.of(
                url = "https://DRIVE-PC.Quark.cn/1/clouddrive/file/thumbnail?fid=1",
                headers = public,
                authHost = "drive-pc.quark.cn",
                authHeaders = auth
            )?.headers
        )
        // 别的域名（CDN 签名地址/异常数据）：不能把账号 Cookie 带过去
        assertEquals(
            public,
            FileThumbnail.of(
                url = "https://cdn.example.com/a.jpg",
                headers = public,
                authHost = "drive-pc.quark.cn",
                authHeaders = auth
            )?.headers
        )
    }
}
