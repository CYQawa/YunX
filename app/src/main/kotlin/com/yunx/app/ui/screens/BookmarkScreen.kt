package com.yunx.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.BookmarkEntity
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.ui.viewmodel.BookmarkViewModel

/** 「自定义分类」虚拟选项标识（不参与持久化，仅用于弹窗交互） */
private const val CUSTOM_CATEGORY = "__custom__"

/**
 * 收藏网盘链接页：分类筛选 + 收藏列表，支持新增 / 解析 / 复制 / 修改分类 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkScreen(
    viewModel: BookmarkViewModel,
    onBack: () -> Unit,
    /** 点击收藏 → 关闭本页并切到解析页自动解析该链接 */
    onResolve: (link: String, pwd: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bookmarks by viewModel.bookmarks.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val context = LocalContext.current

    // 独立全屏覆盖页：自带 Snackbar 宿主（覆盖层会遮挡主页 Scaffold 的 SnackbarHost）
    val snackbarHostState = rememberGlobalSnackbarHostState()

    // null 表示「全部」
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }
    var menuBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }

    val filtered = remember(bookmarks, selectedCategory) {
        val cat = selectedCategory
        if (cat == null) bookmarks else bookmarks.filter { it.category == cat }
    }

    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("收藏网盘链接", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "添加收藏")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 分类筛选：作为列表头部，随列表一起上下滚动
            item(key = "categories") {
                CategoryFilterBar(
                    categories = categories,
                    selected = selectedCategory,
                    onSelect = { selectedCategory = it }
                )
            }

            if (filtered.isEmpty()) {
                item(key = "empty") {
                    EmptyBookmark(onAdd = { showAddDialog = true })
                }
            } else {
                items(filtered, key = { it.id }) { bookmark ->
                    BookmarkRow(
                        bookmark = bookmark,
                        onClick = { onResolve(bookmark.link, bookmark.pwd) },
                        onLongClick = { menuBookmark = bookmark }
                    )
                }
            }
        }
    }

    // 添加收藏弹窗
    if (showAddDialog) {
        AddBookmarkDialog(
            categories = categories,
            onConfirm = { link, title, category, pwd ->
                showAddDialog = false
                val parsed = ShareLinkParser.parse(link)
                viewModel.addBookmark(
                    link = link,
                    title = title,
                    platform = parsed?.platform?.name.orEmpty(),
                    pwd = pwd.ifBlank { parsed?.pwd.orEmpty() },
                    category = category
                )
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // 修改分类弹窗
    editingBookmark?.let { bookmark ->
        EditCategoryDialog(
            currentCategory = bookmark.category,
            categories = categories,
            onConfirm = { category ->
                viewModel.updateCategory(bookmark.id, category)
                editingBookmark = null
            },
            onDismiss = { editingBookmark = null }
        )
    }

    // 长按操作菜单
    menuBookmark?.let { bookmark ->
        BookmarkMenuDialog(
            bookmark = bookmark,
            onResolve = {
                menuBookmark = null
                onResolve(bookmark.link, bookmark.pwd)
            },
            onCopy = {
                menuBookmark = null
                copyToClipboard(context, bookmark.link)
                com.yunx.app.ui.SnackbarController.show("链接已复制")
            },
            onEditCategory = {
                menuBookmark = null
                editingBookmark = bookmark
            },
            onDelete = {
                menuBookmark = null
                viewModel.delete(bookmark.id)
            },
            onDismiss = { menuBookmark = null }
        )
    }
}

/** 分类筛选胶囊：全部 + 各分类 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategoryFilterBar(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("全部") }
        )
        categories.forEach { cat ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelect(cat) },
                label = { Text(cat) }
            )
        }
    }
}

/** 收藏列表项：平台 / 分类标签 + 标题 + 链接，点击解析、长按打开菜单 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    bookmark: BookmarkEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bookmark.platform.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = bookmarkPlatformLabel(bookmark.platform),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = bookmark.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = bookmark.title.ifBlank { bookmark.link },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (bookmark.title.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = bookmark.link,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 空状态 */
@Composable
private fun EmptyBookmark(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.BookmarkBorder,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "还没有收藏任何网盘链接",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "可在解析页点击「添加至收藏」，或点击右上角 + 手动添加",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("添加收藏")
        }
    }
}

/** 新增收藏弹窗：链接 + 标题 + 提取码 + 分类 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddBookmarkDialog(
    categories: List<String>,
    onConfirm: (link: String, title: String, category: String, pwd: String) -> Unit,
    onDismiss: () -> Unit
) {
    var link by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(BookmarkEntity.DEFAULT_CATEGORY) }
    var customCategory by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加收藏") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("网盘链接") },
                    placeholder = { Text("粘贴分享链接") },
                    leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                    minLines = 2,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题（可选）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("提取码（可选）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
                Text(
                    text = "分类",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) }
                        )
                    }
                }
                OutlinedTextField(
                    value = customCategory,
                    onValueChange = { customCategory = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义分类（可选）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
            }
        },
        confirmButton = {
            Button(
                enabled = link.isNotBlank(),
                onClick = {
                    onConfirm(
                        link.trim(),
                        title.trim(),
                        customCategory.ifBlank { selectedCategory },
                        pwd.trim()
                    )
                }
            ) { Text("收藏") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 解析详情页「添加至收藏」弹窗：可自定义标题；分类选择含「自定义」选项，点击后展开输入框 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddToBookmarkDialog(
    title: String,
    initialCategory: String,
    categories: List<String>,
    onConfirm: (title: String, category: String) -> Unit,
    onDismiss: () -> Unit
) {
    var titleInput by remember { mutableStateOf(title) }
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var customCategory by remember { mutableStateOf("") }
    val isCustom = selectedCategory == CUSTOM_CATEGORY

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加至收藏") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题（可选）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
                Text(
                    text = "分类",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) }
                        )
                    }
                    FilterChip(
                        selected = isCustom,
                        onClick = { selectedCategory = CUSTOM_CATEGORY },
                        label = { Text("自定义") }
                    )
                }
                AnimatedVisibility(
                    visible = isCustom,
                    enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                ) {
                    OutlinedTextField(
                        value = customCategory,
                        onValueChange = { customCategory = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自定义分类") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !(isCustom && customCategory.isBlank()),
                onClick = {
                    onConfirm(
                        titleInput.trim(),
                        if (isCustom) customCategory.trim() else selectedCategory
                    )
                }
            ) { Text("收藏") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 修改分类弹窗 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditCategoryDialog(
    currentCategory: String,
    categories: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(currentCategory) }
    var customCategory by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) }
                        )
                    }
                }
                OutlinedTextField(
                    value = customCategory,
                    onValueChange = { customCategory = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义分类（可选）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(customCategory.ifBlank { selectedCategory }) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 长按操作菜单 */
@Composable
private fun BookmarkMenuDialog(
    bookmark: BookmarkEntity,
    onResolve: () -> Unit,
    onCopy: () -> Unit,
    onEditCategory: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = bookmark.title.ifBlank { bookmark.link },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                TextButton(onClick = onResolve, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("解析")
                }
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("复制链接")
                }
                TextButton(onClick = onEditCategory, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("修改分类")
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 平台枚举名 → 展示名 */
internal fun bookmarkPlatformLabel(platform: String): String = when (platform) {
    "QUARK" -> "夸克网盘"
    "UC" -> "UC网盘"
    "XUNLEI" -> "迅雷网盘"
    "BAIDU" -> "百度网盘"
    "C139" -> "139网盘"
    "PAN123" -> "123云盘"
    else -> "网盘"
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("yunx_bookmark", text))
}