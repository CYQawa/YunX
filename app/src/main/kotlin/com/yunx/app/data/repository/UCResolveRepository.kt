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

import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * UC 分享解析仓库：token → 列表 → 转存临时目录 → 下载直链。
 */
class UCResolveRepository(private val api: UCApi) : ShareResolveRepository {

    /** 常见视频扩展名（分享视频走 play 转码流绕过会员墙；play 需个人云盘 fid，先转存临时目录） */
    private val videoExts = setOf("mp4", "mkv", "mov", "avi", "webm", "flv", "ts", "m3u8", "wmv", "rmvb")

    private fun isVideo(name: String): Boolean =
        videoExts.contains(name.substringAfterLast('.', "").lowercase())

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val parsed = ShareLinkParser.parse(link)
            ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
        val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd
        return runCatching {
            val token = api.getShareToken(parsed.shareId, effectivePwd, cookie)
                ?: throw IllegalStateException("未获取到分享凭证")
            ShareSession(parsed.shareId, token.stoken, token.title)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            // 必须用 transfer_share/detail（带 stoken），返回的 share_fid_token 与 stoken 绑定
            val all = mutableListOf<ShareFile>()
            var page = 1
            do {
                val batch = api.getTransferShareFiles(session.shareId, session.stoken, dirFid, cookie, page, 50)
                    ?: throw IllegalStateException("未获取到文件列表")
                all += batch
                page++
            } while (batch.size == 50 && page <= 100)
            all
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        val rootFiles = api.getFileList(UCConstants.DEFAULT_PDIR_FID, cookie)
            ?: throw IllegalStateException("获取网盘目录失败")
        rootFiles.firstOrNull { it.isdir && it.fname == UCConstants.TEMP_DIR_NAME }?.fid
            ?: api.createFolder(UCConstants.TEMP_DIR_NAME, UCConstants.DEFAULT_PDIR_FID, cookie)
            ?: throw IllegalStateException("创建临时目录失败")
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
        val taskId = api.saveShareFile(
            shareId = session.shareId,
            stoken = session.stoken,
            pdirFid = file.pdirFid,
            fid = file.fid,
            fidToken = file.fidToken,
            toPdirFid = toDirFid,
            cookie = cookie
        ) ?: throw IllegalStateException("转存失败")
        api.pollTask(taskId, cookie)
            ?: throw IllegalStateException("转存超时，请稍后重试")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        api.getDownloadLink(fid, cookie)
            ?: throw IllegalStateException("获取下载链接失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /**
     * UC 官方下载流程：无需转存！
     * 直接用分享 fid + fid_token + stoken + pwd_id 调 download 接口取直链。
     */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        // 视频：优先用分享态 video_preview 取**原画**直链（走播放回调 checkplay，不换片，绕过宣传片替换）
        if (isVideo(file.fname)) {
            val preview = api.getVideoPreview(
                pwdId = session.shareId,
                stoken = session.stoken,
                fid = file.fid,
                fidToken = file.fidToken,
                cookie = cookie
            )
            if (preview != null) {
                return@runCatching DownloadLink(
                    fid = file.fid,
                    filename = file.fname,
                    downloadUrl = preview.downloadUrl,
                    size = preview.size,
                    isHls = false
                )
            }
        }
        api.getShareDownloadLink(
            fid = file.fid,
            fidToken = file.fidToken,
            stoken = session.stoken,
            pwdId = session.shareId,
            cookie = cookie
        ) ?: throw IllegalStateException("获取下载链接失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )
}
