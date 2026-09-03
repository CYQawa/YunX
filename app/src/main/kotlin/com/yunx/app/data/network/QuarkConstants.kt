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
 * 夸克网盘登录与 API 相关常量（依据 kk.md）。
 */
object QuarkConstants {

    /** 夸克 PC 客户端 User-Agent，所有请求必须携带 */
    const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/130.0.0.0 Safari/537.36 QuarkPC/6.0.8.649"


    /** WebView 登录页（PC 环境） */
    const val LOGIN_URL = "https://pan.quark.cn/?fr=pc&platform=pc"

    /** 提取 Cookie 的域名 */
    const val COOKIE_DOMAIN = "https://pan.quark.cn"

    /** 验证登录状态的接口 */
    const val ACCOUNT_INFO_URL = "https://pan.quark.cn/account/info"

    /** 解析/下载 API 强制 User-Agent（kkdo.md） 
    const val API_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36 QuarkPC/6.0.8.649"
            */
            const val API_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch"

    /** 业务 API 基础域名 */
    const val API_BASE = "https://drive-pc.quark.cn"

    /** 获取分享 Token */
    const val SHARE_TOKEN_URL = "$API_BASE/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc"

    /** 验证分享提取码 */
    const val SHARE_PASSWORD_URL = "$API_BASE/1/clouddrive/share/password?pr=ucpro&fr=pc"

    /** 获取分享文件列表 */
    const val SHARE_DETAIL_URL = "$API_BASE/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc"

    /** 获取下载直链 */
    const val DOWNLOAD_URL = "$API_BASE/1/clouddrive/file/download?pr=ucpro&fr=pc&sys=win32&ve=3.23.2"

    /** 根目录 fid */
    const val DEFAULT_PDIR_FID = "0"

    /** 个人网盘文件列表 / 创建目录 */
    const val FILE_URL = "$API_BASE/1/clouddrive/file?pr=ucpro&fr=pc"

    /** 个人网盘文件列表（排序；抓包：pdir_fid=0 根目录，带完整 cookie） */
    const val CLOUD_FILE_SORT_URL = "$API_BASE/1/clouddrive/file/sort?pr=ucpro&fr=pc"

    /** 转存分享文件 */
    const val SAVE_URL = "$API_BASE/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc"

    /** 异步任务查询 */
    const val TASK_URL = "$API_BASE/1/clouddrive/task?pr=ucpro&fr=pc"

    /** 删除文件（取链成功后清理临时转存；body: action_type=2 + filelist） */
    const val DELETE_URL = "$API_BASE/1/clouddrive/file/delete?pr=ucpro&fr=pc&uc_param_str="

    /** 重命名文件（body: fid + file_name） */
    const val RENAME_URL = "$API_BASE/1/clouddrive/file/rename?pr=ucpro&fr=pc&uc_param_str="

    /** 移动文件（body: action_type=1 + to_pdir_fid + filelist） */
    const val MOVE_URL = "$API_BASE/1/clouddrive/file/move?pr=ucpro&fr=pc&uc_param_str="

    /** 创建分享（body: fid_list + title + url_type + passcode + expired_type） */
    const val SHARE_CREATE_URL = "$API_BASE/1/clouddrive/share?pr=ucpro&fr=pc&uc_param_str="

    /** 查询分享信息（body: share_id → share_url / passcode / pwd_id） */
    const val SHARE_INFO_URL = "$API_BASE/1/clouddrive/share/password?pr=ucpro&fr=pc&uc_param_str="

    /** 临时转存目录名 */
    const val TEMP_DIR_NAME = "YunX临时转存"

    /** 临时转存子目录前缀（唯一子目录 tr_<时间戳>_<随机>，供启动一次性清理识别） */
    const val TEMP_SUBDIR_PREFIX = "tr_"

    /** 下载直链防盗链必须携带的 Referer（与 AList quark_uc meta.go:37 一致） */
    const val DOWNLOAD_REFERER = "https://pan.quark.cn/"

    /** 会话刷新探测接口：任意接口均可，用于重新下发 __puus（AList util.go:224 用 /config） */
    const val CONFIG_URL = "$API_BASE/1/clouddrive/config?pr=ucpro&fr=pc"

    /** __puus 有效期约 3 小时，提前到 90 分钟刷新（对齐 AList 100±5 分钟） */
    const val PUUS_REFRESH_INTERVAL_MS = 90L * 60 * 1000

    /** 关键 Cookie 字段，缺失则视为未登录 */
    fun isValidCookie(cookie: String?): Boolean =
        cookie != null && cookie.contains("__pus=") && cookie.contains("__puus=")
}