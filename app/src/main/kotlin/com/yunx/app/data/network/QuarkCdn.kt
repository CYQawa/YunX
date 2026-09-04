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
 * 夸克 CDN 节点处理（修复 AlistGo/alist#830 下载 412 的一部分）：
 * 关闭节点优选——AList 等成熟实现均**原样使用夸克下发的直链**。
 * 改写 host / 预先 GET 探测会消耗直链额度，且改写 host 可能命中节点绑定的签名 → 412。
 * 如需提速，应改用「不消耗直链」的方式（如仅测原 host 延迟、按本地出口地理选择），且绝不改写服务端签发的 host。
 */
object QuarkCdn {

    /** 保持原样返回（与 AList quark_uc 行为一致），彻底排除节点改写/探测导致的 412 变量 */
    suspend fun fastest(original: String, cookie: String): String = original
}