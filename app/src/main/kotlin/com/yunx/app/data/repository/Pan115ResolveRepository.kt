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

import com.yunx.app.data.network.Pan115Api
import com.yunx.app.data.network.Pan115Constants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import kotlinx.coroutines.delay

/**
 * 115 网盘分享解析仓库：`share/snap` 列目录 → `share/downurl` 取直链（文档 §3、§5）。
 *
 * 两个与其它平台不同的点：
 * - 直链必须带**分享者** `user_id`（不是自己的 UID），它只出现在 snap 的 `userinfo` 里，
 *   所以 createSession 先探一次分享根目录，把「提取码|分享者UID」塞进 `stoken` 带走；
 * - `share/receive` 转存**不回传新文件 id**，转存后要重新列目标目录按文件名认领。
 *
 * 列目录与取直链都允许在未登录下调用（alist 侧同样结论）；转存要求登录 Cookie，
 * 缺失时由 `transferFile` 明确报错（见 Agent.md §3.25）。
 */
class Pan115ResolveRepository(private val api: Pan115Api) : ShareResolveRepository {

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val parsed = ShareLinkParser.parse(link)
            ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
        return runCatching {
            // 提取码优先级：用户手输 > 链接 ?password= 或文案「访问码：xxxx」
            val receiveCode = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd.orEmpty()
            // 先探分享根目录：既校验提取码（4100012/4100008 由 Api 转成中文提示），
            // 又拿到分享者 UID 与分享标题（取直链的 user_id 只能是分享者，见文档 §5）
            val page = api.getSharePage(
                shareCode = parsed.shareId,
                receiveCode = receiveCode,
                cid = Pan115Constants.ROOT_CID,
                cookie = cookie
            )
            ShareSession(
                shareId = parsed.shareId,
                // ShareSession 只有 shareId/stoken/title 三个字段，分享者 UID 只能随 stoken 带走
                stoken = Pan115Constants.encodeShareToken(receiveCode, page.userId),
                title = page.title.takeIf { it.isNotBlank() } ?: parsed.shareId
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        cookie: String
    ): Result<List<ShareFile>> = runCatching {
        val (receiveCode, _) = Pan115Constants.decodeShareToken(session.stoken)
        // 分享里的目录 ID 就是 cid（文件夹项没有 fid），根目录固定 "0"
        val cid = dirFid.ifBlank { Pan115Constants.ROOT_CID }
        val all = mutableListOf<ShareFile>()
        var offset = 0
        while (true) {
            val page = api.getSharePage(
                shareCode = session.shareId,
                receiveCode = receiveCode,
                cid = cid,
                cookie = cookie,
                offset = offset,
                limit = Pan115Constants.SHARE_PAGE_LIMIT
            )
            all += page.files
            offset += page.files.size
            // 115 的 offset 是索引分页：空页 / 不足一页 / 已取满 count 都说明到底了
            val done = page.files.isEmpty() ||
                page.files.size < Pan115Constants.SHARE_PAGE_LIMIT ||
                (page.total > 0 && all.size >= page.total)
            if (done) break
        }
        all
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 115 分享无需转存（直链接口直接对分享文件下发 CDN 地址），保留失败实现避免误用 */
    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("115 分享无需转存"))

    /**
     * 转存到自己的网盘：`POST /share/receive`。
     * 接口只回接收数量、不回新文件 id（文档 §6），所以转存后按文件名在目标目录里认领新 fid；
     * 目标目录已有同名文件时 115 会命名为 `xxx(1).txt`，因此前缀+后缀一起匹配。
     */
    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        if (!Pan115Constants.hasLoginCookie(cookie)) {
            throw IllegalStateException("请先登录115网盘")
        }
        val (receiveCode, _) = Pan115Constants.decodeShareToken(session.stoken)
        val targetCid = toDirFid.ifBlank { Pan115Constants.ROOT_CID }
        api.receiveShare(session.shareId, receiveCode, file.fid, targetCid, cookie)
        waitNewFid(targetCid, file.fname, cookie)
            ?: throw IllegalStateException("转存已完成，但未在目标目录找到文件，请到115网盘查看")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 转存后列表有延迟，最多重试 5 次、每次间隔 500ms，按文件名找回新 fid */
    private suspend fun waitNewFid(cid: String, name: String, cookie: String): String? {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        repeat(5) { index ->
            if (index > 0) delay(500)
            val page = runCatching {
                api.listFiles(cid, cookie, offset = 0, limit = Pan115Constants.PAGE_LIMIT)
            }.getOrNull() ?: return@repeat
            val hit = page.files.firstOrNull { item -> item.fname.matchesName(name, base, ext) }
            if (hit != null) return hit.fid
        }
        return null
    }

    /** 文件名是否为「原名」或重名后的「原名(序号).后缀」 */
    private fun String.matchesName(name: String, base: String, ext: String): Boolean {
        if (this == name) return true
        if (!startsWith("$base(")) return false
        return if (ext.isEmpty()) endsWith(")") else endsWith(".$ext")
    }

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("115 分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val (receiveCode, userId) = Pan115Constants.decodeShareToken(session.stoken)
        val link = api.getShareDownloadLink(
            shareCode = session.shareId,
            receiveCode = receiveCode,
            userId = userId,
            file = file,
            cookie = cookie
        ) ?: throw IllegalStateException("获取下载链接失败")
        // 文件名以列表为准（downurl 的 fn 偶发为空，会 fallback 成 file_id）
        link.copy(filename = file.fname.ifBlank { link.filename })
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )
}
