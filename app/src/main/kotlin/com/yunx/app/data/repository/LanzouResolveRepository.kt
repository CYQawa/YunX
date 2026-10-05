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

package com.yunx.app.data.repository

import com.yunx.app.data.network.LanzouApi
import com.yunx.app.data.network.LanzouSharePage
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 蓝奏云分享解析仓库（文档 §3）：**匿名**，无需登录。
 * 分享页 HTML → 文件夹分页（filemoreajax）/ 单文件 → 下载参数（ajaxm/ajaxfile）→ HEAD 探测直链。
 * 解析出的分享页缓存在内存中（按分享 URL 作 key），供后续列目录 / 取链复用。
 */
class LanzouResolveRepository(
    private val api: LanzouApi
) : ShareResolveRepository {

    private val pageCache = mutableMapOf<String, LanzouSharePage>()

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val page = api.resolveShare(link, pwd)
            if (page.needsPwd && pwd.isNullOrBlank()) {
                throw IllegalStateException("此蓝奏分享需要提取码，请填写后重新解析")
            }
            pageCache[page.shareUrl] = page
            ShareSession(shareId = page.shareUrl, stoken = pwd.orEmpty(), title = page.title)
        }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val page = pageCache[session.shareId]
                ?: throw IllegalStateException("蓝奏分享会话已失效，请重新解析")
            val pwd = session.stoken.takeIf { it.isNotBlank() }
            if (page.isFolder) {
                api.listShareFolder(page, pwd, dirFid)
            } else {
                listOfNotNull(page.singleFile)
            }
        }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("蓝奏分享暂不支持直接转存，请下载后上传"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = Result.failure(
        UnsupportedOperationException("蓝奏分享暂不支持直接转存，请下载后上传")
    )

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("蓝奏分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val page = pageCache[session.shareId]
            ?: throw IllegalStateException("蓝奏分享会话已失效，请重新解析")
        api.getShareDownloadLink(page, file, session.stoken.takeIf { it.isNotBlank() })
    }

    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = runCatching {
        val page = pageCache[session.shareId]
            ?: throw IllegalStateException("蓝奏分享会话已失效，请重新解析")
        api.getShareDownloadLink(page, file, session.stoken.takeIf { it.isNotBlank() })
    }
}
