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
 * 各云盘的图片**只能通过取链拿原图**，没有统一的公开缩略图/预览接口 ——
 * 所以先把下载直链取出来，再把直链与请求头交给全局预览页 [PreviewLauncher]。
 * 取链本身留在各云盘 ViewModel（凭证、token、Cookie 各不相同，UI 层无法统一）。
 *
 * ★ 这里只放开**图片**，不是漏了视频/音频/文本：
 * 云盘直链是一次性且带配额的（夸克每次取链都消耗一次、百度 dlink 有时效），
 * 图片是「点开看一眼」价值最高、失败代价最小的类型；播放还要额外验证 Range 与取链时效，
 * 等真机逐平台确认后再放开。分享解析页不受此限，仍按 [PreviewTypes] 放开全部可预览类型。
 */
internal object CloudPreview {

    /** 云盘页是否给该文件「在线预览」入口：仅图片，文件夹/压缩包等一律 false */
    fun canPreview(file: ShareFile): Boolean =
        !file.isdir && PreviewTypes.kindOf(file.fname) == PreviewKind.IMAGE

    /**
     * 取链成功后打开预览。
     *
     * @param headers 必须与下载**完全相同**的那一份（Cookie / Referer / UA）：
     *   网盘 CDN 少一个头就 403，而下载能通、预览白屏的差别往往就在这里。
     */
    fun open(file: ShareFile, url: String, headers: Map<String, String>) {
        if (url.isBlank()) return
        PreviewLauncher.openMedia(
            items = listOf(
                PreviewMedia(
                    url = url,
                    kind = PreviewKind.IMAGE,
                    title = file.fname,
                    headers = headers
                )
            ),
            title = file.fname
        )
    }
}
