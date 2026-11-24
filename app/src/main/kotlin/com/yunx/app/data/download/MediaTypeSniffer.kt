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

/**
 * 按**文件头**纠正扩展名。
 *
 * 背景（2026-11 实测）：微信公众号图片的 `wx_fmt` 参数并不可信 —— URL 写 `wx_fmt=jpeg`，服务器实际可能给
 * PNG/WebP；解析器只能照参数写扩展名，于是出现「文件名 .jpg、相册打不开」。
 * 保存前拿合并后的第一个分片读 16 字节，按魔数判断真实图片格式并改扩展名，是成本最低的可靠做法。
 *
 * ★ **只处理图片**：音频/视频的容器格式（mp4/m4a/ts…）用 `ftyp` 一类魔数区分不可靠，
 *   一旦误判（m4a→mp4）会破坏播放，收益远小于风险，所以这里不碰它们。
 */
internal object MediaTypeSniffer {

    /** 允许被纠正的「图片」扩展名 */
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    /** 根据文件头判断图片真实扩展名；判不出返回 null */
    fun sniffImageExtension(head: ByteArray): String? {
        if (head.size >= 3 &&
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        ) return "jpg"
        if (head.size >= 8 &&
            head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() &&
            head[4] == 0x0D.toByte() && head[5] == 0x0A.toByte() && head[6] == 0x1A.toByte() && head[7] == 0x0A.toByte()
        ) return "png"
        if (head.size >= 12 &&
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() &&
            head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()
        ) return "webp"
        if (head.size >= 4 &&
            head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()
        ) return "gif"
        if (head.size >= 2 && head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte()) return "bmp"
        return null
    }

    /**
     * 用真实格式纠正 [fileName] 的扩展名；不需要/判不出时原样返回。
     *
     * 只在「当前扩展名属于图片」时生效，避免把 `.m4a`、`.zip` 这类误改。
     */
    fun correctImageName(head: ByteArray?, fileName: String): String {
        if (head == null || head.isEmpty()) return fileName
        val current = fileName.substringAfterLast('.', "").lowercase()
        if (current !in IMAGE_EXTS) return fileName
        val real = sniffImageExtension(head) ?: return fileName
        if (current == real || (current == "jpeg" && real == "jpg")) return fileName
        val base = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
        return "$base.$real"
    }

    /** 便捷重载：从文件读前 16 字节 */
    fun correctImageName(source: File?, fileName: String): String {
        val head = runCatching {
            source?.inputStream()?.use { it.readNBytes(16) }
        }.getOrNull()
        return correctImageName(head, fileName)
    }
}
