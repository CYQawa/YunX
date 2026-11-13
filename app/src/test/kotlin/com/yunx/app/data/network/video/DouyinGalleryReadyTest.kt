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

class DouyinGalleryReadyTest {

    @Test
    fun extractSizeIsBoundedBeforeParsing() {
        val limit = 256 * 1024
        val payload = "{\"desc\":\"" + "a".repeat(limit - 11) + "\"}"
        assertEquals(limit, payload.length)
        assertTrue(DouyinParser.douyinExtractWithinLimits(payload))
        assertFalse(DouyinParser.douyinExtractWithinLimits(payload + " "))
    }

    @Test
    fun extractNestingIsBoundedOutsideStrings() {
        assertTrue(DouyinParser.douyinExtractWithinLimits("[".repeat(16) + "0" + "]".repeat(16)))
        assertFalse(DouyinParser.douyinExtractWithinLimits("[".repeat(17) + "0" + "]".repeat(17)))
        assertTrue(DouyinParser.douyinExtractWithinLimits(
            """{"desc":"\"[[[[[[[[[[[[[[[[[[[[\\","images":["https://example/a.jpg"]}"""
        ))
        assertFalse(DouyinParser.douyinExtractWithinLimits("{\"desc\":\"unterminated}"))
        assertFalse(DouyinParser.douyinExtractWithinLimits("{"))
        assertFalse(DouyinParser.douyinExtractWithinLimits("}"))
    }

    @Test
    fun lenientJsonCannotHideNesting() {
        assertFalse(DouyinParser.douyinExtractWithinLimits("{'desc':'value'}"))
        assertFalse(DouyinParser.douyinExtractWithinLimits("{/* comment */}"))
        assertFalse(DouyinParser.douyinExtractWithinLimits("{# comment\n}"))
    }

    @Test
    fun domOrEmptyHookDoesNotFinishTheWait() {
        // 修前 readyCheckJs 看到第一张作品图就返回 true，解析只带回这一张。
        assertFalse(DouyinParser.douyinGalleryReady("dom", 1, ""))
        assertFalse(DouyinParser.douyinGalleryReady("dom", 3, ""))
        assertFalse(DouyinParser.douyinGalleryReady("hook", 0, ""))
    }

    @Test
    fun ssrHookGalleryOrVideoFinishesTheWait() {
        assertTrue(DouyinParser.douyinGalleryReady("ssr", 3, ""))
        assertTrue(DouyinParser.douyinGalleryReady("hook", 3, ""))
        assertTrue(DouyinParser.douyinGalleryReady("none", 0, "https://v.example/play/a.mp4"))
    }

    @Test
    fun blankPayloadAndDomVideo() {
        assertFalse(DouyinParser.douyinGalleryReady("", 0, ""))
        assertFalse(DouyinParser.douyinGalleryReady("none", 0, ""))
        assertFalse(DouyinParser.douyinGalleryReady("ssr", 0, ""))
        assertTrue(DouyinParser.douyinGalleryReady("dom", 1, "https://v.example/play/a.mp4"))
    }

    @Test
    fun motionPlaceholdersStayOnTheirImage() {
        assertEquals(
            listOf("https://v.example/1.mp4", "", "https://v.example/3.mp4"),
            DouyinParser.douyinMotionPlaceholders(
                3,
                listOf("https://v.example/1.mp4", "", "https://v.example/3.mp4")
            )
        )
        assertEquals(
            listOf("https://v.example/1.mp4", "", ""),
            DouyinParser.douyinMotionPlaceholders(3, listOf("https://v.example/1.mp4"))
        )
        // 字面量 "null" 变成空位，下一张的 URL 仍留在自己的下标上。
        assertEquals(
            listOf("", "https://v.example/2.mp4", ""),
            DouyinParser.douyinMotionPlaceholders(3, listOf("null", "https://v.example/2.mp4"))
        )
    }
}
