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
     * 读取已加密 Token 并解密；未配置或解密失败返回 null。
     *
     * ★ 解密失败时**必须把存的那条删掉**（自愈）：本机密钥可能已被系统作废（改锁屏密码、
     *   系统升级），此时这条密文再也解不开。不删的话 [hasToken] 会一直返回 true，
     *   界面显示「已配置 Token」但每次请求都在静默回退匿名限额，用户完全看不出问题。
     *   顺带把「本机密钥失效」记进一次性提示，让用户知道要重登/重配。
     */
    fun getToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            cipher.decrypt(stored, PURPOSE).takeIf { it.isNotBlank() }
        } catch (error: Throwable) {
            // 只有「永久失效」才删：Keystore 暂时进不去（设备还锁着）时留着，下次还能读出来
            if (CredentialStore.isKeyLost(error)) {
                prefs.edit().remove(KEY_TOKEN).apply()
                CredentialStore.markKeyLost(context.applicationContext)
            }
            null
        }
    }

    /** 设置/更新 Token；传 null 或空串则清除 */
    fun setToken(context: Context, token: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_TOKEN).apply()
            return
        }
        try {
            val encrypted = cipher.encrypt(trimmed, PURPOSE)
            prefs.edit().putString(KEY_TOKEN, encrypted).apply()
        } catch (error: Throwable) {
            // 加密都失败说明密钥彻底不可用：别静默吞掉（否则界面会显示「已配置」但其实没存上）
            if (CredentialStore.isKeyLost(error)) {
                prefs.edit().remove(KEY_TOKEN).apply()
                CredentialStore.markKeyLost(context.applicationContext)
            }
        }
    }

    /** 是否已配置 Token（仅判断是否存在，不做解密） */
    fun hasToken(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_TOKEN, null).isNullOrBlank()
    }
}
