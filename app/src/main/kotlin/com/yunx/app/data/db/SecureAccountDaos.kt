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

package com.yunx.app.data.db

import com.yunx.app.data.security.CredentialCipher
import com.yunx.app.data.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * DAO decorators: plaintext is exposed only in memory; every database write is encrypted.
 *
 * 性能修复（v1.2.6）：解密/加密全部切到 `Dispatchers.IO`。
 * 之前的实现里 Room suspend 查询返回后，`decrypt*` 在**调用方协程上下文**（viewModelScope = 主线程）执行
 * AndroidKeyStore（Binder IPC，单次 30~75ms）→ 网盘页下拉刷新时 6 平台并发把主线程占死 400~500ms → 全应用掉帧。
 *
 * 稳定性修复（v1.2.9）：原来每处都是 `stored?.let { decryptXxx(...) }`，而 `decryptXxx` 是
 * suspend 函数——**接收者表达式先于 `withContext` 求值**，所以 `key()` 抛出的 Keystore 异常
 * （改锁屏密码后密钥失效）根本没进 `withContext` 里的 try，直接从 `getAccount()` 冒到主线程
 * 把 App 打崩（1.2.8 的三份崩溃报告）。现在统一用 [decryptGuarded] 兜住，
 * 并把「本机密钥失效」与「密文坏了」分开处理：前者清凭证 + 提示重登，后者沿用原先的静默清理。
 */
internal object SecureAccountDaos {
    fun quark(raw: QuarkAccountDao, cipher: CredentialCipher): QuarkAccountDao = object : QuarkAccountDao {
        override fun observeAccount(): Flow<QuarkAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptQuark(raw, cipher, it) }
        }.catch { emit(null) }
        override suspend fun upsert(account: QuarkAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptQuark(cipher, account))
        }
        override suspend fun getAccount(): QuarkAccountEntity? = decryptGuarded(raw::clear) {
            raw.getAccount()?.let { decryptQuark(raw, cipher, it) }
        }
        override suspend fun clear() = raw.clear()
    }

    fun uc(raw: UCAccountDao, cipher: CredentialCipher): UCAccountDao = object : UCAccountDao {
        override fun observeAccount(): Flow<UCAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptUc(raw, cipher, it) }
        }.catch { emit(null) }
        override suspend fun upsert(account: UCAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptUc(cipher, account))
        }
        override suspend fun getAccount(): UCAccountEntity? = decryptGuarded(raw::clear) {
            raw.getAccount()?.let { decryptUc(raw, cipher, it) }
        }
        override suspend fun clear() = raw.clear()
    }

    fun baidu(raw: BaiduAccountDao, cipher: CredentialCipher): BaiduAccountDao = object : BaiduAccountDao {
        override fun observeAccount(): Flow<BaiduAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptBaidu(raw, cipher, it) }
        }.catch { emit(null) }
        override suspend fun upsert(account: BaiduAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptBaidu(cipher, account))
        }
        override suspend fun getAccount(): BaiduAccountEntity? = decryptGuarded(raw::clear) {
            raw.getAccount()?.let { decryptBaidu(raw, cipher, it) }
        }
        override suspend fun clear() = raw.clear()
    }

    fun c139(raw: C139AccountDao, cipher: CredentialCipher): C139AccountDao = object : C139AccountDao {
        override fun observeAccount(): Flow<C139AccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptC139(raw, cipher, it) }
        }.catch { emit(null) }
        override suspend fun upsert(account: C139AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptC139(cipher, account))
        }
        override suspend fun getAccount(): C139AccountEntity? = decryptGuarded(raw::clear) {
            raw.getAccount()?.let { decryptC139(raw, cipher, it) }
        }
        override suspend fun clear() = raw.clear()
    }

    fun pan123(raw: Pan123AccountDao, cipher: CredentialCipher): Pan123AccountDao = object : Pan123AccountDao {
        override fun observeAccount(): Flow<Pan123AccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptPan123(raw, cipher, it) }
        }.catch { emit(null) }
        override suspend fun upsert(account: Pan123AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptPan123(cipher, account))
        }
        override suspend fun getAccount(): Pan123AccountEntity? = decryptGuarded(raw::clear) {
            raw.getAccount()?.let { decryptPan123(raw, cipher, it) }
        }
        override suspend fun clear() = raw.clear()
    }

    fun pan115(raw: Pan115AccountDao, cipher: CredentialCipher): Pan115AccountDao = object : Pan115AccountDao {
        override fun observeAccount(): Flow<Pan115AccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptPan115(raw, cipher, it) }
        }.catch { emit(null) }
        override suspend fun upsert(account: Pan115AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptPan115(cipher, account))
        }
        override suspend fun getAccount(): Pan115AccountEntity? = decryptGuarded(raw::clear) {
            raw.getAccount()?.let { decryptPan115(raw, cipher, it) }
        }
        override suspend fun clear() = raw.clear()
    }

    fun xunlei(raw: XunleiAccountDao, cipher: CredentialCipher): XunleiAccountDao = object : XunleiAccountDao {
        override fun observeAccount(): Flow<XunleiAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptXunlei(raw, cipher, it) }
        }.catch { emit(null) }
        override suspend fun upsert(account: XunleiAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptXunlei(cipher, account))
        }
        override suspend fun getAccount(): XunleiAccountEntity? = decryptGuarded(raw::clear) {
            raw.getAccount()?.let { decryptXunlei(raw, cipher, it) }
        }
        override suspend fun clear() = raw.clear()
    }

    // ---------- 解密（内部）----------

    private suspend fun decryptQuark(raw: QuarkAccountDao, cipher: CredentialCipher, stored: QuarkAccountEntity): QuarkAccountEntity =
        withContext(Dispatchers.IO) {
            val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "quark.cookie"))
            if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptQuark(cipher, plain))
            plain
        }

    private suspend fun decryptUc(raw: UCAccountDao, cipher: CredentialCipher, stored: UCAccountEntity): UCAccountEntity =
        withContext(Dispatchers.IO) {
            val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "uc.cookie"))
            if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptUc(cipher, plain))
            plain
        }

    private suspend fun decryptBaidu(raw: BaiduAccountDao, cipher: CredentialCipher, stored: BaiduAccountEntity): BaiduAccountEntity =
        withContext(Dispatchers.IO) {
            val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "baidu.cookie"))
            if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptBaidu(cipher, plain))
            plain
        }

    private suspend fun decryptC139(raw: C139AccountDao, cipher: CredentialCipher, stored: C139AccountEntity): C139AccountEntity =
        withContext(Dispatchers.IO) {
            val plain = stored.copy(
                cookie = cipher.decrypt(stored.cookie, "c139.cookie"),
                authorization = cipher.decrypt(stored.authorization, "c139.authorization")
            )
            if (!cipher.isEncrypted(stored.cookie) || !cipher.isEncrypted(stored.authorization)) {
                raw.upsert(encryptC139(cipher, plain))
            }
            plain
        }

    private suspend fun decryptPan123(raw: Pan123AccountDao, cipher: CredentialCipher, stored: Pan123AccountEntity): Pan123AccountEntity =
        withContext(Dispatchers.IO) {
            val plain = stored.copy(accessToken = cipher.decrypt(stored.accessToken, "pan123.accessToken"))
            if (!cipher.isEncrypted(stored.accessToken)) raw.upsert(encryptPan123(cipher, plain))
            plain
        }

    private suspend fun decryptPan115(raw: Pan115AccountDao, cipher: CredentialCipher, stored: Pan115AccountEntity): Pan115AccountEntity =
        withContext(Dispatchers.IO) {
            val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "pan115.cookie"))
            if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptPan115(cipher, plain))
            plain
        }

    private suspend fun decryptXunlei(raw: XunleiAccountDao, cipher: CredentialCipher, stored: XunleiAccountEntity): XunleiAccountEntity =
        withContext(Dispatchers.IO) {
            val plain = stored.copy(
                accessToken = cipher.decrypt(stored.accessToken, "xunlei.accessToken"),
                refreshToken = cipher.decrypt(stored.refreshToken, "xunlei.refreshToken"),
                deviceId = cipher.decrypt(stored.deviceId, "xunlei.deviceId"),
                captchaToken = cipher.decrypt(stored.captchaToken, "xunlei.captchaToken")
            )
            if (listOf(stored.accessToken, stored.refreshToken, stored.deviceId, stored.captchaToken).any { !cipher.isEncrypted(it) }) {
                raw.upsert(encryptXunlei(cipher, plain))
            }
            plain
        }

    private fun encryptQuark(cipher: CredentialCipher, value: QuarkAccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "quark.cookie"))
    private fun encryptUc(cipher: CredentialCipher, value: UCAccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "uc.cookie"))
    private fun encryptBaidu(cipher: CredentialCipher, value: BaiduAccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "baidu.cookie"))
    private fun encryptC139(cipher: CredentialCipher, value: C139AccountEntity) = value.copy(
        cookie = cipher.encrypt(value.cookie, "c139.cookie"),
        authorization = cipher.encrypt(value.authorization, "c139.authorization")
    )
    private fun encryptPan123(cipher: CredentialCipher, value: Pan123AccountEntity) =
        value.copy(accessToken = cipher.encrypt(value.accessToken, "pan123.accessToken"))
    private fun encryptPan115(cipher: CredentialCipher, value: Pan115AccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "pan115.cookie"))
    private fun encryptXunlei(cipher: CredentialCipher, value: XunleiAccountEntity) = value.copy(
        accessToken = cipher.encrypt(value.accessToken, "xunlei.accessToken"),
        refreshToken = cipher.encrypt(value.refreshToken, "xunlei.refreshToken"),
        deviceId = cipher.encrypt(value.deviceId, "xunlei.deviceId"),
        captchaToken = cipher.encrypt(value.captchaToken, "xunlei.captchaToken")
    )

    // ---------- 异常兜底 ----------

    /**
     * 兜住**整段**读取（接收者 + withContext 内部）抛出的异常。
     *
     * - 密钥条目**永久失效**（改锁屏密码/系统升级）⇒ 这条凭证真的读不回来了：清掉它，
     *   并记一次性提示让用户知道要重登；
     * - 密钥**暂时**取不到（设备还锁着等）⇒ 只返回 null，**不删任何东西**，
     *   等下次能读到 Keystore 时用户还是登录状态；
     * - 其他异常（GCM 认证失败、格式不支持等）：沿用 v1.2.6 起的静默清理口径，不打扰用户。
     */
    private suspend fun <T> decryptGuarded(clear: suspend () -> Unit, block: suspend () -> T?): T? =
        try {
            block()
        } catch (error: Throwable) {
            if (CredentialStore.isKeyFailure(error)) {
                if (CredentialStore.isKeyLost(error)) {
                    runCatching { clear() }
                    CredentialStore.markKeyLost()
                }
                // 暂时不可用：原样返回 null，不动数据
            } else {
                runCatching { clear() }
            }
            null
        }
}