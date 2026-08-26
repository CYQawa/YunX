package com.yunx.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface CredentialCipher {
    fun encrypt(plaintext: String, purpose: String): String
    fun decrypt(stored: String, purpose: String): String
    fun isEncrypted(stored: String): Boolean
}

/**
 * AES-GCM envelope encryption whose non-exportable key is held by Android Keystore.
 *
 * 性能优化：密钥首次从 Keystore 加载后缓存复用（AndroidKeyStore 每次 KeyStore.load+getKey
 * 都是 Binder IPC，缓存后避免每次解密/加密都重复走 IPC）。
 */
internal class AndroidKeystoreCredentialCipher : CredentialCipher {

    @Volatile
    private var cachedKey: SecretKey? = null

    override fun encrypt(plaintext: String, purpose: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(
            PREFIX,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        ).joinToString(":")
    }

    override fun decrypt(stored: String, purpose: String): String {
        if (!isEncrypted(stored)) return stored
        val parts = stored.split(':', limit = 4)
        require(parts.size == 4 && parts[0] == "yunx" && parts[1] == "v1") {
            "Unsupported encrypted credential format"
        }
        val iv = Base64.decode(parts[2], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[3], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    override fun isEncrypted(stored: String): Boolean = stored.startsWith("$PREFIX:")

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { key ->
                cachedKey = key
                return key
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            val key = generator.generateKey()
            cachedKey = key
            return key
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "yunx.account.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "yunx:v1"
    }
}