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
import org.junit.Test

/**
 * 123 缩略图地址换算单测。
 *
 * 这个函数是从「可能指向原图」的 DownloadUrl 里挑缩略图，认错方向的代价是**每行下载一张原图**，
 * 所以「认不出来就返回空串」这条必须有反例兜住。
 */
class Pan123ThumbnailTest {

    @Test
    fun `24x24 缩略图换成 70x70 并补 w h 与 trade_key`() {
        assertEquals(
            "https://cdn.123pan.com/a/b_70_70?w=70&h=70&trade_key=123pan-thumbnail",
            pan123ThumbnailUrl("https://cdn.123pan.com/a/b_24_24")
        )
    }

    @Test
    fun `已有查询参数保留 顺序不变 只替换 w h`() {
        assertEquals(
            "https://cdn.123pan.com/a/b_70_70?x=1&w=70&h=70&trade_key=123pan-thumbnail",
            pan123ThumbnailUrl("https://cdn.123pan.com/a/b_24_24?x=1&w=24&h=24")
        )
    }

    @Test
    fun `已经是 123pan-thumbnail 变体的地址原样使用`() {
        val url = "https://cdn.123pan.com/a/b.jpg?trade_key=123pan-thumbnail&w=70"
        assertEquals(url, pan123ThumbnailUrl(url))
    }

    @Test
    fun `认不出来的地址一律返回空串`() {
        // 原图直链：当成缩略图用等于每行下一张原图
        assertEquals("", pan123ThumbnailUrl("https://cdn.123pan.com/a/b.jpg"))
        // 其它业务的签名参数也不算缩略图
        assertEquals("", pan123ThumbnailUrl("https://cdn.123pan.com/a/b.jpg?trade_key=other"))
        assertEquals("", pan123ThumbnailUrl(""))
        assertEquals("", pan123ThumbnailUrl("   "))
    }
}
