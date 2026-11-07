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

import android.app.Activity
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.yunx.app.ui.theme.AppLanguage
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.ListGroupPos
import com.yunx.app.ui.theme.LocaleController
import com.yunx.app.ui.theme.listGroupShape

/**
 * 「语言」设置页：应用内界面语言的单选列表。
 *
 * ★ 语言名称始终以各自原文显示（简体中文 / 繁體中文 / English / 日本語 / Français），
 *   这样即使误切到看不懂的语言，也能在列表里找回自己的语言。
 *   切换后 [LocaleController.setLanguage] 落盘并立刻 `recreate()` 重建 Activity，
 *   让 `attachBaseContext` 里的 [LocaleController.wrap] 按新语言重新解析资源 —— 无需重启应用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleLarge) },
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
                Text(
                    text = stringResource(R.string.language_page_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            val options = AppLanguage.values()
            options.forEachIndexed { index, option ->
                if (index > 0) Spacer(modifier = Modifier.height(ListGroupGap))
                LanguageRow(
                    option = option,
                    selected = LocaleController.language == option,
                    shape = listGroupShape(
                        when (index) {
                            0 -> ListGroupPos.FIRST
                            options.lastIndex -> ListGroupPos.LAST
                            else -> ListGroupPos.MIDDLE
                        }
                    ),
                    onSelect = {
                        if (LocaleController.language != option) {
                            LocaleController.setLanguage(context, option)
                            (context as? Activity)?.recreate()
                        }
                    }
                )
            }
        }
    }
}

/** 单个语言选项行：整行 selectable + role=RadioButton，尾部 RadioButton 自身不处理点击 */
@Composable
private fun LanguageRow(
    option: AppLanguage,
    selected: Boolean,
    shape: Shape,
    onSelect: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onSelect
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
            Text(
                text = stringResource(option.labelRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            // onClick = null：点击交给整行处理，避免行与单选框各响应一次
            RadioButton(selected = selected, onClick = null)
        }
    }
}
