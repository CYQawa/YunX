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

import com.yunx.app.data.db.XunleiAccountDao
import com.yunx.app.data.db.XunleiAccountEntity
import com.yunx.app.data.db.resolveAccountSlot
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.PanCaptcha
import com.yunx.app.data.network.QuarkApiException
import com.yunx.app.data.network.WebCaptchaInit
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiLoginStep
import com.yunx.app.data.network.XunleiWebCredential
import com.yunx.app.data.network.video.HeadlessWebView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 迅雷账号仓库：账号+密码登录（可能触发短信验证）→ 换 token 落库。
 */
class XunleiAccountRepository(
    private val dao: XunleiAccountDao,
    private val api: XunleiApi
) {

    /**
     * 网页会话回退的互斥锁：并发解析会同时发现本地签名被拒，不加锁会同时开出多个隐藏
     * WebView，既浪费资源又可能互相覆盖 localStorage 里的会话。
     */
    private val mutex = Mutex()

    fun observeAccount(): Flow<XunleiAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): XunleiAccountEntity? = dao.getAccount()

    /** 该平台全部账号（主账号在前）：多账号下载要按 token 反查该账号自己的设备指纹与 captcha */
    suspend fun getAccounts(): List<XunleiAccountEntity> = dao.getAccounts()

    /**
     * 该平台全部账号的 access_token（主账号在前）：多账号下载（实验性 C1/C2）轮换取链用。
     * 取链时 [XunleiResolveRepository] 会用传进去的 token 取该账号自己的设备指纹，
     * 不能拿主账号的 token 配另一个账号的设备号（迅雷侧会拒）。
     */
    suspend fun allCredentials(): List<String> =
        dao.getAccounts().map { it.accessToken }.filter { it.isNotBlank() }

    /** 账号密码登录；返回登录步骤（needSms=true 表示需短信验证，携带 smsCreditKey/smsToken） */
    suspend fun loginWithPassword(
        username: String,
        password: String
    ): XunleiLoginStep {
        // 必须用官方真实设备 ID（devicesign 配套），否则 userinfo_expired
        val deviceId = XunleiApi.newDeviceId()
        // 官方首次登录：v3/login 不带任何 creditkey/captcha，返回 review_panel(1007) 触发短信验证
        return api.loginWithPassword(username, password, deviceId)
    }

    /** 发送短信验证码（登录触发 review_panel 后调用） */
    suspend fun sendSms(mobile: String): XunleiLoginStep {
        val deviceId = XunleiApi.newDeviceId()
        return api.sendSms(mobile, deviceId)
    }

    /**
     * 短信验证码登录并换取 token，成功落库返回 true。
     * @param newAccount true = 网盘页「添加账号」进来的，落成新的一行；false = 覆盖当前主账号那一行
     */
    suspend fun loginWithSms(
        mobile: String,
        smsCode: String,
        creditKey: String,
        smsToken: String,
        newAccount: Boolean = false
    ): Boolean {
        val deviceId = XunleiApi.newDeviceId()
        val step = api.smsLogin(mobile, smsCode, creditKey, smsToken, deviceId)
        if (step.sessionId.isBlank()) return false
        // 换 token 前先 initCaptcha 拿 captcha_token（官方时序：smslogin → captcha/init → signin/token）。
        // userID 取 smslogin 响应里的真实值：首次登录时进程里没有已缓存的用户 ID，传空会拿降级 token
        val captchaToken = api.initCaptcha(deviceId, mobile, userId = step.userID) ?: ""
        val tokens = api.exchangeToken(step.sessionId, deviceId, captchaToken) ?: return false
        val slot = resolveAccountSlot(DownloadPlatform.XUNLEI, dao.getAccount(), newAccount)
        dao.upsert(
            XunleiAccountEntity(
                id = slot.id,
                accessToken = tokens.first,
                refreshToken = tokens.second,
                deviceId = deviceId,
                captchaToken = captchaToken,
                nickname = step.nickname.ifBlank { "迅雷用户" },
                isPrimary = slot.isPrimary,
                remark = slot.remark
            )
        )
        return true
    }

    /**
     * 密码登录成功后用 sessionID 换 token 落库。
     * @param newAccount true = 网盘页「添加账号」进来的，落成新的一行；false = 覆盖当前主账号那一行
     */
    suspend fun finishLogin(
        step: XunleiLoginStep,
        username: String,
        newAccount: Boolean = false
    ): Boolean {
        if (step.sessionId.isBlank()) return false
        val deviceId = XunleiApi.newDeviceId()
        // 同 loginWithSms：meta.user_id 用 v3/login 响应里的 userID，不能等 currentUserId 缓存
        val captchaToken = api.initCaptcha(deviceId, username, userId = step.userID) ?: ""
        val tokens = api.exchangeToken(step.sessionId, deviceId, captchaToken) ?: return false
        val slot = resolveAccountSlot(DownloadPlatform.XUNLEI, dao.getAccount(), newAccount)
        dao.upsert(
            XunleiAccountEntity(
                id = slot.id,
                accessToken = tokens.first,
                refreshToken = tokens.second,
                deviceId = deviceId,
                captchaToken = captchaToken,
                nickname = step.nickname.ifBlank { "迅雷用户" },
                isPrimary = slot.isPrimary,
                remark = slot.remark
            )
        )
        return true
    }

    /** 刷新 token 后更新 accessToken/refreshToken（deviceId/captchaToken 保持不变） */
    suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val acc = dao.getAccount() ?: return
        dao.upsert(acc.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    /**
     * 网页登录：解析 pan.xunlei.com localStorage 里的凭据，校验可用后落库。
     *
     * 与密码/短信登录的区别只有两点：token 由网页签发，以及刷新时必须换用网页 OAuth 客户端
     * （因此记下 [XunleiWebCredential.AUTH_TYPE]）。返回值直接决定登录页的自动检测是「登录成功」
     * 还是「继续等」——校验不通过（含网络异常）一律 false，绝不把中间态当登录成功。
     *
     * @param newAccount true = 网盘页「添加账号」进来的，落成新的一行；false = 覆盖当前主账号那一行
     */
    suspend fun saveWebCredential(raw: String, newAccount: Boolean = false): Boolean {
        val tokens = XunleiWebCredential.parse(raw) ?: return false
        // 网页凭据不带设备指纹，取不到时用本机指纹兜底（云盘接口需要一个 X-Device-Id）
        val deviceId = tokens.deviceId.ifBlank { XunleiApi.newDeviceId() }
        if (!api.verifyAccessToken(tokens.accessToken, deviceId, tokens.captchaToken)) return false
        val slot = resolveAccountSlot(DownloadPlatform.XUNLEI, dao.getAccount(), newAccount)
        dao.upsert(
            XunleiAccountEntity(
                id = slot.id,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                deviceId = deviceId,
                captchaToken = tokens.captchaToken,
                nickname = tokens.nickname.ifBlank { "迅雷用户" },
                authType = XunleiWebCredential.AUTH_TYPE,
                isPrimary = slot.isPrimary,
                remark = slot.remark
            )
        )
        return true
    }

    /**
     * 网页会话回退（本地签名被拒时用）：用隐藏 WebView 重开 pan.xunlei.com，等网页自己
     * 刷新出有效 captcha 后，把 access/refresh/device/captcha 一并取回落库。
     *
     * @return 拿到新的 captcha_token 才算成功；非网页账号 / 超时 / 会话已失效都返回 false
     */
    suspend fun refreshWebSession(): Boolean {
        val acc = dao.getAccount() ?: return false
        if (acc.authType != XunleiWebCredential.AUTH_TYPE) return false
        return mutex.withLock {
            val raw = HeadlessWebView.loadAndExtract(
                url = XunleiWebCredential.LOGIN_URL,
                userAgent = XunleiWebCredential.DESKTOP_UA,
                readyCheckJs = XunleiWebCredential.REFRESH_READY_JS,
                extractJs = XunleiWebCredential.READ_SCRIPT,
                timeoutMs = 12_000L
            ) ?: return@withLock false
            // READ_SCRIPT 交回的是 encodeURIComponent 过的 JSON（与登录页 evaluateJsEncoded 的约定一致），
            // HeadlessWebView 只解 JSON 引号不做百分号解码，这里补上，否则 parse 会当成裸 token 判失败
            val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            val tokens = XunleiWebCredential.parse(decoded) ?: return@withLock false
            // WebView 期间账号可能被退出/换号：重新读一次再落库，避免把旧会话写回新账号
            val latest = dao.getAccount() ?: return@withLock false
            dao.upsert(
                latest.copy(
                    accessToken = tokens.accessToken.ifBlank { latest.accessToken },
                    refreshToken = tokens.refreshToken.ifBlank { latest.refreshToken },
                    deviceId = tokens.deviceId.ifBlank { latest.deviceId },
                    captchaToken = tokens.captchaToken.ifBlank { latest.captchaToken },
                    updatedAt = System.currentTimeMillis()
                )
            )
            tokens.captchaToken.isNotBlank()
        }
    }

    /**
     * 网页账号取 captcha（[XunleiApi.webCaptchaRefresher] 的落地实现）。
     *
     * 顺序：本地 9 盐签名现换 token（几百毫秒、后台也能用）→ 签名被拒才回退网页会话（隐藏 WebView）。
     * 只有主账号且确实是网页登录才处理：网页会话只有一份，拿它去刷别的账号会把 captcha 串用。
     *
     * @return 带过期时间的 captcha；非网页账号返回 null（调用方继续走 App 通道 captcha_sign 重签）
     * @throws QuarkApiException client_id 配错（回退也没用）、或网页会话已失效
     */
    suspend fun refreshWebCaptcha(deviceId: String, action: String): PanCaptcha? {
        val main = dao.getAccount() ?: return null
        if (main.authType != XunleiWebCredential.AUTH_TYPE) return null
        val byDevice = dao.getAccounts().firstOrNull { it.deviceId == deviceId }
        // 指定的是另一个账号的设备号：这份网页会话不属于它，不能拿主账号 captcha 顶替
        if (byDevice != null && byDevice.id != main.id) return null
        when (val local = api.initWebCaptcha(deviceId, action)) {
            is WebCaptchaInit.Ok -> return local.captcha
            // 签名没问题但 client_id 对不上：网页会话同样只认网页 client_id，回退只会白等十几秒
            WebCaptchaInit.ClientIdMismatch ->
                throw QuarkApiException("验证码与当前登录方式不匹配，请重新登录迅雷网页版")
            // 盐/版本轮换导致签名被拒：回退网页会话自签
            WebCaptchaInit.Failed -> Unit
        }
        if (!refreshWebSession()) {
            throw QuarkApiException("迅雷网页登录已过期，请在迅雷登录页重新登录")
        }
        val token = dao.getAccount()?.captchaToken?.takeIf { it.isNotBlank() } ?: return null
        // 网页会话里的 token 同样是 300s 的短命 token，按保守有效期返回
        return PanCaptcha(token, System.currentTimeMillis() + XunleiWebCredential.WEB_CAPTCHA_TOKEN_TTL_MS)
    }
}