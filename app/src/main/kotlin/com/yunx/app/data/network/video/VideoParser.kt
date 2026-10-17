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

package com.yunx.app.data.network.video

/**
 * 单个视频平台的解析器。
 *
 * 实现约定：
 * - 网络请求走 `HttpClients.apiClient()`（统一超时 + 代理），不要自己 new OkHttpClient；
 * - 失败一律抛 [VideoParseException]（带 [VideoParseErrorKind]），不要抛裸异常；
 * - [cookie] 为该平台账号的完整 Cookie 头（未登录时为空串）。需要登录的平台在空 Cookie 时应抛
 *   `UNSUPPORTED` 并给出「请先到账号页登录」这类可操作提示；
 * - 产出的 [VideoMedia.headers] 要带上该直链真正需要的 Referer/UA/Cookie，下载器会原样使用。
 */
interface VideoParser {

    val platform: VideoPlatform

    /**
     * 解析一条已识别的链接。
     *
     * @param link 由 [VideoLinkParser] 识别出的平台内资源标识（短链已在内部展开）
     * @param cookie 该平台的 Cookie；未登录为空串
     */
    suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult

    /**
     * 保存登录态前的校验：返回**昵称**（拿不到昵称时返回空串），Cookie 无效返回 null。
     *
     * ★ 为什么要有这一步：网页登录抓到的 Cookie 可能是「页面已加载但没真正登录」的中间态，
     *   不做校验就会存下一份永远解析失败的账号，用户看不懂哪里错了（网盘那边同样会先校验再保存）。
     * 默认实现只校验非空，能真正问平台「我是谁」的解析器应当覆盖它。
     */
    suspend fun validateCookie(cookie: String): String? =
        cookie.trim().takeIf { it.isNotBlank() }?.let { "" }
}
