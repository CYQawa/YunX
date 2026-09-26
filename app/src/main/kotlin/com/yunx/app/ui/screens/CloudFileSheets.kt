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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.ui.resolve.BackToParentItem
import com.yunx.app.ui.resolve.CrumbBar
import com.yunx.app.ui.resolve.ShareFileRow
import com.yunx.app.ui.viewmodel.QuarkCloudUiState
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.components.YunXLoading
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.effectsFast
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.listGroupShape
import com.yunx.app.ui.theme.spatialDefault
import com.yunx.app.ui.theme.spatialFast

/** 文件操作菜单类型（FileActionSheet 内切换） */
private enum class ActionStep { MENU, MOVE, SHARE, RENAME }

/** 有效期选项：名称 + expired_type 值 */
private val expireOptions = listOf(
    "永久有效" to 1,
    "1 天" to 2,
    "7 天" to 3,
    "30 天" to 4
)

/**
 * 文件操作底部弹窗（**六大网盘页共用**）：单弹窗多步骤 —— 菜单 → 移动 / 分享 / 重命名，
 * 每一步都有返回键（[StepHeader]）与步骤切换过渡，与原夸克弹窗的形态一致。
 *
 * 平台差异通过参数注入，不需要各平台再各写一个弹窗：
 * - 分享表单的提交走 [onShare]（各平台 shareFile 参数不同，由调用方适配；139 不支持自定义提取码，
 *   按平台传 [PasscodeMode]）
 * - 移动步骤走 [moveStep] 插槽（各平台的目录浏览状态类型不同，无法共享）
 * - 删除只回调 [onDelete]，由页面弹共享的 [ConfirmDeleteSheet]（全项目唯一的删除确认实现）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileActionSheet(
    file: ShareFile,
    operating: Boolean,
    onDownload: () -> Unit,
    onDownloadFolder: () -> Unit,
    onShare: (withPassword: Boolean, passcode: String, expiredType: Int) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** 提取码规则（各平台不同，见 [PasscodeMode]） */
    passcodeMode: PasscodeMode = PasscodeMode.OPTIONAL,
    moveStep: @Composable (onBack: () -> Unit, onDone: () -> Unit) -> Unit
) {
    var step by remember { mutableStateOf(ActionStep.MENU) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (!operating) onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // 步骤切换统一带过渡（六平台一致）
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast()) },
            label = "fileActionStep"
        ) { current ->
            when (current) {
                ActionStep.MENU -> ActionMenu(
                    file = file,
                    onDownload = {
                        onDownload()
                        onDismiss()
                    },
                    onDownloadFolder = if (file.isdir) {
                        {
                            onDownloadFolder()
                            onDismiss()
                        }
                    } else {
                        null
                    },
                    onShare = { step = ActionStep.SHARE },
                    onMove = { step = ActionStep.MOVE },
                    onRename = { step = ActionStep.RENAME },
                    onDelete = {
                        onDelete()
                        onDismiss()
                    }
                )

                ActionStep.MOVE -> moveStep({ step = ActionStep.MENU }, onDismiss)

                ActionStep.SHARE -> ShareStep(
                    title = "分享文件",
                    subtitle = file.fname,
                    operating = operating,
                    passcodeMode = passcodeMode,
                    onBack = { step = ActionStep.MENU },
                    onCreateShare = onShare
                )

                ActionStep.RENAME -> RenameStep(
                    file = file,
                    operating = operating,
                    onBack = { step = ActionStep.MENU },
                    onDone = onDismiss,
                    onRename = onRename
                )
            }
        }
    }
}

/** 操作菜单主界面 */
@Composable
private fun ActionMenu(
    file: ShareFile,
    onDownload: () -> Unit,
    onDownloadFolder: (() -> Unit)? = null,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (file.isdir) Icons.Outlined.Folder else Icons.Outlined.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fname,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = if (file.isdir) "文件夹" else "文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // 操作项
        if (!file.isdir) {
            ActionItem(
                icon = Icons.Outlined.Download,
                title = "下载",
                desc = "使用内置下载功能保存到本机",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onDownload
            )
        } else if (onDownloadFolder != null) {
            ActionItem(
                icon = Icons.Outlined.Download,
                title = "下载文件夹",
                desc = "递归下载整个文件夹，保持目录结构",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onDownloadFolder
            )
        }
        ActionItem(
            icon = Icons.Outlined.Share,
            title = "分享",
            desc = "生成分享链接（可设提取码/有效期）",
            tint = MaterialTheme.colorScheme.primary,
            onClick = onShare
        )
        ActionItem(
            icon = Icons.Outlined.DriveFileMove,
            title = "移动到",
            desc = "移动到网盘的其他目录",
            tint = MaterialTheme.colorScheme.primary,
            onClick = onMove
        )
        ActionItem(
            icon = Icons.Outlined.Edit,
            title = "重命名",
            desc = "修改文件名",
            tint = MaterialTheme.colorScheme.primary,
            onClick = onRename
        )
        ActionItem(
            icon = Icons.Outlined.Delete,
            title = "删除",
            desc = "移入回收站",
            tint = MaterialTheme.colorScheme.error,
            onClick = onDelete
        )
    }
}

/** 操作项行 */
@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = MaterialTheme.shapes.large,
            color = tint.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 移动：浏览目标目录并确认（独立浏览状态 moveUiState，不影响主列表） */
@Composable
internal fun QuarkMoveStep(
    /** 副标题：单文件传文件名，批量传"已选 N 项" */
    subtitle: String,
    viewModel: QuarkCloudViewModel,
    operating: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    // 进入「移动到」步骤即加载根目录（与其余五个平台的移动步骤一致，调用方不必先触发）
    val moveState by viewModel.moveUiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.openMoveRoot() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        StepHeader(title = "移动到", subtitle = subtitle, onBack = onBack)

        Spacer(modifier = Modifier.height(8.dp))
        CrumbBar(
            rootTitle = "根目录",
            pathNames = (moveState as? QuarkCloudUiState.Loaded)?.pathNames ?: emptyList(),
            onNavigate = { viewModel.moveNavigateToLevel(it) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 返回上一级：固定在目录区上方（不参与 AnimatedContent 过渡，避免与目录内容交叉叠加）
        if ((moveState as? QuarkCloudUiState.Loaded)?.pathNames?.isNotEmpty() == true) {
            BackToParentItem(onClick = { viewModel.moveBack() })
            Spacer(modifier = Modifier.height(4.dp))
        }
        // 移动目录切换：淡入过渡
        AnimatedContent(
            targetState = moveState,
            transitionSpec = { fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast()) },
            label = "moveState"
        ) { s ->
            when (s) {
                is QuarkCloudUiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) { YunXLoading() }

                is QuarkCloudUiState.Error -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) { Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                is QuarkCloudUiState.Loaded -> {
                    val dirs = s.files.filter { it.isdir }
                if (dirs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "当前目录没有子文件夹，可直接移动到此处",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        // 目录列表同样拼成一组
                        verticalArrangement = Arrangement.spacedBy(ListGroupGap)
                    ) {
                        itemsIndexed(dirs, key = { _, d -> d.fid }) { index, dir ->
                            ShareFileRow(
                                file = dir,
                                onClick = { viewModel.openMoveFolder(dir) },
                                shape = listGroupShape(index, dirs.size)
                            )
                        }
                    }
                }
            }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        val dirName = (moveState as? QuarkCloudUiState.Loaded)?.pathNames?.lastOrNull() ?: "根目录"
        Button(
            onClick = {
                val to = (moveState as? QuarkCloudUiState.Loaded)?.dirFid ?: "0"
                viewModel.moveFile(to)
                onDone()
            },
            enabled = !operating,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            if (operating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("移动到此处（$dirName）")
            }
        }
    }
}

/**
 * 提取码规则：**各平台不同，必须区分**（不能一律给"无提取码 / 设置提取码"二选一）：
 * - [OPTIONAL] 可选：夸克 / UC / 123
 * - [REQUIRED] 必填 4 位：百度
 * - [REQUIRED_OR_AUTO] 必填但可留空由服务端生成：迅雷
 * - [SERVER_GENERATED] 服务端自动生成、不可设置：139
 */
internal enum class PasscodeMode { OPTIONAL, REQUIRED, REQUIRED_OR_AUTO, SERVER_GENERATED }

/** 分享：提取码 + 有效期设置（六大网盘页共用，提交走 [onCreateShare]） */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShareStep(
    title: String,
    subtitle: String,
    operating: Boolean,
    passcodeMode: PasscodeMode,
    onBack: () -> Unit,
    onCreateShare: (withPassword: Boolean, passcode: String, expiredType: Int) -> Unit
) {
    var withPassword by remember { mutableStateOf(false) }
    var passcode by remember { mutableStateOf("") }
    var expiredType by remember { mutableStateOf(1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        StepHeader(title = title, subtitle = subtitle, onBack = onBack)

        Spacer(modifier = Modifier.height(16.dp))

        // 提取码：按平台规则渲染（四种规则见 PasscodeMode）
        when (passcodeMode) {
            PasscodeMode.OPTIONAL -> {
                Text("提取码", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                // 间距置 0 + checkedShape 指回未选中形状：避免两段之间出现缝/豁口（选中态由填充色表达）
                ButtonGroup(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    ToggleButton(
                        checked = !withPassword,
                        onCheckedChange = { withPassword = false },
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(
                            checkedShape = ButtonGroupDefaults.connectedLeadingButtonShape
                        )
                    ) {
                        Text("无提取码")
                    }
                    ToggleButton(
                        checked = withPassword,
                        onCheckedChange = {
                            withPassword = true
                            if (passcode.isBlank()) passcode = randomPasscode()
                        },
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(
                            checkedShape = ButtonGroupDefaults.connectedTrailingButtonShape
                        )
                    ) {
                        Text("设置提取码")
                    }
                }
                // 切到「设置提取码」时输入框淡入 + 展开（收起时反向），不再突然出现/消失
                AnimatedVisibility(
                    visible = withPassword,
                    enter = fadeIn(effectsDefault()) + expandVertically(spatialDefault()),
                    exit = fadeOut(effectsFast()) + shrinkVertically(spatialFast())
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        PasscodeField(
                            passcode = passcode,
                            onPasscodeChange = { passcode = it },
                            label = "4 位提取码"
                        )
                    }
                }
            }

            PasscodeMode.REQUIRED, PasscodeMode.REQUIRED_OR_AUTO -> {
                val allowBlank = passcodeMode == PasscodeMode.REQUIRED_OR_AUTO
                Text(
                    text = if (allowBlank) {
                        "分享必须带提取码，可自定义 4 位（留空由服务端生成）"
                    } else {
                        "分享必须带 4 位提取码"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                PasscodeField(
                    passcode = passcode,
                    onPasscodeChange = { passcode = it },
                    label = if (allowBlank) "提取码（4 位字母数字，可留空）" else "提取码（4 位字母数字）"
                )
            }

            PasscodeMode.SERVER_GENERATED -> {
                Text("提取码", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "提取码由服务端自动生成，分享创建后可在结果里查看",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("有效期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            expireOptions.forEach { (name, value) ->
                FilterChip(
                    selected = expiredType == value,
                    onClick = { expiredType = value },
                    label = { Text(name) },
                    colors = FilterChipDefaults.filterChipColors()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                // 提取码规则决定提交内容：
                // - OPTIONAL：按用户选择决定是否带提取码
                // - REQUIRED / REQUIRED_OR_AUTO：一定带（迅雷可留空，由服务端生成）
                // - SERVER_GENERATED：不传提取码
                val withPwd = when (passcodeMode) {
                    PasscodeMode.OPTIONAL -> withPassword
                    PasscodeMode.SERVER_GENERATED -> false
                    else -> true
                }
                onCreateShare(withPwd, passcode, expiredType)
                // 不在此关闭：保留弹窗，等 shareResult 弹出分享结果
            },
            enabled = !operating && when (passcodeMode) {
                PasscodeMode.OPTIONAL -> !withPassword || passcode.length == 4
                PasscodeMode.REQUIRED -> passcode.length == 4
                PasscodeMode.REQUIRED_OR_AUTO -> passcode.isEmpty() || passcode.length == 4
                PasscodeMode.SERVER_GENERATED -> true
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            if (operating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("创建分享")
            }
        }
    }
}

/** 提取码输入框（分享步骤内共用）：限 4 位字母数字 */
@Composable
private fun PasscodeField(
    passcode: String,
    onPasscodeChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = passcode,
        onValueChange = { onPasscodeChange(it.take(4).filter { c -> c.isLetterOrDigit() }) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.large
    )
}

/** 重命名输入（六大网盘页共用，提交走 [onRename]） */
@Composable
private fun RenameStep(
    file: ShareFile,
    operating: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf(file.fname) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        StepHeader(title = "重命名", subtitle = file.fname, onBack = onBack)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("新文件名") },
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                if (name.isNotBlank() && name != file.fname) {
                    onRename(name.trim())
                    onDone()
                } else {
                    onBack()
                }
            },
            enabled = !operating && name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("确认重命名")
        }
    }
}

/**
 * 删除确认底部弹窗（**全项目唯一的删除确认实现**）。
 *
 * 六大网盘页的每一条删除路径都走这里：单文件删除、多选批量删除、操作菜单里的删除。
 * 原先是 6 份 AlertDialog（其中夸克的两份还"弹在底部弹窗之上"），形态与层叠都不一致；
 * 现统一为底部弹窗 —— 自带滑入/淡出过渡，且不会出现"弹窗套弹窗"。
 *
 * @param target 删除对象描述，如「文件名.mp4」或 "选中的 3 项"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmDeleteSheet(
    target: String,
    operating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        // 操作进行中禁止下滑关闭：避免请求已发出、弹窗却先消失造成的状态错乱
        onDismissRequest = { if (!operating) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Text("删除文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "确定要删除$target 吗？删除后将移入回收站。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                enabled = !operating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("删除")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                enabled = !operating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("取消")
            }
        }
    }
}

/** 分享结果：链接 + 提取码 + 复制 */
@Composable
internal fun ShareResultDialog(
    info: com.yunx.app.data.network.model.ShareInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // Dialog 内提示宿主（AlertDialog 为独立窗口）
    val snackbarHostState = rememberGlobalSnackbarHostState()
    // 拼接分享文案（按平台区分：139 / 123 / UC / 迅雷 / 百度 / 夸克）
    val platformName = when {
        info.shareUrl.contains("139.com") -> "139网盘"
        info.shareUrl.contains("123pan") || info.shareUrl.contains("123865") -> "123云盘"
        info.shareUrl.contains("uc.cn") -> "UC网盘"
        info.shareUrl.contains("xunlei.com") -> "迅雷网盘"
        info.shareUrl.contains("baidu.com") -> "百度网盘"
        else -> "夸克网盘"
    }
    val shareText = buildString {
        append("我用${platformName}分享了「${info.title}」\n")
        append("链接：${info.shareUrl}")
        if (info.passcode.isNotBlank()) {
            append("\n提取码：${info.passcode}")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享成功") },
        text = {
            // 横屏/小屏时内容超高可滚动，避免按钮被挤出屏幕
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 等宽展示分享文案，便于整段复制
                Text(
                    text = shareText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "有效期：" + expireLabel(info.expiredType),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Dialog 内提示（AlertDialog 为独立窗口，需自带 Snackbar 宿主）
                SnackbarHost(hostState = snackbarHostState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("share_text", shareText))
                    SnackbarController.show("分享文案已复制")
                }
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("复制全部")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/** 步骤头部：返回按钮 + 标题（六大网盘页的移动步骤也用它，故为 internal） */
@Composable
internal fun StepHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回"
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun randomPasscode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    return (1..4).map { chars.random() }.joinToString("")
}

private fun expireLabel(type: Int): String = when (type) {
    2 -> "1 天"
    3 -> "7 天"
    4 -> "30 天"
    else -> "永久有效"
}

/** 批量操作步骤类型 */
internal enum class BatchStep { MENU, SHARE, MOVE }

/**
 * 批量操作弹窗（长按多选后）：下载 / 分享 / 移动 / 删除。
 * 分享/移动/删除复用与单文件一致的表单与独立目录浏览。
 * @param initialStep 初始步骤（底部栏点击下载/删除直接执行，分享/移动传入对应步骤）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchActionSheet(
    count: Int,
    operating: Boolean,
    onDownload: () -> Unit,
    onShare: (withPassword: Boolean, passcode: String, expiredType: Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** 提取码规则（各平台不同，见 [PasscodeMode]） */
    passcodeMode: PasscodeMode = PasscodeMode.OPTIONAL,
    initialStep: BatchStep = BatchStep.MENU,
    moveStep: @Composable (onBack: () -> Unit, onDone: () -> Unit) -> Unit
) {
    var step by remember { mutableStateOf(initialStep) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (!operating) onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // 步骤切换带过渡：与单文件弹窗一致
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast()) },
            label = "batchActionStep"
        ) { current ->
            when (current) {
                BatchStep.MENU -> BatchMenu(
                    count = count,
                    onDownload = {
                        onDownload()
                        onDismiss()
                    },
                    onShare = { step = BatchStep.SHARE },
                    onMove = { step = BatchStep.MOVE },
                    onDelete = {
                        onDelete()
                        onDismiss()
                    }
                )

                BatchStep.SHARE -> ShareStep(
                    title = "分享文件",
                    subtitle = "已选 $count 项",
                    operating = operating,
                    passcodeMode = passcodeMode,
                    onBack = { step = BatchStep.MENU },
                    onCreateShare = onShare
                )

                BatchStep.MOVE -> moveStep({ step = BatchStep.MENU }, onDismiss)
            }
        }
    }
}

/** 批量操作菜单主界面 */
@Composable
private fun BatchMenu(
    count: Int,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "批量操作",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "已选 $count 项",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        ActionItem(
            icon = Icons.Outlined.Download,
            title = "下载",
            desc = "批量下载到本机",
            tint = MaterialTheme.colorScheme.primary,
            onClick = onDownload
        )
        ActionItem(
            icon = Icons.Outlined.Share,
            title = "分享",
            desc = "将选中项创建为一个分享链接",
            tint = MaterialTheme.colorScheme.primary,
            onClick = onShare
        )
        ActionItem(
            icon = Icons.Outlined.DriveFileMove,
            title = "移动到",
            desc = "批量移动到网盘的其他目录",
            tint = MaterialTheme.colorScheme.primary,
            onClick = onMove
        )
        ActionItem(
            icon = Icons.Outlined.Delete,
            title = "删除",
            desc = "批量移入回收站",
            tint = MaterialTheme.colorScheme.error,
            onClick = onDelete
        )
    }
}
