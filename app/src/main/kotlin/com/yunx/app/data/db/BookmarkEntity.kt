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

import androidx.annotation.StringRes
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.yunx.app.R

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
    /** 是否已添加到主页快捷方式：主页（解析页）下方网格展示，点击直接解析 */
    val homePinned: Boolean = false,
    /** 主页快捷方式色块的自定义文字（空串 = 自动取标题前几个字） */
    val homeLabel: String = "",
    val createTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_CATEGORY = "未分类"

        /** 预置分类：新增收藏 / 修改分类 / 分类筛选共用 */
        val PRESET_CATEGORIES = listOf(
            DEFAULT_CATEGORY, "视频", "文档", "软件", "音乐", "图片", "压缩包", "其他"
        )

        /**
         * 预置分类的展示文案资源。
         *
         * 存库与筛选口径仍是 [PRESET_CATEGORIES] 里的规范值（不随语言变化），
         * 仅在界面展示时映射成当前语言；自定义分类返回 null，调用方直接展示原文。
         */
        @StringRes
        fun presetLabelRes(category: String): Int? = when (category) {
            "未分类" -> R.string.rs_category_uncategorized
            "视频" -> R.string.rs_category_video
            "文档" -> R.string.rs_category_document
            "软件" -> R.string.rs_category_software
            "音乐" -> R.string.rs_category_music
            "图片" -> R.string.rs_category_image
            "压缩包" -> R.string.rs_category_archive
            "其他" -> R.string.rs_category_other
            else -> null
        }
    }
}
