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
 * 光鸭云盘登录凭证（账号密码登录换取的 access/refresh token + 设备标识落库，文档 §2）。
 * - accessToken：业务 API 的 `Authorization: Bearer <accessToken>`；
 * - refreshToken：access 过期时 POST /v1/auth/token 续期；
 * - deviceId / deviceSign：账号 API 的 X-Device-* 头（deviceSign 形如 wdi10.<32位十六进制>...）。
 */
@Entity(tableName = "guangya_account")
data class GuangYaAccountEntity(
    @PrimaryKey
    val id: String = "guangya",
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val deviceSign: String = "",
    val account: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
