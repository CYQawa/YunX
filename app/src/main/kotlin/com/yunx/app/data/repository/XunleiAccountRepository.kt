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
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiLoginStep
import com.yunx.app.data.network.XunleiWebCredential
import kotlinx.coroutines.flow.Flow

/**
 * 迅雷账号仓库：账号+密码登录（可能触发短信验证）→ 换 token 落库。
 */
class XunleiAccountRepository(
    private val dao: XunleiAccountDao,
    private val api: XunleiApi
) {

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
        // 换 token 前先 initCaptcha 拿 captcha_token（官方时序：smslogin → captcha/init → signin/token）
        val captchaToken = api.initCaptcha(deviceId, mobile) ?: ""
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
        val captchaToken = api.initCaptcha(deviceId, username) ?: ""
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
}