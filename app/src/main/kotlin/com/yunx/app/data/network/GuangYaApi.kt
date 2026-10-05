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

package com.yunx.app.data.network

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.model.ShareExpire
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** 设备标识（deviceId 32 位十六进制 + deviceSign）。 */
data class GuangYaDevice(val deviceId: String, val deviceSign: String)

/** 刷新 / 登录成功返回的令牌对。 */
data class GuangYaTokens(val accessToken: String, val refreshToken: String, val expiresIn: Long = 0L)

/** 账号信息。 */
data class GuangYaAccountInfo(val userId: String, val nickname: String)

/** 短信验证码挑战（文档 §2.6）。 */
data class GuangYaSmsChallenge(
    val verificationId: String,
    val isUser: Boolean,
    val expiresIn: Int
)

/**
 * 分享下载受限（业务码 207，文档 §4.4）：分享者未开启免登录下载，
 * 已登录用户需「转存到临时目录 → 从个人盘取链」，未登录则要求先登录。
 */
class GuangYaShareRestrictedException :
    Exception("该光鸭分享需登录并转存后下载，请先登录光鸭云盘")

/**
 * 光鸭云盘 API 封装（文档 §1-§7）。
 * 业务 API 带 did/dt/traceparent 头；账号 API 带一整套 X-Device-* 头；Authorization 仅在有令牌时携带。
 */
class GuangYaApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    private val client get() = clientProvider()
    private val jsonMediaType = "application/json;charset=UTF-8".toMediaType()

    /**
     * 业务 API 的 `did` 设备头提供者（文档 §1.3：分享接口与个人盘接口都要求带 did/dt/traceparent）。
     * 由上层从 GuangYaAccountRepository 的 deviceId 缓存注入；未登录时返回 null（此时不带 did）。
     */
    var deviceIdProvider: () -> String? = { null }

    /** 未登录（匿名分享）时业务请求兜底使用的设备 id（文档 §1.3：业务 API 始终需要 did）。 */
    private val publicDeviceId: String = GuangYaConstants.newDeviceId()

    // ---------- 认证（文档 §2） ----------

    /** 人机验证初始化结果：token 可能为空（部分场景服务端不要求）；url 非空表示需要网页验证（文档 §2.4）。 */
    private data class CaptchaInit(val token: String?, val url: String?)

    /** 初始化人机验证（action 见文档 §2.4：登录用 POST:/v1/auth/signin，发码用 POST:/v1/auth/verification）。 */
    private suspend fun initCaptcha(device: GuangYaDevice, meta: JSONObject, action: String): CaptchaInit =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("client_id", GuangYaConstants.CLIENT_ID)
                .put("action", action)
                .put("device_id", device.deviceId)
                .put("captcha_token", "")
                .put("meta", meta)
            val json = executeJson(
                accountRequest(GuangYaConstants.CAPTCHA_INIT, body.toString(), device).build()
            )
            CaptchaInit(
                token = json.optString("captcha_token").takeIf { it.isNotBlank() },
                url = json.optString("url").takeIf { it.isNotBlank() }
            )
        }

    /**
     * 密码登录（文档 §2.5）：先初始化人机验证再 signin；仅在 captcha_required / captcha_invalid 时重试（最多 2 次）。
     */
    suspend fun signIn(username: String, password: String, device: GuangYaDevice): GuangYaTokens =
        withContext(Dispatchers.IO) {
            val normalized = normalizeAccount(username)
            val meta = metaFor(normalized)
            var captcha = initCaptcha(device, meta, "POST:/v1/auth/signin")
            var attempt = 0
            var tokens: GuangYaTokens? = null
            while (tokens == null) {
                try {
                    tokens = doSignIn(normalized, password, captcha.token, device)
                } catch (e: CaptchaException) {
                    if (attempt >= 2) throw IllegalStateException(
                        "光鸭安全验证未通过，请重试或切换网页登录"
                    )
                    attempt++
                    captcha = initCaptcha(device, meta, "POST:/v1/auth/signin")
                }
            }
            tokens!!
        }

    private fun doSignIn(
        username: String,
        password: String,
        captchaToken: String?,
        device: GuangYaDevice
    ): GuangYaTokens {
        val body = JSONObject()
            .put("client_id", GuangYaConstants.CLIENT_ID)
            .put("username", username)
            .put("password", password)
        val builder = accountRequest(GuangYaConstants.SIGNIN, body.toString(), device)
        if (!captchaToken.isNullOrBlank()) builder.header("X-Captcha-Token", captchaToken)
        val json = executeJson(builder.build())
        val code = json.optString("code")
        val error = json.optString("error")
        if (code == "captcha_required" || error == "captcha_required" ||
            code == "captcha_invalid" || error == "captcha_invalid"
        ) {
            throw CaptchaException()
        }
        if (!isSuccess(json)) throw IllegalStateException(mapSigninError(json, error))
        return parseTokens(json)
    }

    /** 刷新 Token（文档 §2.1）。凭据失效返回 null。 */
    suspend fun refreshToken(refreshToken: String, device: GuangYaDevice): GuangYaTokens? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("client_id", GuangYaConstants.CLIENT_ID)
                .put("grant_type", "refresh_token")
                .put("refresh_token", refreshToken)
            val json = executeJson(
                accountRequest(GuangYaConstants.AUTH_TOKEN, body.toString(), device)
                    .header("X-Action", "401")
                    .build()
            )
            if (!isSuccess(json)) null else parseTokens(json)
        }

    // ---------- 短信验证码登录 / 注册（文档 §2.6-§2.8） ----------

    /**
     * 发送短信验证码（文档 §2.6）：返回 verification_id / is_user / expires_in。
     * 发码接口要求人机验证令牌 —— 先按 action=POST:/v1/auth/verification 初始化人机验证，
     * 再把 captcha_token 作为 X-Captcha-Token 请求头发送；被判定需要/令牌无效时重试（最多 2 次）。
     */
    suspend fun sendSmsCode(phoneNumber: String, device: GuangYaDevice): GuangYaSmsChallenge =
        withContext(Dispatchers.IO) {
            val meta = JSONObject().put("phone_number", phoneNumber)
            var captcha = initCaptcha(device, meta, "POST:/v1/auth/verification")
            // 初始化返回 url 表示必须走网页人机验证：应用内无法完成，直接给出可执行提示
            if (!captcha.url.isNullOrBlank()) {
                throw IllegalStateException("光鸭需要网页人机验证，请稍后重试或改用账号密码登录")
            }
            var attempt = 0
            var challenge: GuangYaSmsChallenge? = null
            while (challenge == null) {
                try {
                    challenge = doSendSmsCode(phoneNumber, captcha.token, device)
                } catch (e: CaptchaException) {
                    if (attempt >= 2) throw IllegalStateException("光鸭安全验证未通过，请重试或改用账号密码登录")
                    attempt++
                    captcha = initCaptcha(device, meta, "POST:/v1/auth/verification")
                    if (!captcha.url.isNullOrBlank()) {
                        throw IllegalStateException("光鸭需要网页人机验证，请稍后重试或改用账号密码登录")
                    }
                }
            }
            challenge!!
        }

    private fun doSendSmsCode(phoneNumber: String, captchaToken: String?, device: GuangYaDevice): GuangYaSmsChallenge {
        val body = JSONObject()
            .put("client_id", GuangYaConstants.CLIENT_ID)
            .put("phone_number", phoneNumber)
            .put("target", "ANY")
        val builder = accountRequest(GuangYaConstants.VERIFICATION, body.toString(), device)
        // 始终带上 X-Captcha-Token（即使为空串）：服务端以该头存在与否判定"no captcha"
        builder.header("X-Captcha-Token", captchaToken.orEmpty())
        val json = executeJson(builder.build())
        val code = json.optString("code")
        val error = json.optString("error")
        if (code == "captcha_required" || error == "captcha_required" ||
            code == "captcha_invalid" || error == "captcha_invalid"
        ) {
            throw CaptchaException()
        }
        ensureAccountSuccess(json, "发送验证码失败")
        val verificationId = accountString(json, "verification_id")
        if (verificationId.isBlank()) throw IllegalStateException("验证码请求过于频繁，请稍后重试")
        return GuangYaSmsChallenge(
            verificationId = verificationId,
            isUser = accountBoolean(json, "is_user"),
            expiresIn = accountInt(json, "expires_in", 600).coerceIn(1, 1800)
        )
    }

    /** 校验短信验证码（文档 §2.7）：验证码格式 ^[0-9]{4,8}$，返回 verification_token。 */
    suspend fun verifySmsCode(verificationId: String, code: String, device: GuangYaDevice): String =
        withContext(Dispatchers.IO) {
            if (!Regex("^[0-9]{4,8}$").matches(code)) {
                throw IllegalStateException("短信验证码错误或已过期，请重新获取")
            }
            val body = JSONObject()
                .put("client_id", GuangYaConstants.CLIENT_ID)
                .put("verification_id", verificationId)
                .put("verification_code", code)
            val json = executeJson(
                accountRequest(GuangYaConstants.VERIFICATION_VERIFY, body.toString(), device).build()
            )
            ensureAccountSuccess(json, "短信验证码错误或已过期，请重新获取")
            val token = accountString(json, "verification_token")
            if (token.isBlank()) throw IllegalStateException("短信验证码错误或已过期，请重新获取")
            token
        }

    /** 老用户短信登录（文档 §2.8）。 */
    suspend fun smsSignIn(
        phoneNumber: String,
        code: String,
        verificationToken: String,
        device: GuangYaDevice
    ): GuangYaTokens = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", GuangYaConstants.CLIENT_ID)
            .put("username", phoneNumber)
            .put("verification_code", code)
            .put("verification_token", verificationToken)
        val json = executeJson(accountRequest(GuangYaConstants.SIGNIN, body.toString(), device).build())
        ensureAccountSuccess(json, "短信验证码错误或已过期，请重新获取")
        parseTokens(json)
    }

    /** 新用户短信注册（文档 §2.8）：name 规则 = 前 3 位 + **** + 后 4 位。 */
    suspend fun smsSignUp(
        phoneNumber: String,
        name: String,
        code: String,
        verificationToken: String,
        device: GuangYaDevice
    ): GuangYaTokens = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", GuangYaConstants.CLIENT_ID)
            .put("phone_number", phoneNumber)
            .put("name", name)
            .put("verification_code", code)
            .put("verification_token", verificationToken)
        val json = executeJson(accountRequest(GuangYaConstants.SIGNUP, body.toString(), device).build())
        ensureAccountSuccess(json, "短信验证码错误或已过期，请重新获取")
        parseTokens(json)
    }

    /** 账号信息：GET /v1/user/me（文档 §2.2）。 */
    suspend fun fetchAccountInfo(accessToken: String, device: GuangYaDevice): GuangYaAccountInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = executeJson(accountRequest(GuangYaConstants.USER_ME, null, device, accessToken).get().build())
                if (!isSuccess(json)) return@runCatching null
                val userId = firstNonBlank(json.optString("sub"), json.optString("user_id"), json.optString("userId"))
                val nickname = firstNonBlank(
                    json.optString("nickname"), json.optString("nick_name"),
                    json.optString("name"), json.optString("username")
                )
                if (userId.isBlank()) null else GuangYaAccountInfo(userId, nickname)
            }.getOrNull()
        }

    /** 容量信息（文档 §2.3）：POST /assets/v1/get_assets。 */
    suspend fun getQuota(accessToken: String, device: GuangYaDevice): QuotaInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = executeJson(
                    businessRequest(GuangYaConstants.GET_ASSETS, JSONObject().toString(), device, accessToken).build()
                )
                checkBusiness(json, "获取容量信息失败")
                val data = dataOf(json) ?: return@runCatching null
                val total = data.optString("totalSpaceSize").toLongOrNull()
                    ?: data.optLong("totalSpaceSize")
                if (total < 0) return@runCatching null
                val used = data.optString("usedSpaceSize").toLongOrNull() ?: 0L
                QuotaInfo(used = used, total = total)
            }.getOrNull()
        }

    // ---------- 分享（文档 §4） ----------

    /** 分享摘要标题（文档 §4.1）。 */
    suspend fun getShareSummary(shareId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val json = executeJson(
                businessRequest(
                    GuangYaConstants.SHARE_SUMMARY,
                    JSONObject().put("shareId", shareId).toString(),
                    device = null,
                    token = null
                ).build()
            )
            checkBusiness(json, "获取分享信息失败")
            val data = dataOf(json) ?: json
            firstNonBlank(data.optString("title"), data.optString("shareName"))
                .ifBlank { null }
        }.getOrNull()
    }

    /** 用提取码换取分享访问令牌（文档 §4.2）。code 209 = 提取码错误。 */
    suspend fun getShareAccessToken(shareId: String, code: String): String = withContext(Dispatchers.IO) {
        val json = executeJson(
            businessRequest(
                GuangYaConstants.SHARE_ACCESS_TOKEN,
                JSONObject().put("shareId", shareId).put("code", code.ifBlank { "" }).toString(),
                device = null,
                token = null
            ).build()
        )
        checkBusiness(json, "获取分享访问令牌失败")
        val data = dataOf(json) ?: throw IllegalStateException("光鸭分享链接无效")
        firstNonBlank(data.optString("accessToken"), data.optString("access_token"))
            .ifBlank { throw IllegalStateException("光鸭分享链接无效") }
    }

    /** 分享列目录（文档 §4.3）：返回 (文件列表, 下一页游标 or null=无更多)。 */
    suspend fun listShareFiles(
        accessToken: String,
        parentId: String,
        cursor: String?
    ): Pair<List<ShareFile>, String?> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("parentId", parentId)
            .put("pageSize", GuangYaConstants.PAGE_SIZE)
            .put("orderBy", 0)
            .put("sortType", 0)
            .put("accessToken", accessToken)
            .apply { if (!cursor.isNullOrBlank()) put("cursor", cursor) }
        val json = executeJson(
            businessRequest(GuangYaConstants.SHARE_FILES, body.toString(), device = null, token = null).build()
        )
        checkBusiness(json, "获取分享文件列表失败")
        val data = dataOf(json) ?: return@withContext Pair(emptyList(), null)
        val list = parseFileList(listArray(data))
        val hasMore = data.optBoolean("hasMore", false)
        val next = data.optString("cursor").takeIf { it.isNotBlank() }
        val effectiveNext = if (hasMore && next != null) next else if (!hasMore) null else next
        Pair(list, effectiveNext)
    }

    /** 分享取下载地址（文档 §4.4）；业务码 207 表示需登录并转存。 */
    suspend fun getShareDownloadUrl(file: ShareFile, accessToken: String): DownloadLink? = withContext(Dispatchers.IO) {
        val json = executeJson(
            businessRequest(
                GuangYaConstants.SHARE_DOWNLOAD,
                JSONObject().put("fileId", file.fid).put("accessToken", accessToken).toString(),
                device = null,
                token = null
            ).build()
        )
        if (json.optString("code") == "207") {
            throw GuangYaShareRestrictedException()
        }
        checkBusiness(json, "获取下载地址失败")
        val data = dataOf(json) ?: return@withContext null
        val url = firstNonBlank(
            data.optString("signedURL"), data.optString("signedUrl"), data.optString("downloadUrl"),
            data.optString("download_url"), data.optString("url"), data.optString("cdnUrl")
        )
        if (url.isBlank()) throw IllegalStateException("光鸭未返回有效下载地址，请确认下载权限")
        val size = data.optString("size").toLongOrNull() ?: file.fsize
        DownloadLink(fid = file.fid, filename = file.fname, downloadUrl = url, size = size)
    }

    /**
     * 转存分享（文档 §4.5）：body 的 `accessToken` 是**分享访问令牌**（session.stoken），
     * `Authorization` 用**账号 access token**（参考实现 wenxi `_post(session: session)` 同款）；
     * 返回含 taskId 时用账号 token 轮询任务状态。
     */
    suspend fun restoreShare(
        accountToken: String,
        shareToken: String,
        fileIds: List<String>,
        parentId: String
    ): Unit = withContext(Dispatchers.IO) {
        val ids = JSONArray().apply { fileIds.forEach { put(it) } }
        val body = JSONObject()
            .put("accessToken", shareToken)
            .put("fileIds", ids)
            .put("parentId", parentId)
        val json = executeJson(
            businessRequest(GuangYaConstants.RESTORE_SHARE, body.toString(), null, accountToken).build()
        )
        checkBusiness(json, "转存失败")
        val taskId = dataOf(json)?.optString("taskId").orEmpty()
        if (taskId.isNotBlank()) waitTask(accountToken, taskId)
    }

    /** 创建分享（文档 §4.6）。 */
    suspend fun createShare(
        accessToken: String,
        fileIds: List<String>,
        title: String,
        expireDays: Int?,
        passcode: String?
    ): ShareInfo = withContext(Dispatchers.IO) {
        val ids = JSONArray().apply { fileIds.forEach { put(it) } }
        val code = passcode.orEmpty()
        val body = JSONObject()
            .put("fileIds", ids)
            .put("title", title)
            .put("validateDuration", expireDays ?: 0)
            .put("shareType", if (code.isNotBlank()) 1 else 0)
            .put("code", code)
            .put("autoFillCode", false)
            .put("trafficLimit", "0")
            .put("maxRestoreCount", 0)
            .put("downloadType", 1)
        val json = executeJson(businessRequest(GuangYaConstants.SHARE_FILE, body.toString(), null, accessToken).build())
        checkBusiness(json, "创建分享失败")
        val data = dataOf(json) ?: throw IllegalStateException("创建分享失败")
        val shareUrl = data.optString("shareUrl")
        val shareId = firstNonBlank(data.optString("shareId"), data.optString("id"))
        val url = shareUrl.ifBlank {
            if (shareId.isBlank()) throw IllegalStateException("创建分享失败")
            "${GuangYaConstants.WEB_SITE}/s/$shareId"
        }
        val realCode = data.optString("code").ifBlank { code }
        ShareInfo(shareUrl = url, passcode = realCode, pwdId = shareId, title = title, expiredType = ShareExpire.UNKNOWN)
    }

    // ---------- 个人盘（文档 §5） ----------

    /** 个人盘列目录（文档 §5.1）：page 从 0 开始自动翻页。 */
    suspend fun listCloudFiles(accessToken: String, parentId: String): List<ShareFile> = withContext(Dispatchers.IO) {
        val all = mutableListOf<ShareFile>()
        val seen = mutableSetOf<String>()
        var page = 0
        while (page < 1000) {
            val body = JSONObject()
                .put("parentId", parentId)
                .put("pageSize", GuangYaConstants.PAGE_SIZE)
                .put("orderBy", 0)
                .put("sortType", 0)
                .put("page", page)
            val json = executeJson(businessRequest(GuangYaConstants.FILE_LIST, body.toString(), null, accessToken).build())
            checkBusiness(json, "获取文件列表失败")
            val data = dataOf(json)
            if (data == null) break
            val list = parseFileList(listArray(data))
            if (list.isEmpty()) break
            var added = 0
            list.forEach { if (seen.add(it.fid)) { all.add(it); added++ } }
            if (added == 0) throw IllegalStateException("光鸭文件列表分页重复，请重试")
            val total = data.optString("total").toLongOrNull() ?: data.optLong("total", 0L)
            if (total > 0 && all.size >= total) break
            if (total <= 0 && list.size < GuangYaConstants.PAGE_SIZE) break
            page++
        }
        all
    }

    /** 新建目录（文档 §5.2）。 */
    suspend fun createDir(accessToken: String, parentId: String, name: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("parentId", parentId)
            .put("dirName", name)
            .put("failIfNameExist", true)
        val json = executeJson(businessRequest(GuangYaConstants.CREATE_DIR, body.toString(), null, accessToken).build())
        checkBusiness(json, "新建文件夹失败")
        val data = dataOf(json) ?: throw IllegalStateException("光鸭未创建文件夹，请检查是否存在同名文件")
        val id = firstNonBlank(data.optString("fileId"), data.optString("id"))
        if (id.isBlank() || data.optString("resType") == "1") {
            throw IllegalStateException("光鸭未创建文件夹，请检查是否存在同名文件")
        }
        id
    }

    /** 重命名（文档 §5.3）。 */
    suspend fun renameFile(accessToken: String, fileId: String, newName: String): Unit = withContext(Dispatchers.IO) {
        val json = executeJson(
            businessRequest(
                GuangYaConstants.RENAME,
                JSONObject().put("fileId", fileId).put("newName", newName).toString(),
                null, accessToken
            ).build()
        )
        checkBusiness(json, "重命名失败")
        dataOf(json)?.optString("taskId")?.takeIf { it.isNotBlank() }?.let { waitTask(accessToken, it) }
    }

    /** 移动文件（文档 §5.4）。 */
    suspend fun moveFiles(accessToken: String, fileIds: List<String>, parentId: String): Unit = withContext(Dispatchers.IO) {
        val ids = JSONArray().apply { fileIds.forEach { put(it) } }
        val json = executeJson(
            businessRequest(
                GuangYaConstants.MOVE_FILE,
                JSONObject().put("fileIds", ids).put("parentId", parentId).toString(),
                null, accessToken
            ).build()
        )
        checkBusiness(json, "移动失败")
        dataOf(json)?.optString("taskId")?.takeIf { it.isNotBlank() }?.let { waitTask(accessToken, it) }
    }

    /** 删除文件（文档 §5.5）。 */
    suspend fun deleteFiles(accessToken: String, fileIds: List<String>): Unit = withContext(Dispatchers.IO) {
        val ids = JSONArray().apply { fileIds.forEach { put(it) } }
        val json = executeJson(
            businessRequest(
                GuangYaConstants.DELETE_FILE,
                JSONObject().put("fileIds", ids).toString(),
                null, accessToken
            ).build()
        )
        checkBusiness(json, "删除失败")
        dataOf(json)?.optString("taskId")?.takeIf { it.isNotBlank() }?.let { waitTask(accessToken, it) }
    }

    /** 个人盘取下载地址（文档 §5.6）。 */
    suspend fun getDownloadLink(accessToken: String, file: ShareFile): DownloadLink? = withContext(Dispatchers.IO) {
        val json = executeJson(
            businessRequest(
                GuangYaConstants.RES_DOWNLOAD,
                JSONObject().put("fileId", file.fid).toString(),
                null, accessToken
            ).build()
        )
        checkBusiness(json, "获取下载地址失败")
        val data = dataOf(json) ?: return@withContext null
        val url = firstNonBlank(
            data.optString("signedURL"), data.optString("signedUrl"), data.optString("downloadUrl"),
            data.optString("download_url"), data.optString("url"), data.optString("cdnUrl")
        )
        if (url.isBlank()) throw IllegalStateException("光鸭未返回有效下载地址，请确认下载权限")
        val size = data.optString("size").toLongOrNull() ?: file.fsize
        DownloadLink(fid = file.fid, filename = file.fname, downloadUrl = url, size = size)
    }

    /** 任务状态轮询（文档 §5.7）：status 2 且 detail.code 0 = 成功。 */
    suspend fun waitTask(accessToken: String, taskId: String): Unit = withContext(Dispatchers.IO) {
        delay(GuangYaConstants.TASK_DELAY_MS)
        repeat(GuangYaConstants.TASK_MAX_POLLS) {
            val json = executeJson(
                businessRequest(
                    GuangYaConstants.TASK_STATUS,
                    JSONObject().put("taskId", taskId).toString(),
                    null, accessToken
                ).build()
            )
            checkBusiness(json, "任务状态查询失败")
            val data = dataOf(json) ?: return@repeat
            val status = data.optInt("status", 0)
            val detail = data.optJSONObject("detail")
            val detailCode = detail?.optInt("code", 0) ?: 0
            when {
                status == 2 && detailCode == 0 -> return@withContext
                status == 3 || (status == 2 && detailCode != 0) ->
                    throw IllegalStateException(detail?.optString("msg").orEmpty().ifBlank { "光鸭操作失败" })
            }
            delay(GuangYaConstants.TASK_DELAY_MS)
        }
        throw IllegalStateException("光鸭操作仍在处理中，请稍后刷新列表确认结果")
    }

    // ---------- 内部 ----------

    private class CaptchaException : Exception()

    private fun parseTokens(json: JSONObject): GuangYaTokens {
        val data = json.optJSONObject("data") ?: json
        val access = firstNonBlank(data.optString("access_token"), data.optString("accessToken"))
        if (access.isBlank()) throw IllegalStateException("光鸭登录失败，请检查账号密码，或切换网页登录")
        val refresh = firstNonBlank(data.optString("refresh_token"), data.optString("refreshToken"))
        return GuangYaTokens(access, refresh, expiresInSeconds(data))
    }

    /** 令牌有效期（秒）：优先 expires_in；否则用绝对时间 expire_time/expires_at 反推（毫秒或秒都兼容）。 */
    private fun expiresInSeconds(data: JSONObject): Long {
        val now = System.currentTimeMillis()
        val direct = when (val value = data.opt("expires_in")) {
            is Number -> value.toLong()
            null -> 0L
            else -> value.toString().toLongOrNull() ?: 0L
        }
        if (direct > 0) return direct.coerceAtMost(31_536_000L)
        val absolute = data.opt("expire_time") ?: data.opt("expires_at") ?: return 0L
        val value = when (absolute) {
            is Number -> absolute.toLong()
            else -> absolute.toString().toLongOrNull() ?: return 0L
        }
        if (value <= 0) return 0L
        // > 1e12 视为毫秒绝对时间，否则视为秒绝对时间
        val millis = if (value > 1_000_000_000_000L) value else value * 1000L
        return ((millis - now) / 1000L).coerceIn(0L, 31_536_000L)
    }

    /** 账号 API 的字段可能直接位于顶层，也可能包在 data 节点下（文档各成功响应两种形态都出现过）。 */
    private fun accountNode(json: JSONObject): JSONObject = json.optJSONObject("data") ?: json

    private fun accountString(json: JSONObject, key: String): String {
        val node = accountNode(json)
        val nested = node.optString(key)
        if (nested.isNotBlank()) return nested
        return if (node !== json) json.optString(key) else ""
    }

    private fun accountBoolean(json: JSONObject, key: String): Boolean {
        val node = accountNode(json)
        return node.optBoolean(key, json.optBoolean(key, false))
    }

    private fun accountInt(json: JSONObject, key: String, default: Int): Int {
        val node = accountNode(json)
        return node.optString(key).toIntOrNull()
            ?: json.optString(key).toIntOrNull()
            ?: default
    }

    /** 账号 API 成功判定；失败时把验证码类错误映射为文档 §10 的文案。 */
    private fun ensureAccountSuccess(json: JSONObject, fallback: String) {
        if (isSuccess(json)) return
        val code = json.optString("code").ifBlank { json.optString("error") }
        val serverMsg = firstNonBlank(
            json.optString("msg"), json.optString("message"), json.optString("error_description")
        )
        val mapped = when {
            serverMsg.contains("captcha", ignoreCase = true) || code.contains("captcha", ignoreCase = true) ->
                "光鸭安全验证未通过，请重试或改用账号密码登录"
            code.contains("frequent", ignoreCase = true) || code == "429" || serverMsg.contains("频繁") ->
                "验证码请求过于频繁，请稍后重试"
            code.contains("verification", ignoreCase = true) ||
                code.contains("code", ignoreCase = true) || serverMsg.contains("验证码") ->
                "短信验证码错误或已过期，请重新获取"
            else -> serverMsg.ifBlank { fallback }
        }
        throw IllegalStateException(mapped)
    }

    /** 账号规范化为接口要求的形态：11 位手机号 → "+86 xxx"；带 +86 前缀去空格（文档 §2.5）。 */
    fun normalizeAccount(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        val digits = s.filter { it.isDigit() }
        return when {
            s.startsWith("+86") -> "+86 " + digits.removePrefix("86")
            s.length == 11 && s.all { it.isDigit() } -> "+86 $s"
            else -> s
        }
    }

    private fun metaFor(account: String): JSONObject = when {
        account.startsWith("+") -> JSONObject().put("phone_number", account)
        account.contains("@") -> JSONObject().put("email", account)
        else -> JSONObject().put("username", account)
    }

    private fun mapSigninError(json: JSONObject, error: String): String {
        val code = json.optString("code").ifBlank { error }
        val serverMsg = firstNonBlank(
            json.optString("msg"), json.optString("message"), json.optString("error_description")
        )
        return when (code) {
            "too_many_requests" -> "光鸭登录尝试过于频繁，请稍后重试"
            "invalid_password", "invalid_account_or_password", "invalid_credentials", "unauthenticated", "user_not_found" ->
                "光鸭账号或密码不正确，请检查后重试"
            "password_required", "password_not_set" -> "该光鸭账号尚未设置密码，请切换网页登录"
            "verification_required", "verification_code_required" -> "光鸭需要短信验证，请切换网页登录继续"
            else -> serverMsg.ifBlank { "光鸭登录失败，请检查账号密码，或切换网页登录" }
        }
    }

    /** 列出阵列字段：光鸭不同接口/版本用 list / items / files / records / content 等多种命名（参考实现同款兼容）。 */
    private fun listArray(data: JSONObject): JSONArray? =
        data.optJSONArray("list")
            ?: data.optJSONArray("items")
            ?: data.optJSONArray("files")
            ?: data.optJSONArray("records")
            ?: data.optJSONArray("content")

    private fun parseFileList(arr: JSONArray?): List<ShareFile> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(parseFile(item))
            }
        }
    }

    private fun parseFile(item: JSONObject): ShareFile {
        val fid = firstNonBlank(
            item.optString("fileId"), item.optString("file_id"), item.optString("id"),
            item.optString("resId"), item.optString("resourceId")
        )
        val name = firstNonBlank(
            item.optString("fileName"), item.optString("name"), item.optString("filename"),
            item.optString("dirName"), item.optString("title")
        )
        val size = firstNonBlank(item.optString("fileSize"), item.optString("size"), item.optString("file_size"))
            .toLongOrNull() ?: 0L
        val resType = item.optString("resType")
        val dirType = item.optString("dirType")
        val isDir = when (resType) {
            "2" -> true
            "1" -> false
            else -> {
                item.optBoolean("isDir") || item.optBoolean("isdir") || item.optBoolean("dir") ||
                    item.optBoolean("isFolder") || item.optBoolean("folder") ||
                    item.optString("dirName").isNotBlank() ||
                    item.optString("type").lowercase() in setOf("folder", "dir", "directory", "0") ||
                    item.optString("fileType").lowercase() in setOf("folder", "dir", "directory", "0") ||
                    (!name.contains('.') && dirType == "1")
            }
        }
        val parent = firstNonBlank(item.optString("parentId"), item.optString("parentFileId"), item.optString("parent_id"))
        val time = firstNonBlank(
            item.optString("updatedAt"), item.optString("updateAt"), item.optString("updated_at"),
            item.optString("updateTime"), item.optString("mtime"), item.optString("utime"),
            item.optString("createdAt"), item.optString("createTime"), item.optString("ctime")
        )
        return ShareFile(
            fid = fid,
            fname = name,
            fsize = size,
            isdir = isDir,
            pdirFid = parent,
            fidToken = "",
            modifyTime = time
        )
    }

    private fun checkBusiness(json: JSONObject, fallback: String) {
        if (isSuccess(json)) return
        val code = json.optString("code")
        val serverMsg = firstNonBlank(
            json.optString("msg"), json.optString("message"), json.optString("error_description")
        )
        val mapped = when (code) {
            "209" -> "光鸭分享提取码错误或缺失"
            "200", "201", "202" -> "光鸭分享已取消、过期或不可访问"
            "205", "206", "207", "504" -> "光鸭分享下载受限，请在官网确认该分享的下载权限"
            "157" -> "光鸭网盘空间不足"
            else -> null
        }
        throw IllegalStateException(mapped ?: serverMsg.ifBlank { "$fallback（code=$code）" })
    }

    private fun isSuccess(json: JSONObject): Boolean {
        if (json.optBoolean("success", true).not()) return false
        if (json.optString("error").isNotBlank()) return false
        val code = json.optString("code")
        return code.isBlank() || code == "0"
    }

    private fun dataOf(json: JSONObject): JSONObject? {
        val data = json.optJSONObject("data") ?: return null
        return data
    }

    private fun businessRequest(
        url: String,
        body: String,
        device: GuangYaDevice?,
        token: String?
    ): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("User-Agent", GuangYaConstants.WEB_UA)
            .header("Origin", GuangYaConstants.WEB_SITE)
            .header("Referer", GuangYaConstants.DOWNLOAD_REFERER)
            .header("dt", "4")
            .header("traceparent", GuangYaConstants.newTraceparent())
        // did 为业务 API 必需设备头：登录设备优先，其次上层注入，最后匿名兜底设备
        val did = device?.deviceId ?: deviceIdProvider() ?: publicDeviceId
        if (did.isNotBlank()) builder.header("did", did)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        return builder.post(body.toRequestBody(jsonMediaType))
    }

    private fun accountRequest(
        url: String,
        body: String?,
        device: GuangYaDevice,
        token: String? = null
    ): Request.Builder {
        val deviceId = GuangYaConstants.deviceIdFromSign(device.deviceSign, device.deviceId)
        val builder = Request.Builder()
            .url(url)
            .header("X-Client-Id", GuangYaConstants.CLIENT_ID)
            .header("X-Client-Version", GuangYaConstants.CLIENT_VERSION)
            // 参考实现（wenxi guangya_login.dart）：X-Device-Id / X-Device-Sign 需 URL 编码
            .header("X-Device-Id", URLEncoder.encode(deviceId, "UTF-8"))
            .header("X-Device-Model", GuangYaConstants.DEVICE_MODEL)
            .header("X-Device-Name", GuangYaConstants.DEVICE_NAME)
            .header("X-Device-Sign", URLEncoder.encode(device.deviceSign, "UTF-8"))
            .header("X-Os-Version", GuangYaConstants.OS_VERSION)
            .header("X-Platform-Version", GuangYaConstants.PLATFORM_VERSION)
            .header("X-Protocol-Version", GuangYaConstants.PROTOCOL_VERSION)
            .header("X-Sdk-Version", GuangYaConstants.SDK_VERSION)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", GuangYaConstants.WEB_UA)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        return if (body == null) {
            builder.get()
        } else {
            builder.header("Content-Type", "application/json").post(body.toRequestBody(jsonMediaType))
        }
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                if (!response.isSuccessful) throw IllegalStateException("请求失败（HTTP ${response.code}）")
                throw IllegalStateException("请求失败：响应为空")
            }
            return JSONObject(body)
        }
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}
