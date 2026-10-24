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
import android.util.Log
import com.yunx.app.data.security.CredentialCipher
import com.yunx.app.data.security.PlaintextCredentialCipher

/**
 * GitHub Token 存储。
 *
 * 说明：Token 仅用于提升 GitHub API 限额（未认证 60 次/小时/IP，认证后 5000 次/小时）。
 * ★ 2026-10 起与账号凭证同口径，**不再加密**（Android Keystore 字段级加密带来的失效事故
 *   远大于收益，见 [PlaintextCredentialCipher]）；读取时若发现旧版本密文则解密后明文回写。
 * 仍然**严禁输出到日志**、**严禁在 UI 中回显完整 Token**（输入框用 PasswordVisualTransformation）。
 */
object GitHubTokenStore {

    private const val TAG = "YunX"
    private const val PREFS_NAME = "yunx_settings"

    /** ★ 键名保留历史值（`..._encrypted`）：改名会让已配置的用户丢 Token，现在的值就是明文 */
    private const val KEY_TOKEN = "github_token_encrypted"
    private const val PURPOSE = "github_token"

    /** ★ 必须与其他调用方共用同一个 cipher 实例（见 [PlaintextCredentialCipher.shared]） */
    private val cipher: CredentialCipher = PlaintextCredentialCipher.shared

    /**
     * 读取 Token；未配置或读不出来均返回 null。
     *
     * ★ 读到旧版本密文时解密并**以明文回写**（只做一次），之后不再走解密路径。
     * ★ 读不出来时**不删**已存的值：删了才是真的拿不回来，留着还有机会（比如密钥条目只是
     *   暂时取不到）。界面靠 [hasToken] 如实显示「未配置」，用户重新粘贴一次即覆盖。
     */
    fun getToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            val plain = cipher.decrypt(stored, PURPOSE)
            if (cipher.isEncrypted(stored)) prefs.edit().putString(KEY_TOKEN, plain).apply()
            plain.takeIf { it.isNotBlank() }
        } catch (error: Throwable) {
            Log.w(TAG, "GitHub Token 读取失败（数据原样保留）：${error.message}")
            null
        }
    }

    /**
     * 设置/更新 Token；传 null 或空串则**清除**（这是用户主动操作，删除是对的）。
     *
     * @return 是否写入成功；目前只有「传空串清除」和「正常写入」两种结果，均为 true。
     */
    fun setToken(context: Context, token: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_TOKEN).apply()
            return true
        }
        prefs.edit().putString(KEY_TOKEN, trimmed).apply()
        return true
    }

    /**
     * 是否「已配置**且当前能读出来**」。
     *
     * ★ 仍是读一次而不是只看键是否存在：值读不出来时若报「已配置」，界面会显示已登录 GitHub，
     *   实际每个请求都在静默走匿名限额（60 次/小时），用户完全看不出问题。
     */
    fun hasToken(context: Context): Boolean = getToken(context) != null
}
