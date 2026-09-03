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

import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * 139 网盘（中国移动·和彩云）登录常量（依据《139网盘解析方法-alist逆向.md》§3.5）。
 * 登录方案：WebView 加载 yun.139.com（PC UA）由用户手动登录，再提取 Cookie 持久化。
 * 登录态两种形式（都支持）：
 *  - A：mail.10086.cn 的 Os_SSo_Sid + RMKEY（alist fast login 路径，§3.4/§3.5.3）；
 *  - B：yun.139.com 网页版直接给出的 authorization（§3.5.5，连 fast login 都省了）。
 */
object C139Constants {

    /**
     * WebView 登录页：139 云盘主站（网盘账号，手机号登录）。
     * 用手机 UA 加载（139 移动版页面渲染稳定）；PC 版 SPA 在 WebView 环境会因深度环境检测（navigator.plugins/
     * window.chrome 等特征缺失）而 JS 渲染空白，故不用 PC UA 直连。
     */
    const val LOGIN_URL = "https://yun.139.com/m/#/login"

    /** 提取 Cookie 的主域名（fast login 核心：Os_SSo_Sid + RMKEY 在此域） */
    const val COOKIE_DOMAIN = "https://mail.10086.cn"

    /** 备用 Cookie 域名（authorization / ud_id 等在此域，网页版直接给） */
    const val COOKIE_DOMAIN_BACKUP = "https://yun.139.com"

    /** 分享专用 host（§9/§12：share-kd-njs.yun.139.com） */
    const val SHARE_BASE = "https://share-kd-njs.yun.139.com"

    /** 分享列目录（§7/§15 share 类型；7.13+ 请求/响应加密，§14） */
    const val SHARE_LIST_URL = "$SHARE_BASE/yun-share/richlifeApp/devapp/IOutLink/getOutLinkInfoV6"

    /** 分享取直链（§8/§15 share 类型；7.13+ 请求/响应加密，§14） */
    const val SHARE_LINK_URL = "$SHARE_BASE/yun-share/richlifeApp/devapp/IOutLink/dlFromOutLinkV3"

    /** 分享标题信息（getOutLinkGeneral → outLinkGeneral[].lkName） */
    const val SHARE_GENERAL_URL = "$SHARE_BASE/yun-share/richlifeApp/devapp/IOutLink/getOutLinkGeneral"

    /** §14 分享接口 AES-CBC 固定密钥（16 字节，所有账号共用） */
    const val SHARE_AES_KEY = "PVGDwmcvfs1uV3d1"

    // ---------- 个人网盘管理（§1：明文 JSON，无 Cookie；host personal-kd-njs） ----------

    /** 个人网盘管理 host（明文 JSON + Authorization + mcloud-sign） */
    const val CLOUD_BASE = "https://personal-kd-njs.yun.139.com"

    // —— 渠道/上下文头（《139网盘管理认证失败修复》：缺失 → 04000005 认证失败；值来自成功抓包写死）——

    /** 渠道 source / app-channel / huawei-channelSrc（三者同值） */
    const val YUN_CHANNEL_SOURCE = "10000034"

    /** mcloud-version */
    const val MCLOUD_VERSION = "7.17.9"

    /** mcloud-client */
    const val MCLOUD_CLIENT = "10701"

    /** mcloud-channel */
    const val MCLOUD_CHANNEL = "1000101"

    /** x-yun-module-type */
    const val YUN_MODULE_TYPE = "100"

    /** x-m4c-src */
    const val M4C_SRC = "10002"

    /** x-m4c-caller */
    const val M4C_CALLER = "PC"

    /** X-Deviceinfo */
    const val X_DEVICEINFO = "||9|7.17.9|chrome|116.0.0.0|2cdaf7ada9e353c70eba99092e177991||windows 10||zh-CN|||"

    /** x-yun-client-info */
    const val X_CLIENT_INFO = "||9|7.17.9|chrome|116.0.0.0|2cdaf7ada9e353c70eba99092e177991||windows 10||zh-CN|||dW5kZWZpbmVk||"

    /** 列目录（可加 type:"folder" 仅列文件夹） */
    const val FILE_LIST_URL = "$CLOUD_BASE/hcy/file/list"

    /** 重命名 */
    const val FILE_UPDATE_URL = "$CLOUD_BASE/hcy/file/update"

    /** 移动（异步，返回 taskId） */
    const val BATCH_MOVE_URL = "$CLOUD_BASE/hcy/file/batchMove"

    /** 删除（异步移入回收站，返回 taskId） */
    const val BATCH_TRASH_URL = "$CLOUD_BASE/hcy/recyclebin/batchTrash"

    /** 下载直链（OBS 预签名，900s 有效） */
    const val DOWNLOAD_URL = "$CLOUD_BASE/hcy/file/getDownloadUrl"

    /** 异步任务轮询 */
    const val TASK_GET_URL = "$CLOUD_BASE/hcy/task/get"

    /** 创建分享（yun.139.com orchestration，需 Cookie + mcloud-skey + Authorization） */
    const val OUTLINK_CREATE_URL =
        "https://yun.139.com/orchestration/personalCloud-rebuild/outlink/v1.0/getOutLink"

    /** 转存：创建任务（share host，AES 加密） */
    const val TRANSFER_CREATE_URL =
        "$SHARE_BASE/yun-share/richlifeApp/devapp/IBatchOprTask/createOuterLinkBatchOprTask"

    /** 转存：查询结果（share host，AES 加密） */
    const val TRANSFER_QUERY_URL =
        "$SHARE_BASE/yun-share/richlifeApp/devapp/IBatchOprTask/queryBatchOprTaskDetail"

    /** 分享接口必带设备/渠道上下文头（设备头修复文档 §3：缺任一即 9530） */
    const val SHARE_X_DEVICEINFO = "||3|12.27.0|||||chrome 150.0.0.0|360X444|zh-cn|||"
    const val SHARE_X_HUAWEI_CHANNELSRC = "10245500"
    const val SHARE_X_MM_SOURCE = "0002"

    /** 分享接口 User-Agent（必须浏览器/WebView UA，不能用 okhttp/4.x，设备头修复文档 §3.1） */
    const val SHARE_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/150.0.0.0 Mobile Safari/537.36"

    /** PC 桌面 UA（对齐文档 §9 请求头的 chrome/120.0.0.0 windows10；WebView 需桌面版页面才会下发 authorization） */
    const val PC_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** 快速登录必须同时存在的关键字段（路径 A，§3.5.3） */
    private val REQUIRED_FAST_KEYS = setOf("Os_SSo_Sid", "RMKEY")

    /** 尽量保留的字段（§3.5.3 推荐 + 网页版实际字段，按重要性排序） */
    private val KEEP_KEYS = setOf(
        // 路径 A：fast login 核心
        "Os_SSo_Sid", "RMKEY", "UserData", "Login_UserNumber",
        "_139_index_isLoginType", "UUIDToken", "JSESSIONID",
        "areaCode8011", "provCode8011",
        // 路径 B：网页版直接给出的登录态（authorization 可直接作为请求头）
        "authorization", "auth_token", "token", "ud_id",
        // 账号信息
        "ORCHES-I-ACCOUNT-SIMPLIFY", "ORCHES-I-ACCOUNT-ENCRYPT", "nation_code",
        // 其他会话/埋点
        "platform", "cutover_status", "isUserDomainError", "a_k", "skey", "WT_FPC",
        "hecaiyun_stay_url", "hecaiyun_stay_time",
        "hecaiyundata2021jssdkcross", "sajssdk_2015_cross_new_user"
    )

    /**
     * 从 CookieManager 提取 139 登录态 Cookie：
     * mail.10086.cn 优先，yun.139.com 兜底，只收 KEEP_KEYS 中的字段，拼成 "k=v; ..."。
     * @param getCookie CookieManager.getCookie(domain) 的适配（便于测试）
     */
    fun extractCookies(getCookie: (String) -> String?): String {
        val out = linkedMapOf<String, String>()
        val domains = listOf(COOKIE_DOMAIN, COOKIE_DOMAIN_BACKUP)
        for (domain in domains) {
            val raw = getCookie(domain) ?: continue
            for (kv in raw.split(";")) {
                val kv2 = kv.trim()
                val eq = kv2.indexOf('=')
                if (eq <= 0) continue
                val k = kv2.substring(0, eq)
                val v = kv2.substring(eq + 1)
                if (k in KEEP_KEYS && k !in out) out[k] = v
            }
        }
        return out.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 关键字段是否齐全（两种形式任一成立即视为有效登录态）：
     *  - 路径 A：Os_SSo_Sid + RMKEY 同时存在且非空；
     *  - 路径 B：authorization 存在且非空。
     */
    fun isValidCookie(cookie: String?): Boolean {
        if (cookie.isNullOrBlank()) return false
        // 路径 B：网页版直接给的 authorization
        if (cookie.split(";").any {
                val kv = it.trim()
                kv.startsWith("authorization=") && kv.length > "authorization=".length
            }
        ) return true
        // 路径 A：fast login 双字段
        return REQUIRED_FAST_KEYS.all { key ->
            cookie.split(";").any {
                val kv = it.trim()
                kv.startsWith("$key=") && kv.length > key.length + 1
            }
        }
    }

    /** 提取 authorization（§3.5.5，形如 "Basic cGM6..."）；没有返回 null */
    fun extractAuthorization(cookie: String?): String? {
        if (cookie.isNullOrBlank()) return null
        for (kv in cookie.split(";")) {
            val kv2 = kv.trim()
            if (kv2.startsWith("authorization=")) {
                val v = kv2.substringAfter('=')
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    /**
     * 从 Cookie 提取完整账号（解析接口用，必须完整手机号）：
     * 优先 ORCHES-I-ACCOUNT-ENCRYPT（base64 解码）→ authorization 解码 → Login_UserNumber。
     * 拿不到返回 null。
     */
    fun extractAccountFull(cookie: String?): String? {
        if (cookie.isNullOrBlank()) return null
        // 1) ORCHES-I-ACCOUNT-ENCRYPT：base64 手机号
        cookie.split(";").forEach { kv ->
            val kv2 = kv.trim()
            if (kv2.startsWith("ORCHES-I-ACCOUNT-ENCRYPT=")) {
                val v = kv2.substringAfter('=')
                if (v.isNotBlank()) {
                    val decoded = runCatching {
                        String(Base64.decode(v, Base64.DEFAULT), StandardCharsets.UTF_8)
                    }.getOrNull()
                    if (!decoded.isNullOrBlank()) return decoded
                }
            }
        }
        // 2) authorization："Basic base64(pc:账号:authToken)"
        extractAuthorization(cookie)?.let { auth ->
            val account = runCatching {
                val b64 = auth.removePrefix("Basic").trim()
                String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
                    .split(":").getOrNull(1)
            }.getOrNull()
            if (!account.isNullOrBlank()) return account
        }
        // 3) Login_UserNumber：手机号/账号
        cookie.split(";").forEach { kv ->
            val kv2 = kv.trim()
            if (kv2.startsWith("Login_UserNumber=")) {
                val v = kv2.substringAfter('=')
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    /**
     * 从 Cookie 提取账号昵称（优先脱敏手机号 → base64 手机号 → Login_UserNumber）；
     * 拿不到返回 null。
     */
    fun extractAccount(cookie: String?): String? {
        if (cookie.isNullOrBlank()) return null
        // 1) ORCHES-I-ACCOUNT-SIMPLIFY：脱敏手机号，形如 177****8634
        cookie.split(";").forEach { kv ->
            val kv2 = kv.trim()
            if (kv2.startsWith("ORCHES-I-ACCOUNT-SIMPLIFY=")) {
                val v = kv2.substringAfter('=')
                if (v.isNotBlank()) return v
            }
        }
        // 2) ORCHES-I-ACCOUNT-ENCRYPT：base64 手机号
        cookie.split(";").forEach { kv ->
            val kv2 = kv.trim()
            if (kv2.startsWith("ORCHES-I-ACCOUNT-ENCRYPT=")) {
                val v = kv2.substringAfter('=')
                if (v.isNotBlank()) {
                    return runCatching {
                        String(Base64.decode(v, Base64.DEFAULT), StandardCharsets.UTF_8)
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: v
                }
            }
        }
        // 3) Login_UserNumber：手机号/账号
        cookie.split(";").forEach { kv ->
            val kv2 = kv.trim()
            if (kv2.startsWith("Login_UserNumber=")) {
                val v = kv2.substringAfter('=')
                if (v.isNotBlank()) return v
            }
        }
        return null
    }
}