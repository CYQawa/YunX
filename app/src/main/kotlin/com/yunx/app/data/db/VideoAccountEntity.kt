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
 * 视频解析账号（各平台共用一张表，用 [platform] 区分）。
 *
 * ★ 为什么不学网盘那样「每个平台一张表」：网盘账号的凭证字段差异很大（cookie / access token /
 *   设备号 / 令牌刷新态），而视频平台**只需要一个完整 Cookie 头**，结构完全一致；用一张表意味着
 *   以后新增视频平台**不必再写 Room 迁移**。
 *
 * [cookie] 与网盘凭证同等对待：经 `SecureAccountDaos` 的字段级加密后落库，明文只在内存中短暂存在。
 * [id] / [isPrimary] / [remark] 沿用网盘多账号那套约定（见 [MultiAccountEntity]）。
 */
@Entity(tableName = "video_account")
data class VideoAccountEntity(
    @PrimaryKey
    override val id: String,
    /** 平台标识：`VideoPlatform.name`（如 `BILIBILI`） */
    val platform: String,
    /** 该平台完整 Cookie 头（加密存储） */
    val cookie: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = false,
    override val remark: String = ""
) : MultiAccountEntity
