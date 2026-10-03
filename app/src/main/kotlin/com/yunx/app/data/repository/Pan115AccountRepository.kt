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

package com.yunx.app.data.repository

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.yunx.app.data.db.Pan115AccountDao
import com.yunx.app.data.db.Pan115AccountEntity
import com.yunx.app.data.network.Pan115Api
import com.yunx.app.data.network.Pan115Constants
import kotlinx.coroutines.flow.Flow

/**
 * 115 网盘账号仓库：网页登录 Cookie（115.com 域）→ 落库。
 *
 * 凭证就是整串 Cookie（UID/CID/SEID/KID，见 Agent.md §3.25）：
 * 个人盘、转存、创建分享、下载直链全都靠它，所以不做字段级拆解，原样存原样用。
 */
class Pan115AccountRepository(
    private val dao: Pan115AccountDao,
    private val api: Pan115Api
) {

    fun observeAccount(): Flow<Pan115AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): Pan115AccountEntity? = dao.getAccount()

    /**
     * 保存网页登录 Cookie：先用 `/user/info` 确认登录态有效并取昵称（脱敏手机号），成功才落库。
     * @param cookie WebView 登录 115.com 后 CookieManager 取到的完整 Cookie
     */
    suspend fun saveCookie(cookie: String): Boolean {
        val value = cookie.trim()
        if (!Pan115Constants.hasLoginCookie(value)) return false
        val nickname = api.fetchNickname(value) ?: return false
        dao.upsert(
            Pan115AccountEntity(
                id = "pan115",
                cookie = value,
                nickname = nickname.ifBlank { "115用户" }
            )
        )
        return true
    }

    /** 校验当前 Cookie 是否仍有效（失效自动清库，下次重新登录；115 无 refresh 接口） */
    suspend fun validate(): Boolean {
        val account = dao.getAccount() ?: return false
        val ok = api.fetchNickname(account.cookie) != null
        if (!ok) dao.clear()
        return ok
    }

    /**
     * 退出登录：清库 + 清理 WebView 登录态（Cookie 与 localStorage/DOM 存储）。
     * 网页登录态留在 WebView 里，不清理的话再进登录页会被自动登录直接登回旧账号。
     * WebView 存储方法须在带 Looper 的线程（主线程）调用——本方法由 viewModelScope（Main）执行。
     */
    suspend fun logout() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
        dao.clear()
    }
}
