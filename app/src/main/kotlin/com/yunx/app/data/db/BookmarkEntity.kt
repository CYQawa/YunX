package com.yunx.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 网盘链接收藏（Room 持久化，支持多种分类）。
 */
@Entity(tableName = "bookmark")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 完整分享链接 / 分享文案（再次解析用，原样保存） */
    val link: String,
    /** 分享标题（解析后回填；手动添加可为空，展示时回退为链接） */
    val title: String = "",
    /** 平台枚举名（QUARK/UC/XUNLEI/BAIDU/C139/PAN123），未知为空串 */
    val platform: String = "",
    /** 提取码（可选） */
    val pwd: String = "",
    /** 分类 */
    val category: String = DEFAULT_CATEGORY,
    val createTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_CATEGORY = "未分类"

        /** 预置分类：新增收藏 / 修改分类 / 分类筛选共用 */
        val PRESET_CATEGORIES = listOf(
            DEFAULT_CATEGORY, "视频", "文档", "软件", "音乐", "图片", "压缩包", "其他"
        )
    }
}
