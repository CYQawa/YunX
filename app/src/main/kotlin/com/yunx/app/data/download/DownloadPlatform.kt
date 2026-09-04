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

package com.yunx.app.data.download

/**
 * 下载来源平台标识（用于按平台独立设置下载线程数）。
 * 字符串常量而非枚举：便于直接持久化到 Room 字段，也与各 ViewModel 解耦。
 */
object DownloadPlatform {
    const val QUARK = "quark"
    const val UC = "uc"
    const val XUNLEI = "xunlei"
    const val BAIDU = "baidu"
    const val C139 = "c139"
    const val PAN123 = "pan123"
    /** 通用/未知来源（手动添加、应用更新下载等） */
    const val GENERIC = "generic"
}
