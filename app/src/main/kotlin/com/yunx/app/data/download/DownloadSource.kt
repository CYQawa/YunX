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
 * 一个下载源：直链 + 它专属的请求头。
 *
 * ★ 单文件多源分片（实验性 C2）下，同一个文件的多条直链分属**不同账号**，
 *   请求头里的 Cookie 不能混用 —— 所以「直链 + 请求头」必须成对携带，
 *   不能像单源那样全任务共用一份 headers（那会拿 A 账号的直链配 B 账号的 Cookie，CDN 直接 412/403）。
 */
data class DownloadSource(
    val url: String,
    val headers: Map<String, String>
)
