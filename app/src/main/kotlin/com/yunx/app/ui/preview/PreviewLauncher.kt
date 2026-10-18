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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 预览类型。文本预览**仅网盘**提供（见 Agent.md §3.42）。 */
enum class PreviewKind { IMAGE, VIDEO, AUDIO, TEXT }

/**
 * 一条待预览的媒体。
 *
 * ★ [headers] 是必需的而不是可选的：网盘直链与 B站/微博/Pixiv 的直链都校验 `Referer`/`Cookie`，
 *   少一个头浏览器能看、我们这边直接 403。所以请求头跟着媒体一起传，播放器与图片加载器都要用。
 */
data class PreviewMedia(
    val url: String,
    val kind: PreviewKind,
    val title: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** m3u8：交给 ExoPlayer 的 HLS 处理 */
    val isHls: Boolean = false,
    /**
     * 合并音轨地址（哔哩哔哩 DASH 分轨）。
     * 非空表示这条媒体是「音视频分离」的：预览端用 media3 的 `MergingMediaSource` 把两路流式合并播放
     * （所以**预览也有声音**），下载端由 `DashMuxer` 合轨成一个 mp4。
     */
    val audioUrl: String = "",
    val audioHeaders: Map<String, String> = emptyMap()
)

/** 一次预览请求：要么是若干媒体（可左右翻页），要么是一个文本文件 */
data class PreviewRequest(
    val media: List<PreviewMedia> = emptyList(),
    val startIndex: Int = 0,
    val textUrl: String = "",
    val textFileName: String = "",
    val textHeaders: Map<String, String> = emptyMap(),
    val title: String = "",
    /**
     * 非空时预览页顶栏出现「下载」按钮，入参是**当前页下标**（与 [media] 的下标一一对应）。
     *
     * ★ 用「下标回调」而不是把 `VideoMedia` 塞进 [PreviewMedia]：预览只关心能播能看的那几个字段，
     *   而下载还要 `fileName` / 合轨音轨等 —— 让调用方（结果页）拿着下标回自己那份列表取原始条目，
     *   两边都不必为了对方改自己的数据模型。网盘预览不传它，于是网盘预览页没有下载按钮（行为不变）。
     */
    val onDownload: ((Int) -> Unit)? = null
)

/**
 * 全局预览入口（与 `SnackbarController` 同一思路）。
 *
 * ★ 为什么用全局入口而不是把回调一层层传下去：能调起预览的地方很分散 —— 分享解析的文件列表、
 *   各网盘云盘浏览、视频解析结果、将来还有更多；逐个往下传回调会牵动十几个文件的参数表，
 *   而预览是「和当前页面无关的一次性动作」，与提示的性质相同。
 */
object PreviewLauncher {

    private val _request = MutableStateFlow<PreviewRequest?>(null)

    /** MainScreen 订阅它来挂载预览页（一次性：页面关闭后置空） */
    val request: StateFlow<PreviewRequest?> = _request.asStateFlow()

    fun openMedia(
        items: List<PreviewMedia>,
        startIndex: Int = 0,
        title: String = "",
        onDownload: ((Int) -> Unit)? = null
    ) {
        val valid = items.filter { it.url.isNotBlank() }
        if (valid.isEmpty()) return
        _request.value = PreviewRequest(
            media = valid,
            startIndex = startIndex.coerceIn(0, valid.lastIndex),
            title = title,
            onDownload = onDownload
        )
    }

    fun openText(url: String, fileName: String, headers: Map<String, String> = emptyMap(), title: String = fileName) {
        if (url.isBlank()) return
        _request.value = PreviewRequest(textUrl = url, textFileName = fileName, textHeaders = headers, title = title)
    }

    fun dismiss() {
        _request.value = null
    }
}

/**
 * 「这个文件能不能在线预览」——由扩展名判断，供网盘文件菜单与预览页共用。
 *
 * 压缩包、安装包、未知类型一律返回 null（不显示预览入口）：
 * 与其给一个点了必然失败的菜单项，不如根本不显示。
 */
object PreviewTypes {

    /** 常见纯文本类扩展名（含代码/配置/字幕，都是「按文本看」比下载下来更有用的） */
    private val textExtensions = setOf(
        "txt", "text", "log", "md", "markdown", "json", "xml", "csv", "tsv", "ini", "conf", "cfg",
        "yml", "yaml", "properties", "toml", "srt", "ass", "vtt", "html", "htm", "css", "js", "kt",
        "java", "py", "sh", "bat", "sql", "gradle", "pro"
    )

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif"
    )

    private val videoExtensions = setOf(
        "mp4", "mkv", "mov", "avi", "flv", "webm", "ts", "m4v", "m3u8", "rmvb", "wmv", "3gp"
    )

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "ape"
    )

    /** 扩展名（小写，不含点）；没有扩展名返回空串 */
    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..8 }.orEmpty()

    /** 可预览类型；不可预览返回 null */
    fun kindOf(fileName: String): PreviewKind? = when (extensionOf(fileName)) {
        in textExtensions -> PreviewKind.TEXT
        in imageExtensions -> PreviewKind.IMAGE
        in videoExtensions -> PreviewKind.VIDEO
        in audioExtensions -> PreviewKind.AUDIO
        else -> null
    }

    /** 文本预览是否渲染成 Markdown（只有 md/markdown 走渲染，其余保持等宽纯文本） */
    fun isMarkdown(fileName: String): Boolean = extensionOf(fileName) in setOf("md", "markdown")
}
