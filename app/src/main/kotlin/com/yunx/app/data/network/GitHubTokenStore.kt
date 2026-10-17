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

package com.yunx.app.data.network

import android.content.Context
import com.yunx.app.data.security.AndroidKeystoreCredentialCipher
import com.yunx.app.data.security.CredentialStore

/**
 * GitHub Token 加密存储。
 *
 * 安全说明：
 * - Token 仅用于提升 GitHub API 限额（未认证 60 次/小时/IP，认证后 5000 次/小时）；
 * - **严禁明文存储**：统一经 Android Keystore AES-GCM 加密（不可导出密钥）后落盘；
 * - **严禁输出到日志**，**严禁在 UI 中回显完整 Token**（输入框用 PasswordVisualTransformation）。
 */
object GitHubTokenStore {

    private const val PREFS_NAME = "yunx_settings"
    private const val KEY_TOKEN = "github_token_encrypted"
    private const val PURPOSE = "github_token"

    /** ★ 必须与其他调用方共用同一个 cipher 实例（见 [AndroidKeystoreCredentialCipher.shared]） */
    private val cipher = AndroidKeystoreCredentialCipher.shared

    /**
     * 读取并解密 Token；未配置或**这次读不出来**均返回 null。
     *
     * ★ 读不出来时**不删**存的那份密文（2026-10 起与账号凭证同口径，见 Agent.md §3.36）：
     *   删了才是真的拿不回来，而留着还有机会 —— Keystore 的失败多数是一过性的（进程刚起来、
     *   服务正忙、直接启动未解锁），下一次读取往往就好了。
     *   只有「条目永久失效」才记一次提示（[CredentialStore.markKeyLost]），且**只提示、不删数据**。
     *   代价是密文可能长期读不出来：界面靠 [hasToken] 如实显示「未配置」，用户重新粘贴一次即覆盖。
     */
    fun getToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            cipher.decrypt(stored, PURPOSE).takeIf { it.isNotBlank() }
        } catch (error: Throwable) {
            if (CredentialStore.isKeyLost(error)) {
                CredentialStore.markKeyLost(context.applicationContext)
            }
            null
        }
    }

    /**
     * 设置/更新 Token；传 null 或空串则**清除**（这是用户主动操作，删除是对的）。
     *
     * @return 是否写入成功。加密失败（本机密钥暂时不可用）时返回 false，并**保留原来存的那份** ——
     *   它可能还是好的，删掉等于把用户已配置的 Token 一起弄丢；由调用方如实提示「没能保存」。
     */
    fun setToken(context: Context, token: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_TOKEN).apply()
            return true
        }
        return try {
            val encrypted = cipher.encrypt(trimmed, PURPOSE)
            prefs.edit().putString(KEY_TOKEN, encrypted).apply()
            true
        } catch (error: Throwable) {
            if (CredentialStore.isKeyLost(error)) {
                CredentialStore.markKeyLost(context.applicationContext)
            }
            false
        }
    }

    /**
     * 是否「已配置**且当前能读出来**」。
     *
     * ★ 刻意解密一次，而不是只判断键是否存在：密文读不出来时若仍报「已配置」，
     *   界面会显示已登录 GitHub，实际每个请求都在静默走匿名限额（60 次/小时），用户完全看不出问题。
     *   代价是一次 Keystore IPC（30~75ms），所以调用方要 remember 住、别每帧调（见 MainScreen）。
     */
    fun hasToken(context: Context): Boolean = getToken(context) != null
}
