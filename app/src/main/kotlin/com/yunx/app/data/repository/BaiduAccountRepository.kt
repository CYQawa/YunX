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

import com.yunx.app.R
import com.yunx.app.data.db.BaiduAccountDao
import com.yunx.app.data.db.BaiduAccountEntity
import com.yunx.app.data.db.resolveAccountSlot
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.util.AppStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 百度账号数据仓库：Room 持久化 + 网络验证（gettemplatevariable 拿昵称）。
 */
class BaiduAccountRepository(
    private val dao: BaiduAccountDao,
    private val api: BaiduApi
) {

    /**
     * 当前可用账号：跳过旧版本落库的缺 STOKEN 半成品 Cookie，按主账号优先回退到下一个有效账号。
     *
     * 这样升级后坏账号会重新显示为「未登录」，用户再走一次登录即可由
     * [saveBaiduAccount] 原地覆盖（`newAccount = false` 时仍以数据库里的原始主账号行为准）。
     */
    fun observeAccount(): Flow<BaiduAccountEntity?> =
        dao.observeAccounts().map { accounts ->
            accounts.firstOrNull { BaiduConstants.isValidCookie(it.cookie) }
        }

    suspend fun getAccount(): BaiduAccountEntity? =
        dao.getAccounts().firstOrNull { BaiduConstants.isValidCookie(it.cookie) }

    /**
     * 该平台全部有效账号的 Cookie（主账号在前）：多账号下载（实验性 C1）轮换取链用。
     * 百度没有会话刷新接口，直接取各行已存的 Cookie；形态不完整的旧记录不参与轮询。
     */
    suspend fun allCredentials(): List<String> =
        dao.getAccounts()
            .map { BaiduConstants.sanitizeCookie(it.cookie) }
            .filter { BaiduConstants.isValidCookie(it) }

    /**
     * 校验 Cookie 有效性并落库。
     *
     * 形态检查要求 BDUSS + Pan 域 STOKEN；随后 `gettemplatevariable` 必须返回 errno=0。
     * 不能在校验失败时回退到默认昵称——那会把缺少 STOKEN 的半成品 Cookie 当成登录成功，
     * 后续个人盘接口全部 errno=-6。
     *
     * @param newAccount true = 网盘页「添加账号」进来的，落成新的一行；false = 覆盖当前主账号那一行
     */
    suspend fun saveBaiduAccount(cookie: String, newAccount: Boolean = false): Boolean {
        val cleaned = BaiduConstants.sanitizeCookie(cookie)
        if (!BaiduConstants.isValidCookie(cleaned)) return false
        val nickname = api.fetchNickname(cleaned) ?: return false
        // 这里刻意读原始主账号（不经过 getAccount 的完整性过滤）：坏账号重新登录时才能覆盖同一行，
        // 而不是新增一个非主账号后继续使用旧的半成品 Cookie。
        val slot = resolveAccountSlot(DownloadPlatform.BAIDU, dao.getAccount(), newAccount)
        dao.upsert(
            BaiduAccountEntity(
                id = slot.id,
                cookie = cleaned,
                nickname = nickname.ifBlank { AppStrings.get(R.string.er_baidu_default_nickname) },
                isPrimary = slot.isPrimary,
                remark = slot.remark
            )
        )
        return true
    }
}
