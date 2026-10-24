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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 凭证**不再加密**（2026-10）后，装饰器只负责两件事：写入直通、读到旧密文时解密并明文回写。
 * 这组测试钉住这两条，以及「读取失败绝不删数据」这条从真实事故里换来的底线。
 */
class SecureAccountDaosTest {

    @Test
    fun writesPlaintextAndReadsItBackUnchanged() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "cookie"))
        val secure = SecureAccountDaos.quark(raw, FakeCipher())

        assertEquals("cookie", secure.getAccount()?.cookie)

        secure.upsert(QuarkAccountEntity(cookie = "new-secret"))
        assertEquals("new-secret", secure.getAccount()?.cookie)
        // 落库的就是明文本身，不再有密文形态
        assertEquals("new-secret", raw.value()?.cookie)
    }

    /** 读明文时不做任何回写（否则每次读取都会多一次 UPDATE） */
    @Test
    fun plaintextReadDoesNotRewriteRow() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "cookie"))
        val secure = SecureAccountDaos.quark(raw, FakeCipher())

        assertEquals("cookie", secure.getAccount()?.cookie)
        assertEquals("cookie", secure.getAccounts().single().cookie)
        assertEquals(0, raw.upsertCount)
    }

    /** 旧版本密文：读取时解密返回，并**以明文回写覆盖**（只迁移一次，老用户无需重新登录） */
    @Test
    fun legacyCiphertextIsDecryptedAndRewrittenAsPlaintext() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "sealed:cookie"))
        val secure = SecureAccountDaos.quark(raw, FakeCipher())

        assertEquals("cookie", secure.getAccount()?.cookie)
        assertEquals("cookie", raw.value()?.cookie)
        assertEquals(1, raw.upsertCount)
        assertFalse(raw.value()?.cookie?.startsWith("sealed:") == true)
    }

    /**
     * 回归（1.2.8 崩溃）：改锁屏密码后 Keystore 里的密钥被系统永久作废，
     * `getAccount()` 当年会把 `InvalidKeyException` 直接抛到主线程把 App 打崩。
     * 现在必须**吞掉异常、返回 null**；而且**不能删掉这条读不回来的凭证** ——
     * 老实现会在这里清库，配合「密钥被误判损坏」就酿成了「划掉后台后账号全丢」（真实事故）。
     */
    @Test
    fun brokenCiphertextReturnsNullAndKeepsRow() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "sealed:cookie"))
        val secure = SecureAccountDaos.quark(raw, CorruptCipher())

        assertNull(secure.getAccount())
        // 只返回 null（界面表现即「未登录」），密文原样留在库里：用户重新登录会覆盖同一个主键
        assertEquals("sealed:cookie", raw.value()?.cookie)
    }

    /** 观测流同样不能被异常击穿（这条路径崩的是收集方协程，比 getAccount 更早触发） */
    @Test
    fun observeAccountSwallowsDecryptFailure() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "sealed:cookie"))
        val secure = SecureAccountDaos.quark(raw, ThrowingCipher())

        assertNull(secure.observeAccount().first())
        assertEquals("sealed:cookie", raw.value()?.cookie)
    }

    /** Keystore 暂时读不到（设备还锁着等）时也只能返回 null，**不能删数据** */
    @Test
    fun unavailableKeyKeepsStoredCredential() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "sealed:cookie"))
        val secure = SecureAccountDaos.quark(raw, ThrowingCipher())

        assertNull(secure.getAccount())
        assertEquals("sealed:cookie", raw.value()?.cookie)
    }

    private class FakeQuarkDao(initial: QuarkAccountEntity?) : QuarkAccountDao {
        private val state = MutableStateFlow(initial)

        /** 记录回写次数：用来断言「明文读取不产生多余的写」 */
        var upsertCount = 0
            private set

        fun value(): QuarkAccountEntity? = state.value
        override fun observeAccount(): Flow<QuarkAccountEntity?> = state
        override suspend fun upsert(account: QuarkAccountEntity) {
            upsertCount++
            state.value = account
        }

        override suspend fun getAccount(): QuarkAccountEntity? = state.value
        // 多账号（v20）新增的接口：本测试只关心单个账号的读写，列表/主账号/删除按最小语义实现
        override fun observeAccounts(): Flow<List<QuarkAccountEntity>> =
            state.map { listOfNotNull(it) }

        override suspend fun getAccounts(): List<QuarkAccountEntity> = listOfNotNull(state.value)

        override suspend fun setPrimary(id: String) {
            val current = state.value ?: return
            state.value = current.copy(isPrimary = current.id == id)
        }

        override suspend fun updateRemark(id: String, remark: String) {
            val current = state.value ?: return
            if (current.id == id) state.value = current.copy(remark = remark)
        }

        override suspend fun deleteById(id: String) {
            if (state.value?.id == id) state.value = null
        }

        override suspend fun clear() { state.value = null }
    }

    /** 明文 cipher：写入直通，读取兼容 `sealed:` 前缀的历史密文（与 PlaintextCredentialCipher 同形态） */
    private class FakeCipher : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String = plaintext

        override fun decrypt(stored: String, purpose: String): String =
            if (isEncrypted(stored)) stored.removePrefix("sealed:") else stored

        override fun isEncrypted(stored: String): Boolean = stored.startsWith("sealed:")
    }

    /**
     * 模拟「密文与当前密钥不匹配」（换过密钥 / 跨版本残留）：解密抛非密钥类异常。
     * 用来钉住「这类失败也绝不能清库」。
     */
    private class CorruptCipher : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String = plaintext

        override fun decrypt(stored: String, purpose: String): String =
            throw IllegalStateException("GCM tag mismatch")

        override fun isEncrypted(stored: String): Boolean = stored.startsWith("sealed:")
    }

    /** 模拟旧密钥彻底取不到（Keystore 条目没了 / 暂时进不去）：读取必失败，且**不得**删数据 */
    private class ThrowingCipher : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String = plaintext

        override fun decrypt(stored: String, purpose: String): String =
            throw IllegalStateException("旧加密密钥已不存在，无法解密历史凭证")

        override fun isEncrypted(stored: String): Boolean = stored.startsWith("sealed:")
    }
}
