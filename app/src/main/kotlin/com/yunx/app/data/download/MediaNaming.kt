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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「详细命名」：`平台_作者(@ID)_标题_时间_类型_序号.扩展名`
 *
 * 例：`xhs_<作者>(@<ID>)_<标题>_20260101-1200_photo_1.jpg`（示例值均为占位符）
 * 无标题时（微博常见）：`wb_某某(@1234567890)_20261123-2126_video_1.mp4`（标题段整段省略）
 *
 * 设计约束（都是踩过的坑）：
 * - 分段里的内容一律过 [DownloadPathPolicy.sanitizeName]：标题/昵称里带 `/`、`:` 会直接毁掉保存路径；
 * - **标题截 20 字**（用户要求：标题只有这一项缩）：文件名上限 120 字符，加上时间/ID 段后必须收紧；
 * - 时间取「内容发布时间」，格式 `yyyyMMdd-HHmm`（设备本地时区）；取不到（时间戳=0）就**不加这一段**，
 *   不写 `19700101-0000` 这种假值；
 * - 序号按**总张数**补零：13 张 → `01..13`，104 张 → `001..104`；单张不补零（`1`）；
 * - 作者或 ID 缺失时整段省略，不留 `_(@)_` 这种空洞；
 * - 只改 `fileName`，不动 url / headers / kind 等任何下载相关字段。
 */
object MediaNaming {

    /** 平台简称（用户指定：xhs / dy / ks / wb / acfun / bili / pixiv） */
    fun platformTag(platform: VideoPlatform): String = when (platform) {
        VideoPlatform.XHS -> "xhs"
        VideoPlatform.DOUYIN -> "dy"
        VideoPlatform.KUAISHOU -> "ks"
        VideoPlatform.WEIBO -> "wb"
        VideoPlatform.ACFUN -> "acfun"
        VideoPlatform.BILIBILI -> "bili"
        VideoPlatform.PIXIV -> "pixiv"
    }

    /** 类型段（英文）：动图单独算 motion，其余按媒体类型 */
    fun kindTag(media: VideoMedia): String = when {
        media.motionUrl.isNotBlank() -> "motion"
        media.kind == VideoMediaKind.IMAGE -> "photo"
        media.kind == VideoMediaKind.AUDIO -> "audio"
        else -> "video"
    }

    /**
     * 「详细命名」开启时，「全部下载」多文件用的**文件夹名**：`平台_作者(@ID)_时间`。
     *
     * ★ 为什么需要它：原逻辑拿**作品标题**当文件夹名，而微博图集这类内容没有标题，
     *   `sanitizeName("")` 得到空串 → 代码判定「不需要文件夹」→ 多张图平铺在下载目录里。
     *   这里改用「平台 + 作者 + 发布时间」拼一个**必然非空**的名字，同时保留可读性。
     */
    fun folderName(result: VideoParseResult): String {
        val authorPart = buildString {
            val name = truncate(DownloadPathPolicy.sanitizeName(result.author.trim()), 20)
            if (name.isNotBlank()) append(name)
            val id = result.authorId.trim()
            if (id.isNotBlank()) append("(@").append(DownloadPathPolicy.sanitizeName(id)).append(")")
        }
        val timePart = formatPublishTime(result.timestampSeconds)
        val parts = mutableListOf(platformTag(result.platform))
        if (authorPart.isNotBlank()) parts += authorPart
        if (timePart.isNotBlank()) parts += timePart
        // 兜底：作者与时间都缺时用作品 ID（URL 最后一段）区分，避免所有作品落进同一个「wb」目录
        if (authorPart.isBlank() && timePart.isBlank()) {
            result.url.substringAfterLast('/').substringBefore('?')
                .takeIf { it.isNotBlank() }
                ?.let { parts += DownloadPathPolicy.sanitizeName(it) }
        }
        return parts.joinToString("_").take(80)
    }

    /** 生成详细命名后的结果（不修改入参，返回副本） */
    fun detailed(result: VideoParseResult): VideoParseResult {
        val total = result.medias.size
        if (total == 0) return result
        val width = total.toString().length

        val authorPart = buildString {
            val name = truncate(DownloadPathPolicy.sanitizeName(result.author.trim()), 20)
            if (name.isNotBlank()) append(name)
            val id = result.authorId.trim()
            if (id.isNotBlank()) append("(@").append(DownloadPathPolicy.sanitizeName(id)).append(")")
        }
        val title = truncate(DownloadPathPolicy.sanitizeName(result.title.trim()), 15)
        val platform = platformTag(result.platform)
        // 内容发布时间（本地时区，精确到分）；解析器没给（=0）就整段省略
        val timePart = formatPublishTime(result.timestampSeconds)

        return result.copy(
            medias = result.medias.mapIndexed { index, media ->
                val seq = (index + 1).toString().padStart(width, '0')
                val ext = extensionOf(media)
                val name = buildString {
                    append(platform)
                    append('_')
                    if (authorPart.isNotBlank()) {
                        append(authorPart)
                        append('_')
                    }
                    if (title.isNotBlank()) {
                        append(title)
                        append('_')
                    }
                    if (timePart.isNotBlank()) {
                        append(timePart)
                        append('_')
                    }
                    append(kindTag(media))
                    append('_')
                    append(seq)
                    append('.')
                    append(ext)
                }
                media.copy(fileName = name)
            }
        )
    }

    /**
     * 按「完整字符」截断（`String.take(n)` 数的是 UTF-16 单元，可能把 emoji / 罕用汉字从中间切坏，
     * 变成半个代理对 → 文件名里出现乱码方块）。这里按 codePoint 数。
     */
    private fun truncate(text: String, maxCodePoints: Int): String {
        if (text.isEmpty()) return text
        if (text.codePointCount(0, text.length) <= maxCodePoints) return text
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints))
    }

    /** 内容发布时间 → `yyyyMMdd-HHmm`（设备本地时区）；时间戳 <=0 视为未知，返回空串（不写假时间） */
    private fun formatPublishTime(seconds: Long): String {
        if (seconds <= 0L) return ""
        return runCatching {
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(seconds * 1000L))
        }.getOrDefault("")
    }

    /** 沿用解析器给的原扩展名（`.mp4`/`.jpg`…）；拿不到时按类型兜底 */
    private fun extensionOf(media: VideoMedia): String {
        val raw = media.fileName.substringAfterLast('.', "")
        if (raw.isNotBlank() && raw.length <= 5 && raw.none { !it.isLetterOrDigit() }) return raw.lowercase()
        return when (media.kind) {
            VideoMediaKind.IMAGE -> "jpg"
            VideoMediaKind.AUDIO -> "m4a"
            else -> "mp4"
        }
    }
}
