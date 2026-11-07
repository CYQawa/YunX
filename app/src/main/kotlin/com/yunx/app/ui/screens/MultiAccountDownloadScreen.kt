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

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.R
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.ExperimentalBadge
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.ListGroupPos
import com.yunx.app.ui.theme.listGroupShape
import com.yunx.app.util.AppStrings

/**
 * 「多账号下载」设置子页：两个**实验性**开关。
 *
 * - C1 多账号下载：批量下载时每个文件轮换用不同账号取链，绕开单账号的并发/限速；
 * - C2 单文件多源分片：同一个文件用多个账号各取一条直链、分片轮流交给不同直链（依赖 C1）。
 *
 * ★ 两个开关都改的是「取链/下载策略」，异常时只会表现为风控（验证码、限流），用户很难自己
 *   反推是哪一项造成的，所以开启前一律先弹二次风险确认；关 C1 时顺带强制关 C2（见 [SettingsRepository.disableMultiSource]）。
 * ★ 这里每次切换都做两件事：改本地状态（驱动 UI）、写 prefs（真正生效的口径），两者必须同步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiAccountDownloadScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    // 进页面读一次：之后以本地状态为准（同时写回 prefs）
    var multiAccount by remember { mutableStateOf(settingsRepo.multiAccountDownload) }
    var multiSource by remember { mutableStateOf(settingsRepo.multiSourceDownload) }
    // 非 null = 正在二次确认哪一项（只有「开启」才需要确认，关闭直接生效）
    var pendingRisk by remember { mutableStateOf<SwitchRisk?>(null) }

    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dl_ma_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                        text = stringResource(R.string.dl_ma_intro),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dl_ma_desc1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dl_ma_desc2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dl_ma_desc3),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            MultiAccountSwitchRow(
                title = stringResource(R.string.dl_ma_title),
                subtitle = stringResource(R.string.dl_ma_subtitle),
                checked = multiAccount,
                shape = listGroupShape(ListGroupPos.FIRST),
                badge = stringResource(R.string.dl_badge_experimental),
                onCheckedChange = { on ->
                    if (on) {
                        // 开启前二次风险确认：取消时什么都没写，状态不变
                        pendingRisk = SwitchRisk.MULTI_ACCOUNT
                    } else {
                        val multiSourceWasOn = multiSource
                        multiAccount = false
                        settingsRepo.multiAccountDownload = false
                        // ★ C2 依赖 C1：关 C1 必须同时关 C2（prefs + 本地状态），
                        //   否则会留下「多账号下载关了、单文件还在多账号取链」的不一致状态
                        settingsRepo.disableMultiSource()
                        multiSource = false
                        SnackbarController.show(
                            if (multiSourceWasOn) {
                                AppStrings.get(R.string.dl_ma_off_both)
                            } else {
                                AppStrings.get(R.string.dl_ma_off)
                            }
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(ListGroupGap))

            MultiAccountSwitchRow(
                title = stringResource(R.string.dl_ms_title),
                subtitle = if (multiAccount) {
                    stringResource(R.string.dl_ms_subtitle)
                } else {
                    // C2 依赖 C1：C1 关闭时这一项不可操作，副标题直接说明原因（而不是留个点不动的开关）
                    stringResource(R.string.dl_ms_needs_ma)
                },
                checked = multiSource,
                shape = listGroupShape(ListGroupPos.LAST),
                enabled = multiAccount,
                badge = stringResource(R.string.dl_badge_experimental),
                onCheckedChange = { on ->
                    if (on) {
                        pendingRisk = SwitchRisk.MULTI_SOURCE
                    } else {
                        multiSource = false
                        settingsRepo.multiSourceDownload = false
                        SnackbarController.show(AppStrings.get(R.string.dl_ms_off))
                    }
                }
            )
        }
    }

    pendingRisk?.let { risk ->
        AlertDialog(
            onDismissRequest = { pendingRisk = null },
            title = { Text(stringResource(risk.titleRes)) },
            text = { Text(stringResource(risk.messageRes)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (risk) {
                            SwitchRisk.MULTI_ACCOUNT -> {
                                multiAccount = true
                                settingsRepo.multiAccountDownload = true
                                SnackbarController.show(AppStrings.get(R.string.dl_ma_on))
                            }
                            SwitchRisk.MULTI_SOURCE -> {
                                multiSource = true
                                settingsRepo.multiSourceDownload = true
                                SnackbarController.show(AppStrings.get(R.string.dl_ms_on))
                            }
                        }
                        pendingRisk = null
                    }
                ) {
                    Text(text = stringResource(R.string.dl_confirm_enable), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRisk = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * 需要二次风险确认的两个开关（确认弹窗的标题与正文按它取，见 [SwitchRisk.messageRes]）。
 *
 * 用枚举而不是两个布尔状态：弹窗同一时刻只会有一个，语义上不会出现「两个都待确认」的非法态。
 */
private enum class SwitchRisk(val titleRes: Int, val messageRes: Int) {
    // 参数顺序 = (titleRes, messageRes)
    MULTI_ACCOUNT(
        R.string.dl_ma_confirm_title,
        R.string.dl_ma_confirm_message
    ),
    MULTI_SOURCE(
        R.string.dl_ms_confirm_title,
        R.string.dl_ms_confirm_message
    )
}

/** 单个开关行：标题 + 副标题 + 尾部开关（样式与 LinkOpenScreen 的行、设置页的开关行一致） */
@Composable
private fun MultiAccountSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    shape: Shape,
    enabled: Boolean = true,
    /** 标题行右端的小徽标（两个开关都是实验性功能）；null 时不显示 */
    badge: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    // ★ 与设置页的开关行同一套规范（#164「设置整行 Switch 语义」）：整行 toggleable + role=Switch，
    //   尾部 Switch 自己不处理点击 —— 这样 TalkBack 读出来的是「多账号下载，已开启」，
    //   而不是一个没有名字、也不知道开的是什么的开关。
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(
                value = checked,
                // enabled=false 时整行连水波纹都没有，用户点不动也听不出焦点（C2 依赖 C1）
                enabled = enabled,
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
                // 标题行：标题占满剩余宽度后放徽标 ⇒ 徽标落在标题行右端（右上角），
                // 与「下载引擎」页 Gopeed 段的实验性徽标同位置同样式
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ExperimentalBadge(badge)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // onCheckedChange = null：点击交给整行处理，避免行与开关各响应一次
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}
