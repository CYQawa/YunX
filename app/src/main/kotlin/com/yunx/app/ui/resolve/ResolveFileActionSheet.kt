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

package com.yunx.app.ui.resolve

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.ui.screens.ActionItem
import com.yunx.app.ui.screens.FileSheetHeader
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.effectsFast
import kotlinx.coroutines.launch

/** 解析页文件操作弹窗内的步骤：主菜单 / 转存目录选择 */
private enum class ResolveActionStep { MENU, SAVE }

/**
 * 解析页文件操作弹窗（点击文件行打开；文件夹行由行尾「更多」按钮打开）。
 *
 * 与网盘页的 [com.yunx.app.ui.screens.FileActionSheet] 保持同一形态与过渡：
 * 同为 M3 [ModalBottomSheet] + 顶部文件信息（[FileSheetHeader]）+ [ActionItem] 操作项，
 * 区别只在可做的事 —— 分享里的文件不属于自己，没有分享/移动/重命名/删除。
 *
 * 过渡：
 * - 「转存」是**本弹窗内的第二级步骤**（与网盘页的「移动到」完全一致）：内容淡入淡出切换，
 *   不再关掉弹窗另开一个转存弹窗；返回箭头回到主菜单。
 * - 下载类动作没有第二级内容（下载链接弹窗是独立弹窗），先把弹窗滑下去
 *   （[androidx.compose.material3.SheetState.hide]）再执行，避免两个弹窗叠在一起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResolveFileActionSheet(
    file: ShareFile,
    /** 当前分享是否支持转存（GitHub 分享没有转存）；false 时只显示下载 */
    canSave: Boolean,
    /** 转存进行中：期间不允许下滑关闭、也不允许返回菜单（与网盘页的 operating 一致） */
    saving: Boolean,
    /** 文件：获取下载链接并下载；文件夹不会走到这里 */
    onDownload: () -> Unit,
    /** 文件夹：递归下载整个文件夹 */
    onDownloadFolder: () -> Unit,
    /** 点「转存」时准备转存状态（`ResolveViewModel.requestSave`），随后切到转存步骤 */
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    /** 「转存」步骤内容（六大平台的目录选择器，共用 `SaveStepScaffold`）；onBack 回菜单，onDone 关闭弹窗 */
    saveStep: @Composable (onBack: () -> Unit, onDone: () -> Unit) -> Unit
) {
    var step by remember { mutableStateOf(ResolveActionStep.MENU) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    /** 先播放退场动画再执行动作（动作里会关掉本弹窗，故不能直接调 onDismiss） */
    fun act(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // 步骤切换统一带过渡（与网盘页文件操作弹窗的菜单→移动到/分享 一致）
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast()) },
            label = "resolveActionStep"
        ) { current ->
            when (current) {
                ResolveActionStep.MENU -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
                ) {
                    // 顶部：图标 + 文件名 + 类型（与网盘页文件操作弹窗同一组件，观感一致）
                    FileSheetHeader(file = file)

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // 操作项：文件夹是「下载文件夹」，文件是「下载」（取链 → 下载链接弹窗 → 开始下载）
                    if (file.isdir) {
                        ActionItem(
                            icon = Icons.Outlined.Download,
                            title = "下载文件夹",
                            desc = "递归下载整个文件夹，保持目录结构",
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = { act(onDownloadFolder) }
                        )
                    } else {
                        ActionItem(
                            icon = Icons.Outlined.Download,
                            title = "下载",
                            desc = "获取下载链接并保存到本机",
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = { act(onDownload) }
                        )
                    }

                    if (canSave) {
                        ActionItem(
                            icon = Icons.Outlined.SaveAlt,
                            title = "转存",
                            desc = if (file.isdir) "把整个文件夹存到我的网盘" else "把文件存到我的网盘",
                            tint = MaterialTheme.colorScheme.primary,
                            // 不关弹窗：先准备转存状态，再切到转存步骤（内容淡入）
                            onClick = {
                                onSave()
                                step = ResolveActionStep.SAVE
                            }
                        )
                    }
                }

                // 转存步骤：转存中不允许返回菜单（返回后进度提示会随内容一起消失）
                ResolveActionStep.SAVE -> saveStep(
                    { if (!saving) step = ResolveActionStep.MENU },
                    onDismiss
                )
            }
        }
    }
}
