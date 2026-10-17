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
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
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

    /**
     * 「本机此前已经生成过密钥」的标记：[onKeyCreated] 用它区分**首次生成**与**密钥被替换**。
     * 首次生成是正常的（新装/刚登录），只有替换才意味着磁盘上的旧密文再也解不开。
     */
    private const val KEY_PROVISIONED_FLAG = "credential_key_provisioned"

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

    /**
     * 密钥刚被**新建**时调用（挂在 [CredentialCipher.onKeyProvisioned] 上）。
     *
     * ★ 只有「此前已经有过密钥」时才认为这是坏消息：那说明这次新建是**替换**，
     *   磁盘上旧密文必然由上一把（已不在的）密钥加密，只能让用户重登。
     *   首次安装 / 首次加密时新建密钥是正常流程，弹「登录状态已失效」只会吓人
     *   （老实现就在这里误报，新装用户第一次登录后重启也会看到那屏提示）。
     */
    internal fun onKeyCreated() {
        val app = appContext ?: return
        runCatching {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val hadKey = prefs.getBoolean(KEY_PROVISIONED_FLAG, false)
            prefs.edit().putBoolean(KEY_PROVISIONED_FLAG, true).commit()
            if (hadKey) markKeyLost(app)
        }
    }

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
        AndroidKeystoreCredentialCipher.shared.onKeyProvisioned { onKeyCreated() }
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
 *   1. **条目确定坏了**（查无此键 / blob 解不开 / 被系统作废，判定见 [isBrokenEntry]）：
 *      **删之前先复查一次**，还是坏的才删掉它 → 生成新密钥 → **同一段加解密流程重试一次**
 *      （GCM 的 iv / AAD / 密文都还在，重建 Cipher 即可，不需要调用方参与）；
 *   2. **判不准 / 只是暂时取不到**（Keystore 抖动、未解锁的直接启动…）：抛
 *      [CredentialKeyException.Unavailable]，**绝不删键**——删键不可逆，会让磁盘上全部历史
 *      密文永久读不回来；
 *   3. 重试仍失败：抛 [CredentialKeyException.PermanentlyInvalid]，由数据层记一条提示
 *      （**不删凭证**，见 `SecureAccountDaos.decryptGuarded`）。
 *
 * ★ 2026-10 修正（真实的「划掉后台后账号全丢」事故）：[isBrokenEntry] 曾经是**过宽的白名单**
 *   （`ProviderException`、泛化 `GeneralSecurityException`、甚至「消息里带 keystore 字样」都算
 *   「条目坏了」）。于是进程刚起来那一下 Keystore 的**一过性**失败就被当成条目损坏 →
 *   `deleteEntry` 把**好密钥**删掉 → 建新密钥 → 磁盘上所有密文永久解不开 → 六家网盘账号当场全丢。
 *   现在三道闸：① 铁证不足一律不删（判不准的按 Unavailable）；② 删前必须**复查**条目确实读不出来；
 *   ③ 数据层不再因为解密失败清任何凭证。
 *
 * ★ 为什么仍要删了重建：`KeyPermanentlyInvalidatedException` 意味着这条 keyblob 再也解不开，
 *   留着它只会让**每一次**读写都失败（每个平台各自失败一遍）。此时旧密文本来就已经废了，
 *   换新密钥不额外损失数据，新写入的凭证也能立刻恢复正常。
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
     * 跑一次加解密；只有「条目确定坏了」才丢掉缓存重跑一次。
     *
     * ★ 重试必须落在**整段流程**外面（而不是只重试 `key()`）：`doFinal` 的异常也要按密钥故障
     *   看待——部分机型把「操作初始化失败」推迟到 `doFinal` 才报，只重试取密钥是接不住的。
     * ★ 这里**不删键**：删键不可逆，只允许 [key] 在锁内、且复查过之后做（见 [discardBrokenEntry]）。
     */
    private inline fun <T> withRetry(block: () -> T): T = try {
        block()
    } catch (first: Throwable) {
        if (!isBrokenEntry(first)) throw first
        cachedKey = null      // 丢掉可能已坏的缓存；下一次 block() 会经 key() 复查
        try {
            block()
        } catch (second: Throwable) {
            throw if (isBrokenEntry(second)) PermanentlyInvalid(second) else second
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
            val key = loadKeyWithRepair()
            cachedKey = key
            return key
        }
    }

    /**
     * 取密钥（含「一过性失败原样重试一次」+「条目确定损坏时删了重建」）。
     *
     * ★ 为什么一过性失败要立刻重试：这类失败（进程刚起来第一次访问 keystore、keystore 服务正忙、
     *   直接启动未解锁）占绝大多数，而它们通常**下一次调用就正常**。不重试的话，用户会看到
     *   「偶发地所有账号都显示未登录 / 保存登录时报错」，虽然数据没丢，观感与真丢了一样。
     *   重试成功即当无事发生；仍失败才按 [CredentialKeyException.Unavailable] 上报
     *   （数据与密钥原样保留，下一次读取自动恢复）。
     */
    private fun loadKeyWithRepair(): SecretKey =
        try {
            loadOrCreateKey()
        } catch (first: Throwable) {
            if (!isBrokenEntry(first)) {
                cachedKey = null
                try {
                    loadOrCreateKey()
                } catch (second: Throwable) {
                    throw asUnavailable(second)
                }
            } else {
                // 条目确定坏了（铁证）：删掉后重建 —— 此时磁盘上的旧密文本来也已经解不开
                discardBrokenEntry()
                try {
                    loadOrCreateKey()
                } catch (second: Throwable) {
                    throw if (isBrokenEntry(second)) PermanentlyInvalid(second) else asUnavailable(second)
                }
            }
        }

    /**
     * 把「非条目损坏」的取键失败统一成 [CredentialKeyException.Unavailable]。
     *
     * ★ 这类失败只是**此刻**读不到键：数据层收到它只会「本次返回 null、不删任何东西」，
     *   等下一次读取（或设备解锁后）就能自动恢复 —— 所以宁可当暂时失败，也不能按损坏处理。
     */
    private fun asUnavailable(error: Throwable): Throwable =
        if (error is CredentialKeyException) error
        else CredentialKeyException.Unavailable(describe(error), error)

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
        // 通知数据层「密钥是新建的」：由 CredentialStore.onKeyCreated 判断这次新建是不是坏消息
        // （首次生成 = 正常；替换 = 旧密文再也解不开，提示用户重登）
        runCatching { provisionedListener?.invoke() }
        return created
    }

    /**
     * 删掉不可用的 Keystore 条目 —— **只有在条目确实读不出来时**才真正删。
     *
     * ★ 删键是**不可逆**操作：删掉之后磁盘上由它加密的全部密文（六家网盘账号 / GitHub Token /
     *   下载请求头）就永久解不开了。所以这里有三道保护：
     *   ① 连 `KeyStore.load` 都进不去 ⇒ 环境暂时不可用，抛 [CredentialKeyException.Unavailable]，不删；
     *   ② 删之前**再取一次**：能取到说明上一次失败只是一过性抖动，原样保留，不删；
     *   ③ 只有「确实没有这个条目」或「再取一次仍然明确报坏」才删。
     *   调用方 [key] 在锁内调用本方法，避免多线程各自「删 + 建」把密钥来回换。
     */
    private fun discardBrokenEntry() {
        val keyStore = try {
            openKeyStore()
        } catch (error: Throwable) {
            throw CredentialKeyException.Unavailable(describe(error), error)
        }
        cachedKey = null
        val stillBroken = runCatching { keyStore.getKey(KEY_ALIAS, null) }
            .fold(
                // 取不到（null）= 条目本来就不在，删无可删，直接建新的；
                // 又抛异常 = 只有再次明确报「坏了」才算坏，其他一概按可用处理
                onSuccess = { it == null },
                onFailure = { isBrokenEntry(it) }
            )
        if (!stillBroken) {
            Log.w(TAG, "Keystore 条目复查仍然可用（上次失败应为一过性抖动），保留原密钥")
            return
        }
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
            .onFailure { Log.w(TAG, "删除不可用的 Keystore 条目失败：${describe(it)}") }
        Log.w(TAG, "已删除确认不可用的 Keystore 条目 $KEY_ALIAS，将生成新密钥")
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
         * 是否「Keystore 里的**条目本身**确定坏了」（才允许删掉重建）。
         *
         * ★ 只认两个铁证，**宁可不认**：
         *   - `KeyPermanentlyInvalidatedException`：系统明确说这条 keyblob 永久作废
         *     （改锁屏密码 / 指纹、OEM keystore 升级）；
         *   - `KeyStoreException` 且消息是「查无此键 / keyblob 解不开」（`Key not found` /
         *     `Invalid key blob`）——条目确实没了或读不出来了。
         *
         * ★ **绝不能**把 `ProviderException`、泛化的 `GeneralSecurityException`、或「消息里带
         *   keystore 字样」的异常算进来（老实现就是这么写的，酿成过真实事故）：这些**多半只是
         *   Keystore 的一时抖动**（进程刚起来、keystore 服务正忙、直接启动未解锁…）。
         *   误判的代价不可逆 —— 删掉好密钥 ⇒ 磁盘上全部历史密文永久解不开 ⇒ 用户账号全丢。
         *   判不准的一律返回 false，上层会按 [CredentialKeyException.Unavailable] 处理：
         *   本次读不出来，但**数据照旧留着**。
         * ★ 判断顺序陷阱保留：`AEADBadTagException` 是 `GeneralSecurityException` 的子类，
         *   必须在任何泛化判断之前单独排除（它是「密文被改 / 跨密钥残留」，换密钥也解不开）。
         */
        fun isBrokenEntry(error: Throwable): Boolean {
            if (error is CredentialKeyException.PermanentlyInvalid) return true
            if (error is KeyPermanentlyInvalidatedException) return true
            if (error is AEADBadTagException) return false
            if (error is KeyStoreException) {
                val message = error.message.orEmpty().lowercase()
                return message.contains("not found") ||
                    message.contains("invalid key blob") ||
                    message.contains("blob")
            }
            return false
        }

        /** 把机型的原始报错压成一行，附在用户可读文案后面（便于日后对日志） */
        fun describe(error: Throwable): String {
            val type = error::class.java.simpleName.ifBlank { "Keystore 异常" }
            val detail = error.message.orEmpty().replace('\n', ' ').trim()
            return if (detail.isEmpty()) type else "$type: ${detail.take(160)}"
        }
    }
}
