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
import com.yunx.app.data.security.CredentialKeyException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureAccountDaosTest {
    @Test
    fun encryptsWritesAndMigratesLegacyPlaintextOnRead() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "legacy-secret"))
        val secure = SecureAccountDaos.quark(raw, FakeCipher())

        assertEquals("legacy-secret", secure.getAccount()?.cookie)
        assertTrue(raw.value()?.cookie?.startsWith("sealed:") == true)
        assertFalse(raw.value()?.cookie?.contains("legacy-secret") == true)

        secure.upsert(QuarkAccountEntity(cookie = "new-secret"))
        assertEquals("new-secret", secure.getAccount()?.cookie)
        assertFalse(raw.value()?.cookie?.contains("new-secret") == true)
    }

    /**
     * 回归（1.2.8 崩溃）：改锁屏密码后 Keystore 里的密钥被系统永久作废，
     * `getAccount()` 当年会把 `InvalidKeyException` 直接抛到主线程把 App 打崩。
     * 现在必须**吞掉异常、返回 null**；而且**不能删掉这条读不回来的凭证** ——
     * 老实现会在这里清库，配合「密钥被误判损坏」就酿成了「划掉后台后账号全丢」（真实事故）。
     */
    @Test
    fun permanentlyInvalidKeyReturnsNullAndKeepsRow() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "sealed:cookie"))
        val secure = SecureAccountDaos.quark(raw, ThrowingCipher(keyLost = true))

        assertNull(secure.getAccount())
        // 只返回 null（界面表现即「未登录」），密文原样留在库里：用户重新登录会覆盖同一个主键
        assertEquals("sealed:cookie", raw.value()?.cookie)
    }

    /**
     * 密文解不开（换过密钥 / 跨版本残留，抛的是非密钥类异常）时同样**只返回 null、不删数据**：
     * 老实现对这类失败走「静默清理」，一次抖动就能把整表账号清空且不可恢复。
     */
    @Test
    fun brokenCiphertextReturnsNullAndKeepsRow() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "sealed:cookie"))
        val secure = SecureAccountDaos.quark(raw, CorruptCipher())

        assertNull(secure.getAccount())
        assertEquals("sealed:cookie", raw.value()?.cookie)
    }

    /** 观测流同样不能被异常击穿（这条路径崩的是收集方协程，比 getAccount 更早触发） */
    @Test
    fun observeAccountSwallowsKeyFailure() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "sealed:cookie"))
        val secure = SecureAccountDaos.quark(raw, ThrowingCipher(keyLost = true))

        assertNull(secure.observeAccount().first())
    }

    /**
     * Keystore **暂时**读不到（设备还锁着等）时只能返回 null，**不能删数据**：
     * 删了等于把用户本来还能解开的账号白白作废。
     */
    @Test
    fun unavailableKeyKeepsStoredCredential() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "sealed:cookie"))
        val secure = SecureAccountDaos.quark(raw, ThrowingCipher(keyLost = false))

        assertNull(secure.getAccount())
        assertEquals("sealed:cookie", raw.value()?.cookie)
    }

    private class FakeQuarkDao(initial: QuarkAccountEntity?) : QuarkAccountDao {
        private val state = MutableStateFlow(initial)
        fun value(): QuarkAccountEntity? = state.value
        override fun observeAccount(): Flow<QuarkAccountEntity?> = state
        override suspend fun upsert(account: QuarkAccountEntity) { state.value = account }
        override suspend fun getAccount(): QuarkAccountEntity? = state.value
        // 多账号（v20）新增的接口：本测试只关心单个账号的加解密，列表/主账号/删除按最小语义实现
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

    private class FakeCipher : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String =
            "sealed:${purpose.reversed()}:${plaintext.reversed()}"

        override fun decrypt(stored: String, purpose: String): String =
            if (isEncrypted(stored)) stored.substringAfterLast(':').reversed() else stored

        override fun isEncrypted(stored: String): Boolean = stored.startsWith("sealed:")

        override fun onKeyProvisioned(listener: () -> Unit) = Unit
    }

    /**
     * 模拟「密文与当前密钥不匹配」（换过密钥 / 跨版本残留）：解密抛非密钥类异常。
     * 用来钉住「这类失败也绝不能清库」。
     */
    private class CorruptCipher : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String = "sealed:$plaintext"

        override fun decrypt(stored: String, purpose: String): String =
            throw IllegalStateException("GCM tag mismatch")

        override fun isEncrypted(stored: String): Boolean = stored.startsWith("sealed:")

        override fun onKeyProvisioned(listener: () -> Unit) = Unit
    }

    /** 模拟 Keystore 失效：加解密都抛 [CredentialKeyException]（这正是修复前会击穿主线程的形态） */
    private class ThrowingCipher(private val keyLost: Boolean) : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String = fail()

        override fun decrypt(stored: String, purpose: String): String = fail()

        override fun isEncrypted(stored: String): Boolean = true

        override fun onKeyProvisioned(listener: () -> Unit) = Unit

        private fun fail(): Nothing = if (keyLost) {
            throw CredentialKeyException.PermanentlyInvalid("Key permanently invalidated")
        } else {
            throw CredentialKeyException.Unavailable("device locked")
        }
    }
}
