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

package com.yunx.app.data.network

/**
 * UC 网盘登录与 API 相关常量（依据 uckk.md）。
 * 与夸克网盘共用 API 结构，仅域名、参数、UA 不同。
 */
object UCConstants {

    /** UC 网盘客户端 User-Agent */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    /** 官方 Web 客户端页面（Referer/Origin 基准域名；UC OSS 直链按 Referer 档位限速） */
    const val WEB_ORIGIN = "https://drive.uc.cn"
    /** 下载 OSS 直链必须携带的 Referer（缺它被 Callback 限速 ~100KB/s） */
    const val DOWNLOAD_REFERER = "$WEB_ORIGIN/"
    /** WebView 登录页 */
    const val LOGIN_URL = "https://drive.uc.cn/"

    /** 提取 Cookie 的域名 */
    const val COOKIE_DOMAIN = "https://drive.uc.cn"

    /** 验证登录状态的接口 */
    const val ACCOUNT_INFO_URL = "https://drive.uc.cn/account/info"

    /** 业务 API 基础域名 */
    const val API_BASE = "https://pc-api.uc.cn"

    /** 获取分享 Token（与夸克路径相同，仅域名/pr 不同） */
    const val SHARE_TOKEN_URL = "$API_BASE/1/clouddrive/share/sharepage/token?pr=UCBrowser&fr=pc"

    /** 获取分享文件列表（UC 用 v2/detail） */
    const val SHARE_DETAIL_URL = "$API_BASE/1/clouddrive/share/sharepage/v2/detail?pr=UCBrowser&fr=pc"

    /**
     * 转存分享详情（官方下载流程实际使用的文件列表接口，GET + query 带 stoken）。
     * 该接口返回的 share_fid_token 与 stoken 绑定，download 才能通过 token 校验。
     */
    const val TRANSFER_SHARE_DETAIL_URL = "$API_BASE/1/clouddrive/transfer_share/detail?entry=ft&fr=pc&pr=UCBrowser"

    /** 获取下载直链（官方抓包：entry=ft） */
    const val DOWNLOAD_URL = "$API_BASE/1/clouddrive/file/download?entry=ft&fr=pc&pr=UCBrowser"

    /** 转码播放流（非会员视频下载绕过会员墙；返回 m3u8/fmp4 分片地址）。
 * 注意：play 是个人云盘播放动作，不需要 entry=ft（分享转存通道参数），带它反而可能返回非 0。 */
    const val PLAY_URL = "$API_BASE/1/clouddrive/file/v2/play/project?pr=UCBrowser&fr=pc"

    /** 分享视频预览（GET，返回 data.play_info.url 原画 OSS 直链；走播放回调 checkplay，不换片，可绕过非会员视频下载被替换成宣传片） */
    const val VIDEO_PREVIEW_URL = "$API_BASE/1/clouddrive/share/sharepage/video_preview"

    /** 根目录 fid */
    const val DEFAULT_PDIR_FID = "0"

    /** 个人网盘文件列表 / 创建目录 */
    const val FILE_URL = "$API_BASE/1/clouddrive/file?pr=UCBrowser&fr=pc"

    /** 转存分享文件 */
    const val SAVE_URL = "$API_BASE/1/clouddrive/share/sharepage/save?pr=UCBrowser&fr=pc"

    /** 异步任务查询 */
    const val TASK_URL = "$API_BASE/1/clouddrive/task?pr=UCBrowser&fr=pc"

    /** 云盘文件列表（排序；抓包 pc-api.uc.cn/1/clouddrive/file/sort） */
    const val CLOUD_FILE_SORT_URL = "$API_BASE/1/clouddrive/file/sort?pr=UCBrowser&fr=pc"

    /** 重命名文件（body: fid + file_name） */
    const val RENAME_URL = "$API_BASE/1/clouddrive/file/rename?pr=UCBrowser&fr=pc"

    /** 移动文件（body: action_type=1 + to_pdir_fid + filelist） */
    const val MOVE_URL = "$API_BASE/1/clouddrive/file/move?pr=UCBrowser&fr=pc"

    /** 创建分享（body: fid_list + title + url_type + passcode + expired_type + public_search） */
    const val SHARE_CREATE_URL = "$API_BASE/1/clouddrive/share?pr=UCBrowser&fr=pc"

    /** 查询分享信息（body: share_id → share_url / passcode / pwd_id） */
    const val SHARE_INFO_URL = "$API_BASE/1/clouddrive/share/password?pr=UCBrowser&fr=pc"

    /** UC 云盘客户端 UA（抓包：uc-cloud-drive Electron） */
    const val CLOUD_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "uc-cloud-drive/1.6.1 Chrome/100.0.4896.160 Electron/18.3.5.16-b62cf9c50d Safari/537.36 Channel/ucpan_other_ch"

    /** 云盘下载直链（抓包：?pr=UCBrowser&fr=pc&sys=win32&ve=1.6.1；个人云盘文件用，非 entry=ft 分享通道） */
    const val CLOUD_DOWNLOAD_URL = "$API_BASE/1/clouddrive/file/download"

    /** 删除文件（body: action_type=2 + filelist + exclude_fids） */
    const val DELETE_URL = "$API_BASE/1/clouddrive/file/delete?pr=UCBrowser&fr=pc"

    /** 临时转存目录名 */
    const val TEMP_DIR_NAME = "YunX临时转存"

    /** 会话刷新探测接口：任意接口均可，用于重新下发 __puus（AList quark_uc util.go:224 用 /config，UC/夸克通用） */
    const val CONFIG_URL = "$API_BASE/1/clouddrive/config?pr=UCBrowser&fr=pc"

    /** __puus 有效期约 3 小时，提前到 90 分钟刷新（对齐 AList 100±5 分钟） */
    const val PUUS_REFRESH_INTERVAL_MS = 90L * 60 * 1000

    /** 关键 Cookie 字段，缺失则视为未登录（UC 与夸克共用） */
    fun isValidCookie(cookie: String?): Boolean =
        cookie != null && cookie.contains("__pus=") && cookie.contains("__puus=")
}