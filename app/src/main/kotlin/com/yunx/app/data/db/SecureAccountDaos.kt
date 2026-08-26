package com.yunx.app.data.db

import com.yunx.app.data.security.CredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * DAO decorators: plaintext is exposed only in memory; every database write is encrypted.
 *
 * 性能修复（v1.2.6）：解密/加密全部切到 `Dispatchers.IO`。
 * 之前的实现里 Room suspend 查询返回后，`decrypt*` 在**调用方协程上下文**（viewModelScope = 主线程）执行
 * AndroidKeyStore（Binder IPC，单次 30~75ms）→ 网盘页下拉刷新时 6 平台并发把主线程占死 400~500ms → 全应用掉帧。
 */
internal object SecureAccountDaos {
    fun quark(raw: QuarkAccountDao, cipher: CredentialCipher): QuarkAccountDao = object : QuarkAccountDao {
        override fun observeAccount(): Flow<QuarkAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptQuark(raw, cipher, it) }
        }
        override suspend fun upsert(account: QuarkAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptQuark(cipher, account))
        }
        override suspend fun getAccount(): QuarkAccountEntity? = raw.getAccount()?.let { decryptQuark(raw, cipher, it) }
        override suspend fun clear() = raw.clear()
    }

    fun uc(raw: UCAccountDao, cipher: CredentialCipher): UCAccountDao = object : UCAccountDao {
        override fun observeAccount(): Flow<UCAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptUc(raw, cipher, it) }
        }
        override suspend fun upsert(account: UCAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptUc(cipher, account))
        }
        override suspend fun getAccount(): UCAccountEntity? = raw.getAccount()?.let { decryptUc(raw, cipher, it) }
        override suspend fun clear() = raw.clear()
    }

    fun baidu(raw: BaiduAccountDao, cipher: CredentialCipher): BaiduAccountDao = object : BaiduAccountDao {
        override fun observeAccount(): Flow<BaiduAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptBaidu(raw, cipher, it) }
        }
        override suspend fun upsert(account: BaiduAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptBaidu(cipher, account))
        }
        override suspend fun getAccount(): BaiduAccountEntity? = raw.getAccount()?.let { decryptBaidu(raw, cipher, it) }
        override suspend fun clear() = raw.clear()
    }

    fun c139(raw: C139AccountDao, cipher: CredentialCipher): C139AccountDao = object : C139AccountDao {
        override fun observeAccount(): Flow<C139AccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptC139(raw, cipher, it) }
        }
        override suspend fun upsert(account: C139AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptC139(cipher, account))
        }
        override suspend fun getAccount(): C139AccountEntity? = raw.getAccount()?.let { decryptC139(raw, cipher, it) }
        override suspend fun clear() = raw.clear()
    }

    fun pan123(raw: Pan123AccountDao, cipher: CredentialCipher): Pan123AccountDao = object : Pan123AccountDao {
        override fun observeAccount(): Flow<Pan123AccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptPan123(raw, cipher, it) }
        }
        override suspend fun upsert(account: Pan123AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptPan123(cipher, account))
        }
        override suspend fun getAccount(): Pan123AccountEntity? = raw.getAccount()?.let { decryptPan123(raw, cipher, it) }
        override suspend fun clear() = raw.clear()
    }

    fun xunlei(raw: XunleiAccountDao, cipher: CredentialCipher): XunleiAccountDao = object : XunleiAccountDao {
        override fun observeAccount(): Flow<XunleiAccountEntity?> = raw.observeAccount().map { value ->
            value?.let { decryptXunlei(raw, cipher, it) }
        }
        override suspend fun upsert(account: XunleiAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptXunlei(cipher, account))
        }
        override suspend fun getAccount(): XunleiAccountEntity? = raw.getAccount()?.let { decryptXunlei(raw, cipher, it) }
        override suspend fun clear() = raw.clear()
    }

    private suspend fun decryptQuark(raw: QuarkAccountDao, cipher: CredentialCipher, stored: QuarkAccountEntity): QuarkAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrClear(raw::clear) {
                val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "quark.cookie"))
                if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptQuark(cipher, plain))
                plain
            }
        }

    private suspend fun decryptUc(raw: UCAccountDao, cipher: CredentialCipher, stored: UCAccountEntity): UCAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrClear(raw::clear) {
                val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "uc.cookie"))
                if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptUc(cipher, plain))
                plain
            }
        }

    private suspend fun decryptBaidu(raw: BaiduAccountDao, cipher: CredentialCipher, stored: BaiduAccountEntity): BaiduAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrClear(raw::clear) {
                val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "baidu.cookie"))
                if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptBaidu(cipher, plain))
                plain
            }
        }

    private suspend fun decryptC139(raw: C139AccountDao, cipher: CredentialCipher, stored: C139AccountEntity): C139AccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrClear(raw::clear) {
                val plain = stored.copy(
                    cookie = cipher.decrypt(stored.cookie, "c139.cookie"),
                    authorization = cipher.decrypt(stored.authorization, "c139.authorization")
                )
                if (!cipher.isEncrypted(stored.cookie) || !cipher.isEncrypted(stored.authorization)) {
                    raw.upsert(encryptC139(cipher, plain))
                }
                plain
            }
        }

    private suspend fun decryptPan123(raw: Pan123AccountDao, cipher: CredentialCipher, stored: Pan123AccountEntity): Pan123AccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrClear(raw::clear) {
                val plain = stored.copy(accessToken = cipher.decrypt(stored.accessToken, "pan123.accessToken"))
                if (!cipher.isEncrypted(stored.accessToken)) raw.upsert(encryptPan123(cipher, plain))
                plain
            }
        }

    private suspend fun decryptXunlei(raw: XunleiAccountDao, cipher: CredentialCipher, stored: XunleiAccountEntity): XunleiAccountEntity? =
        withContext(Dispatchers.IO) {
            decryptOrClear(raw::clear) {
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
    private fun encryptXunlei(cipher: CredentialCipher, value: XunleiAccountEntity) = value.copy(
        accessToken = cipher.encrypt(value.accessToken, "xunlei.accessToken"),
        refreshToken = cipher.encrypt(value.refreshToken, "xunlei.refreshToken"),
        deviceId = cipher.encrypt(value.deviceId, "xunlei.deviceId"),
        captchaToken = cipher.encrypt(value.captchaToken, "xunlei.captchaToken")
    )

    private suspend fun <T> decryptOrClear(clear: suspend () -> Unit, block: suspend () -> T): T? =
        try {
            block()
        } catch (error: Exception) {
            clear()
            null
        }
}