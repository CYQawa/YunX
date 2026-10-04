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

package com.yunx.app.util

import android.os.Environment
import java.io.File

/**
 * 下载目录的公共口径（内置分片下载器与 Gopeed 引擎共用，别各自写死一份）。
 *
 * 两套下载器的落盘方式完全不同 —— 内置的走 MediaStore/SAF（写 `content://`，Android 10+ 免存储权限），
 * 引擎是原生核心、**只能按真实文件路径写** —— 但**默认目录是同一个**：
 * 公共 `Download` 根目录，也就是 `/storage/emulated/0/Download/`（用户要求「默认下载目录统一」）。
 */
object StorageDirs {

    /**
     * 默认下载目录：公共 `Download` 根目录。
     *
     * 内置下载器落点就是这里（MediaStore 的 `RELATIVE_PATH = Download`）；引擎直接拿绝对路径。
     * 注意 `getExternalStoragePublicDirectory` 已废弃（分区存储时代推荐 MediaStore），
     * 但引擎本来就必须用真实路径，这里没有替代品。
     */
    @Suppress("DEPRECATION")
    fun defaultDownloadDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** 默认下载目录的绝对路径（UI 展示用） */
    fun defaultDownloadPath(): String = defaultDownloadDir().absolutePath
}
