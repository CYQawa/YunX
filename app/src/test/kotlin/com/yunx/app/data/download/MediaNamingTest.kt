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

import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.data.network.video.VideoParseResult
import com.yunx.app.data.network.video.VideoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「详细文件命名」纯逻辑单测。
 *
 * ★ 为什么不碰 JSON：工程开了 `unitTests.isReturnDefaultValues = true`，JVM 单测里 android.jar 的
 *   `org.json` 是空壳（解析永远返回 null）。这里的入参是 data class，天然可以纯 JVM 测。
 */
class MediaNamingTest {

    private fun media(name: String = "", kind: VideoMediaKind = VideoMediaKind.IMAGE) =
        VideoMedia(url = "https://example.com/a.jpg", kind = kind, fileName = name)

    private fun result(
        platform: VideoPlatform = VideoPlatform.XHS,
        title: String = "",
        author: String = "",
        authorId: String = "",
        timestamp: Long = 0L,
        url: String = "",
        medias: List<VideoMedia> = listOf(media())
    ) = VideoParseResult(
        platform = platform,
        title = title,
        author = author,
        authorId = authorId,
        timestampSeconds = timestamp,
        url = url,
        medias = medias
    )

    @Test
    fun `平台简称按约定映射`() {
        assertEquals("xhs", MediaNaming.platformTag(VideoPlatform.XHS))
        assertEquals("dy", MediaNaming.platformTag(VideoPlatform.DOUYIN))
        assertEquals("ks", MediaNaming.platformTag(VideoPlatform.KUAISHOU))
        assertEquals("wb", MediaNaming.platformTag(VideoPlatform.WEIBO))
        assertEquals("acfun", MediaNaming.platformTag(VideoPlatform.ACFUN))
        assertEquals("bili", MediaNaming.platformTag(VideoPlatform.BILIBILI))
        assertEquals("pixiv", MediaNaming.platformTag(VideoPlatform.PIXIV))
    }

    @Test
    fun `类型段区分 photo video motion audio`() {
        assertEquals("photo", MediaNaming.kindTag(media(kind = VideoMediaKind.IMAGE)))
        assertEquals("video", MediaNaming.kindTag(media(kind = VideoMediaKind.VIDEO)))
        assertEquals("audio", MediaNaming.kindTag(media(kind = VideoMediaKind.AUDIO)))
        assertEquals(
            "motion",
            MediaNaming.kindTag(media(kind = VideoMediaKind.IMAGE).copy(motionUrl = "https://example.com/a.mp4"))
        )
    }

    @Test
    fun `单张不补零`() {
        val named = MediaNaming.detailed(result(medias = listOf(media("a.jpg")))).medias.single().fileName
        assertTrue("单张序号应为 1：$named", named.endsWith("_photo_1.jpg"))
    }

    @Test
    fun `13 张按两位补零`() {
        val medias = (1..13).map { media("a$it.jpg") }
        val names = MediaNaming.detailed(result(medias = medias)).medias.map { it.fileName }
        assertTrue(names[0].endsWith("_photo_01.jpg"))
        assertTrue(names[12].endsWith("_photo_13.jpg"))
    }

    @Test
    fun `104 张按三位补零`() {
        val medias = (1..104).map { media("a$it.jpg") }
        val names = MediaNaming.detailed(result(medias = medias)).medias.map { it.fileName }
        assertTrue(names[0].endsWith("_photo_001.jpg"))
        assertTrue(names[103].endsWith("_photo_104.jpg"))
    }

    @Test
    fun `标题超过 15 个字符被截断`() {
        val title = "一二三四五六七八九十一二三四五六七八九十"
        val named = MediaNaming.detailed(result(title = title)).medias.single().fileName
        assertTrue("标题应截到 15 字：$named", named.contains("一二三四五六七八九十一二三四五"))
        assertFalse("不应包含超出部分：$named", named.contains("一二三四五六七八九十一二三四五六"))
    }

    @Test
    fun `按完整字符截断不会切坏 emoji`() {
        val emoji = "😀".repeat(20)
        val named = MediaNaming.detailed(result(title = emoji)).medias.single().fileName
        // 15 个 emoji = 30 个 UTF-16 单元；若按 take(15) 会切出半个代理对（出现 U+FFFD 或孤代理）
        val part = named.substringAfter("xhs_").substringBefore("_photo")
        assertEquals("应保留 15 个完整 emoji", 15, part.codePointCount(0, part.length))
        assertFalse("不应出现替换字符", part.contains('\uFFFD'))
    }

    @Test
    fun `无标题时不产生连续下划线`() {
        val named = MediaNaming.detailed(result(title = "", author = "作者", authorId = "123"))
            .medias.single().fileName
        assertEquals("xhs_作者(@123)_photo_1.jpg", named)
    }

    @Test
    fun `时间为 0 时省略时间段`() {
        val named = MediaNaming.detailed(result(title = "T", timestamp = 0L)).medias.single().fileName
        assertFalse("不应含时间段：$named", Regex("""_\d{8}-\d{4}_""").containsMatchIn(named))
    }

    @Test
    fun `有时间时格式为 yyyyMMdd-HHmm`() {
        val named = MediaNaming.detailed(result(title = "T", timestamp = 1_767_225_600L))
            .medias.single().fileName
        assertTrue("应含时间段：$named", Regex("""_\d{8}-\d{4}_""").containsMatchIn(named))
    }

    @Test
    fun `沿用原始扩展名`() {
        val named = MediaNaming.detailed(result(medias = listOf(media("x.mp4", VideoMediaKind.VIDEO))))
            .medias.single().fileName
        assertTrue("应保留 .mp4：$named", named.endsWith(".mp4"))
    }

    @Test
    fun `文件夹名包含平台作者与时间`() {
        val name = MediaNaming.folderName(
            result(author = "作者", authorId = "123", timestamp = 1_767_225_600L, url = "https://example.com/note/abc")
        )
        assertTrue("应以平台开头：$name", name.startsWith("xhs_"))
        assertTrue("应含作者与 ID：$name", name.contains("作者(@123)"))
        assertTrue("应含时间：$name", Regex("""\d{8}-\d{4}""").containsMatchIn(name))
    }

    @Test
    fun `文件夹名在作者与时间都缺失时用作品 ID 兜底`() {
        val name = MediaNaming.folderName(result(url = "https://www.xiaohongshu.com/explore/abc123"))
        assertEquals("xhs_abc123", name)
    }

    @Test
    fun `文件夹名不会因为作者名为空而产生空段`() {
        val name = MediaNaming.folderName(result(timestamp = 1_767_225_600L))
        assertFalse("不应出现连续下划线：$name", name.contains("__"))
    }
}
