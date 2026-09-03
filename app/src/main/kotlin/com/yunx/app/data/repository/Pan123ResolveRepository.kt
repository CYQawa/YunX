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

import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 123 云盘分享解析仓库（依据《123网盘API文档_面向Agent.md》§4.2）：
 * - createSession：GET /b/api/share/get（匿名，带 SharePwd）校验提取码 + 取标题；
 * - listFiles：GET /b/api/share/get 翻页（Next=="-1" 末页；空串表示还有下一页）；
 * - getShareDownloadLink：POST /b/api/share/download/info（需登录 token + 签名）→ 解码 DownloadURL；
 * - 123 分享下载**无需转存**（类似 UC）：transferFile / ensureTempDir / cleanupTempDir 空实现。
 * @param tokenProvider 当前登录 token（ResolveViewModel 传 accessToken）
 */
class Pan123ResolveRepository(
    private val api: Pan123Api,
    private val tokenProvider: suspend () -> String?
) : ShareResolveRepository {

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val parsed = ShareLinkParser.parse(link)
            ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
        return runCatching {
            // 提取码优先级：用户手输 > 链接/文案自带
            val sharePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd.orEmpty()
            // 用分享根目录列表校验提取码 + 取标题（标题用首个目录名/ShareKey 占位，文档待验证 #4）
            val (files, _) = api.getShareFiles(parsed.shareId, sharePwd, "0", "0", 1)
            val title = files.firstOrNull()?.fname?.takeIf { it.isNotBlank() } ?: parsed.shareId
            ShareSession(shareId = parsed.shareId, stoken = sharePwd, title = title)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            // alist 实证（drivers/123_share/util.go）：next 参数始终固定 "0"，翻页靠 Page 递增；
            // 结束条件：Next=="-1" 或列表为空（Next=="" 表示还有，继续翻页）
            val all = mutableListOf<ShareFile>()
            var page = 1
            do {
                val (files, nextCursor) = api.getShareFiles(session.shareId, session.stoken, dirFid, "0", page)
                all += files
                val hasMore = files.isNotEmpty() && nextCursor != null
                page++
            } while (hasMore && page < 50)
            all
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    /** 123 分享下载无需转存（文档 §4.2） */
    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("123 分享无需转存"))

    /**
     * 保存他人分享到个人网盘（copy/save，文档 §4.3）：mshare 子域无需签名，仅 Bearer+LoginUuid；
     * 异步任务 → 轮询 copy/save/get 拿转存后的新 fileId。
     * @param toDirFid 转存目标目录 ID（个人盘 fileId；0/空 = 根目录）
     */
    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val token = cookie.ifBlank { tokenProvider() ?: "" }
        if (token.isBlank()) throw IllegalStateException("请先登录123云盘")
        val (taskId, shareId) = api.copySave(
            shareKey = session.shareId,
            sharePwd = session.stoken,
            file = file,
            toDirFid = toDirFid.ifBlank { "0" },
            token = token
        ) ?: throw IllegalStateException("创建转存任务失败")
        api.pollCopySave(taskId, shareId, token)
            ?: throw IllegalStateException("转存超时或失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("123 分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        // cookie 参数即登录 token（ResolveViewModel.currentCredential 返回 accessToken）
        val token = cookie.ifBlank { tokenProvider() ?: "" }
        if (token.isBlank()) throw IllegalStateException("请先登录123云盘")
        val link = api.getShareDownloadLink(session.shareId, file, token)
            ?: throw IllegalStateException("获取下载链接失败")
        link.copy(filename = file.fname.ifBlank { link.filename })
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )
}