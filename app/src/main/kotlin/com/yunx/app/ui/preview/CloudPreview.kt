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

/**
 * 云盘浏览页（各网盘「我的网盘」目录）的在线预览入口。
 *
 * 与分享解析页（`ShareDetailScreen` → `ResolveViewModel.openPreview`）走的是同一条路：
 * 各云盘的图片/视频**只能通过取链拿原始直链**，没有统一的公开预览接口 ——
 * 所以先把下载直链取出来，再把直链与请求头交给全局预览页 [PreviewLauncher]。
 * 取链本身留在各云盘 ViewModel（凭证、token、Cookie 各不相同，UI 层无法统一）。
 *
 * ★ **放开到什么程度**：只要 [PreviewTypes] 认识的类型就给入口（图片 / 视频 / 音频 / 文本），
 *   与分享解析页一致。以前这里只放开图片（直链按次/带时效，怕视频播放再叠一层 Range 风险）；
 *   现在视频/音频**是流式播放**（ExoPlayer 按 HTTP Range 边下边播，不整段下载），文本也只读前
 *   512KB（见 `PreviewTextLoader`）—— 代价与「点开看一眼」相称，所以不再按类型收紧。
 *   ★ 各平台直链都支持 Range：内置下载器的分片/断点续传本来就依赖它。真遇到某平台播放失败，
 *   先排查取链与请求头（下载能通、预览 403 几乎都是少 Referer/Cookie/UA），再看该 CDN 是否无视 Range。
 *
 * ★ 文本走 [PreviewLauncher.openText] 而不是媒体列表：预览页对文本有独立的读取/渲染路径
 *   （Markdown 渲染 / 等宽纯文本），塞进媒体列表只会得到一句「请用文本预览打开」。
 */
internal object CloudPreview {

    /** 云盘页是否给该文件「在线预览」入口：文件夹与 [PreviewTypes] 不认识的后缀一律不给 */
    fun canPreview(file: ShareFile): Boolean =
        !file.isdir && PreviewTypes.kindOf(file.fname) != null

    /**
     * 取链成功后打开预览。
     *
     * @param headers 必须与下载**完全相同**的那一份（Cookie / Referer / UA）：
     *   网盘 CDN 少一个头就 403，而下载能通、预览白屏的差别往往就在这里。
     * @param isHls 直链是不是 m3u8（UC 的 video_preview 回退链路会下转发码流）：
     *   这类地址不一定带 `.m3u8` 后缀，播放器无法靠后缀识别，必须显式告诉它（见 `MediaPreviewScreen`）。
     */
    fun open(
        file: ShareFile,
        url: String,
        headers: Map<String, String>,
        isHls: Boolean = false
    ) {
        if (url.isBlank()) return
        val kind = PreviewTypes.kindOf(file.fname) ?: return
        if (kind == PreviewKind.TEXT) {
            PreviewLauncher.openText(url = url, fileName = file.fname, headers = headers, title = file.fname)
            return
        }
        PreviewLauncher.openMedia(
            items = listOf(
                PreviewMedia(
                    url = url,
                    kind = kind,
                    title = file.fname,
                    headers = headers,
                    isHls = isHls
                )
            ),
            title = file.fname
        )
    }
}
