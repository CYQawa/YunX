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

package com.yunx.app.data.network.model

/**
 * 分享解析会话（一次解析流程的凭证）。
 */
data class ShareSession(
    val shareId: String,
    val stoken: String,
    val title: String
)

/**
 * 分享 Token 响应（4.1 接口）。
 */
data class ShareToken(
    val stoken: String,
    val title: String,
    val firstFid: String
)

/**
 * 文件/目录项。
 *
 * @param thumbnail 列表行小图（图片文件才有）。由**列表接口在拿到凭证的那一次**一并产出，
 *   见 [FileThumbnail] 的说明 —— 列表行只负责画，不去问 ViewModel 要账号。
 */
data class ShareFile(
    val fid: String,
    val fname: String,
    val fsize: Long,
    val isdir: Boolean,
    val pdirFid: String,
    val fidToken: String,
    val modifyTime: String = "",
    val thumbnail: FileThumbnail? = null
)

/**
 * 列表行缩略图：图片地址 + 取图所需的请求头。
 *
 * 各家列表接口给缩略图的方式并不统一：多数只是列表项里的一个 URL 字段
 * （百度 `thumbs.*`、139 `thumbnailUrls[]`、迅雷 `thumbnail_link`…），123 还要从一个**可能指向原图**
 * 的 `DownloadUrl` 里挑出缩略图变体；而夸克/UC 的缩略图地址指向自家接口，**不带账号 Cookie 不给图**。
 *
 * 所以「地址 + 请求头」跟着条目一起返回：列表接口本来就有 Cookie，顺手拼好即可；
 * 否则列表行还得回头去问 ViewModel 要账号凭证，UI 与数据层无谓地互相依赖。
 * 没有缩略图（文件夹、没图的平台、地址非法）时整条为 null，列表行据此退回文件图标。
 */
data class FileThumbnail(
    val url: String,
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * 构造缩略图信息；地址为空或不是 https 时返回 null。
         *
         * ★ 只认 https 不是洁癖：应用的 `network_security_config` 全局禁止明文，`http://` 图片必然加载失败，
         *   留着只会让每行都留一个空框（还多一次注定失败的请求）。
         */
        fun of(url: String, headers: Map<String, String> = emptyMap()): FileThumbnail? {
            val trimmed = url.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) return null
            return FileThumbnail(trimmed, headers)
        }

        /**
         * 需要账号鉴权的缩略图：只有 [url] 的 host 恰好是 [authHost] 时才带 [authHeaders]，
         * 其余域名只用 [headers]。
         *
         * ★ 为什么按 host 分流而不是一律带 Cookie：列表响应是外部数据，地址里的域名不由我们控制。
         *   账号 Cookie 只该发给平台自己的接口；万一响应把缩略图指到别的域名（CDN 签名地址/异常数据），
         *   一律带 Cookie 等于把登录态主动交出去（同类取舍见夸克/UC 的取图接口）。
         */
        fun of(
            url: String,
            headers: Map<String, String>,
            authHost: String,
            authHeaders: Map<String, String>
        ): FileThumbnail? {
            val trimmed = url.trim()
            val host = runCatching { java.net.URI(trimmed).host }.getOrNull().orEmpty()
            val picked = if (host.equals(authHost, ignoreCase = true)) authHeaders else headers
            return of(trimmed, picked)
        }
    }
}

/**
 * 分享信息（云盘功能：创建分享后查询得到的链接与提取码）。
 * @param warning 非致命提示：分享已创建但某个后续设置没生效（如 115 改有效期失败）。
 *   界面在弹分享结果的同时用 Snackbar 展示；为空表示一切正常。
 */
data class ShareInfo(
    val shareUrl: String,
    val passcode: String,
    val pwdId: String,
    val title: String,
    val expiredType: Int,
    val warning: String? = null
)

/**
 * 网盘空间详情（总容量 / 已用，单位字节）。
 */
data class QuotaInfo(
    val used: Long,
    val total: Long,
    val usedInTrash: Long = 0L
)

/**
 * 下载直链。
 * @param cleanupDirFid 下载完成后需删除的临时转存子目录 fid（根治夸克去重返回已删 fid）；null 表示无需清理
 */
data class DownloadLink(
    val fid: String,
    val filename: String,
    val downloadUrl: String,
    val size: Long,
    val cleanupDirFid: String? = null,
    /** 是否为 HLS（m3u8）转码流地址：下载走 HLS 分片合并路径（UC play 绕过会员墙） */
    val isHls: Boolean = false,
    /**
     * 游客（未登录）直链携带的游客态 Cookie（如夸克/UC 的 `__pugs=xxx`）。
     * 登录态直链为空串 —— 那时下载用登录 Cookie（见 ResolveViewModel.enqueueDownload）；
     * 游客态下登录 Cookie 不存在，只能靠这份令牌过 CDN 校验（缺了夸克返回 412、UC 返回 403）。
     */
    val guestCookie: String = ""
)

/** UC 转码播放流（绕过非会员视频下载被换成宣传片的问题；url 为 m3u8/fmp4 分片地址） */
data class PlayLink(
    val url: String,
    val resolution: String,
    val format: String,
    val isHls: Boolean
)