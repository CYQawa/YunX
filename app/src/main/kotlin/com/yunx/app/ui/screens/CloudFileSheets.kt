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
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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

/** 文件操作菜单类型（FileActionSheet 内切换） */
private enum class ActionStep { MENU, MOVE, SHARE, RENAME, DELETE }

/** 有效期选项：名称 + expired_type 值 */
private val expireOptions = listOf(
    "永久有效" to 1,
    "1 天" to 2,
    "7 天" to 3,
    "30 天" to 4
)

/**
 * 夸克云盘文件操作弹窗：更多按钮 → 操作菜单（下载/分享/移动/重命名/删除），
 * 内部按步骤切换：移动选目录 / 分享设置 / 重命名输入 / 删除确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionSheet(
    file: ShareFile,
    viewModel: QuarkCloudViewModel,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf(ActionStep.MENU) }
    // 移动目标浏览用独立状态（moveUiState），不影响主列表
    val moveState by viewModel.moveUiState.collectAsState()
    val operating = viewModel.isOperating

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (!operating) onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        when (step) {
            ActionStep.MENU -> ActionMenu(
                file = file,
                onDownload = {
                    viewModel.downloadFile()
                    onDismiss()
                },
                onDownloadFolder = {
                    viewModel.downloadFolder()
                    onDismiss()
                },
                onShare = { step = ActionStep.SHARE },
                onMove = {
                    viewModel.openMoveRoot()
                    step = ActionStep.MOVE
                },
                onRename = { step = ActionStep.RENAME },
                onDelete = { step = ActionStep.DELETE }
            )

            ActionStep.MOVE -> MoveStep(
                file = file,
                viewModel = viewModel,
                moveState = moveState,
                operating = operating,
                onBack = { step = ActionStep.MENU },
                onDone = onDismiss
            )

            ActionStep.SHARE -> ShareStep(
                file = file,
                viewModel = viewModel,
                operating = operating,
                onBack = { step = ActionStep.MENU }
            )

            ActionStep.RENAME -> RenameStep(
                file = file,
                viewModel = viewModel,
                operating = operating,
                onBack = { step = ActionStep.MENU },
                onDone = onDismiss
            )

            ActionStep.DELETE -> DeleteStep(
                file = file,
                viewModel = viewModel,
                operating = operating,
                onBack = { step = ActionStep.MENU },
                onDone = onDismiss
            )
        }
    }

    // 分享创建成功：展示链接与提取码（可复制）
    viewModel.shareResult?.let { info ->
        ShareResultDialog(
            info = info,
            onDismiss = { viewModel.dismissShareResult() }
        )
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
private fun MoveStep(
    file: ShareFile,
    viewModel: QuarkCloudViewModel,
    moveState: QuarkCloudUiState,
    operating: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        StepHeader(title = "移动到", subtitle = file.fname, onBack = onBack)

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
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            label = "moveState"
        ) { s ->
            when (s) {
                is QuarkCloudUiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

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
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(dirs, key = { it.fid }) { dir ->
                            ShareFileRow(file = dir, onClick = { viewModel.openMoveFolder(dir) })
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

/** 分享：提取码 + 有效期设置 */
@Composable
private fun ShareStep(
    file: ShareFile,
    viewModel: QuarkCloudViewModel,
    operating: Boolean,
    onBack: () -> Unit
) {
    var withPassword by remember { mutableStateOf(false) }
    var passcode by remember { mutableStateOf("") }
    var expiredType by remember { mutableStateOf(1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        StepHeader(title = "分享文件", subtitle = file.fname, onBack = onBack)

        Spacer(modifier = Modifier.height(16.dp))

        Text("提取码", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !withPassword,
                onClick = { withPassword = false },
                label = { Text("无提取码") },
                colors = FilterChipDefaults.filterChipColors()
            )
            FilterChip(
                selected = withPassword,
                onClick = {
                    withPassword = true
                    if (passcode.isBlank()) passcode = randomPasscode()
                },
                label = { Text("设置提取码") },
                colors = FilterChipDefaults.filterChipColors()
            )
        }
        if (withPassword) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = passcode,
                onValueChange = { passcode = it.take(4).filter { c -> c.isLetterOrDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("4 位提取码") },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
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
                viewModel.shareFile(
                    urlType = if (withPassword) 2 else 1,
                    passcode = passcode,
                    expiredType = expiredType
                )
                // 不在此关闭：保留弹窗，等 shareResult 弹出分享结果
            },
            enabled = !operating && (!withPassword || passcode.length == 4),
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

/** 重命名输入 */
@Composable
private fun RenameStep(
    file: ShareFile,
    viewModel: QuarkCloudViewModel,
    operating: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit
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
                    viewModel.renameFile(name.trim())
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

/** 删除确认 */
@Composable
private fun DeleteStep(
    file: ShareFile,
    viewModel: QuarkCloudViewModel,
    operating: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!operating) onBack() },
        title = { Text("删除文件") },
        text = { Text("确定要删除「${file.fname}」吗？删除后将移入回收站。") },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.deleteFile()
                    onDone()
                },
                enabled = !operating
            ) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text("取消") }
        }
    )
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
            Column {
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

/** 步骤头部：返回按钮 + 标题 */
@Composable
private fun StepHeader(title: String, subtitle: String, onBack: () -> Unit) {
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
internal enum class BatchStep { MENU, SHARE, MOVE, DELETE }

/**
 * 批量操作弹窗（长按多选后）：下载 / 分享 / 移动 / 删除。
 * 分享/移动/删除复用与单文件一致的表单与独立目录浏览。
 * @param initialStep 初始步骤（底部栏点击下载/删除直接执行，分享/移动传入对应步骤）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchActionSheet(
    viewModel: QuarkCloudViewModel,
    onDismiss: () -> Unit,
    initialStep: BatchStep = BatchStep.MENU
) {
    var step by remember { mutableStateOf(initialStep) }
    val moveState by viewModel.moveUiState.collectAsState()
    val operating = viewModel.isOperating
    val count = viewModel.selected.size

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (!operating) onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        when (step) {
            BatchStep.MENU -> BatchMenu(
                count = count,
                onDownload = {
                    viewModel.downloadSelected()
                    onDismiss()
                },
                onShare = { step = BatchStep.SHARE },
                onMove = {
                    viewModel.openMoveRoot()
                    step = BatchStep.MOVE
                },
                onDelete = { step = BatchStep.DELETE }
            )

            BatchStep.SHARE -> BatchShareStep(
                count = count,
                viewModel = viewModel,
                operating = operating,
                onBack = { step = BatchStep.MENU }
            )

            BatchStep.MOVE -> BatchMoveStep(
                count = count,
                viewModel = viewModel,
                moveState = moveState,
                operating = operating,
                onBack = { step = BatchStep.MENU },
                onDone = onDismiss
            )

            BatchStep.DELETE -> BatchDeleteStep(
                count = count,
                viewModel = viewModel,
                operating = operating,
                onBack = { step = BatchStep.MENU },
                onDone = onDismiss
            )
        }
    }

    // 分享创建成功：展示链接与提取码（保留弹窗以正常显示）
    viewModel.shareResult?.let { info ->
        ShareResultDialog(
            info = info,
            onDismiss = { viewModel.dismissShareResult() }
        )
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

/** 批量分享：提取码 + 有效期 */
@Composable
private fun BatchShareStep(
    count: Int,
    viewModel: QuarkCloudViewModel,
    operating: Boolean,
    onBack: () -> Unit
) {
    var withPassword by remember { mutableStateOf(false) }
    var passcode by remember { mutableStateOf("") }
    var expiredType by remember { mutableStateOf(1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        StepHeader(title = "分享文件", subtitle = "已选 $count 项", onBack = onBack)

        Spacer(modifier = Modifier.height(16.dp))

        Text("提取码", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !withPassword,
                onClick = { withPassword = false },
                label = { Text("无提取码") },
                colors = FilterChipDefaults.filterChipColors()
            )
            FilterChip(
                selected = withPassword,
                onClick = {
                    withPassword = true
                    if (passcode.isBlank()) passcode = randomPasscode()
                },
                label = { Text("设置提取码") },
                colors = FilterChipDefaults.filterChipColors()
            )
        }
        if (withPassword) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = passcode,
                onValueChange = { passcode = it.take(4).filter { c -> c.isLetterOrDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("4 位提取码") },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
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
                viewModel.shareSelected(
                    urlType = if (withPassword) 2 else 1,
                    passcode = passcode,
                    expiredType = expiredType
                )
                // 不关闭：等 shareResult 弹出分享结果
            },
            enabled = !operating && (!withPassword || passcode.length == 4),
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

/** 批量移动：浏览目标目录并确认 */
@Composable
private fun BatchMoveStep(
    count: Int,
    viewModel: QuarkCloudViewModel,
    moveState: QuarkCloudUiState,
    operating: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    // 首次进入该步骤：加载移动目标根目录（否则 moveUiState 停留在 Loading 一直转圈）
    LaunchedEffect(Unit) {
        viewModel.openMoveRoot()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        StepHeader(title = "移动到", subtitle = "已选 $count 项", onBack = onBack)

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
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            label = "batchMoveState"
        ) { s ->
            when (s) {
                is QuarkCloudUiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

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
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(dirs, key = { it.fid }) { dir ->
                            ShareFileRow(file = dir, onClick = { viewModel.openMoveFolder(dir) })
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
                viewModel.moveSelected(to)
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

/** 批量删除确认 */
@Composable
private fun BatchDeleteStep(
    count: Int,
    viewModel: QuarkCloudViewModel,
    operating: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!operating) onBack() },
        title = { Text("删除文件") },
        text = { Text("确定要删除选中的 $count 项吗？删除后将移入回收站。") },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.deleteSelected()
                    onDone()
                },
                enabled = !operating
            ) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text("取消") }
        }
    )
}