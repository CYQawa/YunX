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
 * 蓝奏云常量（文档 §1）。
 */
object LanzouConstants {

    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 个人盘登录入口（文档 §1.2） */
    const val MYDISK_URL = "https://pc.woozooo.com/mydisk.php"
    const val MYDISK_FILES_URL = "https://pc.woozooo.com/mydisk.php?item=files&action=index"
    const val DOUPLOAD_URL = "https://pc.woozooo.com/doupload.php"
    const val ORIGIN = "https://pc.woozooo.com"

    /**
     * 原生账号密码登录（官网登录页 accounts.woozooo.com）：
     * POST /accounts.php {task=uselogin, username, password, ref=pc.woozooo.com} → {zt, msgs}；
     * 无挑战 Cookie 时返回 JS 人机校验页（arg1 + acw_sc__v2），需计算后重试。
     */
    const val ACCOUNTS_URL = "https://accounts.woozooo.com/accounts.php"
    const val ACCOUNTS_ORIGIN = "https://accounts.woozooo.com"
    const val LOGIN_REF = "pc.woozooo.com"

    /** 个人盘根目录 folder_id */
    const val ROOT_FOLDER_ID = "-1"

    /** 页大小相关（文档 §B3/§B4 分页上限） */
    const val MAX_FILE_PAGES = 1000
    const val MAX_FOLDER_PAGES = 500

    /** 下载节点注入的 Cookie */
    const val DOWN_IP_COOKIE = "down_ip=1"

    /** 文件夹 id 前缀 d: / 文件 id 前缀 f: */
    const val FOLDER_PREFIX = "d:"
    const val FILE_PREFIX = "f:"

    /** 域名族：lanzou*、lan[zs]o[ux]，后缀 com/net/org/cn */
    private val HOST_REGEX =
        Regex("""^(?:[a-z0-9-]+\.)*(?:lanzou[a-z]?|lan[zs]o[ux])\.(?:com|net|org|cn)$""", RegexOption.IGNORE_CASE)

    fun isLanzouHost(host: String): Boolean = HOST_REGEX.matches(host)

    /** 将分享链接规范为 https、去掉 fragment、去掉多余 query（保留 pwd 类参数由调用方处理） */
    fun normalizeShareUrl(url: String): String {
        var u = url.trim()
        u = u.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        u = u.substringBefore('#')
        return u.trimEnd('/')
    }
}
