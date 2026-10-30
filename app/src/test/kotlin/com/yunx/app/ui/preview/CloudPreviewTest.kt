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

package com.yunx.app.ui.preview

import com.yunx.app.data.network.model.ShareFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云盘页「在线预览」入口可见性单测。
 *
 * 重点不在「图片能显示」，而在「不该出现的入口一个都别出现」：菜单项一旦显示，点下去就要取一次直链，
 * 而各网盘的直链是按次/带配额的（夸克每次取链都算一次额度、百度 dlink 会过期），
 * 文件夹、压缩包、以及当前还没放开的视频/音频/文本都必须判 false。
 */
class CloudPreviewTest {

    private fun file(name: String, isDir: Boolean = false) = ShareFile(
        fid = "1",
        fname = name,
        fsize = 1024,
        isdir = isDir,
        pdirFid = "0",
        fidToken = ""
    )

    @Test
    fun `图片扩展名给预览入口`() {
        assertTrue(CloudPreview.canPreview(file("photo.jpg")))
        assertTrue(CloudPreview.canPreview(file("A.JPEG")))
        assertTrue(CloudPreview.canPreview(file("动画.webp")))
        assertTrue(CloudPreview.canPreview(file("原图.avif")))
    }

    @Test
    fun `文件夹即使名字像图片也不给入口`() {
        assertFalse(CloudPreview.canPreview(file("照片.jpg", isDir = true)))
    }

    @Test
    fun `当前未放开的类型不给入口`() {
        assertFalse(CloudPreview.canPreview(file("video.mp4")))
        assertFalse(CloudPreview.canPreview(file("song.flac")))
        assertFalse(CloudPreview.canPreview(file("readme.txt")))
        assertFalse(CloudPreview.canPreview(file("archive.zip")))
        // 没有扩展名 / 扩展名过长（不是真扩展名，PreviewTypes 会判成不可预览）
        assertFalse(CloudPreview.canPreview(file("README")))
        assertFalse(CloudPreview.canPreview(file("x.verylongext")))
    }
}
