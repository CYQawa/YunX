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

/** 媒体类型：视频（mp4/flv/m3u8）、图片（图集/九宫格）、音频（网易云那类单文件） */
enum class VideoMediaKind { VIDEO, IMAGE, AUDIO }

/**
 * 一条可下载的媒体。
 *
 * 与网盘的 `DownloadLink` 的区别：这里**逐个媒体自带请求头**——各平台的直链往往要求特定的
 * `Referer` / `User-Agent` / `Cookie` 才不返回 403，而这个头是解析那一刻才知道的
 * （例如抖音要用分享页作 Referer），所以跟随媒体一起往上传，最终由 `DownloadManager` 加密落库。
 */
data class VideoMedia(
    val url: String,
    val kind: VideoMediaKind,
    val fileName: String = "",
    val size: Long = -1L,
    /** m3u8 流：交给现有的 HLS 下载路径（AcFun 用） */
    val isHls: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    /** 清晰度/规格标签，仅用于界面展示（如 `720P`、`原图`） */
    val quality: String = "",
    /**
     * 需要合轨的**音轨地址**（哔哩哔哩 DASH：高清是音视频分离的两路流）。
     *
     * 非空表示这条媒体是分轨的：下载器会把 [url] 当视频轨、本字段当音轨各自下载后合轨成一个 mp4
     * （见 `DashMuxer`，MediaExtractor + MediaMuxer 逐样本重封装）。预览端用 media3 的
     * `MergingMediaSource` 把两路流式合并，所以**预览也有声音**，界面无需再挂「无声」提示。
     * 空 = 单文件直链，与其它平台行为一致。
     */
    val audioUrl: String = "",
    /** 音轨的请求头（各平台校验 Referer/Cookie，缺了直接 403） */
    val audioHeaders: Map<String, String> = emptyMap()
)

/**
 * 一次解析的结果：**一个作品**（视频/图文/图集）。
 *
 * 不落库（不做历史记录），只在解析页内存里存活，所以字段可以随意增删，不需要 Room 迁移。
 */
data class VideoParseResult(
    val platform: VideoPlatform,
    val title: String,
    val author: String = "",
    val avatar: String = "",
    val cover: String = "",
    /** 正文/描述（小红书笔记、微博正文那类） */
    val text: String = "",
    /** 发布时间戳（秒），0 表示未知 */
    val timestampSeconds: Long = 0L,
    /** 作品原始网页地址 */
    val url: String = "",
    val medias: List<VideoMedia> = emptyList(),
    /**
     * 非致命提示：解析成功、但部分内容降级了（如小红书去水印原图不可用、已回退到平台地址）。
     *
     * 空串 = 一切正常。界面据此**弹一次**提示即可，不要去改 [medias] 里已有的地址 ——
     * 降级与否在解析阶段就已定好，这里只是把原因告诉用户。
     */
    val notice: String = ""
) {
    val videoCount: Int get() = medias.count { it.kind == VideoMediaKind.VIDEO }
    val imageCount: Int get() = medias.count { it.kind == VideoMediaKind.IMAGE }
}

/** 解析失败的原因分类 —— 决定给用户看什么话，而不是把 HTTP 状态码甩出去 */
enum class VideoParseErrorKind {
    /** 被平台风控拦截（403/418/验证码页） */
    BLOCKED,

    /** 内容不存在/已删除/私密 */
    NOT_FOUND,

    /** 网络请求失败（超时、DNS、连接被拒） */
    NETWORK,

    /** 链接形态不支持，或该平台必须登录但当前没有登录态 */
    UNSUPPORTED,

    /** 其他（响应格式变了、平台改版等） */
    UNKNOWN
}

/**
 * 视频解析异常。
 *
 * ★ 各解析器**必须**用它抛出失败，不要抛裸的 `IOException`：上层要靠 [kind] 决定提示文案，
 *   比如风控拦截要提示「请稍后重试」而不是静默失败（微博/小红书匿名解析偶发 403/418 是常态）。
 */
class VideoParseException(
    val kind: VideoParseErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** 面向用户的一句话提示（细节留在 message 里给日志用） */
fun VideoParseException.userMessage(): String = when (kind) {
    VideoParseErrorKind.BLOCKED -> "平台风控拦截了本次请求，请稍后重试"
    VideoParseErrorKind.NOT_FOUND -> "内容不存在、已删除或不可见"
    VideoParseErrorKind.NETWORK -> "网络请求失败，请检查网络后重试"
    VideoParseErrorKind.UNSUPPORTED -> message?.takeIf { it.isNotBlank() } ?: "暂不支持该链接"
    VideoParseErrorKind.UNKNOWN -> message?.takeIf { it.isNotBlank() } ?: "解析失败，请稍后重试"
}
