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

import com.yunx.app.data.db.GuangYaAccountDao
import com.yunx.app.data.db.GuangYaAccountEntity
import com.yunx.app.data.network.GuangYaApi
import com.yunx.app.data.network.GuangYaConstants
import com.yunx.app.data.network.GuangYaDevice
import com.yunx.app.data.network.GuangYaSmsChallenge
import com.yunx.app.data.network.GuangYaTokens
import kotlinx.coroutines.flow.Flow

/**
 * 光鸭云盘账号仓库：账号密码登录 → access/refresh token + 设备标识落库（文档 §2）。
 * 设备标识首次登录生成一次并持久化；access 过期时用 refresh 续期。
 */
class GuangYaAccountRepository(
    private val dao: GuangYaAccountDao,
    private val api: GuangYaApi
) {

    fun observeAccount(): Flow<GuangYaAccountEntity?> = dao.observeAccount()

    /**
     * 最近一次读到的 deviceId 缓存：供 [com.yunx.app.data.network.GuangYaApi.deviceIdProvider]
     * 同步取用（业务 API 的 did 头需要同步值，而 getAccount 是挂起函数）。
     */
    @Volatile
    private var cachedDeviceId: String? = null

    fun cachedDeviceId(): String? = cachedDeviceId

    /**
     * access token 的本地到期时间（毫秒，0 = 未知）。冷启动为 0，会在首次 ensureAccessToken 时
     * 用 refresh_token 换一次新 token（对齐参考实现 wenxi 的 sessions.fresh 语义）。
     */
    @Volatile
    private var accessExpiresAt: Long = 0L

    suspend fun getAccount(): GuangYaAccountEntity? = dao.getAccount().also { cachedDeviceId = it?.deviceId }

    /** 账号密码登录并落库；返回最终写入的实体。 */
    suspend fun login(account: String, password: String): Result<GuangYaAccountEntity> = runCatching {
        val existing = dao.getAccount()
        val device = deviceOf(existing)
        cachedDeviceId = device.deviceId
        val tokens = api.signIn(account, password, device)
        accessExpiresAt = expiryOf(tokens)
        val info = api.fetchAccountInfo(tokens.accessToken, device)
        val entity = GuangYaAccountEntity(
            id = "guangya",
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.ifBlank { existing?.refreshToken.orEmpty() },
            deviceId = device.deviceId,
            deviceSign = device.deviceSign,
            account = account.trim(),
            nickname = info?.nickname.orEmpty().ifBlank { account.trim() }
        )
        dao.upsert(entity)
        entity
    }

    /** 发送短信验证码（文档 §2.6）：手机号规范化为 "+86 xxx" 后请求。 */
    suspend fun sendSms(phoneNumber: String): Result<GuangYaSmsChallenge> = runCatching {
        val device = deviceOf(dao.getAccount())
        api.sendSmsCode(api.normalizeAccount(phoneNumber), device)
    }

    /**
     * 短信验证码登录 / 注册（文档 §2.7/§2.8）：先校验验证码换 verification_token，
     * 老用户走 signin、新用户走 signup，成功后拉取账号信息并落库。
     */
    suspend fun smsLogin(
        phoneNumber: String,
        verificationId: String,
        code: String,
        isUser: Boolean
    ): Result<GuangYaAccountEntity> = runCatching {
        val existing = dao.getAccount()
        val device = deviceOf(existing)
        cachedDeviceId = device.deviceId
        val normalized = api.normalizeAccount(phoneNumber)
        val verificationToken = api.verifySmsCode(verificationId, code, device)
        val tokens = if (isUser) {
            api.smsSignIn(normalized, code, verificationToken, device)
        } else {
            api.smsSignUp(normalized, smsName(normalized), code, verificationToken, device)
        }
        accessExpiresAt = expiryOf(tokens)
        val info = api.fetchAccountInfo(tokens.accessToken, device)
        val entity = GuangYaAccountEntity(
            id = "guangya",
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.ifBlank { existing?.refreshToken.orEmpty() },
            deviceId = device.deviceId,
            deviceSign = device.deviceSign,
            account = normalized,
            nickname = info?.nickname.orEmpty().ifBlank { normalized }
        )
        dao.upsert(entity)
        entity
    }

    /** 取有效 access token：临近或已过期时用 refresh_token 续期并落库（参考实现会刷新后重试）。 */
    suspend fun ensureAccessToken(): String? {
        val acc = dao.getAccount() ?: return null
        cachedDeviceId = acc.deviceId
        val now = System.currentTimeMillis()
        // 仍在有效期内（留 60s 缓冲）直接用
        if (acc.accessToken.isNotBlank() && accessExpiresAt > now + 60_000L) return acc.accessToken
        // 无 refresh_token 时只能退回现有 access（冷启动未记录到期时间时它可能仍有效）
        if (acc.refreshToken.isBlank()) return acc.accessToken.takeIf { it.isNotBlank() }
        val refreshed = api.refreshToken(acc.refreshToken, deviceOf(acc))
            ?: return acc.accessToken.takeIf { it.isNotBlank() }
        accessExpiresAt = expiryOf(refreshed)
        dao.upsert(
            acc.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken.ifBlank { acc.refreshToken },
                updatedAt = now
            )
        )
        return refreshed.accessToken
    }

    suspend fun logout() {
        cachedDeviceId = null
        accessExpiresAt = 0L
        dao.clear()
    }

    /** 令牌到期时间（毫秒）；expiresIn<=0 表示未知（记 0，下次会尝试刷新）。 */
    private fun expiryOf(tokens: GuangYaTokens): Long =
        if (tokens.expiresIn > 0) System.currentTimeMillis() + tokens.expiresIn * 1000L else 0L

    /** 新用户昵称规则（文档 §2.8）：手机号前 3 位 + **** + 后 4 位。 */
    private fun smsName(normalizedPhone: String): String {
        val local = normalizedPhone.filter { it.isDigit() }.removePrefix("86")
        return if (local.length >= 11) local.take(3) + "****" + local.takeLast(4) else "光鸭用户"
    }

    private fun deviceOf(entity: GuangYaAccountEntity?): GuangYaDevice {
        val id = entity?.deviceId.orEmpty().ifBlank { GuangYaConstants.newDeviceId() }
        val sign = entity?.deviceSign.orEmpty().ifBlank { GuangYaConstants.newDeviceSign(id) }
        return GuangYaDevice(deviceId = id, deviceSign = sign)
    }
}
