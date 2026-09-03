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
