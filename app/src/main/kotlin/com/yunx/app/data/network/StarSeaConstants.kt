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

/**
 * 星海「中文口令解析」服务常量（夸克 / UC）。
 *
 * 用途：把中文口令（如「绝对有效 咐置松君杂货铺叩苓」这种不是分享链接的文本）解析成 `pwd_id`，
 * 之后由 App 自己的链路继续（`pan.quark.cn/s/<pwd_id>` → getShareToken → 列表 → 取链），
 * 所以这里**只做口令 → pwd_id 这一步**，不下发 stoken、也不碰用户 Cookie（见 [StarSeaApi]）。
 *
 * ★ API Key 是硬编码的（服务端 60 次/分钟/IP 限流，超限返回 429）：仓库开源，Key 会公开，
 *   泄漏后到服务端改 `API_KEY` 环境变量并重启即可轮换，届时同步改这里的 [API_KEY]。
 */
object StarSeaConstants {

    /** 服务根地址（HTTPS 反代，海外节点） */
    const val BASE_URL = "https://starsea-api.qwq.pics"

    /** 中文口令解析接口 */
    const val RESOLVE_URL = "$BASE_URL/api/resolve"

    /** 鉴权头 `X-API-Key` 的值 */
    const val API_KEY = "8bfbe163c670ae500aca5f3b2f060ecc8463e1eb1691ba8d"

    /**
     * 认为是「中文口令」的最大长度：口令都很短，超过这个长度多半是用户粘贴了一整段文案，
     * 不该拿它去打第三方接口（既能省一次请求，也少把无关正文发出去）。
     */
    const val MAX_COMMAND_LENGTH = 120
}
