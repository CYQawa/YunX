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

import com.yunx.app.data.network.ILanzouApi
import com.yunx.app.data.network.ILanzouConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import kotlinx.coroutines.delay

/**
 * 蓝奏云优享版分享解析仓库。
 *
 * 平台差异：
 * - 目录：`/unproved/share/list`（匿名可用）——根目录不传 `folderId`，子目录传数字 id；
 * - 取链：`/unproved/file/redirect`（匿名可用，302 Location）；
 * - 转存：`/proved/file/transfer`（需登录 appToken）。
 */
class ILanzouResolveRepository(
    private val api: ILanzouApi,
    private val accountRepository: ILanzouAccountRepository
) : ShareResolveRepository {

    @Volatile
    private var cachedUuid: String? = null

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val shareId = ShareLinkParser.parse(link)?.shareId?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("无法识别蓝奏云优享版分享链接")
            ShareSession(shareId = shareId, stoken = shareId, title = "蓝奏云优享版分享")
        }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        cookie: String
    ): Result<List<ShareFile>> = runCatching {
        api.shareList(session.stoken, folderIdOf(dirFid), uuid())
    }

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        if (file.isdir) throw IllegalStateException("文件夹无法直接下载")
        val fileId = file.fid.removePrefix(ILanzouConstants.FILE_PREFIX)
        // downloadId = AES('<fileId>|<userId>')：未登录传空串，登录则用当前账号 userId
        val userId = accountRepository.getAccount()?.userId.orEmpty()
        api.shareDownload(session.stoken, fileId, userId, uuid())
    }

    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = runCatching {
        if (file.isdir) throw IllegalStateException("文件夹无法直接下载")
        val fileId = file.fid.removePrefix(ILanzouConstants.FILE_PREFIX)
        api.shareDownload(session.stoken, fileId, "", uuid())
    }

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val appToken = cookie.takeIf { it.isNotBlank() }
            ?: accountRepository.getAccount()?.appToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("请先登录蓝奏云优享版")
        val target = toDirFid.ifBlank { ILanzouConstants.ROOT_FOLDER_ID }
        val fileIds = if (file.isdir) emptyList() else listOf(file.fid.removePrefix(ILanzouConstants.FILE_PREFIX))
        val folderIds = if (file.isdir) listOf(file.fid.removePrefix(ILanzouConstants.FOLDER_PREFIX)) else emptyList()
        val key = api.transferShare(appToken, uuid(), session.stoken, fileIds, folderIds, target)
        if (key.isNotBlank()) {
            var done = false
            repeat(30) {
                if (done) return@repeat
                delay(500)
                if (runCatching { api.transferCount(appToken, uuid(), key) }.getOrDefault(0) == 1) done = true
            }
            if (!done) throw IllegalStateException("蓝奏优享仍在处理转存，请稍后刷新列表")
        }
        target
    }

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("蓝奏优享分享请使用 getShareDownloadLink"))

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("蓝奏优享分享无需临时目录"))

    private fun folderIdOf(dirFid: String): String? = when {
        dirFid.isBlank() -> null
        dirFid.startsWith(ILanzouConstants.FOLDER_PREFIX) ->
            dirFid.removePrefix(ILanzouConstants.FOLDER_PREFIX).takeIf { it.isNotBlank() }
        dirFid == ILanzouConstants.ROOT_FOLDER_ID -> null
        else -> dirFid
    }

    private suspend fun uuid(): String {
        cachedUuid?.let { return it }
        val existing = accountRepository.getAccount()?.uuid?.takeIf { isValidUuid(it) }
        val value = existing ?: api.getUuid()
        cachedUuid = value
        return value
    }

    private fun isValidUuid(uuid: String): Boolean = Regex("^[A-Za-z0-9_-]{8,128}$").matches(uuid)
}
