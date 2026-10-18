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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

    // ★ 一个平台一张卡片（与「网盘账号」区的 DriveAccountCard 同款：一样的 shape / 底色 / 边距 / 字号）。
    //   早先是「全部平台挤在一张卡片里、用分隔线隔开」，两个区上下相邻，一眼就能看出不统一。
    //   卡片之间的 12dp 与 DriveScreen 里 LazyColumn 的 item 间距相同，所以整页节奏一致。
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        platforms.forEach { platform ->
            VideoPlatformCard(
                platform = platform,
                account = loginState[platform],
                accountCount = counts[platform.name] ?: 0,
                onLogin = onLogin,
                onManage = onManage
            )
        }
    }
}

/**
 * 单个视频平台的卡片外壳：刻意与 [com.yunx.app.ui.screens.DriveScreen] 里的 `DriveAccountCard` 同款
 * （shape = large、containerColor = surfaceContainerLow、可点则用带 onClick 的 Card）。
 * 免登录平台没有任何可操作项，所以用不带 onClick 的 Card（不是「可点但无动作」）。
 */
@Composable
private fun VideoPlatformCard(
    platform: VideoPlatform,
    account: VideoAccountEntity?,
    accountCount: Int,
    onLogin: (VideoPlatform) -> Unit,
    onManage: (VideoPlatform) -> Unit
) {
    val onClick: (() -> Unit)? = when {
        !platform.needsLogin -> null
        account != null -> { { onManage(platform) } }
        else -> { { onLogin(platform) } }
    }
    val cardShape = MaterialTheme.shapes.large
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )
    val content: @Composable () -> Unit = {
        VideoPlatformCardContent(
            platform = platform,
            account = account,
            accountCount = accountCount,
            onLogin = onLogin,
            onManage = onManage
        )
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = cardColors
        ) { content() }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = cardColors
        ) { content() }
    }
}

/** 单个视频平台卡片的内容行（卡片外壳见 [VideoPlatformCard]）：版式与网盘卡片的内容行对齐 */
@Composable
private fun VideoPlatformCardContent(
    platform: VideoPlatform,
    account: VideoAccountEntity?,
    accountCount: Int,
    onLogin: (VideoPlatform) -> Unit,
    onManage: (VideoPlatform) -> Unit
) {
    val enabled = platform.needsLogin

    Row(
        // ★ 与网盘卡片一致：整行 16dp 内边距（原来是 top/bottom 10dp，比网盘那张紧一档）。
        // ★ 这里不再自己 clickable：点击手势由外层 Card 承担，否则会叠出两层涟漪。
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
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

        // ★ 与网盘卡片一致：文字列左右各 16dp（原来靠一个 14dp 的 Spacer 顶开），字号也统一到
        //   titleMedium / bodyMedium —— 两种卡片同页混排才不会一大一小。
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = platform.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = videoStatusText(platform, account, accountCount),
                style = MaterialTheme.typography.bodyMedium,
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
