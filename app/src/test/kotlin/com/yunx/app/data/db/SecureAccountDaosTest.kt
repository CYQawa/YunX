package com.yunx.app.data.db

import com.yunx.app.data.security.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }
}
