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
 * 迅雷网盘常量（依据抓包 + 迅雷网盘API文档，两者互相印证）。
 */
object XunleiConstants {

    /** 登录 / 验证码 / Token 主机 */
    const val AUTH_BASE = "https://xluser-ssl.xunlei.com"

    /** 文件 / 分享 / 下载主机 */
    const val PAN_BASE = "https://api-pan.xunlei.com"

    /** Web 端公开凭据（文档推荐，可正常换 Token） */
    const val CLIENT_ID = "Xp6pAdwyJv9sQuoN"
    const val CLIENT_SECRET = "standard_a@api#"

    /** App 端凭据（官方 app 抓包，/v1/auth/signin/token 换 token 用） */
    const val APP_CLIENT_ID = "Xp6vsxz_7IYVw2BB"
    const val APP_CLIENT_SECRET = "Xp6vsy4tN9toTVdMSpomVdXpRmES"

    /** Android 端身份（captcha_sign 计算用，alist 验证与 MoePal 抓包一致） */
    const val APP_CLIENT_VERSION = "8.31.0.9726"
    const val APP_PACKAGE_NAME = "com.xunlei.downloadprovider"

    /** Android 端 captcha 盐（10 个，alist 源码确认，活体验证通过） */
    val CAPTCHA_SALTS = listOf(
        "9uJNVj/wLmdwKrJaVj/omlQ",
        "Oz64Lp0GigmChHMf/6TNfxx7O9PyopcczMsnf",
        "Eb+L7Ce+Ej48u",
        "jKY0",
        "ASr0zCl6v8W4aidjPK5KHd1Lq3t+vBFf41dqv5+fnOd",
        "wQlozdg6r1qxh0eRmt3QgNXOvSZO6q/GXK",
        "gmirk+ciAvIgA/cxUUCema47jr/YToixTT+Q6O",
        "5IiCoM9B1/788ntB",
        "P07JH0h6qoM6TSUAK2aL9T5s2QBVeY9JWvalf",
        "+oK0AN"
    )

    /** App UA（官方 app 抓包） */
    const val APP_UA =
        "ANDROID-com.xunlei.downloadprovider/8.31.0.9726 netWorkType/5G appid/40 " +
            "deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC OSVersion/12 protocolVersion/301 " +
            "platformVersion/10 sdkVersion/512000 Oauth2Client/0.9 (Linux 4_14_186-perf-gddfs8vbb238b) (JAVA 0)"

    /** 浏览器 UA（Web 端 pan 请求） */
    const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    // ---------- 设备标识（fallback 官方指纹，正常情况下由 XunleiDeviceFingerprint 动态生成） ----------
    // devicesign 后半段为迅雷 SDK 生成的设备指纹，动态生成算法见 XunleiDeviceFingerprint（§8 公式）。
    // 以下官方抓包值仅作为「指纹未初始化/异常路径」的兜底，避免请求缺字段崩溃。

    /** 设备 ID（x-device-id / captcha device_id / devicesign 前半；fallback） */
    const val DEVICE_ID = "78a70629a2b17d0b4302317ffa94807a"

    /** 登录请求 peerID（fallback） */
    const val PEER_ID = "92df4c42e0926ff55f1c605ebe4c3754"

    /** 设备指纹 div101.设备ID+SDK指纹（fallback） */
    const val DEVICE_SIGN = "div101.78a70629a2b17d0b4302317ffa94807a31491e163e795b39e798ed33ae58858b"

    // ---------- 登录端点 ----------

    /** 验证码盾初始化 */
    const val CAPTCHA_INIT_URL = "$AUTH_BASE/v1/shield/captcha/init"

    /** 账号密码登录（xluser 会话） */
    const val LOGIN_URL = "$AUTH_BASE/xluser.core.login/v3/login"

    /** 发送短信验证码 */
    const val SEND_SMS_URL = "$AUTH_BASE/xluser.core.login/v3/sendsms"

    /** 短信验证码登录 */
    const val SMS_LOGIN_URL = "$AUTH_BASE/xluser.core.login/v3/smslogin"

    /** 换取 access_token（官方 app 抓包：POST /v1/auth/signin/token，body 带 signin_token=sessionID） */
    const val TOKEN_URL = "$AUTH_BASE/v1/auth/signin/token"

    /** 刷新 access_token（OAuth2 refresh_token；导入恢复后 token 过期自动续期） */
    const val REFRESH_URL = "$AUTH_BASE/v1/auth/token"

    // ---------- Pan 端点 ----------

    /** 文件列表 / 详情 / 建目录 */
    const val FILES_URL = "$PAN_BASE/drive/v1/files"

    /** 分享解析（GET ?share_id=&pass_code=&limit=&page_token=&thumbnail_size=） */
    const val SHARE_URL = "$PAN_BASE/drive/v1/share"

    /** 分享子目录文件列表（GET ?share_id=&parent_id=&pass_code_token=&limit=&page_token=&thumbnail_size=） */
    const val SHARE_DETAIL_URL = "$PAN_BASE/drive/v1/share/detail"

    /** 转存（POST） */
    const val RESTORE_URL = "$PAN_BASE/drive/v1/share/restore"

    /** 异步任务轮询（GET /tasks/{taskId}?type=share） */
    const val TASKS_URL = "$PAN_BASE/drive/v1/tasks"

    /** 转存目标目录名 */
    const val TEMP_DIR_NAME = "YunX临时转存"

    /** 移动文件（batchMove：ids + to.parent_id） */
    const val MOVE_URL = "$PAN_BASE/drive/v1/files:batchMove"

    /** 删除文件（batchTrash：ids + space） */
    const val TRASH_URL = "$PAN_BASE/drive/v1/files:batchTrash"

    /** 创建分享（POST /drive/v1/share，file_ids + title + expiration_days） */
    const val SHARE_CREATE_URL = "$PAN_BASE/drive/v1/share"
}