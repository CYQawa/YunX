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

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 分享解析仓库公共接口：夸克 / UC 共用同一套流程（token → 列表 → 转存 → 直链）。
 */
interface ShareResolveRepository {
    suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession>
    suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>>
    suspend fun ensureTempDir(cookie: String): Result<String>
    suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String>
    suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink>

    /**
     * 获取分享文件下载直链（平台差异在此收敛）：
     * - 夸克：转存到临时目录 → 用转存后新 fid 取直链；
     * - UC：直接用分享 fid + stoken + fid_token 取直链（无需转存）。
     */
    suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink>

    /**
     * 免转存取链（登录态）：不把分享文件转存到用户网盘，直接用分享凭证换下载直链 ——
     * 少一次「建目录 → 转存 → 轮询」，不在用户网盘留临时目录，也不会撞夸克的转存去重。
     *
     * 默认实现回退到 [getShareDownloadLink]，行为与改动前完全一致：UC 本来就直接取链不转存，
     * 其余平台的取链语义各自独立，不需要走这条路；目前只有夸克覆写。
     */
    suspend fun getShareDownloadLinkWithoutSave(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = getShareDownloadLink(session, file, cookie)

    /**
     * 未登录（游客）取分享直链：目前只有夸克/UC 实现。
     * 返回的 [DownloadLink.guestCookie] 必须带进下载请求（服务端随取链响应下发的游客态 __pugs，
     * 夸克缺它 412、UC 缺它 403）；不需要也不应该传账号 Cookie。
     * 其余平台默认失败 —— 它们的取链/转存中转都依赖账号态。
     */
    suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = Result.failure(
        IllegalStateException("该平台未登录时无法下载，请先登录后再试")
    )

    /**
     * 下载完成后清理临时转存目录（夸克实现删除 tr_* 子目录；其它平台默认空实现）。
     * @param dirFid DownloadLink.cleanupDirFid 带回的临时目录 fid
     */
    suspend fun cleanupTempDir(dirFid: String, cookie: String) {}
}