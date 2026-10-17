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

import com.yunx.app.data.db.Pan123AccountDao
import com.yunx.app.data.db.Pan123AccountEntity
import com.yunx.app.data.db.resolveAccountSlot
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.Pan123LoginResult
import kotlinx.coroutines.flow.Flow

/**
 * 123 云盘账号仓库：两条登录路径产出同一种凭证（authorToken，Bearer JWT，约 90 天过期）——
 * ① 网页登录读 yun.123pan.cn 的 localStorage；② 账号密码打原生 sign_in 接口。
 * 123 没有 refresh 接口，token 失效后只能重新登录（两条路任选）。
 */
class Pan123AccountRepository(
    private val dao: Pan123AccountDao,
    private val api: Pan123Api
) {

    fun observeAccount(): Flow<Pan123AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): Pan123AccountEntity? = dao.getAccount()

    /**
     * 网页登录凭证（authorToken）校验并落库：先用 user/info 接口确认 token 有效并取昵称，成功返回 true。
     * @param token 123 云盘网页 localStorage 的 authorToken（Bearer JWT）
     * @param newAccount true = 网盘页「添加账号」进来的，落成新的一行；false = 覆盖当前主账号那一行
     *                   （日常重新登录、会话过期后重登都走这条，避免次次重登都堆出一个失效账号）
     */
    suspend fun saveToken(token: String, newAccount: Boolean = false): Boolean {
        val t = token.trim()
        if (t.isBlank()) return false
        // 网页登录拿不到手机号：account 留空，账号页展示时回退昵称
        val nickname = api.fetchNickname(t) ?: return false
        val slot = resolveAccountSlot(DownloadPlatform.PAN123, dao.getAccount(), newAccount)
        dao.upsert(
            Pan123AccountEntity(
                id = slot.id,
                accessToken = t,
                account = "",
                nickname = nickname,
                isPrimary = slot.isPrimary,
                remark = slot.remark
            )
        )
        return true
    }

    /**
     * 账号密码登录（123 原生接口 sign_in）。
     *
     * 与网页登录落库的是同一种凭证（authorToken，同源同形），区别只在「token 从哪来」：
     * 这条不需要 WebView，也不用让用户在网页里手输一次。
     *
     * @return 成功返回 null；失败返回可直接展示给用户的原因（已做过错误映射，不回显服务端原文）
     * @param newAccount true = 网盘页「添加账号」进来的，落成新的一行；false = 覆盖当前主账号那一行
     */
    suspend fun loginWithPassword(
        account: String,
        password: String,
        newAccount: Boolean = false
    ): String? =
        when (val result = api.passwordLogin(account, password)) {
            is Pan123LoginResult.Success -> {
                // sign_in 只回 token，昵称要另取一次；取不到（网络抖动）不算登录失败——
                // token 是刚签发的确实可用，昵称退化成账号名展示即可，别把到手的登录态扔掉
                val nickname = api.fetchNickname(result.token).orEmpty()
                val slot = resolveAccountSlot(DownloadPlatform.PAN123, dao.getAccount(), newAccount)
                dao.upsert(
                    Pan123AccountEntity(
                        id = slot.id,
                        accessToken = result.token,
                        account = account.trim(),
                        nickname = nickname,
                        isPrimary = slot.isPrimary,
                        remark = slot.remark
                    )
                )
                null
            }

            is Pan123LoginResult.Failure -> result.message
        }

    /** 校验当前 token 是否仍有效（失效只删这一个账号，同平台其他账号保留） */
    suspend fun validate(): Boolean {
        val acc = dao.getAccount() ?: return false
        val ok = api.fetchNickname(acc.accessToken) != null
        if (!ok) dao.deleteById(acc.id)
        return ok
    }
}
