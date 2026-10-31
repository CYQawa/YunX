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

package com.yunx.app.data.upload

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 用户在系统文件选择器里选中的本地文件。
 *
 * 只保存 uri 与上传前就要用的元数据（文件名 / 大小 / MIME），**不缓存任何字节**：
 * 各家上传协议都要先报 size 和哈希，再按区间分片读取，所以这里提供 [open] 按需开流。
 *
 * ★ 用 SAF（`OpenDocument`）而不是直接读路径：Android 10+ 的分区存储下，别的应用提供的文件
 *   根本拿不到可读路径，而 uri 能一直授权到本进程结束。
 */
data class UploadSource(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
) {

    /**
     * 打开文件的 `[start, end)` 区间流（调用方负责 close）。
     *
     * 优先走 `openFileDescriptor` + `FileChannel.position`：分片上传和分块哈希会反复跳区间，
     * 能 seek 就不用每次都把前面的字节重读一遍。Provider 给不出可定位的 fd（云端文档、
     * 压缩包透传等）时退回「重开流 + 跳过 start 字节」，慢但正确。
     */
    fun open(resolver: ContentResolver, start: Long = 0L, end: Long = size): InputStream {
        if (start < 0 || end < start || end > size) {
            throw IllegalArgumentException("上传读取范围无效")
        }
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (descriptor != null) {
            // AutoCloseInputStream：流关闭时连带关掉 ParcelFileDescriptor，避免 fd 泄漏
            val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            val positioned = runCatching { stream.channel.position(start) }.isSuccess
            if (positioned) return BoundedInputStream(stream, end - start)
            runCatching { stream.close() }
        }
        val raw = resolver.openInputStream(uri) ?: throw IOException("无法打开所选文件")
        if (start > 0) {
            val buffer = ByteArray(SKIP_BUFFER)
            var remaining = start
            while (remaining > 0) {
                val read = raw.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) {
                    runCatching { raw.close() }
                    throw IOException("读取本地文件不完整，请重新选择")
                }
                remaining -= read
            }
        }
        return BoundedInputStream(raw, end - start)
    }

    companion object {
        private const val SKIP_BUFFER = 64 * 1024

        private val MIME_PATTERN = Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$")

        /**
         * 把选择器返回的 uri 解析成 [UploadSource]。
         *
         * 文件名/大小取自 `OpenableColumns`；Provider 没给大小时先问 `AssetFileDescriptor.length`，
         * 再不行才把文件读一遍数出来 —— 各家上传协议都必须先报 size，缺了这一步根本发不出去。
         * 实在读不出大小返回 null（调用方跳过该文件并提示）。
         */
        fun resolve(resolver: ContentResolver, uri: Uri): UploadSource? {
            var name = uri.lastPathSegment.orEmpty()
            var size = -1L
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            name = cursor.getString(nameIndex) ?: name
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            }
            if (size < 0) {
                size = runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                }.getOrDefault(-1L)
            }
            if (size < 0) {
                size = runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(SKIP_BUFFER)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                        }
                        total
                    } ?: -1L
                }.getOrDefault(-1L)
            }
            if (size < 0) return null
            // Provider 给的 MIME 有时带参数或干脆是脏值（"image/png image/jpeg"）：
            // 它会被塞进上传请求的 Content-Type，必须过一遍白名单再下发
            val rawType = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
            val type = rawType.substringBefore(';').trim()
                .takeIf { MIME_PATTERN.matches(it) }
                ?: "application/octet-stream"
            return UploadSource(
                uri = uri,
                name = name.trim().ifBlank { "未命名文件" },
                size = size,
                mimeType = type
            )
        }
    }
}

/** 只允许读出前 [remaining] 个字节的包装流：multipart / 固定长度请求体必须按声明长度收尾 */
private class BoundedInputStream(
    source: InputStream,
    private var remaining: Long
) : FilterInputStream(source) {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val read = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }

    override fun skip(count: Long): Long {
        val skipped = super.skip(minOf(count, remaining))
        if (skipped > 0) remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
}
