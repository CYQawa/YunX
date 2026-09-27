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

    private val cipher = AndroidKeystoreCredentialCipher()

    /** 读取已加密 Token 并解密；未配置或解密失败返回 null */
    fun getToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching { cipher.decrypt(stored, PURPOSE) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** 设置/更新 Token；传 null 或空串则清除 */
    fun setToken(context: Context, token: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_TOKEN).apply()
            return
        }
        runCatching {
            val encrypted = cipher.encrypt(trimmed, PURPOSE)
            prefs.edit().putString(KEY_TOKEN, encrypted).apply()
        }
    }

    /** 是否已配置 Token（仅判断是否存在，不做解密） */
    fun hasToken(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_TOKEN, null).isNullOrBlank()
    }
}
