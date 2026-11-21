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

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunx.app.R
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.DownloadManagerHolder
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.C139DeviceInfo
import com.yunx.app.data.network.C139KoulingParser
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.data.network.GuangYaApi
import com.yunx.app.data.network.Pan115Api
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.repository.BaiduAccountRepository
import com.yunx.app.data.repository.C139AccountRepository
import com.yunx.app.data.repository.GuangYaAccountRepository
import com.yunx.app.data.repository.Pan115AccountRepository
import com.yunx.app.data.repository.Pan123AccountRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.XunleiAccountRepository
import com.yunx.app.ui.screens.BaiduSaveContent
import com.yunx.app.ui.screens.C139SaveContent
import com.yunx.app.ui.screens.GuangYaSaveContent
import com.yunx.app.ui.screens.Pan115SaveContent
import com.yunx.app.ui.screens.Pan123SaveContent
import com.yunx.app.ui.screens.SaveToCloudContent
import com.yunx.app.ui.screens.UCSaveContent
import com.yunx.app.ui.screens.XunleiSaveContent
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunx.app.ui.viewmodel.Pan115CloudViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel
import kotlinx.coroutines.flow.map
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.items.logoRes
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.ui.viewmodel.ResolveUiState
import com.yunx.app.ui.viewmodel.ResolveViewModel
import androidx.compose.ui.platform.LocalDensity

/**
 * 控制中心快捷磁贴唤起的轻量解析悬浮窗。
 *
 * 全程运行在独立的透明悬浮窗口中，不进入主界面；
 * 支持剪贴板识别卡片、一键解析、文件详情查看，并在触发下载后直接在后台启动下载并优雅关闭退出。
 */
class FloatResolveActivity : ComponentActivity() {

    // 窗口获焦与前台触发器：通知栏收起完毕、窗口真正取得焦点后触发剪贴板检测
    private val focusTrigger = mutableStateOf(0)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            focusTrigger.value++
        }
    }

    override fun onResume() {
        super.onResume()
        focusTrigger.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_FloatingDialog)
        super.onCreate(savedInstanceState)
        com.yunx.app.ui.theme.ThemeController.init(this)
        enableEdgeToEdge()

        setContent {
            ComposeEmptyActivityTheme {
                val viewModel: ResolveViewModel = viewModel(
                    factory = ResolveViewModel.Factory.createDefault(this)
                )

                // 监听下载入队事件：入队后弹轻提示并直接关闭退出悬浮窗
                LaunchedEffect(viewModel.downloadStarted) {
                    if (viewModel.downloadStarted) {
                        viewModel.consumeDownloadStarted()
                        Toast.makeText(
                            this@FloatResolveActivity,
                            getString(R.string.dl_snack_redownload_added),
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }

                // 下载 / 转存的失败提示（游客转存拦截、取链失败、直链过期等）：浮窗用 Toast 轻提示
                LaunchedEffect(viewModel.downloadError) {
                    viewModel.downloadError?.let { error ->
                        viewModel.consumeDownloadError()
                        Toast.makeText(this@FloatResolveActivity, error, Toast.LENGTH_SHORT).show()
                    }
                }

                FloatResolveScreen(
                    viewModel = viewModel,
                    focusTrigger = focusTrigger.value,
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
fun FloatResolveScreen(
    viewModel: ResolveViewModel,
    focusTrigger: Int,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uiState = viewModel.uiState

    var link by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }
    var ignoredClipboard by remember { mutableStateOf<String?>(null) }
    // 文件行展开态上提到此作用域：转存往返（Detail→Save→Detail）不丢展开状态
    var expandedFileFid by remember { mutableStateOf<String?>(null) }
    // 转场位移 8dp 转 px（Density 捕获进 AnimatedContent 的 transitionSpec）
    val slidePx = with(LocalDensity.current) { 8.dp.roundToPx() }
    // 非文件列表状态（输入/加载/视频/错误）时清空展开态，避免下次进列表残留
    LaunchedEffect(uiState) {
        if (uiState !is ResolveUiState.Detail) expandedFileFid = null
    }

    // 剪贴板检测核心函数：前台有焦点时读取
    val detectClipboard: () -> Unit = {
        val enabled = SettingsRepository(context).clipboardSuggestEnabled
        if (enabled && uiState is ResolveUiState.Idle) {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                if (!text.isNullOrBlank() && text != link && text != ignoredClipboard) {
                    if (ShareLinkParser.parse(text) != null ||
                        GitHubLinkParser.parse(text) != null ||
                        VideoLinkParser.looksLikeVideoLink(text) ||
                        C139KoulingParser.parse(text) != null ||
                        com.yunx.app.data.network.XunleiKouling.looksLikeKouling(text) ||
                        com.yunx.app.data.download.MagnetLink.isMagnet(text)
                    ) {
                        clipboardSuggestion = text
                    }
                }
            }
        }
    }

    // 窗口获焦触发 + 延迟重试（应对 Android 10+ 控制中心收起动效延迟，确保获得 Window Focus 后能读到）
    LaunchedEffect(focusTrigger) {
        detectClipboard()
        kotlinx.coroutines.delay(200)
        detectClipboard()
        kotlinx.coroutines.delay(500)
        detectClipboard()
    }

    // 监听剪贴板变更与恢复前台事件
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            detectClipboard()
        }
        cm?.addPrimaryClipChangedListener(clipListener)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                detectClipboard()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            cm?.removePrimaryClipChangedListener(clipListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 监听获取下载直链成功的事件并直接开始下载
    LaunchedEffect(viewModel.downloadLink) {
        val linkObj = viewModel.downloadLink
        if (linkObj != null) {
            viewModel.dismissDownloadDialog()
            viewModel.startDownload(linkObj)
        }
    }

    // 最外层暗化全屏蒙层：点击外部空白直接退出
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            ),
        contentAlignment = Alignment.Center
    ) {
        // 中间浮动圆角卡片：阻断点击事件穿透到遮罩
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 580.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
            // 内容切换极简过渡：120ms 淡入淡出 + 8dp 上移（内容只读 lambda 传入的 state，不读外层 uiState，避免转场期两个 slot 内容错乱）
            AnimatedContent(
                targetState = uiState,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(tween(120)) + slideInVertically(tween(120)) { slidePx }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(120)) { slidePx })
                }
            ) { targetState ->
                when (val state = targetState) {
                    is ResolveUiState.Idle, is ResolveUiState.Error -> {
                        FloatInputContent(
                            link = link,
                            pwd = pwd,
                            onLinkChange = { link = it },
                            onPwdChange = { pwd = it },
                            onStartResolve = {
                                if (link.isNotBlank()) {
                                    viewModel.startVideoResolve(link.trim(), pwd.trim().takeIf { it.isNotEmpty() })
                                }
                            },
                            errorMessage = (state as? ResolveUiState.Error)?.message,
                            clipboardSuggestion = clipboardSuggestion,
                            onPasteSuggestion = { suggestion, parsedPwd ->
                                link = suggestion
                                if (!parsedPwd.isNullOrBlank()) pwd = parsedPwd
                                clipboardSuggestion = null
                                viewModel.startVideoResolve(suggestion, parsedPwd)
                            },
                            onDismissSuggestion = {
                                ignoredClipboard = clipboardSuggestion
                                clipboardSuggestion = null
                            },
                            onClose = onClose
                        )
                    }

                    is ResolveUiState.Loading -> {
                        FloatLoadingContent(
                            onCancel = { viewModel.backToInput() }
                        )
                    }

                    is ResolveUiState.Detail -> {
                        if (viewModel.saveTarget != null) {
                            // 转存步骤：平台目录选择器（转存成功后 ViewModel 清空 saveTarget 自动回列表）
                            FloatSaveContent(
                                viewModel = viewModel,
                                onBack = { viewModel.dismissSave() },
                                onDone = { }
                            )
                        } else {
                            FloatDetailContent(
                                sessionTitle = state.session.title.ifBlank { stringResource(R.string.rs_share_content) },
                                files = state.files,
                                onFileDownload = { file ->
                                    if (file.isdir) {
                                        viewModel.openFolder(file)
                                    } else {
                                        viewModel.fetchDownloadLink(file)
                                    }
                                },
                                // 仅支持转存的平台显示转存按钮（GitHub / 视频结果没有转存）；游客点击由 ViewModel 拦下并 Toast 提示
                                onSave = if (viewModel.canSave) {
                                    { file -> viewModel.requestSave(file) }
                                } else {
                                    null
                                },
                                saving = viewModel.isSaving,
                                expandedFile = expandedFileFid,
                                onExpandedChange = { expandedFileFid = it },
                                // 与主界面一致（ResolveScreen:311）：根目录时返回输入页，子目录时返回上一级
                                onBack = {
                                    viewModel.navigateBack()
                                }
                            )
                        }
                    }

                    is ResolveUiState.Video -> {
                        FloatVideoContent(
                            title = state.result.title,
                            medias = state.result.medias,
                            onDownloadMedia = { media ->
                                viewModel.downloadVideoMedia(media)
                            },
                            onBack = { viewModel.backToInput() }
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * 图一：解析输入界面（附带图二的悬浮剪贴板建议卡片）
 */
@Composable
private fun FloatInputContent(
    link: String,
    pwd: String,
    onLinkChange: (String) -> Unit,
    onPwdChange: (String) -> Unit,
    onStartResolve: () -> Unit,
    errorMessage: String?,
    clipboardSuggestion: String?,
    onPasteSuggestion: (text: String, pwd: String?) -> Unit,
    onDismissSuggestion: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 顶栏：标题与关闭按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.tab_resolve),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.rs_input_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 图二：剪贴板检测到的分享链接提示卡片
        AnimatedVisibility(
            visible = clipboardSuggestion != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            clipboardSuggestion?.let { suggestion ->
                val shareParsed = ShareLinkParser.parse(suggestion)
                val c139Parsed = C139KoulingParser.parse(suggestion)
                val githubParsed = GitHubLinkParser.parse(suggestion)
                val videoParsed = VideoLinkParser.parse(suggestion)
                val isXunlei = com.yunx.app.data.network.XunleiKouling.looksLikeKouling(suggestion)

                val platformName = when {
                    videoParsed != null -> videoParsed.platform.displayName
                    githubParsed != null -> "GitHub"
                    shareParsed != null -> shareParsed.platform.displayName
                    c139Parsed != null -> SharePlatform.C139.displayName
                    isXunlei -> SharePlatform.XUNLEI.displayName
                    com.yunx.app.data.download.MagnetLink.isMagnet(suggestion) -> "磁力链接"
                    else -> stringResource(R.string.rs_platform_generic)
                }
                val platformLogo = when {
                    videoParsed != null -> videoParsed.platform.logoRes()
                    githubParsed != null -> SharePlatform.GITHUB.logoRes()
                    shareParsed != null -> shareParsed.platform.logoRes()
                    c139Parsed != null -> SharePlatform.C139.logoRes()
                    isXunlei -> SharePlatform.XUNLEI.logoRes()
                    else -> null
                }
                val parsedPwd = shareParsed?.pwd ?: c139Parsed?.password

                FloatClipboardSuggestCard(
                    platformName = platformName,
                    platformLogo = platformLogo,
                    onPaste = { onPasteSuggestion(suggestion, parsedPwd) },
                    onDismiss = onDismissSuggestion
                )
            }
        }

        // 链接输入框
        OutlinedTextField(
            value = link,
            onValueChange = onLinkChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.rs_input_placeholder),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            minLines = 3,
            maxLines = 5,
            shape = MaterialTheme.shapes.large
        )

        // 提取码输入框
        OutlinedTextField(
            value = pwd,
            onValueChange = onPwdChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rs_extract_code_optional)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )

        // 错误提示
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // 开始解析大按钮
        Button(
            onClick = onStartResolve,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = link.isNotBlank(),
            shape = MaterialTheme.shapes.large
        ) {
            Text(
                text = stringResource(R.string.rs_start_resolve),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 图二：剪贴板建议卡片
 */
@Composable
private fun FloatClipboardSuggestCard(
    platformName: String,
    @DrawableRes platformLogo: Int?,
    onPaste: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (platformLogo != null) {
                    Image(
                        painter = painterResource(platformLogo),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.rs_detected_share, platformName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stringResource(R.string.rs_paste_and_resolve_q),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.rs_ignore),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onPaste) {
                    Text(stringResource(R.string.rs_paste_and_resolve))
                }
            }
        }
    }
}

/**
 * 解析加载中
 */
@Composable
private fun FloatLoadingContent(
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.rs_collecting_files),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/**
 * 解析结果文件列表展示
 */
@Composable
private fun FloatDetailContent(
    sessionTitle: String,
    files: List<ShareFile>,
    onFileDownload: (ShareFile) -> Unit,
    /** 转存回调；null = 当前平台不支持转存（GitHub / 视频结果），不显示转存按钮 */
    onSave: ((ShareFile) -> Unit)?,
    /** 转存请求进行中：期间禁用行内按钮，避免重复触发 */
    saving: Boolean,
    /** 当前展开的文件行 fid（null = 全部收起） */
    expandedFile: String?,
    /** 行点击切换展开态回调 */
    onExpandedChange: (String?) -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null
                )
            }
            Text(
                text = sessionTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.rs_folder_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                items(files, key = { it.fid }) { file ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 文件夹行点击直接进目录；文件行点击切换展开态（面板内动作另行执行）
                                .clickable {
                                    when {
                                        file.isdir -> onFileDownload(file)
                                        expandedFile == file.fid -> onExpandedChange(null)
                                        else -> onExpandedChange(file.fid)
                                    }
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (file.isdir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                                contentDescription = null,
                                tint = if (file.isdir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.fname,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!file.isdir) {
                                    Text(
                                        text = formatFileSize(context, file.fsize),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (onSave != null) {
                                // 行内「转存」按钮：打开平台目录选择器；与主界面文件操作弹窗同一图标与文案
                                IconButton(
                                    onClick = { onSave(file) },
                                    enabled = !saving
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.SaveAlt,
                                        contentDescription = stringResource(R.string.sh_save_to_cloud),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (!file.isdir) {
                                IconButton(onClick = { onFileDownload(file) }, enabled = !saving) {
                                    Icon(
                                        imageVector = Icons.Outlined.Download,
                                        contentDescription = stringResource(R.string.dl_start_download),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // 行内下展面板：点击文件行展开，两行动作列表（下载 / 转存）；文件夹行不展开
                        AnimatedVisibility(
                            visible = expandedFile == file.fid && !file.isdir,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 8.dp, bottom = 8.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onFileDownload(file)
                                            onExpandedChange(null)
                                        }
                                        .padding(vertical = 8.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Download,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.dl_start_download),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                if (onSave != null) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onSave(file)
                                                onExpandedChange(null)
                                            }
                                            .padding(vertical = 8.dp, horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.SaveAlt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = stringResource(R.string.sh_save_to_cloud),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 视频解析结果展示
 */
@Composable
private fun FloatVideoContent(
    title: String,
    medias: List<VideoMedia>,
    onDownloadMedia: (VideoMedia) -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null
                )
            }
            Text(
                text = title.ifBlank { stringResource(R.string.rs_online_preview) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        ) {
            items(medias) { media ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDownloadMedia(media) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = media.fileName.ifBlank { media.url.substringAfterLast('/') },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (media.size > 0) {
                            Text(
                                text = formatFileSize(context, media.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { onDownloadMedia(media) }) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = stringResource(R.string.dl_start_download),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private fun formatFileSize(context: Context, bytes: Long): String =
    if (bytes <= 0) "--" else Formatter.formatFileSize(context, bytes)

/**
 * 悬浮窗内的转存目录选择宿主：按当前分享平台惰性创建对应平台的 CloudViewModel
 * （只在转存时创建需要的那一个），并复用主界面同款的平台目录选择器。
 *
 * - 转存成功后 [ResolveViewModel.saveToCloud] 会清空 [ResolveViewModel.saveTarget]，
 *   这里侦听到后回调 [onDone] 回到文件列表；失败 / 未登录则保留，让用户重试。
 * - 返回箭头回文件列表（转存进行中禁用，避免中途取消造成状态残留）。
 */
@Composable
private fun FloatSaveContent(
    viewModel: ResolveViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val deps = remember { FloatSaveDeps(context) }

    LaunchedEffect(viewModel.saveTarget) {
        if (viewModel.saveTarget == null) onDone()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, enabled = !viewModel.isSaving) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null
                )
            }
            Text(
                text = stringResource(R.string.sh_save_to_cloud),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 与主界面文件操作弹窗的 saveStep 同一套分支（见 ShareDetailScreen.saveStep）
        when {
            viewModel.isSaveXunlei -> XunleiSaveContent(
                viewModel,
                viewModel(factory = XunleiCloudViewModel.Factory(
                    deps.xunleiApi,
                    { deps.xunleiRepository.getAccount()?.accessToken },
                    { deps.xunleiRepository.getAccount()?.deviceId },
                    { deps.xunleiRepository.getAccount()?.captchaToken },
                    deps.downloadManager,
                    loginState = deps.xunleiLoginState
                )),
                onBack
            )

            viewModel.isSaveBaidu -> BaiduSaveContent(
                viewModel,
                viewModel(factory = BaiduCloudViewModel.Factory(
                    deps.baiduApi,
                    { deps.baiduRepository.getAccount()?.cookie },
                    deps.downloadManager,
                    loginState = deps.baiduLoginState
                )),
                onBack
            )

            viewModel.isSaveC139 -> C139SaveContent(
                viewModel,
                viewModel(factory = C139CloudViewModel.Factory(
                    deps.c139Api,
                    { deps.c139Repository.getAccount()?.cookie },
                    deps.downloadManager,
                    loginState = deps.c139LoginState
                )),
                onBack
            )

            viewModel.isSaveUC -> UCSaveContent(
                viewModel,
                viewModel(factory = UCCoudViewModel.Factory(
                    deps.ucApi,
                    { deps.ucRepository.getFreshCookie() },
                    deps.downloadManager,
                    loginState = deps.ucLoginState
                )),
                onBack
            )

            viewModel.isSavePan123 -> Pan123SaveContent(
                viewModel,
                viewModel(factory = Pan123CloudViewModel.Factory(
                    deps.pan123Api,
                    { deps.pan123Repository.getAccount()?.accessToken },
                    deps.downloadManager,
                    loginState = deps.pan123LoginState
                )),
                onBack
            )

            viewModel.isSavePan115 -> Pan115SaveContent(
                viewModel,
                viewModel(factory = Pan115CloudViewModel.Factory(
                    deps.pan115Api,
                    { deps.pan115Repository.getAccount()?.cookie },
                    deps.downloadManager,
                    loginState = deps.pan115LoginState
                )),
                onBack
            )

            viewModel.isSaveGuangYa -> GuangYaSaveContent(
                viewModel,
                viewModel(factory = GuangYaCloudViewModel.Factory(
                    deps.guangyaApi,
                    deps.guangyaRepository,
                    deps.downloadManager,
                    loginState = deps.guangyaLoginState
                )),
                onBack
            )

            else -> SaveToCloudContent(
                viewModel,
                viewModel(factory = QuarkCloudViewModel.Factory(
                    deps.quarkApi,
                    { deps.quarkRepository.getFreshCookie() },
                    deps.downloadManager,
                    loginState = deps.quarkLoginState
                )),
                onBack
            )
        }
    }
}

/**
 * 悬浮窗转存所需的平台依赖集合：一次构建 8 套轻量 API 与账号仓库
 * （与 MainScreen 的装配同款；CloudViewModel 按 when 分支惰性创建，只实例化当前平台那一个）。
 * 蓝奏两族不在转存支持范围内（canSave 不含蓝奏），不装配。
 */
private class FloatSaveDeps(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    val downloadManager = DownloadManagerHolder.get(appContext)

    val quarkApi = QuarkApi()
    val quarkRepository = QuarkAccountRepository(db.quarkAccountDao(), quarkApi)
    val quarkLoginState = quarkRepository.observeAccount().map { it != null }

    val ucApi = UCApi()
    val ucRepository = UCAccountRepository(db.ucAccountDao(), ucApi)
    val ucLoginState = ucRepository.observeAccount().map { it != null }

    val xunleiApi = XunleiApi()
    val xunleiRepository = XunleiAccountRepository(db.xunleiAccountDao(), xunleiApi)
    val xunleiLoginState = xunleiRepository.observeAccount().map { it != null }

    init {
        // 迅雷 access_token 过期（401 unauthenticated）自动刷新：refresh_token 换新并持久化（对齐官方 /v1/auth/token 抓包）
        xunleiApi.refreshTokenProvider = { deviceId ->
            val acc = xunleiRepository.getAccount()
            if (acc == null || acc.refreshToken.isBlank()) null
            else xunleiApi.refreshToken(acc.refreshToken, deviceId, acc.authType)?.also { (at, nrt) ->
                xunleiRepository.updateTokens(at, nrt)
            }
        }
        // 网页登录的 captcha：本地 9 盐签名现换（快、后台可用），签名被拒才回退网页会话
        xunleiApi.webCaptchaRefresher = { deviceId, action ->
            xunleiRepository.refreshWebCaptcha(deviceId, action)
        }
    }

    val baiduApi = BaiduApi()
    val baiduRepository = BaiduAccountRepository(db.baiduAccountDao(), baiduApi)
    val baiduLoginState = baiduRepository.observeAccount().map { it != null }

    val c139Api = C139Api(deviceInfoProvider = { C139DeviceInfo.from(appContext) })
    val c139Repository = C139AccountRepository(db.c139AccountDao())
    val c139LoginState = c139Repository.observeAccount().map { it != null }

    val pan123Api = Pan123Api()
    val pan123Repository = Pan123AccountRepository(db.pan123AccountDao(), pan123Api)
    val pan123LoginState = pan123Repository.observeAccount().map { it != null }

    val pan115Api = Pan115Api()
    val pan115Repository = Pan115AccountRepository(db.pan115AccountDao(), pan115Api)
    val pan115LoginState = pan115Repository.observeAccount().map { it != null }

    val guangyaApi = GuangYaApi()
    val guangyaRepository = GuangYaAccountRepository(db.guangyaAccountDao(), guangyaApi)
    val guangyaLoginState = guangyaRepository.observeAccount().map { it != null }

    init {
        // 光鸭业务 API（个人盘 / 分享）必须带 did 设备头：从仓库缓存的 deviceId 同步注入
        guangyaApi.deviceIdProvider = { guangyaRepository.cachedDeviceId() }
    }
}
