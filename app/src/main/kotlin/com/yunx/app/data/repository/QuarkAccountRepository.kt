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

import com.yunx.app.data.db.QuarkAccountDao
import com.yunx.app.data.db.QuarkAccountEntity
import com.yunx.app.data.db.resolveAccountSlot
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 夸克账号数据仓库：Room 持久化 + 网络验证 + __puus 会话刷新（修复 AlistGo/alist#830 下载 412）。
 * __puus 约 3 小时过期，是下载直链签名校验的关键字段；下载前惰性刷新 + 响应 Set-Cookie 自动回写双保险。
 */
class QuarkAccountRepository(
    private val dao: QuarkAccountDao,
    private val api: QuarkApi
) {

    /** 上次主动刷新 __puus 的时间戳（进程内；跨进程重启后首次下载会因间隔超时触发刷新） */
    private var lastRefreshTs = 0L

    /** cookieSink 落库用独立作用域（非 UI 线程，避免阻塞 API 调用链） */
    private val sinkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 每次 API 响应若带 Set-Cookie（__puus/__pus），自动合并并落库，保持会话始终新鲜。
        // ★ 只回写「本次请求所用 Cookie 所属的那一行」：多账号下载（实验性 C1/C2）会拿别的账号取链，
        //   把它的 __puus 合并进主账号那一行会让主账号的会话签名与 uid 对不上（取链/直链 412）。
        api.cookieSink = { merged, requestCookie ->
            sinkScope.launch {
                val target = dao.getAccounts().firstOrNull { it.cookie == requestCookie } ?: return@launch
                if (target.cookie != merged) {
                    dao.upsert(target.copy(cookie = merged, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    fun observeAccount(): Flow<QuarkAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): QuarkAccountEntity? = dao.getAccount()

    /**
     * 该平台全部账号可用的 Cookie（主账号在前）：多账号下载（实验性 C1）轮换取链用。
     * 每条都经 [cookieFor] 保证 __puus 新鲜 —— 直链签名绑定取链那一刻的 Cookie，取链与下载必须同一份。
     */
    suspend fun allCredentials(): List<String> =
        dao.getAccounts().map { cookieFor(it) }.filter { it.isNotBlank() }

    /**
     * 只对主账号做 __puus 刷新：传入的 Cookie 就是主账号那一份时返回刷新后的（等价于 [getFreshCookie]），
     * 否则原样返回。
     *
     * ★ 多账号下载轮换到非主账号时必须保留它自己的 Cookie：换成主账号的 Cookie 会让
     *   直链与身份配成两个账号，CDN 直接 412（与「取链和下载必须同一份 Cookie」是同一条约束）。
     */
    suspend fun refreshIfPrimary(cookie: String): String {
        val primary = dao.getAccount() ?: return cookie
        if (primary.cookie != cookie) return cookie
        return getFreshCookie() ?: cookie
    }

    /**
     * 返回「保证 __puus 未过期」的 Cookie（下载前调用）：
     * - 距上次刷新超过 PUUS_REFRESH_INTERVAL_MS 时，先 refreshSession 再落库；
     * - 刷新失败则回退返回当前 Cookie（不让下载直接崩）。
     */
    suspend fun getFreshCookie(): String? {
        val acc = dao.getAccount() ?: return null
        val need = System.currentTimeMillis() - lastRefreshTs > QuarkConstants.PUUS_REFRESH_INTERVAL_MS
        if (!need) return acc.cookie
        val refreshed = api.refreshSession(acc.cookie)
        return if (refreshed != null) {
            dao.upsert(acc.copy(cookie = refreshed, updatedAt = System.currentTimeMillis()))
            lastRefreshTs = System.currentTimeMillis()
            refreshed
        } else {
            acc.cookie
        }
    }

    /**
     * 取指定账号可用的 Cookie（多账号下载轮到非主账号时用）。
     * 主账号沿用 [getFreshCookie] 的时间节流刷新；其他账号按需刷新一次并落库。
     */
    suspend fun cookieFor(account: QuarkAccountEntity): String {
        if (account.isPrimary) return getFreshCookie() ?: account.cookie
        val refreshed = api.refreshSession(account.cookie)
        return if (refreshed != null) {
            dao.upsert(account.copy(cookie = refreshed, updatedAt = System.currentTimeMillis()))
            refreshed
        } else {
            account.cookie
        }
    }

    /**
     * 保存夸克 Cookie。
     * @param newAccount true = 网盘页「添加账号」进来的，落成新的一行；false = 覆盖当前主账号那一行
     *                   （日常重新登录、会话过期后重登都走这条，避免次次重登都堆出一个失效账号）
     */
    suspend fun saveQuarkAccount(cookie: String, newAccount: Boolean = false): Boolean {
        if (!QuarkConstants.isValidCookie(cookie)) return false
        val nickname = api.fetchNickname(cookie) ?: "夸克用户"
        val slot = resolveAccountSlot(DownloadPlatform.QUARK, dao.getAccount(), newAccount)
        dao.upsert(
            QuarkAccountEntity(
                id = slot.id,
                cookie = cookie,
                nickname = nickname,
                isPrimary = slot.isPrimary,
                remark = slot.remark
            )
        )
        // 重置刷新计时：新登录的 __puus 是新鲜的，避免立刻触发一次无谓刷新
        lastRefreshTs = System.currentTimeMillis()
        return true
    }
}