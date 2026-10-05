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

import com.yunx.app.data.network.GuangYaApi
import com.yunx.app.data.network.GuangYaConstants
import com.yunx.app.data.network.GuangYaShareRestrictedException
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * 光鸭云盘分享解析仓库（文档 §4）：
 * 摘要取标题 → 提取码换分享访问令牌（存 session.stoken）→ 游标翻页列目录 → 分享取下载地址；
 * 转存走 restore_share（需登录态 access token，由 ResolveViewModel 以 cookie 参数传入）。
 * 分享列目录与取链本身匿名可用（业务 API 公共头，无 Authorization）。
 */
class GuangYaResolveRepository(
    private val api: GuangYaApi,
    private val tokenProvider: suspend () -> String?
) : ShareResolveRepository {

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val parsed = ShareLinkParser.parse(link)
            ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
        return runCatching {
            val sharePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd.orEmpty()
            val accessToken = api.getShareAccessToken(parsed.shareId, sharePwd)
            val title = api.getShareSummary(parsed.shareId)?.ifBlank { null } ?: "光鸭分享"
            ShareSession(shareId = parsed.shareId, stoken = accessToken, title = title)
        }
    }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val all = mutableListOf<ShareFile>()
            val seen = mutableSetOf<String>()
            var cursor: String? = null
            var page = 0
            do {
                val (files, next) = api.listShareFiles(session.stoken, dirFid, cursor)
                files.forEach { if (seen.add(it.fid)) all.add(it) }
                cursor = next
                page++
            } while (cursor != null && page < 1000)
            all
        }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("光鸭分享无需预建临时目录"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val token = cookie.ifBlank { tokenProvider() ?: "" }
        if (token.isBlank()) throw IllegalStateException("请先登录光鸭云盘")
        // body 用分享访问令牌，Authorization 用账号 token
        api.restoreShare(token, session.stoken, listOf(file.fid), toDirFid.ifBlank { "" })
        toDirFid
    }

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("光鸭分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        try {
            api.getShareDownloadUrl(file, session.stoken)
                ?: throw IllegalStateException("光鸭未返回有效下载地址，请确认下载权限")
        } catch (e: GuangYaShareRestrictedException) {
            // 分享者未开启免登录下载：已登录则转存到临时目录再从个人盘取链（参考实现 wenxi _shareDownload）
            transferAndDownload(session, file, cookie)
        }
    }

    /**
     * 受限分享下载：在个人盘根目录建临时目录 → restore_share 转存 → 轮询目标文件 →
     * 用转存后的文件 ID 取个人盘直链，并把临时目录 id 作为 cleanupDirFid 交给下载完成时清理。
     */
    private suspend fun transferAndDownload(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): DownloadLink {
        val token = cookie.takeIf { it.isNotBlank() } ?: tokenProvider()
            ?: throw IllegalStateException("请先登录光鸭云盘")
        // 名称规则与参考实现一致，便于识别与清理
        val folderName = "AsterLink临时转存_${UUID.randomUUID()}"
        val folderId = api.createDir(token, "", folderName)
        try {
            api.restoreShare(token, session.stoken, listOf(file.fid), folderId)
            val target = pollCopiedFile(token, folderId, file)
            val link = api.getDownloadLink(token, target)
                ?: throw IllegalStateException("光鸭未返回有效下载地址，请确认下载权限")
            return link.copy(fid = target.fid, filename = file.fname, cleanupDirFid = folderId)
        } catch (e: Exception) {
            // 取链/校验失败：清掉刚建的临时目录，避免残留
            runCatching { api.deleteFiles(token, listOf(folderId)) }
            throw e
        }
    }

    /** 轮询临时目录直到转存完成（同名文件出现），最多 30 次、间隔 taskDelay。 */
    private suspend fun pollCopiedFile(
        token: String,
        folderId: String,
        source: ShareFile
    ): ShareFile {
        repeat(30) {
            delay(GuangYaConstants.TASK_DELAY_MS)
            val files = runCatching { api.listCloudFiles(token, folderId) }.getOrDefault(emptyList())
            val matches = files.filter { !it.isdir && it.fname == source.fname }
            if (matches.size > 1) throw IllegalStateException("光鸭临时目录出现同名文件，无法确定下载目标")
            val target = matches.firstOrNull()
            if (target != null) {
                if (source.fsize > 0 && target.fsize > 0 && source.fsize != target.fsize) {
                    throw IllegalStateException("光鸭转存文件大小不一致")
                }
                return target
            }
        }
        throw IllegalStateException("光鸭转存已提交，目标文件暂不可见，请稍后重试")
    }

    /** 下载完成/放弃后清理临时转存目录（ResolveViewModel 通过 cleanupDirFid 回调）。 */
    override suspend fun cleanupTempDir(dirFid: String, cookie: String) {
        if (dirFid.isBlank()) return
        val token = cookie.takeIf { it.isNotBlank() } ?: tokenProvider() ?: return
        runCatching { api.deleteFiles(token, listOf(dirFid)) }
    }

    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = runCatching {
        try {
            api.getShareDownloadUrl(file, session.stoken)
                ?: throw IllegalStateException("光鸭未返回有效下载地址，请确认下载权限")
        } catch (e: GuangYaShareRestrictedException) {
            throw IllegalStateException("该光鸭分享需登录并转存后下载，请先登录光鸭云盘")
        }
    }
}
