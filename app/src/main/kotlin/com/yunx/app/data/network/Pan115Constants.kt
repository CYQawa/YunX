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
 * 115 网盘常量。
 *
 * 依据实测抓包文档 `云析分析资料/网盘API文档/115/115_api.md`（个人盘）与 `115_share_api.md`（分享）。
 * 几个容易踩的点：
 * - 业务 API 全部在 `webapi.115.com`，POST 默认表单编码；
 * - 写操作里的 `fid` 对文件是 `file_id`、对文件夹是 `cate_id`（即列表里的 `cid`），见文档 §2.1 的提醒；
 * - 直链 CDN 需要「个人盘直链响应头下发的 900 秒 Cookie」，且要与登录 Cookie 拼接后一起发。
 */
object Pan115Constants {

    // ---------- 基址 ----------

    /** 业务 API 主域（个人盘 + 分享接口同域） */
    const val API_BASE = "https://webapi.115.com"

    /** 网页版登录入口（WebView 登录后由 CookieManager 取登录 Cookie） */
    const val WEB_LOGIN_URL = "https://115.com/"

    /** 分享链接域名（新分享默认 115cdn.com；115.com / 115rc.com 同样有效） */
    const val SHARE_HOST = "https://115cdn.com"

    /** 通用 Referer（文档 §1：请求头需带对应页面 Referer） */
    const val REFERER = "https://115.com/"

    /** 直链下载必须携带的 Referer（文档 §4.3） */
    const val DOWNLOAD_REFERER = "https://115.com/"

    // ---------- 接口路径 ----------

    /** 登录态校验 + 昵称（data 为脱敏手机号；失效 errno=990001） */
    const val USER_INFO_URL = "$API_BASE/user/info?ct=json"

    /** 个人盘列目录 */
    const val FILES_URL = "$API_BASE/files"

    /** 个人盘空间信息 */
    const val INDEX_INFO_URL = "$API_BASE/files/index_info?count_space_nums=1"

    /** 新建文件夹（pid、cname） */
    const val FILE_ADD_URL = "$API_BASE/files/add"

    /** 移动 / 复制（pid、fid[i]、copy=1 表示复制） */
    const val FILE_MOVE_URL = "$API_BASE/files/move"

    /** 移动异步进度（move_proid） */
    const val MOVE_PROGRESS_URL = "$API_BASE/files/move_progress"

    /** 重命名（fid、file_name、files_new_name[id]） */
    const val FILE_RENAME_URL = "$API_BASE/files/batch_rename"

    /** 删除（进回收站；pid、fid[0]、ignore_warn=1） */
    const val FILE_DELETE_URL = "$API_BASE/rb/delete"

    /** 个人盘直链（pickcode=<列表项的 pc>）。仅小文件可用，大文件返回 50028 */
    const val FILE_DOWNLOAD_URL = "$API_BASE/files/download"

    /**
     * 「电脑端」直链接口（加密协议，见 [Pan115Crypto]）。调用时需拼 `?t=<unix秒>`，
     * 表单 `data=<加密后的 {"pickcode":…}>`。这是 115 官方客户端下载大文件的通道，
     * 网页端接口（[FILE_DOWNLOAD_URL]）对大文件一律 50028。
     */
    const val APP_DOWNLOAD_URL = "https://proapi.115.com/app/chrome/downurl"

    /** 首次分享前的协议确认（action=pubshare） */
    const val ALLOW_PROTOCOL_URL = "$API_BASE/user/allow_protocol"

    /** 创建分享（user_id、file_ids、ignore_warn） */
    const val SHARE_SEND_URL = "$API_BASE/share/send"

    /** 修改分享（share_code、share_duration 等） */
    const val SHARE_UPDATE_URL = "$API_BASE/share/updateshare"

    /** 分享详情回读 */
    const val SHARE_INFO_URL = "$API_BASE/share/shareinfo"

    /** 分享列目录（cid=0 为分享根） */
    const val SHARE_SNAP_URL = "$API_BASE/share/snap"

    /** 分享直链（user_id 为分享者 UID） */
    const val SHARE_DOWNLOAD_URL = "$API_BASE/share/downurl"

    /** 转存（接收分享到自己的网盘） */
    const val SHARE_RECEIVE_URL = "$API_BASE/share/receive"

    // ---------- 请求参数 ----------

    /** 个人盘区域 ID（固定 1） */
    const val AID_PERSONAL = "1"

    /** 根目录 cid */
    const val ROOT_CID = "0"

    /** 每页条数（与网页版一致） */
    const val PAGE_LIMIT = 100

    /** 分享列目录每页条数（文档 §3 抓包用 20，放宽到 100） */
    const val SHARE_PAGE_LIMIT = 100

    /** 移动异步轮询上限与间隔（毫秒） */
    const val MOVE_POLL_MAX = 20
    const val MOVE_POLL_INTERVAL_MS = 400L

    /** 临时转存根目录名（分享直链被拒时，先转存到这里再用电脑端接口取链，下载完删掉子目录） */
    const val TEMP_DIR_NAME = "YunX临时转存"

    /** 每次转存用的唯一子目录前缀（同名文件不会互相覆盖） */
    const val TEMP_SUBDIR_PREFIX = "tr_"

    // ---------- 公共请求头 ----------

    /**
     * 桌面 Chrome UA。
     * 文档只要求 Referer + Cookie，但 115 对非浏览器 UA 有风控，保持与网页版一致更稳。
     */
    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    /** 携带 Cookie 时必须声明（文档 §1） */
    const val X_REQUESTED_WITH = "XMLHttpRequest"

    /**
     * **下载专用客户端 UA 的版本号兜底值**。
     *
     * 115 会校验客户端版本：UA 里版本过低时，连取直链都会被拒（`50029 当前版本过低，请升级到最新版本下载。`
     * 实测来自 alist 默认版本 `27.0.5.7`，2026-10-03 抓包 `/storage/emulated/0/抓包/bug/115/`）。
     * 所以版本号不写死：`Pan115Api.refreshClientVersion()` 会从 [APP_VERSION_URL] 拉当前版本
     * （alist `drivers/115/appver.go` 的 `getAppVer()` 同款做法），拉不到时才用这个兜底值。
     */
    const val CLIENT_UA_VERSION_FALLBACK = "36.0.1"

    /** 115 客户端版本接口（`data.win.version_code` 即当前 Windows 版 115 浏览器版本，免登录可用） */
    const val APP_VERSION_URL = "https://appversion.115.com/1/web/1.0/api/chrome"

    /**
     * **下载专用客户端 UA**（形如 `Mozilla/5.0 115Browser/<版本>`，同 alist `drivers/115/util.go:132`）。
     *
     * 115 会按 UA 判定「网页端 / 客户端」：浏览器 UA 连小文件的下载请求都会被差别对待；
     * 版本号过低则直接 `50029`。因此这里是**可变的进程级缓存** —— 取直链前由
     * `Pan115Api.refreshClientVersion()` 用 [APP_VERSION_URL] 的最新版本刷新 [applyClientVersion]，
     * 取链与随后的下载请求都读同一个值，版本更新后无需发版。
     *
     * **注意**：UA 只是门票，拿大文件直链仍必须走「电脑端」加密接口 [APP_DOWNLOAD_URL]（见 [Pan115Crypto]）：
     * 个人盘走 `getAppDownloadLink`，分享走「转存 + 电脑端接口」。
     */
    @Volatile
    var CLIENT_UA: String = clientUa(CLIENT_UA_VERSION_FALLBACK)
        private set

    /** 拼客户端 UA */
    fun clientUa(version: String): String = "Mozilla/5.0 115Browser/$version"

    /** 用最新的客户端版本号刷新 [CLIENT_UA]（非法值忽略，失败时保留旧值） */
    fun applyClientVersion(version: String?) {
        val value = version?.trim().orEmpty()
        // 形如 36.0.1 / 36.0.10，过滤掉接口异常时的空值或垃圾串
        if (value.isEmpty() || !value.first().isDigit() || value.length > 16) return
        if (!value.all { it.isDigit() || it == '.' }) return
        if (CLIENT_UA.endsWith("/$value")) return
        CLIENT_UA = clientUa(value)
    }

    /** 分享链接域名（用于展示/收藏平台识别） */
    const val SHARE_LINK_HOST = "115cdn.com"

    /** 网页登录 Cookie 主域（登录后 UID/CID/SEID/KID 都下发在 115.com 下） */
    const val COOKIE_DOMAIN = "https://115.com"

    /** 备用 Cookie 域名（部分会话字段挂在 webapi 子域） */
    const val COOKIE_DOMAIN_BACKUP = "https://webapi.115.com"

    /**
     * 需要保留的 Cookie 字段：alist 实测最小可用集为 UID/CID/SEID/KID，
     * 其余会话字段一并保留（缺字段时服务端会以 errno=990001 明确报错）。
     */
    val COOKIE_KEEP_KEYS = linkedSetOf(
        "UID", "CID", "SEID", "KID", "PHPSESSID", "GST", "USERSESSIONID"
    )

    // ---------- 错误码（文档 115_api.md §1.1 / 115_share_api.md §7） ----------

    /** 登录超时 */
    const val ERRNO_LOGIN_TIMEOUT = 990001

    /** 分享需要访问码 */
    const val ERRNO_NEED_CODE = 4100012

    /** 访问码错误 */
    const val ERRNO_WRONG_CODE = 4100008

    /** 分享链接不存在或已被删除 */
    const val ERRNO_SHARE_GONE = 4100026

    /** 不允许分享空文件夹 */
    const val ERRNO_EMPTY_FOLDER = 4100016

    /** 上一次操作尚未执行完成（删除/移动） */
    const val ERRNO_BUSY = 990009

    /** 需要二次确认（删除旧文件等） */
    const val ERRNO_NEED_CONFIRM = 800007

    /** 个人盘下载：文件大小超出网页端限制（`文件大小超出限制，请使用115电脑端下载`） */
    const val ERRNO_WEB_SIZE_LIMIT = 50028

    /** 分享下载：被判定客户端版本过低（`当前版本过低，请升级到最新版本下载`） */
    const val ERRNO_CLIENT_TOO_OLD = 50029

    // ---------- 工具方法 ----------

    /** 分享页 Referer（alist 实践：`https://115cdn.com/s/<code>?password=<code>&`） */
    fun shareReferer(shareCode: String, receiveCode: String): String =
        if (receiveCode.isBlank()) {
            "$SHARE_HOST/s/$shareCode"
        } else {
            "$SHARE_HOST/s/$shareCode?password=$receiveCode&"
        }

    /** 取 Cookie 中某字段的值（Cookie 形如 `UID=103616022_A1_169; CID=xxx`） */
    fun cookieValue(cookie: String, name: String): String? =
        cookie.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    /**
     * 登录态 Cookie 是否可用。
     * 只校验 `UID`：`SEID`/`KID` 是 HttpOnly 且会轮换，缺失时让服务端用 errno=990001 明确报错更好定位。
     */
    fun hasLoginCookie(cookie: String): Boolean = cookieValue(cookie, "UID") != null

    /**
     * Cookie 里的 `UID` 形如 `103616022_A1_<时间戳>`，取前面的纯数字部分。
     * 创建分享的 `user_id` 与分享直链的 `user_id` 都要这个数字形式。
     */
    fun extractUserId(cookie: String): String? =
        cookieValue(cookie, "UID")?.substringBefore('_')?.takeIf { it.isNotBlank() }

    /** 合并多段 Cookie（登录 Cookie + 直链响应下发的 CDN Cookie，文档 §4.3） */
    fun mergeCookies(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString("; ")

    /**
     * 从 WebView 的 CookieManager 提取登录 Cookie。
     * 依赖注入的 [getCookie] 依次读取主域与备用域（Android 的 CookieManager 能读到 HttpOnly 的 SEID/KID），
     * 只保留 [COOKIE_KEEP_KEYS] 中的字段并去重，拼成请求头可直接使用的整串 Cookie。
     */
    fun extractCookies(getCookie: (String) -> String?): String {
        val out = linkedMapOf<String, String>()
        for (domain in listOf(COOKIE_DOMAIN, COOKIE_DOMAIN_BACKUP)) {
            val raw = getCookie(domain) ?: continue
            for (kv in raw.split(";")) {
                val pair = kv.trim()
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val key = pair.substring(0, eq)
                val value = pair.substring(eq + 1)
                if (key in COOKIE_KEEP_KEYS && key !in out) out[key] = value
            }
        }
        return out.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 登录 Cookie 是否形似可用（登录页自动检测的「plausible」判据）。
     * 只要求 UID + SEID；真正是否有效由 `GET /user/info` 校验（[Pan115Api.fetchNickname]）。
     */
    fun isValidCookie(cookie: String?): Boolean {
        if (cookie.isNullOrBlank()) return false
        fun has(name: String) = cookie.split(";").any {
            val pair = it.trim()
            pair.startsWith("$name=") && pair.length > name.length + 1
        }
        return has("UID") && has("SEID")
    }

    /**
     * 分享会话 token：`<receive_code>|<分享者 user_id>`。
     * `ShareSession` 只有 shareId/stoken/title 三个字段，分享者 UID（取直链必需）只能随 stoken 带走。
     */
    fun encodeShareToken(receiveCode: String, userId: String): String = "$receiveCode|$userId"

    /** 反解 [encodeShareToken]；格式异常时把整串当提取码、UID 视为空 */
    fun decodeShareToken(token: String): Pair<String, String> {
        val index = token.indexOf('|')
        if (index < 0) return token to ""
        return token.substring(0, index) to token.substring(index + 1)
    }
}
