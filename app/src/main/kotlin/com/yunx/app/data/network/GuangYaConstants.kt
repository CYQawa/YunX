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

import java.util.concurrent.ThreadLocalRandom

/**
 * 光鸭云盘常量（文档 §1）。
 */
object GuangYaConstants {

    const val CLIENT_ID = "aMe-8VSlkrbQXpUR"

    const val API_BASE = "https://api.guangyapan.com"
    const val ACCOUNT_BASE = "https://account.guangyapan.com"
    const val WEB_SITE = "https://www.guangyapan.com"

    /** 下载直链必须携带的 Referer（文档 §5.6/§7.1） */
    const val DOWNLOAD_REFERER = "$WEB_SITE/"

    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // ---------- 账号 API 公共头（文档 §1.3） ----------
    const val CLIENT_VERSION = "0.0.1"
    const val SDK_VERSION = "9.1.3"
    const val PROTOCOL_VERSION = "301"
    const val DEVICE_MODEL = "chrome%2F131.0.0.0"
    const val DEVICE_NAME = "PC-Chrome"
    const val OS_VERSION = "Win32"
    const val PLATFORM_VERSION = "1"

    // ---------- 端点（文档 §3 业务接口总表） ----------
    const val AUTH_TOKEN = "$ACCOUNT_BASE/v1/auth/token"
    const val USER_ME = "$ACCOUNT_BASE/v1/user/me"
    const val CAPTCHA_INIT = "$ACCOUNT_BASE/v1/shield/captcha/init"
    const val SIGNIN = "$ACCOUNT_BASE/v1/auth/signin"
    const val SIGNUP = "$ACCOUNT_BASE/v1/auth/signup"
    const val VERIFICATION = "$ACCOUNT_BASE/v1/auth/verification"
    const val VERIFICATION_VERIFY = "$ACCOUNT_BASE/v1/auth/verification/verify"

    const val GET_ASSETS = "$API_BASE/assets/v1/get_assets"
    const val SHARE_SUMMARY = "$API_BASE/userres/v1/get_share_summary"
    const val SHARE_ACCESS_TOKEN = "$API_BASE/userres/v1/get_share_access_token"
    const val SHARE_FILES = "$API_BASE/userres/v1/get_share_page_files_list"
    const val SHARE_DOWNLOAD = "$API_BASE/userres/v1/get_share_download_url"
    const val RESTORE_SHARE = "$API_BASE/userres/v1/restore_share"
    const val SHARE_FILE = "$API_BASE/userres/v1/share_file"

    const val FILE_LIST = "$API_BASE/userres/v1/file/get_file_list"
    const val CREATE_DIR = "$API_BASE/userres/v1/file/create_dir"
    const val RENAME = "$API_BASE/userres/v1/file/rename"
    const val MOVE_FILE = "$API_BASE/userres/v1/file/move_file"
    const val DELETE_FILE = "$API_BASE/userres/v1/file/delete_file"
    const val RES_DOWNLOAD = "$API_BASE/userres/v1/get_res_download_url"
    const val TASK_STATUS = "$API_BASE/userres/v1/get_task_status"

    /** 分享根目录 id（空串） */
    const val ROOT_PARENT_ID = ""

    /** 分享页大小（文档 §4.3/§5.1） */
    const val PAGE_SIZE = 100

    /** 任务轮询：首次前等待 taskDelay（默认 750ms），最多 80 次（文档 §5.7/§8.2） */
    const val TASK_DELAY_MS = 750L
    const val TASK_MAX_POLLS = 80

    /** 生成 32 位十六进制设备 id */
    fun newDeviceId(): String {
        val rnd = ThreadLocalRandom.current()
        return buildString(32) {
            repeat(32) { append("0123456789abcdef"[rnd.nextInt(16)]) }
        }
    }

    /** 形如 wdi10.<32位十六进制>xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx 的设备签名 */
    fun newDeviceSign(deviceId: String): String {
        val rnd = ThreadLocalRandom.current()
        val tail = buildString(32) {
            repeat(32) { append("0123456789abcdef"[rnd.nextInt(16)]) }
        }
        return "wdi10.$deviceId$tail"
    }

    /** X-Device-Id：取 deviceSign 中第一个 "." 之后的 32 个字符；不足则回退 deviceId（文档 §1.4） */
    fun deviceIdFromSign(deviceSign: String, fallback: String): String {
        val idx = deviceSign.indexOf('.')
        if (idx < 0) return fallback
        val rest = deviceSign.substring(idx + 1)
        return if (rest.length >= 32) rest.substring(0, 32) else fallback
    }

    /** 生成 traceparent（文档 §1.3：每次请求重新生成） */
    fun newTraceparent(): String {
        val rnd = ThreadLocalRandom.current()
        fun hex(n: Int) = buildString(n) { repeat(n) { append("0123456789abcdef"[rnd.nextInt(16)]) } }
        return "00-${hex(32)}-${hex(16)}-01"
    }
}
