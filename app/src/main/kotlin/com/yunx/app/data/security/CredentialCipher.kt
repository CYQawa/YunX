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

package com.yunx.app.data.security

import android.util.Base64
import com.yunx.app.util.DiagnosticLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭证读写的统一入口。
 *
 * ★ 2026-10 起凭证**不再加密**：Android Keystore 的字段级加密带来过真实事故
 *   （改锁屏密码 / 指纹会让密钥被系统永久作废，历史上还因误判「条目损坏」删掉好密钥，
 *   导致六家网盘账号当场全丢），收益与代价不成比例，因此改为明文存储。
 */
internal interface CredentialCipher {
    fun encrypt(plaintext: String, purpose: String): String
    fun decrypt(stored: String, purpose: String): String
    fun isEncrypted(stored: String): Boolean
}

/**
 * 明文实现：写入直通、读取兼容历史密文。
 *
 * ★ 写入：原样返回，不再做任何加密。
 * ★ 读取：若读到的是旧版本留下的 `yunx:v1:` 密文，用**旧 Keystore 密钥**解一次，
 *   调用方拿到明文后会以明文回写覆盖（见 `SecureAccountDaos` / `DownloadManager` /
 *   `GitHubTokenStore`），老用户无需重新登录即可平滑过渡。
 * ★ 这条旧密钥路径**只读**：绝不 `generateKey()`、绝不 `deleteEntry()` —— 任何「自愈/重建」
 *   都可能把用户还能读的凭证永久作废，那正是本次改动要根除的行为。
 */
internal class PlaintextCredentialCipher : CredentialCipher {

    override fun encrypt(plaintext: String, purpose: String): String = plaintext

    override fun isEncrypted(stored: String): Boolean = stored.startsWith("$PREFIX:")

    override fun decrypt(stored: String, purpose: String): String {
        if (!isEncrypted(stored)) return stored
        // 诊断日志：这条路径只在升级后首次读到旧数据时走一次（只记用途与长度，不记内容）
        DiagnosticLog.log(
            DiagnosticLog.CRYPTO, "decrypt-legacy", size = stored.length.toLong(),
            summary = "purpose=$purpose"
        )
        val parts = stored.split(':', limit = 4)
        require(parts.size == 4 && parts[0] == "yunx" && parts[1] == "v1") {
            "Unsupported encrypted credential format"
        }
        val iv = Base64.decode(parts[2], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[3], Base64.NO_WRAP)
        val aad = purpose.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, legacyKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    /**
     * 取旧版本的 Keystore 密钥（只读）。
     *
     * ★ 密钥不存在时抛异常（由调用方按「本条读不出来」处理并**保留原数据**）：
     *   这种情况下旧密文已经不可能解开，但重建密钥也救不回来，只能等用户重新登录。
     */
    private fun legacyKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("旧加密密钥已不存在，无法解密历史凭证")
    }

    companion object {
        /** 旧密文前缀：`yunx:v1:<base64(iv)>:<base64(ciphertext)>` */
        private const val PREFIX = "yunx:v1"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "yunx.account.credentials.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /**
         * ★ **全进程唯一实例**：数据库 DAO、GitHub Token、下载请求头三处共用，保证
         *   「读到旧密文 → 第一次就识破」的判断口径一致。
         */
        val shared: CredentialCipher by lazy { PlaintextCredentialCipher() }
    }
}
