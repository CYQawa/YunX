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
    val id: String = "ilanzou",
    val appToken: String = "",
    val uuid: String = "",
    val account: String = "",
    val password: String = "",
    val userId: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
