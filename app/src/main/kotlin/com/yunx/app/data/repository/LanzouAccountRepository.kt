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

import com.yunx.app.data.db.LanzouAccountDao
import com.yunx.app.data.db.LanzouAccountEntity
import com.yunx.app.data.network.LanzouApi
import kotlinx.coroutines.flow.Flow

/**
 * 蓝奏云账号仓库：账号密码登录（官网 accounts.woozooo.com，含 acw_sc__v2 人机校验）→ 提取 Cookie
 * （ylogin + phpdisk_info）并校验 uid/vei 后落库。昵称蓝奏个人盘接口不返回，用账号名/占位。
 */
class LanzouAccountRepository(
    private val dao: LanzouAccountDao,
    private val api: LanzouApi
) {

    fun observeAccount(): Flow<LanzouAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): LanzouAccountEntity? = dao.getAccount()

    /**
     * 原生账号密码登录：调用官网登录接口（accounts.woozooo.com，含人机校验）换取 Cookie，
     * 再用 B1 校验 uid/vei，成功则落库。
     */
    suspend fun login(account: String, password: String): Result<LanzouAccountEntity> = runCatching {
        val cookie = api.login(account, password)
        val params = api.fetchLoginParams(cookie)
            ?: throw IllegalStateException("蓝奏云登录凭据校验失败，请重试")
        if (params.uid.isBlank() || params.vei.isBlank()) {
            throw IllegalStateException("蓝奏云登录凭据无效，请重试")
        }
        val entity = LanzouAccountEntity(
            id = "lanzou",
            cookie = cookie,
            nickname = account.trim().ifBlank { "蓝奏云用户" }
        )
        dao.upsert(entity)
        entity
    }

    /** 校验 Cookie 是否包含 ylogin 与 phpdisk_info 且能取得 uid/vei，成功则落库。 */
    suspend fun saveCookie(cookie: String): Boolean {
        val c = cookie.trim()
        if (c.isBlank()) return false
        if (!c.contains("ylogin") || !c.contains("phpdisk_info")) return false
        val params = runCatching { api.fetchLoginParams(c) }.getOrNull() ?: return false
        if (params.uid.isBlank() || params.vei.isBlank()) return false
        dao.upsert(
            LanzouAccountEntity(
                id = "lanzou",
                cookie = c,
                nickname = "蓝奏云用户"
            )
        )
        return true
    }

    /** 校验当前 Cookie 是否仍有效（失效自动清库）。 */
    suspend fun validate(): Boolean {
        val acc = dao.getAccount() ?: return false
        val ok = runCatching { api.fetchLoginParams(acc.cookie) }.getOrNull() != null
        if (!ok) dao.clear()
        return ok
    }

    /** 退出登录：清库（仅账号密码登录，无 WebView 登录态需清理）。 */
    suspend fun logout() {
        dao.clear()
    }
}
