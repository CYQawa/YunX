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

package com.yunx.app.data.network.model

/**
 * 分享有效期（UI 中性码，六大网盘页共用，详见 Agent.md §3.20）。
 *
 * 取值：[FOREVER] = 1 永久有效、[ONE_DAY] = 2 1 天、[SEVEN_DAYS] = 3 7 天、[THIRTY_DAYS] = 4 30 天。
 * `CloudFileSheets` 的有效期选择器与结果显示（`expireLabel`）都以此为准。
 *
 * **各平台接口的有效期语义完全不同，禁止把中性码直接下发给接口**，必须先用本对象转换
 * （2026-10 的 bug：139/百度/123 三个平台把中性码当成了天数，导致「选永久建成 1 天、
 * 选其他显示永久/30 天」）：
 *
 * | 平台 | 接口字段 | 真实语义 | 转换 |
 * |---|---|---|---|
 * | 139 | `period` | 天数；永久 = **不传该字段** | [daysOrNull] |
 * | 百度 | `period` | 0/1/7/30；0 = 永久 | [baiduPeriod] |
 * | 123 | `expiration` | 绝对 ISO 时间串（now + 天数）；永久 = 2099 哨兵 | [daysOrNull] |
 * | 迅雷 | `expiration_days` | **字符串** "-1"/"1"/"7"/"30"；-1 = 永久 | [xunleiDays] |
 * | 夸克 / UC | `expired_type` | 取值恰好等于中性码本身，原值直传 | 无 |
 * | 115 | `share_duration` | **字符串** "-1"/"1"/"3"/"5"/"7"/"15"；档位与本中性码不同（无 30 天、多 3/15 天） | [pan115Duration] |
 *
 * 未知代码一律抛异常（fail-loud）：过去各平台用 `else -> 永久 / 30 天 / "-1"` 兜底，
 * 会把「新增一种有效期但忘了映射」静默变成另一种有效期，宁可报错也不要建错分享。
 */
object ShareExpire {
    /**
     * 未知有效期：服务端未返回该字段、或返回了本项目不认识的取值。
     * **只用于显示回填**（`expireLabel` 会显示「未知」）；作为请求参数会被转换函数抛异常拦下。
     */
    const val UNKNOWN = 0

    /** 永久有效 */
    const val FOREVER = 1

    /** 1 天 */
    const val ONE_DAY = 2

    /** 7 天 */
    const val SEVEN_DAYS = 3

    /** 30 天 */
    const val THIRTY_DAYS = 4

    /**
     * 中性码 → 天数；永久返回 `null`（139 的 `period`、123 的 `expiration` 用）。
     */
    fun daysOrNull(expiredType: Int): Int? = when (expiredType) {
        ONE_DAY -> 1
        SEVEN_DAYS -> 7
        THIRTY_DAYS -> 30
        FOREVER -> null
        else -> throw IllegalArgumentException("未知的分享有效期代码：$expiredType")
    }

    /**
     * 中性码 → 百度 `period`（0 = 永久有效）。
     */
    fun baiduPeriod(expiredType: Int): Int = daysOrNull(expiredType) ?: 0

    /**
     * 中性码 → 迅雷 `expiration_days`（**字符串**，-1 = 永久有效）。
     */
    fun xunleiDays(expiredType: Int): String = daysOrNull(expiredType)?.toString() ?: "-1"

    // ---------- 115 专属档位 ----------
    // 115 的档位是「长期 / 1 天 / 3 天 / 5 天 / 7 天 / 15 天」（接口 share_duration = -1/1/3/5/7/15），
    // 既不是中性码取值、也没有 30 天，所以单独占用 101..105 一段码位，
    // 避免与中性码 3（7 天）等撞车后结果弹窗显示成另一种有效期。
    // 本项目 UI 只暴露「长期 / 1 天 / 3 天 / 7 天 / 15 天」五档（用户的档位决策，见 Agent.md §3.25）。

    /** 115：长期（接口 `share_duration=-1`） */
    const val PAN115_FOREVER = 101

    /** 115：1 天 */
    const val PAN115_ONE_DAY = 102

    /** 115：3 天（通用中性码没有这一档） */
    const val PAN115_THREE_DAYS = 103

    /** 115：7 天 */
    const val PAN115_SEVEN_DAYS = 104

    /** 115：15 天（通用中性码没有这一档） */
    const val PAN115_FIFTEEN_DAYS = 105

    /** 115 有效期选项（展示文案 → 码位），由 115 云盘页传给 `CloudFileSheets` */
    val PAN115_OPTIONS: List<Pair<String, Int>> = listOf(
        "永久有效" to PAN115_FOREVER,
        "1 天" to PAN115_ONE_DAY,
        "3 天" to PAN115_THREE_DAYS,
        "7 天" to PAN115_SEVEN_DAYS,
        "15 天" to PAN115_FIFTEEN_DAYS
    )

    /** 115 码位 → `share_duration`（`-1` = 长期，其余为天数串） */
    fun pan115Duration(expiredType: Int): String = when (expiredType) {
        PAN115_FOREVER -> "-1"
        PAN115_ONE_DAY -> "1"
        PAN115_THREE_DAYS -> "3"
        PAN115_SEVEN_DAYS -> "7"
        PAN115_FIFTEEN_DAYS -> "15"
        else -> throw IllegalArgumentException("未知的分享有效期代码：$expiredType")
    }
}
