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

package com.yunx.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.VideoAccountEntity
import com.yunx.app.data.network.video.VideoParsers
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.ui.items.logoRes

/**
 * 账号页分组小标题。
 *
 * ★ 没有复用 SettingsScreen 里的同名函数：那个是 `private`，跨文件不可见；这里照它的样式重写一份，
 *   只为「网盘账号 / 视频解析」两个分组标题服务，不去动 SettingsScreen。
 */
@Composable
internal fun AccountSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/**
 * 账号页「视频解析」区：列出全部视频平台及其登录态。
 *
 * ★ 平台列表由 [VideoParsers.supportedPlatforms] 驱动，这里不硬编码——新增平台只改解析器注册表即可。
 * ★ 只负责展示 + 把点击意图抛出去（登录 / 管理），弹窗与页面挂载交给 DriveScreen / MainScreen。
 *
 * @param loginState 各平台当前账号（platform → 账号），决定显示「未登录 / 昵称 / 备注」
 * @param accounts 全部平台的视频账号，用于统计「共 N 个账号」
 */
@Composable
internal fun VideoAccountSection(
    loginState: Map<VideoPlatform, VideoAccountEntity>,
    accounts: List<VideoAccountEntity>,
    onLogin: (VideoPlatform) -> Unit,
    onManage: (VideoPlatform) -> Unit
) {
    val platforms = remember { VideoParsers.supportedPlatforms }
    // 每个平台已登录的账号数：只有多账号时才需要在状态里补「共 N 个账号」
    val counts = remember(accounts) { accounts.groupingBy { it.platform }.eachCount() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            platforms.forEachIndexed { index, platform ->
                if (index > 0) HorizontalDivider()
                VideoPlatformRow(
                    platform = platform,
                    account = loginState[platform],
                    accountCount = counts[platform.name] ?: 0,
                    onLogin = onLogin,
                    onManage = onManage
                )
            }
        }
    }
}

/** 单个视频平台行：圆形徽标 + 平台名/状态 + 右侧「登录 / 管理」动作 */
@Composable
private fun VideoPlatformRow(
    platform: VideoPlatform,
    account: VideoAccountEntity?,
    accountCount: Int,
    onLogin: (VideoPlatform) -> Unit,
    onManage: (VideoPlatform) -> Unit
) {
    // 免登录平台整行不可点、也没有按钮：它对用户没有任何可操作项
    val enabled = platform.needsLogin
    val rowClick: (() -> Unit)? = when {
        !enabled -> null
        account != null -> { { onManage(platform) } }
        else -> { { onLogin(platform) } }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (rowClick != null) Modifier.clickable(onClick = rowClick) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 平台 Logo：按图标自身形状原样画（不裁圆、不垫底色，理由见 PlatformLogos 的 KDoc）。
        // 需要登录且当前没登录的平台压暗一档，一眼能看出「这个还没配账号」。
        Image(
            painter = painterResource(platform.logoRes()),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .alpha(if (platform.needsLogin && account == null) 0.45f else 1f),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = platform.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = videoStatusText(platform, account, accountCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when {
            !enabled -> Unit
            account != null -> TextButton(onClick = { onManage(platform) }) { Text("管理") }
            else -> TextButton(onClick = { onLogin(platform) }) { Text("登录") }
        }
    }
}

/** 平台状态文案：免登录 / 未登录 / 账号名（备注优先，其次昵称，都空则「已登录」） */
private fun videoStatusText(
    platform: VideoPlatform,
    account: VideoAccountEntity?,
    accountCount: Int
): String = when {
    !platform.needsLogin -> "无需登录，直接解析"
    account == null -> "未登录"
    else -> {
        val name = account.remark.ifBlank { account.nickname }.ifBlank { "已登录" }
        if (accountCount > 1) "$name · 共 $accountCount 个账号" else name
    }
}
