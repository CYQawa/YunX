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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 视频解析账号。所有平台共用一张表，因此**按 platform 过滤**的查询都要带上平台条件
 * （`setPrimary` 尤其注意：不带平台条件会把别的平台的主账号一起清掉）。
 */
@Dao
interface VideoAccountDao {

    /** 全部平台的账号（主账号在前），账号页展示用 */
    @Query("SELECT * FROM video_account ORDER BY platform ASC, isPrimary DESC, updatedAt DESC")
    fun observeAccounts(): Flow<List<VideoAccountEntity>>

    @Query("SELECT * FROM video_account ORDER BY platform ASC, isPrimary DESC, updatedAt DESC")
    suspend fun getAccounts(): List<VideoAccountEntity>

    /** 某平台的账号（主账号在前） */
    @Query("SELECT * FROM video_account WHERE platform = :platform ORDER BY isPrimary DESC, updatedAt DESC")
    fun observeAccountsOf(platform: String): Flow<List<VideoAccountEntity>>

    /** 该平台当前使用的账号：主账号优先，没有主账号时用最近更新的那条 */
    @Query("SELECT * FROM video_account WHERE platform = :platform ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1")
    suspend fun getActive(platform: String): VideoAccountEntity?

    /** 该平台已存在的行数（判断「首次登录」还是「再加一个账号」） */
    @Query("SELECT COUNT(*) FROM video_account WHERE platform = :platform")
    suspend fun countOf(platform: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: VideoAccountEntity)

    /** 设为主账号：**只在本平台范围内**把目标行置 1、其余置 0 */
    @Query("UPDATE video_account SET isPrimary = CASE WHEN id = :id THEN 1 ELSE 0 END WHERE platform = :platform")
    suspend fun setPrimary(platform: String, id: String)

    @Query("UPDATE video_account SET remark = :remark WHERE id = :id")
    suspend fun updateRemark(id: String, remark: String)

    /** 退出单个账号 */
    @Query("DELETE FROM video_account WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 退出某平台的全部账号 */
    @Query("DELETE FROM video_account WHERE platform = :platform")
    suspend fun clearPlatform(platform: String)
}
