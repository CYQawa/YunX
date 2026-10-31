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
 * 123 缩略图地址拼装单测。
 *
 * 这里锁的是**实测出来的三条硬约束**（拿真实列表响应打 CDN 验证过）：
 * 1. 必须带 `trade_key=123pan-thumbnail`，少它 403；
 * 2. `trade_key` 只能出现一次，重复参数会让 CDN 直接回原图；
 * 3. 路径后缀无关，尺寸由 w/h 决定。
 * 所以「先剥掉旧的 w/h/trade_key 再各写一次」这段逻辑必须有反例兜住。
 */
class Pan123ThumbnailTest {

    private val downloadUrl =
        "https://download-cdn.cjjd19.com/123-137/fbced95c/1826542194-0/" +
            "fbced95c2532fca70bc4af83c1ade089/c-m8007?v=5&t=1793995964&s=abc&filename=a.jpg&cache_type=2"

    @Test
    fun `在原图直链上补 w h 与单个 trade_key`() {
        assertEquals(
            "$downloadUrl&w=120&h=120&trade_key=123pan-thumbnail",
            pan123ThumbnailUrl(downloadUrl)
        )
    }

    @Test
    fun `没有 query 时用问号起头`() {
        assertEquals(
            "https://download-cdn.cjjd19.com/a/c-m8007?w=120&h=120&trade_key=123pan-thumbnail",
            pan123ThumbnailUrl("https://download-cdn.cjjd19.com/a/c-m8007")
        )
    }

    @Test
    fun `已有的 w h 与 trade_key 要剥掉再各写一次`() {
        // 重复的 trade_key 会让 CDN 回原图（实测），所以不能简单追加
        val withStale =
            "https://download-cdn.cjjd19.com/a/c-m8007?v=5&w=400&h=400&trade_key=123pan-thumbnail&t=1"
        assertEquals(
            "https://download-cdn.cjjd19.com/a/c-m8007?v=5&t=1&w=120&h=120&trade_key=123pan-thumbnail",
            pan123ThumbnailUrl(withStale)
        )
    }

    @Test
    fun `列表自带的 Thumbnail 字段优先`() {
        assertEquals(
            "https://cdn.example.com/thumb.jpg",
            pan123ThumbnailUrl(downloadUrl, platformThumbnail = " https://cdn.example.com/thumb.jpg ")
        )
        // JSON null 经 optString 会变成字符串 "null"：不能当缩略图，也不能挡住后面的拼装
        assertEquals(
            "$downloadUrl&w=120&h=120&trade_key=123pan-thumbnail",
            pan123ThumbnailUrl(downloadUrl, platformThumbnail = "null")
        )
    }

    @Test
    fun `片段参数拼在井号之前`() {
        assertEquals(
            "https://download-cdn.cjjd19.com/a/c-m8007?w=120&h=120&trade_key=123pan-thumbnail#frag",
            pan123ThumbnailUrl("https://download-cdn.cjjd19.com/a/c-m8007#frag")
        )
    }

    @Test
    fun `空地址与明文地址都返回空串`() {
        assertEquals("", pan123ThumbnailUrl(""))
        assertEquals("", pan123ThumbnailUrl("   "))
        // 应用全局禁明文，http 图必然加载失败，不如直接当没有缩略图
        assertEquals("", pan123ThumbnailUrl("http://download-cdn.cjjd19.com/a/c-m8007"))
    }
}
