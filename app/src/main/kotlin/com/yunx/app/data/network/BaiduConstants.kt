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
 * 百度网盘登录与 API 相关常量（依据抓包 + 文档）。
 */
object BaiduConstants {

    /** WebView 登录页（提取 BDUSS/STOKEN） */
    const val LOGIN_URL = "https://pan.baidu.com/"

    /** 提取 Cookie 的域名（Pan 域同时包含 .baidu.com 与 .pan.baidu.com 的 Cookie） */
    const val COOKIE_DOMAIN = "https://pan.baidu.com"

    /** 根域兜底：补齐只写在 .baidu.com 上的非核心字段 */
    private const val ROOT_COOKIE_DOMAIN = "https://baidu.com"

    /** PC 网页 UA（share/verify、xpan/share、gettemplatevariable 使用） */
    const val UA_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 百度客户端 UA（filemanager、locatedownload 等老接口使用） */
    const val UA_NETDISK =
        "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0"

    /**
     * 个人盘列表「App 通道」的 UA：版本号必须与 [APP_VERSION] 一致。
     *
     * 百度会交叉校验 UA 里的版本与 query 的 version——两边对不上就按「不是同一个客户端」拒绝，
     * 所以这两个值只能成对修改。
     */
    const val UA_APP =
        "netdisk;13.15.4;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0;"

    /** 个人盘列表「App 通道」的协议版本（api/list 的 version 参数） */
    const val APP_VERSION = "13.15.4"

    /** 百度网盘统一 app_id */
    const val APP_ID = "250528"

    /** 临时转存目录名（对齐抓包） */
    const val TEMP_DIR_NAME = "YunX临时转存"

    /** 登录态不完整的统一提示（errno=-6 时拼在接口错误后面） */
    const val INCOMPLETE_LOGIN_HINT = "登录态不完整或已失效，请重新登录百度网盘"

    /** 下载方式：只使用 locatedownload 返回的 CDN/HTTP 直链 */
    const val DOWNLOAD_MODE_CDN = "cdn"

    /** 下载方式：Seed/POP/TPDATA P2P（只下载，不上传做种） */
    const val DOWNLOAD_MODE_P2P = "p2p"

    /** 百度 P2P SDK 的 User-Agent（Seed/TPDATA 控制面使用） */
    const val UA_P2SP =
        "netdisk;P2SP;3.0.20.242;netdisk;8.9.7.101;PC;PC-Windows;10.0.19045;WindowsBaiduYunGuanJia"

    /** Seed 查询接口；uk 由调用方追加 */
    const val P2P_SEED_URL = "http://nj.t.bcsp2p.baidu.com/seed"

    /**
     * POP 节点查询接口。
     *
     * 抓包走的是默认 80 端口；旧常量里的 8827 作为第二候选保留，
     * 由下载器在首个地址不可用时自动回退。
     */
    const val P2P_POP_QUERY_URL =
        "http://nj.t.bcsp2p.baidu.com/pop/query_pop?pop_type=7&version=3.0.20.242"

    /** POP 查询备用端口（部分网络只开放 8827）。 */
    const val P2P_POP_QUERY_URL_ALT =
        "http://nj.t.bcsp2p.baidu.com:8827/pop/query_pop?pop_type=7&version=3.0.20.242"

    /** P2P 内核协议版本 */
    const val P2P_SDK_VERSION = "3.0.20.242"

    /** P2P 数据分片大小（抓包与内核均使用 1 MiB 请求） */
    const val P2P_RANGE_SIZE = 1024 * 1024

    /** P2P 加密 slice 大小 */
    const val P2P_ENCRYPT_SLICE_SIZE = 8192

    /** TPDATA 请求里的客户端类型（桌面下载内核）。 */
    const val P2P_CLIENT_TYPE = 9

    /** TPDATA 请求里的资源场景。 */
    const val P2P_SCENE = "personal"

    /** TPDATA 请求里的用户等级（抓包固定为 1）。 */
    const val P2P_USER_LEVEL = 1

    private const val COOKIE_BDUSS = "BDUSS"
    private const val COOKIE_STOKEN = "STOKEN"

    /**
     * 清洗 Cookie 串：
     * - 丢弃空项、无 `=` 的裸值（WebView 中间态会返回裸 `undefined`）、空值、`undefined`/`null`；
     * - 同名 Cookie 只保留第一次出现的值（调用方按 Pan 域优先拼接）；
     * - 丢弃含控制字符的键值，避免把网页内容原样塞进请求头。
     */
    fun sanitizeCookie(cookie: String?): String {
        if (cookie.isNullOrBlank()) return ""
        val trimmed = cookie.trim()
        // 允许用户从抓包工具直接复制「Cookie: ...」整行
        val body = if (trimmed.startsWith("Cookie:", ignoreCase = true)) {
            trimmed.substring("Cookie:".length).trimStart()
        } else {
            trimmed
        }
        val entries = linkedMapOf<String, String>()
        for (raw in body.split(';', '\r', '\n')) {
            val part = raw.trim()
            val equals = part.indexOf('=')
            if (equals <= 0) continue
            val name = part.substring(0, equals).trim()
            val value = part.substring(equals + 1).trim()
            if (!validCookieName(name) || !validCookieValue(value)) continue
            entries.putIfAbsent(name, value)
        }
        // 按名字排序，保证同一组 Cookie 每次采样得到完全相同的字符串（自动检测靠它判断「连续稳定」）
        return entries.entries
            .sortedBy { it.key }
            .joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 从 WebView 读取百度登录态。
     *
     * 必须先从 Pan 域拿到 STOKEN：Passport 域也有同名 STOKEN，但**不能**用于个人盘接口；
     * 因此 Pan 快照缺少 STOKEN 时直接返回，不拿根域/Passport 域的同名字段补齐，
     * 让上层继续轮询到真正的登录完成态。
     */
    fun extractCookies(getCookie: (String) -> String?): String {
        val pan = sanitizeCookie(getCookie(COOKIE_DOMAIN))
        if (cookieValue(pan, COOKIE_STOKEN) == null) return pan
        val root = sanitizeCookie(getCookie(ROOT_COOKIE_DOMAIN))
        return sanitizeCookie("$pan; $root")
    }

    /**
     * 百度个人盘登录态的最小形态：BDUSS + STOKEN。
     * WebView 路径由 [extractCookies] 保证 STOKEN 来自 Pan 域；手动粘贴的最终有效性由服务端校验。
     *
     * 只出现 BDUSS 是网页登录中间态（或 Passport 登录态），此时 `/api/list`、`/api/quota`
     * 等接口会返回 errno=-6；真正是否有效仍由 `gettemplatevariable` 的 errno=0 校验。
     */
    fun isValidCookie(cookie: String?): Boolean {
        val cleaned = sanitizeCookie(cookie)
        return cookieValue(cleaned, COOKIE_BDUSS) != null &&
            cookieValue(cleaned, COOKIE_STOKEN) != null
    }

    private fun cookieValue(cookie: String, name: String): String? =
        cookie.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    private fun validCookieName(name: String): Boolean =
        name.isNotEmpty() && name.all { ch ->
            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch in "!#$%&'*+-.^_`|~"
        }

    private fun validCookieValue(value: String): Boolean =
        value.isNotEmpty() &&
            !value.equals("undefined", ignoreCase = true) &&
            !value.equals("null", ignoreCase = true) &&
            value.none { it.code < 0x20 || it.code == 0x7f }
}
