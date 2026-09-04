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

import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 迅雷分享解析仓库：解析分享 → 转存到临时目录 → 文件详情取直链。
 * 认证用 access_token（经 xunleiAccount 提供），无需转存密码（pass_code 由分享提供）。
 */
class XunleiResolveRepository(
    private val api: XunleiApi,
    private val accountProvider: suspend () -> String?,
    private val deviceIdProvider: suspend () -> String?,
    private val captchaProvider: suspend () -> String?,
    /** token 过期自动刷新回调：返回新 (access_token, refresh_token) 并持久化；失败返回 null */
    private val refreshProvider: (suspend () -> Pair<String, String>?)? = null
) : ShareResolveRepository {

    /** shareId → 提取码（转存时仍需携带） */
    private val passCodes = mutableMapOf<String, String>()

    private suspend fun token(): String =
        accountProvider() ?: throw IllegalStateException("请先登录迅雷网盘")

    /** 取 access_token 并缓存 user_id（captcha/init 需要，空 user_id 会得到降级 token） */
    private suspend fun access(): String {
        ensureFreshToken()   // token 过期则先自动刷新
        val t = token()
        api.cacheUserId(t)
        return t
    }

    /** token 即将过期（<60s）或已过期时，用 refresh_token 自动刷新（导入恢复后旧 token 过期也适用） */
    private suspend fun ensureFreshToken() {
        val acc = accountProvider() ?: return
        val exp = api.jwtExp(acc)
        if (exp > 0 && exp - System.currentTimeMillis() / 1000 > 60) return
        // refreshProvider 内部负责读取 refreshToken、刷新并持久化新 token
        // 刷新失败（refresh_token 被轮换/过期）→ 抛明确错误引导重新登录，而不是继续发无效请求
        if (refreshProvider?.invoke() == null) {
            throw IllegalStateException("迅雷登录已过期，请重新登录")
        }
    }

    private suspend fun deviceId(): String =
        deviceIdProvider() ?: throw IllegalStateException("缺少设备标识")

    private suspend fun captcha(): String = captchaProvider() ?: ""

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val shareId = ShareLinkParser.parse(link)?.shareId
                ?: throw IllegalArgumentException("无法识别迅雷分享链接")
            val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: ShareLinkParser.parse(link)?.pwd ?: ""
            passCodes[shareId] = effectivePwd
            val access = access()
            val result = api.getShare(shareId, effectivePwd, access, deviceId(), captcha())
                ?: throw IllegalStateException("未获取到分享信息")
            ShareSession(shareId, result.passCodeToken, result.title)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val access = access()
            // 迅雷分享：顶层用 share（带提取码）；子目录用 share/detail（parent_id + pass_code_token）
            val files = mutableListOf<ShareFile>()
            var pageToken = ""
            var pages = 0
            do {
                val next = if (dirFid.isBlank() || dirFid == "0") {
                    val page = api.getShare(
                        session.shareId, passCodes[session.shareId] ?: "", access,
                        deviceId(), captcha(), pageToken
                    ) ?: throw IllegalStateException("未获取到文件列表")
                    files += page.files
                    page.nextPageToken
                } else {
                    val page = api.getShareDetail(
                        session.shareId, dirFid, session.stoken, access,
                        deviceId(), captcha(), pageToken
                    ) ?: throw IllegalStateException("未获取到文件列表")
                    files += page.files
                    page.nextPageToken
                }
                pageToken = next
                pages++
            } while (pageToken.isNotBlank() && pages < 100)
            files
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        api.ensureTempDir(access(), deviceId(), captcha())
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
        // 官方同步转存：restore 返回 trace_file_ids 映射，直接得到转存后的新文件 id（无需轮询）
        val newId = api.restore(
            shareId = session.shareId,
            passCodeToken = session.stoken,
            parentFolderId = toDirFid,
            fileIds = listOf(file.fid),
            accessToken = access(),
            deviceId = deviceId(),
            captchaToken = captcha()
        ) ?: throw IllegalStateException("转存失败")
        newId
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        api.getFileDetail(fid, access(), deviceId(), captcha())
            ?: throw IllegalStateException("获取下载链接失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 迅雷取直链：转存 → 取详情直链 → 删除临时转存文件（直链自带签名，删除不影响下载） */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val dirFid = ensureTempDir(cookie).getOrThrow()
        val savedFid = transferFile(session, file, dirFid, cookie).getOrThrow()
        val link = api.getFileDetail(savedFid, access(), deviceId(), captcha())
            ?: throw IllegalStateException("获取下载链接失败")
        // 拿到直链后立即删除临时转存的文件（对齐官方 batchDelete；失败不阻断下载）
        runCatching { api.batchDelete(listOf(savedFid), access(), deviceId(), captcha()) }
        link
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )
}
