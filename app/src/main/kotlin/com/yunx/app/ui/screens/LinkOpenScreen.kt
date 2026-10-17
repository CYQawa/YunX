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

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.prefs.LinkOpenTarget
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.ListGroupPos
import com.yunx.app.ui.theme.listGroupShape
import com.yunx.app.util.LinkOpener

/**
 * 「链接唤起」（深链接注册项）设置页。
 *
 * 每行对应清单里一个 activity-alias：关掉就是把那个 alias 置为 DISABLED，
 * 系统随即不再把那种链接派给云析（详见 [LinkOpenTarget] / [LinkOpener]）。
 *
 * ★ 页面只是开关的「用户意愿」，真正生效的组件状态由 [LinkOpener.apply] 立刻同步 —— 不写回就不会生效，
 *   所以这里每次切换都同时做三件事：改本地状态、写 prefs、同步组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkOpenScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    // 进页面读一次：之后以本地状态为准（写回 prefs + 同步组件）
    var states by remember {
        mutableStateOf(LinkOpenTarget.entries.associateWith { settingsRepo.linkOpen(it) })
    }
    val enabledCount = states.count { it.value }

    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("链接唤起", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "在浏览器或其它 App 里点网盘分享链接，系统会问用哪个应用打开 —— " +
                            "选「云析」并勾选「始终」，之后点链接就直接进来解析。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 说清楚「已验证的链接为什么永远是 0」：验证要在对方服务器放校验文件，第三方 App 做不到
                    Text(
                        text = "系统设置里那个「已验证的链接」会一直是 0：验证要求每个网盘域名的服务器上" +
                            "放置本应用的校验文件（assetlinks.json），而这些域名都不属于本项目，" +
                            "第三方 App 无法做到（开了自动验证反而可能让云析从选择器里彻底消失）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "官方对「无法验证域名的第三方 App」给出的做法是手动关联域名：在系统的" +
                            "「默认打开 → 打开支持的链接」里把网盘域名勾给云析，效果与验证相同" +
                            "（不再弹选择器），只是记在「要在此应用中打开的链接」里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "关掉某一项 = 不再接管那种链接（系统会重新交给浏览器或网盘官方 App）；" +
                            "改动立即生效，无需重启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 一键跳到系统的关联页：免选择器的唯一落地方式（域名验证做不到，只能手动关联）
            val rowInteraction = remember { MutableInteractionSource() }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable(
                        interactionSource = rowInteraction,
                        indication = ripple(bounded = true)
                    ) { openLinkHandlingSettings(context) },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "去系统设置关联域名", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "打开「默认打开 → 打开支持的链接」，把要接管的网盘域名勾给云析",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "已启用 $enabledCount / ${LinkOpenTarget.entries.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            LinkOpenTarget.entries.forEachIndexed { index, target ->
                if (index > 0) Spacer(modifier = Modifier.height(ListGroupGap))
                LinkOpenRow(
                    target = target,
                    checked = states[target] == true,
                    shape = listGroupShape(
                        when (index) {
                            0 -> ListGroupPos.FIRST
                            LinkOpenTarget.entries.lastIndex -> ListGroupPos.LAST
                            else -> ListGroupPos.MIDDLE
                        }
                    ),
                    onCheckedChange = { on ->
                        states = states + (target to on)
                        settingsRepo.setLinkOpen(target, on)
                        LinkOpener.apply(context, target, on)
                        SnackbarController.show(
                            if (on) "已接管「${target.label}」的链接" else "已交出「${target.label}」的链接"
                        )
                    }
                )
            }
        }
    }
}

/** 单个注册项：名字 + 域名样例 + 开关（样式与设置页的开关行一致） */
@Composable
private fun LinkOpenRow(
    target: LinkOpenTarget,
    checked: Boolean,
    shape: Shape,
    onCheckedChange: (Boolean) -> Unit
) {
    // ★ 与设置页的开关行同一套规范（#164「设置整行 Switch 语义」）：整行 toggleable + role=Switch，
    //   尾部 Switch 自己不处理点击 —— 这样 TalkBack 读出来的是「夸克网盘，已开启」，
    //   而不是一个没有名字、也不知道开的是什么的开关。
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onValueChange = onCheckedChange
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = target.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = target.sample,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // onCheckedChange = null：点击交给整行处理，避免行与开关各响应一次
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/**
 * 打开系统的「默认打开 / 打开支持的链接」页 —— 用户在那里把网盘域名手动关联给云析。
 *
 * 这是免选择器直开的**唯一**落地方式：域名验证要求每个网盘域名自己托管 assetlinks.json，
 * 第三方 App 做不到（官方文档「在您的应用中打开您的应用无法验证的网域」一节给的就是手动关联）。
 * `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` 是 API 31+，低版本没有这个页面，退到应用详情页。
 */
private fun openLinkHandlingSettings(context: android.content.Context) {
    val pkgUri = Uri.parse("package:${context.packageName}")
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkgUri)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        SnackbarController.show("无法打开系统设置，请手动到「应用信息 → 默认打开」里关联域名")
    }
}
