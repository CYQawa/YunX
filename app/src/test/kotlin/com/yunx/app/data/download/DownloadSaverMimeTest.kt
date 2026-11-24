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

package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** 保存层 MIME 推断单测：图片必须按真实扩展名给 MIME，否则 SAF 会补出 `.png.jpg`。 */
class DownloadSaverMimeTest {

    @Test
    fun `图片按真实格式给 MIME`() {
        assertEquals("image/png", DownloadSaver.mimeOf("a.png"))
        assertEquals("image/jpeg", DownloadSaver.mimeOf("a.jpg"))
        assertEquals("image/jpeg", DownloadSaver.mimeOf("a.JPEG"))
        assertEquals("image/gif", DownloadSaver.mimeOf("a.gif"))
        assertEquals("image/webp", DownloadSaver.mimeOf("a.webp"))
        assertEquals("image/bmp", DownloadSaver.mimeOf("a.bmp"))
    }

    @Test
    fun `带目录与双扩展名时只看最后一段`() {
        assertEquals("image/png", DownloadSaver.mimeOf("标题/封面_1.png"))
        assertEquals("application/octet-stream", DownloadSaver.mimeOf("a.png.bak"))
    }

    @Test
    fun `非图片类型不受影响`() {
        assertEquals("video/mp4", DownloadSaver.mimeOf("v.mp4"))
        assertEquals("audio/mp4", DownloadSaver.mimeOf("a.m4a"))
        assertEquals("application/pdf", DownloadSaver.mimeOf("d.pdf"))
    }
}
