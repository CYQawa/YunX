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

/** 扩展名按文件头纠正的纯逻辑单测（不碰 Android API）。 */
class MediaTypeSnifferTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val webp = bytes(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)
    private val gif = bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0)

    @Test
    fun `jpg 名字但实际是 png 时改成 png`() {
        assertEquals("a.png", MediaTypeSniffer.correctImageName(png, "a.jpg"))
    }

    @Test
    fun `png 名字但实际是 webp 时改成 webp`() {
        assertEquals("作品_1.webp", MediaTypeSniffer.correctImageName(webp, "作品_1.png"))
    }

    @Test
    fun `格式一致时不动`() {
        assertEquals("a.jpg", MediaTypeSniffer.correctImageName(jpeg, "a.jpg"))
        assertEquals("a.jpeg", MediaTypeSniffer.correctImageName(jpeg, "a.jpeg"))
        assertEquals("a.gif", MediaTypeSniffer.correctImageName(gif, "a.gif"))
    }

    @Test
    fun `非图片扩展名一律不碰`() {
        assertEquals("song.m4a", MediaTypeSniffer.correctImageName(png, "song.m4a"))
        assertEquals("pack.zip", MediaTypeSniffer.correctImageName(png, "pack.zip"))
    }

    @Test
    fun `判不出格式时保持原名`() {
        assertEquals("a.jpg", MediaTypeSniffer.correctImageName(bytes(1, 2, 3, 4), "a.jpg"))
        assertEquals("a.jpg", MediaTypeSniffer.correctImageName(null as ByteArray?, "a.jpg"))
    }

    @Test
    fun `保留目录前缀与多级扩展名`() {
        assertEquals("标题/封面_1.png", MediaTypeSniffer.correctImageName(png, "标题/封面_1.jpg"))
    }
}
