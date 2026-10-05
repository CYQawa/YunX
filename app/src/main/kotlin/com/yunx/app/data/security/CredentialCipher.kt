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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.yunx.app.util.DiagnosticLog
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本机密钥不可用的两种口径。
 *
 * ★ 上层**必须区分**这两种（[CredentialStore.isKeyLost]）：[PermanentlyInvalid] 代表密文
 *   永远解不开了、该清就清；[Unavailable] 只是**此刻**拿不到键，清数据会把用户本来还能读的
 *   账号白白废掉。1.2.8 的崩溃就是因为这两种都没被区分，裸 Keystore 异常直接击穿了主线程。
 */
internal sealed class CredentialKeyException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Keystore 里的条目**永久不可用**：查无此键（`Key not found`）/ 密钥 blob 无法解密
     * （`Invalid key blob`）/ 被系统永久作废（`KeyPermanentlyInvalidatedException`，典型诱因是
     * **用户改了锁屏密码或指纹**、OEM 的 keystore 升级，以及部分机型的 keyblob 升级路径）。
     * 记录本身已经坏了，只能用**新密钥**重新开始。
     */
    class PermanentlyInvalid(reason: String, cause: Throwable? = null) :
        CredentialKeyException("本机密钥不可用：$reason", cause)

    /**
     * Keystore 条目**本身完好，只是暂时取不到**：设备还处于「直接启动」未解锁状态等。
     * 此时**绝不能**删键重建，否则会把本来还能读的密文全部作废（误伤用户账号）。
     */
    class Unavailable(reason: String, cause: Throwable? = null) :
        CredentialKeyException("本机密钥暂时不可用：$reason", cause)
}

/**
 * 凭证密钥的「丢了没丢」判定 + 丢失通知。
 *
 * 抽成独立对象是因为判定异常的地方不止加解密本身（还有各 DAO 的兜底），
 * 而通知必须**跨层**（数据层失钥 → UI 层告知用户），这里用一次性标记 + 启动时补发的方式解耦。
 */
object CredentialStore {

    private const val PREFS = "yunx_settings"

    /** 一次性标记：本机密钥已丢失、凭证已清空，等界面弹一次提示。 */
    private const val KEY_LOST_FLAG = "credential_key_lost"

    /** [installRecovery] 记下的 application context：数据层深处（DAO）没有 Context 也能记标记 */
    @Volatile
    private var appContext: Context? = null

    internal const val LOST_TITLE = "登录状态已失效"
    internal const val LOST_MESSAGE =
        "本机加密密钥不可用（常见于修改锁屏密码、指纹或系统升级后），已保存的网盘登录凭证与 " +
            "GitHub Token 无法再解密，需要重新登录。\n\n" +
            "如果之前导出过认证备份，可在「设置 → 认证备份」里导入恢复。"

    /** 该异常是否属于「本机密钥故障」（区别于密文损坏、格式不支持等） */
    internal fun isKeyFailure(error: Throwable): Boolean = error is CredentialKeyException

    /**
     * 该异常是否代表**这条凭证已经彻底读不回来了**（密钥条目永久失效）。
     *
     * ★ 只有它为 true 时才允许删数据：`Unavailable`（Keystore 暂时进不去）时删掉等于
     *   把用户本来还能解开的账号白白作废，下次设备解锁后就没有了。
     */
    internal fun isKeyLost(error: Throwable): Boolean =
        error is CredentialKeyException.PermanentlyInvalid

    /** 记一次性标记（数据层用：Context 由 [installRecovery] 记着；未装配时静默跳过） */
    internal fun markKeyLost() {
        appContext?.let { markKeyLost(it) }
    }

    /** 记一次性标记：界面起来后弹一次提示 */
    internal fun markKeyLost(context: Context) {
        runCatching {
            // ★ 用 commit() 而不是 apply()：调用方可能刚写完就在这里读到（同一线程内先写后读），
            //   异步落盘会读到旧值，导致「密钥失效」这一屏不弹提示。
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LOST_FLAG, true)
                .commit()
        }
    }

    /** 启动时检查是否有未消费的「密钥失效」提示 */
    fun hasKeyLostNotice(context: Context): Boolean = runCatching {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOST_FLAG, false)
    }.getOrDefault(false)

    /** 提示已展示（或用户已确认），清标记 */
    fun consumeKeyLostNotice(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LOST_FLAG)
                .commit()
        }
    }

    /**
     * 装配「密钥被重建 → 记一次性提示」的监听。
     *
     * ★ 必须在**任何**凭证读写之前调用（`YunXApp.onCreate` 里就调一次），否则密钥在监听注册
     *   之前就被重建，那一轮的提示就丢了。重复调用只是覆盖同一个回调，幂等。
     */
    fun installRecovery(context: Context) {
        val app = context.applicationContext
        appContext = app
        AndroidKeystoreCredentialCipher.shared.onKeyProvisioned { markKeyLost(app) }
    }
}

internal interface CredentialCipher {
    fun encrypt(plaintext: String, purpose: String): String
    fun decrypt(stored: String, purpose: String): String
    fun isEncrypted(stored: String): Boolean

    /**
     * 注册「密钥是**新建的**」监听：只在本机密钥由 `generateKey()` 现造出来时回调一次
     * （正常读出来的旧密钥不回调）。
     *
     * ★ 判断依据只能是「新建」而不是「失败过」：`load(null)` 拿不到键时可能是条目坏了，
     *   也可能只是还没生成过（首次启动）。只有真的新建了密钥，才能断定
     *   **此前的密文必然由另一把密钥加密**、已经读不回来，才该去清残留。
     */
    fun onKeyProvisioned(listener: () -> Unit)
}

/**
 * AES-GCM envelope encryption whose non-exportable key is held by Android Keystore.
 *
 * 性能优化：密钥首次从 Keystore 加载后缓存复用（AndroidKeyStore 每次 KeyStore.load+getKey
 * 都是 Binder IPC，缓存后避免每次解密/加密都重复走 IPC）。
 *
 * ★ 密钥失效自愈（v1.2.9）：旧实现在密钥不可用时**直接把 Keystore 异常抛出去**，于是
 *   「用户改了锁屏密码」这类事件会让 App 在主线程上崩掉（1.2.8 收到三种崩溃报告：
 *   `InvalidKeyException: Keystore operation failed`、`KeyPermanentlyInvalidatedException`、
 *   `KeyStoreException: Invalid key blob`）。现在按三级处理：
 *
 *   1. **条目坏了**（查无此键 / blob 解不开 / 被系统作废）：删掉坏条目 → 生成新密钥 →
 *      **同一段加解密流程重试一次**（GCM 的 iv / AAD / 密文都还在，重建 Cipher 即可，
 *      不需要调用方参与）；
 *   2. **条目只是暂时取不到**（如未解锁的直接启动）：抛
 *      [CredentialKeyException.Unavailable]，**绝不删键**——否则会误伤本来还能读的密文；
 *   3. 重试仍失败：抛 [CredentialKeyException.PermanentlyInvalid]，由数据层清残留 + 提示重登。
 *
 * ★ 为什么必须删了重建：`KeyPermanentlyInvalidatedException` 意味着这条 keyblob 再也解不开，
 *   留着它只会让**每一次**读写都失败（每个平台各自失败一遍）。换新密钥后，新写入的凭证
 *   立即恢复正常，用户只需重登一次。
 */
internal class AndroidKeystoreCredentialCipher : CredentialCipher {

    @Volatile
    private var cachedKey: SecretKey? = null

    @Volatile
    private var provisionedListener: (() -> Unit)? = null

    override fun onKeyProvisioned(listener: () -> Unit) {
        provisionedListener = listener
    }

    override fun encrypt(plaintext: String, purpose: String): String {
        val aad = purpose.toByteArray(Charsets.UTF_8)
        val plain = plaintext.toByteArray(Charsets.UTF_8)
        // 诊断日志只记「用途 + 明文长度」，绝不打明文/密文本身
        DiagnosticLog.log(
            DiagnosticLog.CRYPTO, "encrypt", size = plain.size.toLong(),
            summary = "purpose=$purpose"
        )
        return withRetry {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plain)
            listOf(
                PREFIX,
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            ).joinToString(":")
        }
    }

    override fun decrypt(stored: String, purpose: String): String {
        if (!isEncrypted(stored)) return stored
        // 诊断日志：解密是「密钥失效」类问题的第一现场（只记用途与长度，不记内容）
        DiagnosticLog.log(
            DiagnosticLog.CRYPTO, "decrypt", size = stored.length.toLong(),
            summary = "purpose=$purpose"
        )
        val parts = stored.split(':', limit = 4)
        require(parts.size == 4 && parts[0] == "yunx" && parts[1] == "v1") {
            "Unsupported encrypted credential format"
        }
        val iv = Base64.decode(parts[2], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[3], Base64.NO_WRAP)
        val aad = purpose.toByteArray(Charsets.UTF_8)
        return withRetry {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }
    }

    override fun isEncrypted(stored: String): Boolean = stored.startsWith("$PREFIX:")

    /**
     * 跑一次加解密；遇到「条目坏了」先删掉坏条目，再原样重跑一次。
     *
     * ★ 重试必须落在**整段流程**外面（而不是只重试 `key()`）：`doFinal` 的异常也要按密钥故障
     *   看待——部分机型把「操作初始化失败」推迟到 `doFinal` 才报，只重试取密钥是接不住的。
     * ★ 重试前必须确认 Keystore **本身可达**（[discardStaleEntry]）：不可达时删键等于把
     *   用户本来还能读的账号全废掉。
     */
    private inline fun <T> withRetry(block: () -> T): T = try {
        block()
    } catch (first: Throwable) {
        if (!isKeyProblem(first)) throw first
        discardStaleEntry()
        try {
            block()
        } catch (second: Throwable) {
            throw if (isKeyProblem(second)) PermanentlyInvalid(second) else second
        }
    }

    /**
     * 取密钥：缓存 → Keystore 已有 → 新建。**失败一律转成 [CredentialKeyException]**，
     * 绝不让 `java.security.InvalidKeyException` 这类裸异常冒到调用方（那是崩溃报告里
     * 主线程被击穿的根因）。
     */
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val key = try {
                loadOrCreateKey()
            } catch (first: Throwable) {
                if (!isKeyProblem(first)) throw first
                discardStaleEntry()
                try {
                    loadOrCreateKey()
                } catch (second: Throwable) {
                    throw if (isKeyProblem(second)) PermanentlyInvalid(second) else second
                }
            }
            cachedKey = key
            return key
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = openKeyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return createKey()
    }

    private fun openKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // ★ 绝不加 setUserAuthenticationRequired(true)：那会让密钥**必然**因锁屏密码变更
                //   被系统永久作废（KeyPermanentlyInvalidatedException），等于把老用户账号全废掉
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        val created = generator.generateKey()
        // ★ 只有真的新建了密钥，才能断定旧密文由另一把密钥加密 → 通知数据层清残留
        runCatching { provisionedListener?.invoke() }
        return created
    }

    /**
     * 删掉不可用的 Keystore 条目。
     *
     * ★ **删之前先确认 Keystore 可达**：如果连 `KeyStore.load` 都进不去（设备还锁着、
     *   keystore 服务暂时不可用），那「读不到条目」只是**暂时**的，此时删键会把用户
     *   本来还能解开的凭证全部作废。这种情况抛 [CredentialKeyException.Unavailable]，
     *   让调用方原样失败、下次再试，而不是毁数据。
     */
    private fun discardStaleEntry() {
        val keyStore = try {
            openKeyStore()
        } catch (error: Throwable) {
            throw CredentialKeyException.Unavailable(describe(error), error)
        }
        cachedKey = null
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
            .onFailure { Log.w(TAG, "删除不可用的 Keystore 条目失败：${describe(it)}") }
        Log.w(TAG, "已删除不可用的 Keystore 条目 $KEY_ALIAS，将生成新密钥")
    }

    private fun PermanentlyInvalid(error: Throwable) =
        CredentialKeyException.PermanentlyInvalid(describe(error), error)

    /**
     * ★ 注意：companion **不能**声明成 `private` —— [shared] 要给 DAO / GitHubTokenStore /
     *   DownloadManager 三处用，而 companion 的可见性会**连带限制它自己的成员**
     *   （`private companion object` 里写 `val shared` ⇒ 报
     *   `Cannot access 'companion object Companion': it is private`）。
     *   常量仍然是 private，只有 [shared] 公开给同模块。
     */
    companion object {
        const val TAG = "YunX"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "yunx.account.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "yunx:v1"
        const val GCM_TAG_BITS = 128

        /**
         * ★ **全进程唯一实例**：数据库 DAO、GitHub Token、下载请求头三处必须共用同一个 cipher。
         *   否则 `cachedKey` 各缓存一份、`provisionedListener` 只有构造者那一个能收到，
         *   而且「密钥刚被重建」的判断会在几个实例之间错位（一个已换新密钥、一个还拿着坏的）。
         */
        val shared: CredentialCipher by lazy { AndroidKeystoreCredentialCipher() }

        /**
         * 哪些异常算「本机密钥故障」。
         *
         * ★ 只认**白名单**：不能见错就删键重建。误判的代价是**好密钥被删掉、全部账号真的作废**，
         *   所以宁可不认：判定不了的一律当普通异常抛给调用方。
         * ★ **判断顺序有陷阱**：`AEADBadTagException` 是 `GeneralSecurityException` 的子类，
         *   必须先单独排除它，否则「密文被改 / 跨版本残留」会被误认成密钥故障。
         */
        fun isKeyProblem(error: Throwable): Boolean {
            // 自己包的异常：只有「条目永久不可用」才值得换密钥重试
            if (error is CredentialKeyException) return error is CredentialKeyException.PermanentlyInvalid
            // GCM 认证标签不匹配：密文坏了，换密钥也解不开 —— 先于 GeneralSecurityException 判断
            if (error is AEADBadTagException) return false
            // 环境根本不支持算法/填充：换密钥同样不行
            if (error is javax.crypto.NoSuchPaddingException) return false
            if (error is java.security.NoSuchAlgorithmException) return false
            // AndroidKeyStore provider 侧的密钥故障（Key not found / Invalid key blob 等）
            if (error is android.security.KeyStoreException) return true
            // ProviderException 是 AndroidKeyStore provider 的兜底异常类型
            if (error is java.security.ProviderException) return true
            // java.security 侧：InvalidKey / KeyStore / UnrecoverableKey
            if (error is java.security.GeneralSecurityException) return true
            // 少数机型只抛出带关键字的消息，没有可判定的类型
            val message = error.message.orEmpty()
            return message.contains("keystore", ignoreCase = true) ||
                message.contains("key blob", ignoreCase = true) ||
                message.contains("key not found", ignoreCase = true)
        }

        /** 把机型的原始报错压成一行，附在用户可读文案后面（便于日后对日志） */
        fun describe(error: Throwable): String {
            val type = error::class.java.simpleName.ifBlank { "Keystore 异常" }
            val detail = error.message.orEmpty().replace('\n', ' ').trim()
            return if (detail.isEmpty()) type else "$type: ${detail.take(160)}"
        }
    }
}
