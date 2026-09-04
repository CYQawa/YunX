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

import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 百度分享解析仓库：verify 拿 sekey → xpan/share 列文件 → 转存临时目录（自动创建，失败回退根目录）→ locatedownload 拿 appall 高速链 → 立即删除临时转存。
 * 全部基于抓包链路（share/verify → xpan/share list → share/transfer → locatedownload）。
 * appall 直链 URL 自带签名、删除转存后仍有效，故取链成功后立即删除临时转存（失败不阻断）。
 */
class BaiduResolveRepository(private val api: BaiduApi) : ShareResolveRepository {

    /** surl -> sekey（verify 返回的 randsk） */
    private val sekeys = mutableMapOf<String, String>()

    /** surl -> (share_id, uk)，由列表接口返回（转存必需） */
    private val shareInfos = mutableMapOf<String, Pair<String, String>>()

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val parsed = ShareLinkParser.parse(link)
                ?: throw IllegalArgumentException("无法识别百度分享链接")
            val surl = parsed.shareId
            // 修复：公共分享（pwd 为空）不强制提取码——跳过 verify，sekey 置空，
            // listShare 将不带 sekey 直接列出（抓包实证：公共分享无需 sekey/Cookie 即 errno=0）
            val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd
            val sekey = if (effectivePwd.isNullOrBlank()) {
                ""
            } else {
                api.verifyShare(surl, effectivePwd, cookie)
            }
            sekeys[surl] = sekey
            ShareSession(surl, sekey, "")
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val sekey = session.stoken.ifBlank { sekeys[session.shareId] ?: "" }
            // 顶层 dirFid 为空/"/"；子目录 dirFid 为目录 path（如 /folder）
            val all = mutableListOf<ShareFile>()
            var page = 1
            var result: com.yunx.app.data.network.BaiduShareList
            do {
                result = api.listShare(session.shareId, sekey, dirFid, cookie, page)
                all += result.files
                page++
            } while (result.files.size == 100 && page <= 100)
            // 缓存 share_id/uk（转存需要）
            shareInfos[session.shareId] = result.shareId to result.uk
            all
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        val dir = "/${BaiduConstants.TEMP_DIR_NAME}"
        // 已存在则直接复用；不存在则创建（web UA 已修正）；创建失败回退根目录（鲁棒性）
        val exists = runCatching { api.listDir("/", cookie).any { it == dir } }.getOrDefault(false)
        val ok = exists || api.createDir(dir, cookie)
        if (ok) dir else "/"
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val (shareId, uk) = requireShareInfo(session, cookie)
        val result = api.transfer(shareId, uk, session.stoken, file.fid, toDirFid, cookie)
        result.fsId
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 个人网盘文件直链（filemetas） */
    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        val dlink = api.fileMetasDlink(fid, cookie)
        DownloadLink(fid = fid, filename = "", downloadUrl = dlink, size = 0L)
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 百度取直链：转存临时目录（自动创建，失败回退根目录）→ locatedownload 拿 appall 高速链 → 立即删除临时转存（失败不阻断） */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val (shareId, uk) = requireShareInfo(session, cookie)
        val dirPath = ensureTempDir(cookie).getOrThrow()
        val transferred = api.transfer(shareId, uk, session.stoken, file.fid, dirPath, cookie)
        // locatedownload 按转存后的完整路径取链，返回 appallNN.baidupcs.com CDN 直链
        // （自带 sign/expires，删除转存后仍有效；仅需 BDUSS + 手机 UA 即可满速下载）
        val dlink = api.locateDownload(transferred.path, cookie)
        // appall 直链不依赖转存文件存活：取链成功后立即删除临时转存，网盘不留残留
        deleteTransferred(transferred.path, cookie)
        DownloadLink(
            fid = transferred.fsId,
            filename = file.fname,
            downloadUrl = dlink,
            size = file.fsize
        )
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 删除转存文件（失败不阻断）；转存在临时目录时，删完文件后尝试删空目录 */
    private suspend fun deleteTransferred(path: String, cookie: String) {
        runCatching { api.deleteFile(path, cookie) }
        val tempDir = "/${BaiduConstants.TEMP_DIR_NAME}"
        if (path.startsWith("$tempDir/")) {
            runCatching { api.deleteFile(tempDir, cookie) }
        }
    }

    /** 取 share_id/uk：优先用列表接口缓存的，否则先列一次根目录 */
    private suspend fun requireShareInfo(session: ShareSession, cookie: String): Pair<String, String> {
        shareInfos[session.shareId]?.let { return it }
        val sekey = session.stoken.ifBlank { sekeys[session.shareId] ?: "" }
        val result = api.listShare(session.shareId, sekey, "/", cookie)
        val info = result.shareId to result.uk
        shareInfos[session.shareId] = info
        return info
    }
}
