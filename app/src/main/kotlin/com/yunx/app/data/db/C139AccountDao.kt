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

@Dao
interface C139AccountDao {

    /**
     * 主账号（列目录 / 转存 / 取链默认用它）。
     * ★ 取「主账号优先、否则最近更新的一条」而不是「唯一那条」：多账号下退出主账号后
     *   能立刻回退到同平台其他账号，不会出现「明明还有账号却显示未登录」。
     */
    @Query("SELECT * FROM c139_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1")
    fun observeAccount(): Flow<C139AccountEntity?>

    @Query("SELECT * FROM c139_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1")
    suspend fun getAccount(): C139AccountEntity?

    /** 该平台全部已登录账号（主账号在前）：账号管理页与多账号下载轮询用 */
    @Query("SELECT * FROM c139_account ORDER BY isPrimary DESC, updatedAt DESC")
    fun observeAccounts(): Flow<List<C139AccountEntity>>

    @Query("SELECT * FROM c139_account ORDER BY isPrimary DESC, updatedAt DESC")
    suspend fun getAccounts(): List<C139AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: C139AccountEntity)

    /** 设为主账号：一条 SQL 把目标行置 1、同平台其余行置 0（免事务） */
    @Query("UPDATE c139_account SET isPrimary = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setPrimary(id: String)

    @Query("UPDATE c139_account SET remark = :remark WHERE id = :id")
    suspend fun updateRemark(id: String, remark: String)

    @Query("DELETE FROM c139_account WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空该平台全部账号（「网盘认证」导入前的整体覆盖等场景） */
    @Query("DELETE FROM c139_account")
    suspend fun clear()
}
