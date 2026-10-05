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
 * 蓝奏云优享版常量（文档 §1）。
 */
object ILanzouConstants {

    const val API_BASE = "https://apis.ilanzou.com"
    const val WEB_SITE = "https://www.ilanzou.com"

    /** 下载请求头（文档 §4.1） */
    const val DOWNLOAD_REFERER = "$WEB_SITE/"
    const val DOWNLOAD_ORIGIN = WEB_SITE

    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val DEV_TYPE = "6"
    const val DEV_MODEL = "chrome"
    const val DEV_VERSION = "125"

    /** 页大小固定 60（文档 §3.1） */
    const val PAGE_SIZE = 60

    // ---------- 端点 ----------
    const val GET_UUID = "$API_BASE/unproved/getUuid"
    const val LOGIN = "$API_BASE/unproved/login"
    const val ACCOUNT_MAP = "$API_BASE/proved/user/account/map"
    const val FILE_LIST = "$API_BASE/proved/record/file/list"
    const val FOLDER_SAVE = "$API_BASE/proved/file/folder/save"
    const val FOLDER_EDIT = "$API_BASE/proved/file/folder/edit"
    const val FILE_EDIT = "$API_BASE/proved/file/edit"
    const val FOLDER_MOVE = "$API_BASE/proved/file/folder/move"
    const val FILE_DELETE = "$API_BASE/proved/file/delete"
    const val FILE_REDIRECT = "$API_BASE/unproved/file/redirect"

    // ---------- 分享（本站 Web 分享页走后端 apix，与个人盘 apis 不同） ----------
    /** 分享业务主机（分享页 JS config.apiBaseURL = https://apix.ilanzou.com/） */
    const val SHARE_API_BASE = "https://apix.ilanzou.com"
    const val SHARE_LIST = "$SHARE_API_BASE/unproved/share/list"
    const val SHARE_REDIRECT = "$SHARE_API_BASE/unproved/file/redirect"
    /** 分享页 Referer（分享页地址为 {WEB_SITE}/s/<shareId>） */
    const val SHARE_REFERER_PREFIX = "$WEB_SITE/s/"
    /** 分享页 devModel 与网页一致（首字母大写） */
    const val SHARE_DEV_MODEL = "Chrome"
    /** 分享转存（需登录）：/proved/file/transfer + 轮询 /proved/file/transfer/num */
    const val FILE_TRANSFER = "$API_BASE/proved/file/transfer"
    const val FILE_TRANSFER_NUM = "$API_BASE/proved/file/transfer/num"

    /** 根目录 id */
    const val ROOT_FOLDER_ID = "0"

    /** AES 密钥（文档 §6.1） */
    const val AES_KEY = "lanZouY-disk-app"

    /** 文件夹 id 前缀 d: / 文件 id 前缀 f:（文档 §3.1 ID 规范） */
    const val FOLDER_PREFIX = "d:"
    const val FILE_PREFIX = "f:"

    fun newUuid(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(16) { repeat(16) { append(chars.random()) } }
    }
}
