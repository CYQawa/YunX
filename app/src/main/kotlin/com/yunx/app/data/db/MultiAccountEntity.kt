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

package com.yunx.app.data.db

/**
 * 多账号：各平台账号表的凭证字段各不相同（cookie / access token / 设备号…），
 * 但「账号目录」——列表展示、设主账号、改备注、退出单个账号——只关心下面这几个公共字段。
 *
 * ★ 抽成接口而不是给 UI 写 10 套 `when(platform)`：账号管理界面与多账号下载轮询
 *   都只处理「id / 昵称 / 备注 / 是否主账号 / 更新时间」，加新平台时不用再动这些逻辑。
 */
interface MultiAccountEntity {
    /** 账号行主键：老数据沿用平台常量（如 `"quark"`），新增账号为 `"<platform>-<时间戳36进制>"` */
    val id: String

    /** 平台侧昵称（可能为空串） */
    val nickname: String

    /** 用户自定义备注，空串 = 界面回退显示昵称 */
    val remark: String

    /**
     * 该平台的主账号。
     * ★ 列目录 / 转存 / 上传 / 新建分享等**需要单一身份**的操作走主账号；
     *   多账号下载（C1 轮询、C2 多源）才会用到非主账号。同平台只应有一条为 true。
     */
    val isPrimary: Boolean

    val updatedAt: Long
}

/** 新账号主键：`<平台>-<时间戳36进制>`，与老数据沿用的平台常量主键（如 `"quark"`）并存 */
internal fun newAccountId(platform: String): String =
    "$platform-${System.currentTimeMillis().toString(36)}"

/** [resolveAccountSlot] 的结果：本次登录要写入的账号行（主键 / 是否主账号 / 备注） */
internal data class AccountSlot(val id: String, val isPrimary: Boolean, val remark: String)

/**
 * 一次登录应该落到哪一行 —— 多账号下「多登录一次」不等于「多一个账号」。
 *
 * - 该平台还没有账号 ⇒ 用平台常量主键（与老数据同形）并设为主账号；
 * - 「添加账号」且已有账号 ⇒ 用新主键、非主账号、备注留空；
 * - 日常重新登录（含会话过期后重登）⇒ 覆盖当前主账号那一行，并继承它的主账号标记与备注，
 *   否则每次重登都会多堆一个失效账号出来。
 */
internal fun resolveAccountSlot(
    platform: String,
    existing: MultiAccountEntity?,
    newAccount: Boolean
): AccountSlot = when {
    existing == null -> AccountSlot(platform, isPrimary = true, remark = "")
    newAccount -> AccountSlot(newAccountId(platform), isPrimary = false, remark = "")
    else -> AccountSlot(existing.id, isPrimary = existing.isPrimary, remark = existing.remark)
}
