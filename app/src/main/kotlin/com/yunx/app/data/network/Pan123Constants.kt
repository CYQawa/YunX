package com.yunx.app.data.network

/**
 * 123 云盘（123pan / 123865）常量（依据《123网盘API文档_面向Agent.md》）。
 * 两类主域名：
 * - 分享解析域：mshare.123pan.cn（匿名分享读取）、www.123865.com（分享下载信息）、www.123pan.com / yun.123pan.cn（业务 API）；
 * - 个人盘域：yun.123pan.cn（个人盘 API / 网页登录页）。
 *
 * 鉴权：所有 yun.123pan.cn / www.123865.com 的鉴权请求带 `auth-key` / `auth-value` 签名头（第 6 节），
 * 匿名分享列表无需签名。
 *
 * 登录方式：WebView 打开官网个人盘主页（[WEB_LOGIN_URL]），登录成功后网页把 Bearer JWT 写入
 * 当前域 localStorage（键 [LOCAL_STORAGE_TOKEN_KEY] = authorToken）；应用提取该值作为登录凭证，
 * 与旧账号密码登录接口返回的 data.token 同源同形。
 */
object Pan123Constants {

    // ---------- BaseURL（按用途，文档 §3.1） ----------

    /** 个人盘业务 API / 分享列表（主域式） */
    const val API_BASE = "https://yun.123pan.cn"

    /** 分享下载信息（抓包实证；alist 用 yun.123pan.com 等价） */
    const val DOWNLOAD_BASE = "https://www.123865.com"

    // ---------- API 路径（严格按文档 §5，不要自行加/去 /b） ----------

    /** 网页登录页：官网个人盘主页（未登录自动进入登录流程；登录后 localStorage 写入 authorToken） */
    const val WEB_LOGIN_URL = "https://yun.123pan.cn/"

    /** 网页登录态在 localStorage 中的键名：值即 Bearer JWT（与旧 sign_in 返回的 data.token 同源同形） */
    const val LOCAL_STORAGE_TOKEN_KEY = "authorToken"

    /** 分享文件列表（GET /b/api/share/get，匿名、无签名） */
    const val SHARE_GET_URL = "$API_BASE/b/api/share/get"

    /** 分享下载信息（POST /b/api/share/download/info，需登录+签名） */
    const val SHARE_DOWNLOAD_INFO_URL = "$DOWNLOAD_BASE/b/api/share/download/info"

    /** 个人盘文件列表（GET /b/api/file/list/new，需登录+签名） */
    const val FILE_LIST_URL = "$API_BASE/b/api/file/list/new"

    /** 个人盘下载信息（POST /api/file/download_info，注意无 /b/） */
    const val FILE_DOWNLOAD_INFO_URL = "$API_BASE/api/file/download_info"

    /** 流量校验（POST /b/api/file/download/traffic/check） */
    const val TRAFFIC_CHECK_URL = "$API_BASE/b/api/file/download/traffic/check"

    /** 删除/移入回收站（POST /b/api/file/trash） */
    const val FILE_TRASH_URL = "$API_BASE/b/api/file/trash"

    /** 重命名（POST /b/api/file/rename） */
    const val FILE_RENAME_URL = "$API_BASE/b/api/file/rename"

    /** 移动（POST /b/api/file/mod_pid） */
    const val FILE_MOD_PID_URL = "$API_BASE/b/api/file/mod_pid"

    /** 创建分享（POST /b/api/share/create） */
    const val SHARE_CREATE_URL = "$API_BASE/b/api/share/create"

    /** 用户信息（GET /b/api/user/info，校验登录态/取昵称/容量） */
    const val USER_INFO_URL = "$API_BASE/b/api/user/info"

    // ---------- 公共请求头（文档 §3.2） ----------

    /** 浏览器 UA（web 系抓包） */
    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    /** Dart UA（分享列表匿名请求） */
    const val DART_UA = "Dart/3.12 (dart:io)"

    /** platform：web（个人盘/分享列表/登录） */
    const val PLATFORM_WEB = "web"

    /** platform：android（分享下载信息 www.123865.com） */
    const val PLATFORM_ANDROID = "android"

    /** app-version：web 系 */
    const val APP_VERSION_WEB = "3"

    /** app-version：android 系（仅分享下载） */
    const val APP_VERSION_ANDROID = "39"

    /** 分享下载真实 CDN 直链下载时必须携带的 Referer（文档 §5.3.1） */
    const val DOWNLOAD_REFERER = "https://yun.123pan.cn/"

    // ---------- 签名算法常量（文档 §6.2） ----------

    /** 数字 0-9 的替换表，索引 = 数字值 */
    const val SIGN_TABLE = "adefghlmyijnopkqrstubcvwsz"

    /** 签名内部固定 OS */
    const val SIGN_OS = "web"

    /** 签名内部固定 VER */
    const val SIGN_VER = "3"

    /** timeSign 时间基准偏移：ts + 57600 秒（+16h，UTC 格式化；抓包实证，文档 §6.3） */
    const val SIGN_OFFSET_SECONDS = 57600L

    /** 永久分享的过期时间（文档 §5.10：永久=2099-12-12T08:00:00+08:00） */
    const val EXPIRATION_FOREVER = "2099-12-12T08:00:00+08:00"

    /** 生成 32 位十六进制 loginuuid（文档 §3.2：设备标识，不参与签名，可固定复用） */
    fun newLoginUuid(): String {
        val chars = "0123456789abcdef"
        return buildString {
            repeat(32) { append(chars.random()) }
        }
    }
}
