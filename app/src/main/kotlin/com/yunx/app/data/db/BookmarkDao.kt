package com.yunx.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmark ORDER BY createTime DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    /** 已出现的分类（去重），用于与预置分类合并展示 */
    @Query("SELECT DISTINCT category FROM bookmark ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmark SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun delete(id: Long)
}
