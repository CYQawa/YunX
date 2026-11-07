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

import com.yunx.app.R
import com.yunx.app.util.AppStrings

/**
 * 123 云盘登录的纯逻辑（错误映射 + 令牌校验），单独放一处是为了能脱离网络做单测。
 */
internal object Pan123LoginSupport {

    /** 登录失败的类型：只做分类，具体文案由 [describeFailure] 按当前语言渲染 */
    enum class Failure {
        API_CHANGED,
        TOO_FREQUENT,
        SERVICE_UNAVAILABLE,
        VERIFY_REQUIRED,
        ACCOUNT_LOCKED,
        UNKNOWN
    }

    /** 请求过于频繁（HTTP 429 / 业务 429 / 文案里出现频率相关字样）时的统一提示 */
    val MESSAGE_TOO_FREQUENT: String get() = AppStrings.get(R.string.e2_pan123_too_frequent)

    /** 兜底文案：**绝不透传服务端原文**（它可能把账号甚至密码回显出来） */
    val MESSAGE_FALLBACK: String get() = AppStrings.get(R.string.e2_pan123_login_failed)

    /** 触发风控、需要人工过验证时的提示：直接引导到网页登录（那条路不受这套风控限制） */
    val MESSAGE_VERIFY_REQUIRED: String get() = AppStrings.get(R.string.e2_pan123_verify_required)

    /**
     * 把登录失败的响应归类（对齐上游项目的映射口径）。
     *
     * 注意顺序：HTTP 层先判（3xx 说明接口形态变了、429/5xx 是服务端问题），再看业务码，
     * 最后才看服务端文案里的关键词——服务端文案只用于**分类**，一个字都不会回显给用户。
     *
     * 分类与文案分开是为了可测：JVM 单测没有资源表（[AppStrings] 未装配时取到空串），
     * 断言分类即可覆盖全部分支。
     */
    fun classifyFailure(httpStatus: Int, code: Int, serverMessage: String): Failure {
        val message = serverMessage.lowercase()
        return when {
            httpStatus in 300..399 -> Failure.API_CHANGED
            httpStatus == 429 -> Failure.TOO_FREQUENT
            httpStatus >= 500 -> Failure.SERVICE_UNAVAILABLE
            code == 429 -> Failure.TOO_FREQUENT
            FIFTY_PLUS_KEYWORDS.any { message.contains(it) } -> Failure.TOO_FREQUENT
            VERIFY_KEYWORDS.any { message.contains(it) } -> Failure.VERIFY_REQUIRED
            FROZEN_KEYWORDS.any { message.contains(it) } -> Failure.ACCOUNT_LOCKED
            else -> Failure.UNKNOWN
        }
    }

    /** 把登录失败渲染成一句能给用户看的提示（随应用内语言变化） */
    fun describeFailure(httpStatus: Int, code: Int, serverMessage: String): String =
        when (classifyFailure(httpStatus, code, serverMessage)) {
            Failure.API_CHANGED -> AppStrings.get(R.string.e2_pan123_api_changed)
            Failure.TOO_FREQUENT -> MESSAGE_TOO_FREQUENT
            Failure.SERVICE_UNAVAILABLE -> AppStrings.get(R.string.e2_pan123_service_unavailable)
            Failure.VERIFY_REQUIRED -> MESSAGE_VERIFY_REQUIRED
            Failure.ACCOUNT_LOCKED -> AppStrings.get(R.string.e2_pan123_account_locked)
            Failure.UNKNOWN -> MESSAGE_FALLBACK
        }

    /**
     * 登录返回的令牌是否可用：非空、长度合理、不含控制字符（换行/空字节混进去会污染请求头）。
     * 与网页登录读到的 authorToken 走同一套校验。
     */
    fun isValidToken(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= 16384 &&
            !CONTROL_CHARS.containsMatchIn(value)

    /** 频率类关键词（服务端文案里出现即按「太频繁」提示，让用户等一会儿而不是反复试） */
    private val FIFTY_PLUS_KEYWORDS = listOf("频繁", "频率", "次数", "稍后")

    /** 风控/验证类关键词（忽略大小写匹配，英文词也覆盖） */
    private val VERIFY_KEYWORDS = listOf("验证码", "滑块", "安全验证", "验证失败", "captcha", "verify")

    /** 账号状态类关键词 */
    private val FROZEN_KEYWORDS = listOf("冻结", "封禁", "封停", "注销", "禁用")

    private val CONTROL_CHARS = Regex("[\\x00-\\x1f\\x7f]")
}
