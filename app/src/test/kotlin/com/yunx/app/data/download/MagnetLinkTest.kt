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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 磁力链接识别与显示名提取（纯逻辑，不联网、不碰 android.net.Uri）。
 *
 * 为什么这些用例必须是纯 JVM 能跑的：JVM 单测里 `android.net.Uri` 是空壳
 * （`unitTests.isReturnDefaultValues = true` ⇒ 方法全返回 null/0），一旦解析写在 Uri 上就测不了。
 */
class MagnetLinkTest {

    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun recognisesMagnetScheme() {
        assertTrue(MagnetLink.isMagnet("magnet:?xt=urn:btih:$hash"))
        // 粘进来常带前后空白 / 大小写混写
        assertTrue(MagnetLink.isMagnet("  MAGNET:?xt=urn:btih:$hash  "))
        assertFalse(MagnetLink.isMagnet("https://example.com/a.bin"))
        assertFalse(MagnetLink.isMagnet("magnet:"))
        assertFalse(MagnetLink.isMagnet(""))
    }

    @Test
    fun extractsDisplayNameFromDn() {
        val url = "magnet:?xt=urn:btih:$hash&dn=Ubuntu%2022.04%20Live.iso&tr=udp%3A%2F%2Ftracker"
        assertEquals("Ubuntu 22.04 Live.iso", MagnetLink.displayName(url))
    }

    @Test
    fun keepsLiteralPlusInDisplayName() {
        // `dn` 用的百分号编码（空格是 %20），所以 `+` 必须按字面保留，否则文件名会被吃掉加号
        val url = "magnet:?xt=urn:btih:$hash&dn=C%2B%2B%20Primer.pdf"
        assertEquals("C++ Primer.pdf", MagnetLink.displayName(url))
    }

    @Test
    fun fallsBackToInfoHashWhenNoDisplayName() {
        // 没有 dn：用 info hash 前 8 位占位（元数据到手后会被真正的种子名覆盖）
        assertEquals("磁力_01234567", MagnetLink.displayName("magnet:?xt=urn:btih:$hash"))
        // 连 info hash 都没有：给一个不会变成时间戳的兜底名
        assertEquals("磁力任务", MagnetLink.displayName("magnet:?dn="))
    }

    @Test
    fun truncatesOverlongDisplayName() {
        val long = "A".repeat(200)
        assertEquals(80, MagnetLink.displayName("magnet:?xt=urn:btih:$hash&dn=$long").length)
    }

    @Test
    fun extractsInfoHash() {
        assertEquals(hash.uppercase(), MagnetLink.infoHash("magnet:?xt=urn:btih:$hash"))
        // base32 形式的 info hash 也认得
        assertEquals("MFRGGZDFMZTWQ2LK", MagnetLink.infoHash("magnet:?xt=urn:btih:mfrggzdfmztwq2lk"))
        assertEquals("", MagnetLink.infoHash("magnet:?dn=no-hash-here"))
    }

    @Test
    fun displayNameTakesFirstOccurrenceOnly() {
        // dn 出现多次时只认第一个（有的聚合站会把追踪参数也塞一遍）
        val url = "magnet:?dn=First.iso&dn=Second.iso&xt=urn:btih:$hash"
        assertEquals("First.iso", MagnetLink.displayName(url))
    }
}
