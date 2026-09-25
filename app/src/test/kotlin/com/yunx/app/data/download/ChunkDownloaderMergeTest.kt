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

import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mergeChunksToStream 是大文件「峰值占用 1 份」的核心保证：
 * 边写边删（写完整片立即释放空间）+ 返回的字节数必须等于分片总长（大小校验依据）。
 */
class ChunkDownloaderMergeTest {

    private val downloader = ChunkDownloader { OkHttpClient() }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "yunx_merge_${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun writesAllBytesAndDeletesEachPart() = runBlocking {
        val dir = tempDir()
        try {
            val parts = listOf(
                File(dir, "part_0").apply { writeBytes(ByteArray(300_000) { 1 }) },
                File(dir, "part_1").apply { writeBytes(ByteArray(100_000) { 2 }) }
            )
            val out = java.io.ByteArrayOutputStream()
            val written = downloader.mergeChunksToStream(parts, out)
            assertEquals(400_000L, written)
            assertEquals(400_000, out.size())
            assertFalse("写完整片后必须立即删除分片（否则峰值占用翻倍）", parts.any { it.exists() })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun keepsPartThatFailedToWrite() {
        val dir = tempDir()
        try {
            val first = File(dir, "part_0").apply { writeBytes(ByteArray(10)) }
            val second = File(dir, "part_1").apply { writeBytes(ByteArray(10)) }
            // 只接受 10 字节，第二片写入时抛 ENOSPC 同款 IOException
            val full = object : OutputStream() {
                private var written = 0
                override fun write(b: Int) {
                    if (written + 1 > 10) throw IOException("write failed: ENOSPC (No space left on device)")
                    written++
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (written + len > 10) throw IOException("write failed: ENOSPC (No space left on device)")
                    written += len
                }
            }
            assertThrows(IOException::class.java) {
                runBlocking<Unit> { downloader.mergeChunksToStream(listOf(first, second), full) }
            }
            assertFalse("已完整写入目标的分片应已释放", first.exists())
            assertTrue("未写完的分片必须保留，避免失败后凭空少数据", second.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
