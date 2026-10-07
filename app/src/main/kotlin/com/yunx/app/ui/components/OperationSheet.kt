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

package com.yunx.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.yunx.app.ui.rememberGlobalSnackbarHostState

internal val LocalFileOperationBusy = staticCompositionLocalOf { false }

/** 在状态迁移前阻止隐藏，避免只拦截回调后面板已经消失。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberOperationSheetState(busy: Boolean): SheetState {
    val currentBusy by rememberUpdatedState(busy)
    return rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !currentBusy }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OperationSheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberOperationSheetState(busy),
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = { if (!busy) onDismiss() },
        sheetGesturesEnabled = !busy,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = !busy,
            shouldDismissOnClickOutside = !busy
        ),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        CompositionLocalProvider(LocalFileOperationBusy provides busy) {
            Column(Modifier.weight(1f, fill = false)) { content() }
            SnackbarHost(rememberGlobalSnackbarHostState())
        }
    }
}
