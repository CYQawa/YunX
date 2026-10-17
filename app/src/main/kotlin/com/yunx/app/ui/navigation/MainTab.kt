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

package com.yunx.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 主页底部导航的 4 个 Tab：解析 / 账号 / 下载 / 设置。
 *
 * ★ [Drive] 的枚举名不改（它承载的仍是云盘账号页，只是把「视频解析」平台的登录入口也收进了同一页，
 *   所以对外标题从「网盘」改成更准确的「账号」），避免改动 MainScreen 里所有 `MainTab.Drive` 引用。
 */
enum class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Resolve("解析", Icons.Filled.Link, Icons.Outlined.Link),
    Drive("账号", Icons.Filled.AccountCircle, Icons.Outlined.AccountCircle),
    Download("下载", Icons.Filled.Download, Icons.Outlined.Download),
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
}
