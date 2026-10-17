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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 蓝奏云优享版登录凭证（文档 §1.2/§2.2）：
 * - appToken：所有 apis.ilanzou.com 请求的 URL query 鉴权参数（非请求头）；
 * - uuid：设备标识（所有请求携带）；
 * - account / password：密码登录时同时保存，用于 appToken 失效后自动重新登录
 *   （网页登录得到的 appToken 没有密码，token 失效后只能重新登录）。
 */
@Entity(tableName = "ilanzou_account")
data class ILanzouAccountEntity(
    @PrimaryKey
    override val id: String = "ilanzou",
    val appToken: String = "",
    val uuid: String = "",
    val account: String = "",
    val password: String = "",
    val userId: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    /** 是否该平台的主账号（多账号）：同平台只应有一条为 true，见 [MultiAccountEntity] */
    override val isPrimary: Boolean = false,
    /** 用户备注，便于区分同一平台的多个账号（空串 = 界面回退显示昵称） */
    override val remark: String = ""
) : MultiAccountEntity
