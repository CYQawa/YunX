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

import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 139（和彩云）分享解析仓库：cookie → getOutLinkInfoV6 列目录 → getContentInfoFromOutLink 直链。
 * 139 分享无需转存（share host 直接列目录 + 取直链），credential 为登录 Cookie（含账号信息）；
 * authorization 从 cookie 提取，分享接口按需携带（可空）。
 */
class C139ResolveRepository(private val api: C139Api) : ShareResolveRepository {

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val parsed = ShareLinkParser.parse(link)
            ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
        if (C139Constants.extractAccountFull(cookie).isNullOrBlank()) {
            return Result.failure(IllegalStateException("登录态缺少账号信息，请重新登录"))
        }
        return runCatching {
            // 139 分享无 token：shareId 即 linkID，stoken 暂存提取码
            // 密码优先级：用户手输 > 139 getOutLinkGeneral 明文回吐的 passwd（避免下载因缺密码报 9188）
            val leakedPwd = api.getOutLinkPassword(parsed.shareId)
            val passwd = pwd?.takeIf { it.isNotBlank() } ?: leakedPwd.orEmpty()
            // 标题从 getOutLinkGeneral 拿（失败回退短链 ID）
            val title = api.getOutLinkTitle(parsed.shareId)
                ?.takeIf { it.isNotBlank() } ?: parsed.shareId
            ShareSession(parsed.shareId, passwd, title)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            // 列表端点为匿名调用（§9530修复文档 §3）：无需 authorization/account，Api 内部走匿名请求
            // pCaID 根目录必须传 "root"（§16.2：空串会报 pCaID不能为空），子目录传父 caID / coID
            val pcaId = if (dirFid == "0" || dirFid.isBlank()) "root" else dirFid
            // passwd = 分享提取码（createSession 时存入 stoken；无则空串）
            val all = mutableListOf<ShareFile>()
            var begin = 1
            do {
                val batch = api.getShareFiles(session.shareId, pcaId, session.stoken, begin, begin + 199)
                all += batch
                begin += 200
            } while (batch.size == 200 && begin <= 20_000)
            all
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    /** 139 分享不需要转存/个人网盘直链，保留空实现避免误用 */
    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("139 分享无需转存"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val account = C139Constants.extractAccountFull(cookie)
            ?: throw IllegalStateException("登录态缺少账号信息，请重新登录")
        val authorization = C139Constants.extractAuthorization(cookie)
        // 139 转存：创建批量任务（AES 加密接口）→ 轮询查询结果 → 返回转存后新 fileId
        val taskId = api.createTransferTask(
            coIDLst = listOf(file.fid),
            catalogIDLst = emptyList(),
            toFolderId = toDirFid,
            linkID = session.shareId,
            account = account,
            authorization = authorization
        ) ?: throw IllegalStateException("创建转存任务失败")
        var newId: String? = null
        for (i in 0 until 30) {
            kotlinx.coroutines.delay(800)
            val result = api.queryTransferTask(taskId, account, authorization)
            if (result.done) {
                newId = result.mapping[file.fid]
                break
            }
        }
        newId ?: throw IllegalStateException("转存超时或失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("139 分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val account = C139Constants.extractAccountFull(cookie)
            ?: throw IllegalStateException("登录态缺少账号信息，请重新登录")
        val authorization = C139Constants.extractAuthorization(cookie)
        val link = api.getShareDownloadLink(file.fid, session.shareId, account, authorization)
            ?: throw IllegalStateException("获取下载链接失败")
        // 文件名用列表里的 coName（dlFromOutLinkV3 响应不含文件名，否则会 fallback 成 coID 乱码）
        link.copy(filename = file.fname.ifBlank { link.filename })
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )
}
