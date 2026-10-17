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

import android.util.Log
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
 * 把 App 打崩（1.2.8 的三份崩溃报告）。现在统一用 [decryptGuarded] 兜住：
 * **任何失败都只返回 null、绝不删凭证** —— 删数据换不来任何好处，却可能一次抖动就清空用户账号。
 */
internal object SecureAccountDaos {
    private const val TAG = "YunX"

    fun quark(raw: QuarkAccountDao, cipher: CredentialCipher): QuarkAccountDao = object : QuarkAccountDao {
        override fun observeAccount(): Flow<QuarkAccountEntity?> = raw.observeAccount().map { value ->
            // 单条失败只让这次 emit 为 null，**不能让 Flow 终止**（见 decryptOrNull）
            value?.let { decryptOrNull { decryptQuark(raw, cipher, it) } }
        }.catch { emit(null) }
        override suspend fun upsert(account: QuarkAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptQuark(cipher, account))
        }
        override suspend fun getAccount(): QuarkAccountEntity? = decryptOrNull {
            raw.getAccount()?.let { decryptQuark(raw, cipher, it) }
        }
        override fun observeAccounts(): Flow<List<QuarkAccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptQuark(raw, cipher, it) } }
        }.catch { emit(emptyList()) }
        override suspend fun getAccounts(): List<QuarkAccountEntity> = decryptOrNull {
            // ★ 逐行兜住：多账号下某一条密文坏了只跳过那一条，不能把同平台其他账号一起丢掉
            raw.getAccounts().mapNotNull { decryptOrNull { decryptQuark(raw, cipher, it) } }
        } ?: emptyList()
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun uc(raw: UCAccountDao, cipher: CredentialCipher): UCAccountDao = object : UCAccountDao {
        override fun observeAccount(): Flow<UCAccountEntity?> = raw.observeAccount().map { value ->
            // 单条失败只让这次 emit 为 null，**不能让 Flow 终止**（见 decryptOrNull）
            value?.let { decryptOrNull { decryptUc(raw, cipher, it) } }
        }.catch { emit(null) }
        override suspend fun upsert(account: UCAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptUc(cipher, account))
        }
        override suspend fun getAccount(): UCAccountEntity? = decryptOrNull {
            raw.getAccount()?.let { decryptUc(raw, cipher, it) }
        }
        override fun observeAccounts(): Flow<List<UCAccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptUc(raw, cipher, it) } }
        }.catch { emit(emptyList()) }
        override suspend fun getAccounts(): List<UCAccountEntity> = decryptOrNull {
            // ★ 逐行兜住：多账号下某一条密文坏了只跳过那一条，不能把同平台其他账号一起丢掉
            raw.getAccounts().mapNotNull { decryptOrNull { decryptUc(raw, cipher, it) } }
        } ?: emptyList()
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun baidu(raw: BaiduAccountDao, cipher: CredentialCipher): BaiduAccountDao = object : BaiduAccountDao {
        override fun observeAccount(): Flow<BaiduAccountEntity?> = raw.observeAccount().map { value ->
            // 单条失败只让这次 emit 为 null，**不能让 Flow 终止**（见 decryptOrNull）
            value?.let { decryptOrNull { decryptBaidu(raw, cipher, it) } }
        }.catch { emit(null) }
        override suspend fun upsert(account: BaiduAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptBaidu(cipher, account))
        }
        override suspend fun getAccount(): BaiduAccountEntity? = decryptOrNull {
            raw.getAccount()?.let { decryptBaidu(raw, cipher, it) }
        }
        override fun observeAccounts(): Flow<List<BaiduAccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptBaidu(raw, cipher, it) } }
        }.catch { emit(emptyList()) }
        override suspend fun getAccounts(): List<BaiduAccountEntity> = decryptOrNull {
            // ★ 逐行兜住：多账号下某一条密文坏了只跳过那一条，不能把同平台其他账号一起丢掉
            raw.getAccounts().mapNotNull { decryptOrNull { decryptBaidu(raw, cipher, it) } }
        } ?: emptyList()
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun c139(raw: C139AccountDao, cipher: CredentialCipher): C139AccountDao = object : C139AccountDao {
        override fun observeAccount(): Flow<C139AccountEntity?> = raw.observeAccount().map { value ->
            // 单条失败只让这次 emit 为 null，**不能让 Flow 终止**（见 decryptOrNull）
            value?.let { decryptOrNull { decryptC139(raw, cipher, it) } }
        }.catch { emit(null) }
        override suspend fun upsert(account: C139AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptC139(cipher, account))
        }
        override suspend fun getAccount(): C139AccountEntity? = decryptOrNull {
            raw.getAccount()?.let { decryptC139(raw, cipher, it) }
        }
        override fun observeAccounts(): Flow<List<C139AccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptC139(raw, cipher, it) } }
        }.catch { emit(emptyList()) }
        override suspend fun getAccounts(): List<C139AccountEntity> = decryptOrNull {
            // ★ 逐行兜住：多账号下某一条密文坏了只跳过那一条，不能把同平台其他账号一起丢掉
            raw.getAccounts().mapNotNull { decryptOrNull { decryptC139(raw, cipher, it) } }
        } ?: emptyList()
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun pan123(raw: Pan123AccountDao, cipher: CredentialCipher): Pan123AccountDao = object : Pan123AccountDao {
        override fun observeAccount(): Flow<Pan123AccountEntity?> = raw.observeAccount().map { value ->
            // 单条失败只让这次 emit 为 null，**不能让 Flow 终止**（见 decryptOrNull）
            value?.let { decryptOrNull { decryptPan123(raw, cipher, it) } }
        }.catch { emit(null) }
        override suspend fun upsert(account: Pan123AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptPan123(cipher, account))
        }
        override suspend fun getAccount(): Pan123AccountEntity? = decryptOrNull {
            raw.getAccount()?.let { decryptPan123(raw, cipher, it) }
        }
        override fun observeAccounts(): Flow<List<Pan123AccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptPan123(raw, cipher, it) } }
        }.catch { emit(emptyList()) }
        override suspend fun getAccounts(): List<Pan123AccountEntity> = decryptOrNull {
            // ★ 逐行兜住：多账号下某一条密文坏了只跳过那一条，不能把同平台其他账号一起丢掉
            raw.getAccounts().mapNotNull { runCatching { decryptPan123(raw, cipher, it) }.getOrNull() }
        } ?: emptyList()
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun pan115(raw: Pan115AccountDao, cipher: CredentialCipher): Pan115AccountDao = object : Pan115AccountDao {
        override fun observeAccount(): Flow<Pan115AccountEntity?> = raw.observeAccount().map { value ->
            // 单条失败只让这次 emit 为 null，**不能让 Flow 终止**（见 decryptOrNull）
            value?.let { decryptOrNull { decryptPan115(raw, cipher, it) } }
        }.catch { emit(null) }
        override suspend fun upsert(account: Pan115AccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptPan115(cipher, account))
        }
        override suspend fun getAccount(): Pan115AccountEntity? = decryptOrNull {
            raw.getAccount()?.let { decryptPan115(raw, cipher, it) }
        }
        override fun observeAccounts(): Flow<List<Pan115AccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptPan115(raw, cipher, it) } }
        }.catch { emit(emptyList()) }
        override suspend fun getAccounts(): List<Pan115AccountEntity> = decryptOrNull {
            // ★ 逐行兜住：多账号下某一条密文坏了只跳过那一条，不能把同平台其他账号一起丢掉
            raw.getAccounts().mapNotNull { decryptOrNull { decryptPan115(raw, cipher, it) } }
        } ?: emptyList()
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun guangya(raw: GuangYaAccountDao, cipher: CredentialCipher): GuangYaAccountDao = object : GuangYaAccountDao {
        override fun observeAccount(): Flow<GuangYaAccountEntity?> = raw.observeAccount().map { value ->
            // 单条失败只让这次 emit 为 null，**不能让 Flow 终止**（见 decryptOrNull）
            value?.let { decryptOrNull { decryptGuangYa(raw, cipher, it) } }
        }.catch { emit(null) }
        override suspend fun upsert(account: GuangYaAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptGuangYa(cipher, account))
        }
        override suspend fun getAccount(): GuangYaAccountEntity? = decryptOrNull {
            raw.getAccount()?.let { decryptGuangYa(raw, cipher, it) }
        }
        override fun observeAccounts(): Flow<List<GuangYaAccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptGuangYa(raw, cipher, it) } }
        }.catch { emit(emptyList()) }
        override suspend fun getAccounts(): List<GuangYaAccountEntity> = decryptOrNull {
            // ★ 逐行兜住：多账号下某一条密文坏了只跳过那一条，不能把同平台其他账号一起丢掉
            raw.getAccounts().mapNotNull { decryptOrNull { decryptGuangYa(raw, cipher, it) } }
        } ?: emptyList()
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun ilanzou(raw: ILanzouAccountDao, cipher: CredentialCipher): ILanzouAccountDao = object : ILanzouAccountDao {
        override fun observeAccount(): Flow<ILanzouAccountEntity?> = raw.observeAccount().map { value ->
            // 单条失败只让这次 emit 为 null，**不能让 Flow 终止**（见 decryptOrNull）
            value?.let { decryptOrNull { decryptILanzou(raw, cipher, it) } }
        }.catch { emit(null) }
        override suspend fun upsert(account: ILanzouAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptILanzou(cipher, account))
        }
        override suspend fun getAccount(): ILanzouAccountEntity? = decryptOrNull {
            raw.getAccount()?.let { decryptILanzou(raw, cipher, it) }
        }
        override fun observeAccounts(): Flow<List<ILanzouAccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptILanzou(raw, cipher, it) } }
        }.catch { emit(emptyList()) }
        override suspend fun getAccounts(): List<ILanzouAccountEntity> = decryptOrNull {
            // ★ 逐行兜住：多账号下某一条密文坏了只跳过那一条，不能把同平台其他账号一起丢掉
            raw.getAccounts().mapNotNull { decryptOrNull { decryptILanzou(raw, cipher, it) } }
        } ?: emptyList()
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun lanzou(raw: LanzouAccountDao, cipher: CredentialCipher): LanzouAccountDao = object : LanzouAccountDao {
        override fun observeAccount(): Flow<LanzouAccountEntity?> = raw.observeAccount().map { value ->
            // 单条失败只让这次 emit 为 null，**不能让 Flow 终止**（见 decryptOrNull）
            value?.let { decryptOrNull { decryptLanzou(raw, cipher, it) } }
        }.catch { emit(null) }
        override suspend fun upsert(account: LanzouAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptLanzou(cipher, account))
        }
        override suspend fun getAccount(): LanzouAccountEntity? = decryptOrNull {
            raw.getAccount()?.let { decryptLanzou(raw, cipher, it) }
        }
        override fun observeAccounts(): Flow<List<LanzouAccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptLanzou(raw, cipher, it) } }
        }.catch { emit(emptyList()) }
        override suspend fun getAccounts(): List<LanzouAccountEntity> = decryptOrNull {
            // ★ 逐行兜住：多账号下某一条密文坏了只跳过那一条，不能把同平台其他账号一起丢掉
            raw.getAccounts().mapNotNull { decryptOrNull { decryptLanzou(raw, cipher, it) } }
        } ?: emptyList()
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    fun xunlei(raw: XunleiAccountDao, cipher: CredentialCipher): XunleiAccountDao = object : XunleiAccountDao {
        override fun observeAccount(): Flow<XunleiAccountEntity?> = raw.observeAccount().map { value ->
            // 单条失败只让这次 emit 为 null，**不能让 Flow 终止**（见 decryptOrNull）
            value?.let { decryptOrNull { decryptXunlei(raw, cipher, it) } }
        }.catch { emit(null) }
        override suspend fun upsert(account: XunleiAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptXunlei(cipher, account))
        }
        override suspend fun getAccount(): XunleiAccountEntity? = decryptOrNull {
            raw.getAccount()?.let { decryptXunlei(raw, cipher, it) }
        }
        override fun observeAccounts(): Flow<List<XunleiAccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptXunlei(raw, cipher, it) } }
        }.catch { emit(emptyList()) }
        override suspend fun getAccounts(): List<XunleiAccountEntity> = decryptOrNull {
            // ★ 逐行兜住：多账号下某一条密文坏了只跳过那一条，不能把同平台其他账号一起丢掉
            raw.getAccounts().mapNotNull { decryptOrNull { decryptXunlei(raw, cipher, it) } }
        } ?: emptyList()
        override suspend fun setPrimary(id: String) = raw.setPrimary(id)
        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)
        override suspend fun deleteById(id: String) = raw.deleteById(id)
        override suspend fun clear() = raw.clear()
    }

    // ---------- 解密（内部）----------

    private suspend fun decryptGuangYa(raw: GuangYaAccountDao, cipher: CredentialCipher, stored: GuangYaAccountEntity): GuangYaAccountEntity =
        withContext(Dispatchers.IO) {
            val plain = stored.copy(
                accessToken = cipher.decrypt(stored.accessToken, "guangya.accessToken"),
                refreshToken = cipher.decrypt(stored.refreshToken, "guangya.refreshToken"),
                deviceId = cipher.decrypt(stored.deviceId, "guangya.deviceId"),
                deviceSign = cipher.decrypt(stored.deviceSign, "guangya.deviceSign")
            )
            if (listOf(stored.accessToken, stored.refreshToken, stored.deviceId, stored.deviceSign).any { !cipher.isEncrypted(it) }) {
                raw.upsert(encryptGuangYa(cipher, plain))
            }
            plain
        }

    private suspend fun decryptILanzou(raw: ILanzouAccountDao, cipher: CredentialCipher, stored: ILanzouAccountEntity): ILanzouAccountEntity =
        withContext(Dispatchers.IO) {
            val plain = stored.copy(
                appToken = cipher.decrypt(stored.appToken, "ilanzou.appToken"),
                password = cipher.decrypt(stored.password, "ilanzou.password")
            )
            if (!cipher.isEncrypted(stored.appToken) || !cipher.isEncrypted(stored.password)) {
                raw.upsert(encryptILanzou(cipher, plain))
            }
            plain
        }

    private suspend fun decryptLanzou(raw: LanzouAccountDao, cipher: CredentialCipher, stored: LanzouAccountEntity): LanzouAccountEntity =
        withContext(Dispatchers.IO) {
            val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "lanzou.cookie"))
            if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptLanzou(cipher, plain))
            plain
        }

    /**
     * 视频解析账号（所有视频平台共用一张表，用 platform 列区分）。
     *
     * 与网盘账号同一套安全口径：Cookie 字段级加密；解密失败**只返回 null、绝不删数据**；
     * 观测流逐条兜住，不让一条坏记录把整个账号列表熔断（见 [decryptOrNull]）。
     */
    fun videoAccount(raw: VideoAccountDao, cipher: CredentialCipher): VideoAccountDao = object : VideoAccountDao {
        override fun observeAccounts(): Flow<List<VideoAccountEntity>> = raw.observeAccounts().map { list ->
            // 逐条兜住：坏的这一条跳过，其它平台的账号照常显示（见 decryptOrNull）
            list.mapNotNull { decryptOrNull { decryptVideoAccount(raw, cipher, it) } }
        }.catch { emit(emptyList()) }

        override suspend fun getAccounts(): List<VideoAccountEntity> = decryptOrNull {
            raw.getAccounts().mapNotNull { decryptOrNull { decryptVideoAccount(raw, cipher, it) } }
        } ?: emptyList()

        override fun observeAccountsOf(platform: String): Flow<List<VideoAccountEntity>> =
            raw.observeAccountsOf(platform).map { list ->
                // 逐条兜住：同平台其他账号照常显示，也不能让 Flow 终止（见 decryptOrNull）
                list.mapNotNull { decryptOrNull { decryptVideoAccount(raw, cipher, it) } }
            }.catch { emit(emptyList()) }

        override suspend fun getActive(platform: String): VideoAccountEntity? = decryptOrNull {
            raw.getActive(platform)?.let { decryptVideoAccount(raw, cipher, it) }
        }

        override suspend fun countOf(platform: String): Int = raw.countOf(platform)

        override suspend fun upsert(account: VideoAccountEntity) = withContext(Dispatchers.IO) {
            raw.upsert(encryptVideoAccount(cipher, account))
        }

        override suspend fun setPrimary(platform: String, id: String) = raw.setPrimary(platform, id)

        override suspend fun updateRemark(id: String, remark: String) = raw.updateRemark(id, remark)

        override suspend fun deleteById(id: String) = raw.deleteById(id)

        override suspend fun clearPlatform(platform: String) = raw.clearPlatform(platform)
    }

    private suspend fun decryptVideoAccount(
        raw: VideoAccountDao,
        cipher: CredentialCipher,
        stored: VideoAccountEntity
    ): VideoAccountEntity = withContext(Dispatchers.IO) {
        // ★ 空 Cookie 直接原样返回：空串不是合法密文，交给 cipher 解会抛异常，
        //   而 [decryptOrNull] 会因此把整条账号跳过（账号页看起来「凭空少了一条」）。
        if (stored.cookie.isBlank()) return@withContext stored
        val plain = stored.copy(cookie = cipher.decrypt(stored.cookie, "video.cookie"))
        // 明文残留（导入/老数据）：顺手补加密，与网盘账号一致的自愈
        if (!cipher.isEncrypted(stored.cookie)) raw.upsert(encryptVideoAccount(cipher, plain))
        plain
    }

    private suspend fun encryptVideoAccount(
        cipher: CredentialCipher,
        account: VideoAccountEntity
    ): VideoAccountEntity = withContext(Dispatchers.IO) {
        account.copy(
            cookie = if (account.cookie.isBlank()) "" else cipher.encrypt(account.cookie, "video.cookie")
        )
    }

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

    private fun encryptGuangYa(cipher: CredentialCipher, value: GuangYaAccountEntity) = value.copy(
        accessToken = cipher.encrypt(value.accessToken, "guangya.accessToken"),
        refreshToken = cipher.encrypt(value.refreshToken, "guangya.refreshToken"),
        deviceId = cipher.encrypt(value.deviceId, "guangya.deviceId"),
        deviceSign = cipher.encrypt(value.deviceSign, "guangya.deviceSign")
    )
    private fun encryptILanzou(cipher: CredentialCipher, value: ILanzouAccountEntity) = value.copy(
        appToken = cipher.encrypt(value.appToken, "ilanzou.appToken"),
        password = cipher.encrypt(value.password, "ilanzou.password")
    )
    private fun encryptLanzou(cipher: CredentialCipher, value: LanzouAccountEntity) =
        value.copy(cookie = cipher.encrypt(value.cookie, "lanzou.cookie"))
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
     * 单条凭证解密；失败只返回 null 并记日志，**绝不抛给调用方**。
     *
     * ★ 为什么不删任何凭证（2026-10「划掉后台后账号全丢」事故的收尾修复）：老实现在密钥永久失效时
     *   清库、在其他解密异常时静默清库，于是 Keystore 的一次抖动就能把用户的全部账号不可逆地清掉。
     *   解密失败一律只返回 null —— 界面表现就是「未登录」，用户重新登录即可，而 `upsert` 会
     *   **覆盖同一个主键**（见 `resolveAccountSlot`），残留行既不会堆积、也不会挡住下一次登录。
     * ★ 为什么不把异常交给 Flow 的 `catch` 去兜：`Flow.catch` 会**终止**这条流，长驻订阅
     *   （MainScreen 的 stateIn）之后再也收不到值 —— 一次抖动就会让界面**整个会话**停在未登录/空列表。
     *   所以单条读取失败必须在这里就地消化成 null，让这条流继续活着。
     * ★ 只有「密钥条目永久失效」才记一条提示（[CredentialStore.isKeyLost]）：提示与删数据是两件事，
     *   提示本身不需要清库；暂时取不到（设备还锁着等）连提示都不记。
     */
    private suspend fun <T> decryptOrNull(block: suspend () -> T?): T? =
        try {
            block()
        } catch (error: kotlinx.coroutines.CancellationException) {
            // 协程取消不是「解密失败」：原样抛出，否则会把取消吞掉
            throw error
        } catch (error: Throwable) {
            if (CredentialStore.isKeyLost(error)) CredentialStore.markKeyLost()
            Log.w(TAG, "凭证读取失败（本次按未登录处理，数据原样保留）：${error.message}")
            null
        }
}