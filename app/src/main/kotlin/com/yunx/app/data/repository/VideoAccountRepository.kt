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

import com.yunx.app.data.db.VideoAccountDao
import com.yunx.app.data.db.VideoAccountEntity
import com.yunx.app.data.db.resolveAccountSlot
import com.yunx.app.data.network.video.VideoParsers
import com.yunx.app.data.network.video.VideoPlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 视频解析账号仓库。
 *
 * 复用网盘多账号那套规则（[resolveAccountSlot]）：**同一平台只留一个「当前账号」**，
 * 反复登录不会堆出一串失效账号；只有显式「添加账号」才会新增一行。
 *
 * Cookie 的加解密在 `SecureAccountDaos.videoAccount` 里做，这一层只处理业务规则。
 */
internal class VideoAccountRepository(private val dao: VideoAccountDao) {

    /** 全部平台的视频账号（账号页用） */
    fun observeAccounts(): Flow<List<VideoAccountEntity>> = dao.observeAccounts()

    /** 某平台的账号（登录状态、主账号标记） */
    fun observeAccountsOf(platform: VideoPlatform): Flow<List<VideoAccountEntity>> =
        dao.observeAccountsOf(platform.name)

    /** 各平台的登录状态：platform → 当前账号（无则为 null） */
    fun observeLoginState(): Flow<Map<VideoPlatform, VideoAccountEntity>> =
        dao.observeAccounts().map { accounts ->
            accounts.groupBy { VideoPlatform.fromName(it.platform) }
                .mapNotNull { (platform, list) -> platform?.let { it to list.first() } }
                .toMap()
        }

    /**
     * 当前生效的 Cookie（主账号优先，没有主账号时取最近更新的那条）；未登录返回空串。
     * 解析器用它作为匿名/登录态的分界。
     */
    suspend fun activeCookie(platform: VideoPlatform): String =
        dao.getActive(platform.name)?.cookie.orEmpty()

    /**
     * 保存登录态：**先校验再落库**。
     *
     * @param newAccount true = 「添加账号」（另起一行、不设为主账号）；false = 日常登录/重登
     *   （覆盖当前账号那一行，避免每登一次多一个失效账号）
     * @return 昵称（拿不到昵称时为空串）；Cookie 无效返回 null（调用方据此提示用户）
     */
    suspend fun save(platform: VideoPlatform, cookie: String, newAccount: Boolean = false): String? {
        val trimmed = cookie.trim()
        if (trimmed.isBlank()) return null
        val parser = VideoParsers.of(platform) ?: return null
        val nickname = parser.validateCookie(trimmed) ?: return null

        val existingCount = dao.countOf(platform.name)
        val existing = dao.getActive(platform.name)
        // 该平台一个账号都没有时，即使调用方说「添加账号」也应当落成主账号
        val slot = resolveAccountSlot(
            platform = platform.name,
            existing = existing,
            newAccount = newAccount && existingCount > 0
        )
        dao.upsert(
            VideoAccountEntity(
                id = slot.id,
                platform = platform.name,
                cookie = trimmed,
                nickname = nickname,
                isPrimary = slot.isPrimary,
                remark = slot.remark,
                updatedAt = System.currentTimeMillis()
            )
        )
        return nickname
    }

    suspend fun setPrimary(platform: VideoPlatform, id: String) = dao.setPrimary(platform.name, id)

    suspend fun updateRemark(id: String, remark: String) = dao.updateRemark(id, remark)

    suspend fun remove(id: String) = dao.deleteById(id)

    suspend fun removeAll(platform: VideoPlatform) = dao.clearPlatform(platform.name)
}
