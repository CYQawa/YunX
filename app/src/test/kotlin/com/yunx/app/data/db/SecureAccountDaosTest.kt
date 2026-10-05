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
     * 现在必须**吞掉异常、返回 null、清掉这条读不回来的凭证**。
     */
    @Test
    fun permanentlyInvalidKeyReturnsNullInsteadOfCrashing() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "sealed:cookie"))
        val secure = SecureAccountDaos.quark(raw, ThrowingCipher(keyLost = true))

        assertNull(secure.getAccount())
        // 密文再也解不开，必须清掉；否则界面会一直显示「已登录」但每个请求都失败
        assertNull(raw.value())
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
