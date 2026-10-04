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
 * **列目录允许游客**：未登录时 token 传空串，请求不带 Authorization（分享接口匿名可用）；
 * 取直链/转存仍需登录（[ensureTempDir]/[transferFile]/[getDownloadLink] 会抛「请先登录迅雷网盘」）。
 * 详见 Agent.md §3.19。
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

    /** 游客模式自造的设备标识（迅雷请求需要 X-Device-Id；未登录时进程内复用，不落库） */
    private val guestDeviceId: String by lazy { XunleiApi.newDeviceId() }

    private suspend fun token(): String =
        accountProvider() ?: throw IllegalStateException("请先登录迅雷网盘")

    /**
     * 列表用 token：未登录返回空串 ⇒ 走**匿名分享接口**（带 Authorization 反而被判 unauthenticated）。
     * 登录态仍走 [access]（含 token 过期自动刷新）。详见 Agent.md §3.19。
     */
    private suspend fun accessOrEmpty(): String = if (accountProvider() == null) "" else access()

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

    /** 设备标识：未登录时回退游客设备标识，别让「缺少设备标识」把匿名列目录挡在门外 */
    private suspend fun deviceIdOrGuest(): String = deviceIdProvider() ?: guestDeviceId

    private suspend fun captcha(): String = captchaProvider() ?: ""

    /**
     * 迅雷中文口令（如「张三丰资源」）→ 带提取码的分享链接（`https://pan.xunlei.com/s/xxx?pwd=xxxx`）。
     * 免登录：shoulei 跳转接口不校验账号；拿到链接后按普通分享链接交给 [createSession]。
     */
    suspend fun resolveKouling(keyword: String): Result<String> =
        runCatching { api.parseKouling(keyword) }

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val shareId = ShareLinkParser.parse(link)?.shareId
                ?: throw IllegalArgumentException("无法识别迅雷分享链接")
            val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: ShareLinkParser.parse(link)?.pwd ?: ""
            passCodes[shareId] = effectivePwd
            // 游客模式：未登录时 token 为空 → 匿名请求（无 Authorization 头）
            val access = accessOrEmpty()
            val result = api.getShare(shareId, effectivePwd, access, deviceIdOrGuest(), captcha())
                ?: throw IllegalStateException("未获取到分享信息")
            ShareSession(shareId, result.passCodeToken, result.title)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            // 游客模式同样放行：无 token 时匿名列目录（下载/转存仍要求登录）
            val access = accessOrEmpty()
            // 迅雷分享：顶层用 share（带提取码）；子目录用 share/detail（parent_id + pass_code_token）
            val files = mutableListOf<ShareFile>()
            var pageToken = ""
            var pages = 0
            do {
                val next = if (dirFid.isBlank() || dirFid == "0") {
                    val page = api.getShare(
                        session.shareId, passCodes[session.shareId] ?: "", access,
                        deviceIdOrGuest(), captcha(), pageToken
                    ) ?: throw IllegalStateException("未获取到文件列表")
                    files += page.files
                    page.nextPageToken
                } else {
                    val page = api.getShareDetail(
                        session.shareId, dirFid, session.stoken, access,
                        deviceIdOrGuest(), captcha(), pageToken
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
