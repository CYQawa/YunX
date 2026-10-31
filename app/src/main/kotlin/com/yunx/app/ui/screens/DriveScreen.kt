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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.BaiduAccountEntity
import com.yunx.app.data.db.C139AccountEntity
import com.yunx.app.data.db.GuangYaAccountEntity
import com.yunx.app.data.db.ILanzouAccountEntity
import com.yunx.app.data.db.LanzouAccountEntity
import com.yunx.app.data.db.Pan115AccountEntity
import com.yunx.app.data.db.Pan123AccountEntity
import com.yunx.app.data.db.QuarkAccountEntity
import com.yunx.app.data.db.UCAccountEntity
import com.yunx.app.data.db.XunleiAccountEntity
import com.yunx.app.data.db.VideoAccountEntity
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.video.VideoParsers
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.DriveQuotaViewModel
import com.yunx.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunx.app.ui.viewmodel.ILanzouCloudViewModel
import com.yunx.app.ui.viewmodel.LanzouCloudViewModel
import com.yunx.app.ui.viewmodel.MultiAccountViewModel
import com.yunx.app.ui.viewmodel.Pan115CloudViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel
import com.yunx.app.ui.components.YunXWavyProgress
import com.yunx.app.ui.items.drivePlatformLogoRes
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.effectsFast
import com.yunx.app.ui.theme.spatialDefault
import com.yunx.app.ui.theme.spatialFast

/**
 * 网盘账号展示模型。
 * TODO: 迅雷 / UC 后续接入 cookie 登录后，isLoggedIn 由真实登录态驱动。
 */
private data class DriveAccount(
    val id: String,
    val name: String,
    val description: String,
    val avatarText: String,
    val isLoggedIn: Boolean = false
)

/**
 * 账号页的一行网盘账号：渲染数据 + 该行自己的点击行为。
 *
 * ★ 顺序由用户设置决定，所以点击/更多按钮必须和账号**一起**绑定，不能按位置去猜。
 */
private data class DriveAccountItem(
    val account: DriveAccount,
    val quota: QuotaInfo?,
    val onClick: () -> Unit,
    val onMoreClick: (() -> Unit)?
)

/**
 * 已登录才有「更多」按钮：返回 null 时 [DriveAccountCard] 不画三点图标。
 *
 * ★ 单独抽成函数是为了给 lambda 一个明确的 `() -> Unit` 期望类型 ——
 *   `onMoreClick = if (x) ({ ... }) else null` 这种写法在 Kotlin 里依赖类型推断，
 *   条件分支里的 lambda 字面量推断不稳，抽出来后就没有歧义了。
 */
private fun sheetAction(enabled: Boolean, open: () -> Unit): (() -> Unit)? =
    if (enabled) open else null

/**
 * 账号页（原「网盘」页）：
 * - 上半区「网盘账号」：夸克未登录点击进入登录页；已登录时副标题显示昵称，点击弹出账号信息底部弹窗；
 * - 下半区「视频解析」：列出全部视频平台的登录态（见 [VideoAccountSection]），需要登录的平台可登录 / 管理账号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    quarkAccount: QuarkAccountEntity?,
    ucAccount: UCAccountEntity?,
    xunleiAccount: XunleiAccountEntity?,
    baiduAccount: BaiduAccountEntity?,
    c139Account: C139AccountEntity?,
    pan123Account: Pan123AccountEntity?,
    pan115Account: Pan115AccountEntity?,
    guangyaAccount: GuangYaAccountEntity?,
    ilanzouAccount: ILanzouAccountEntity?,
    lanzouAccount: LanzouAccountEntity?,
    /** 夸克云盘浏览 ViewModel（网盘 Tab 内切换展示，非全屏） */
    quarkCloudViewModel: QuarkCloudViewModel,
    /** UC 网盘云盘浏览 ViewModel */
    ucCloudViewModel: UCCoudViewModel,
    /** 迅雷网盘云盘浏览 ViewModel */
    xunleiCloudViewModel: XunleiCloudViewModel,
    /** 百度网盘云盘浏览 ViewModel */
    baiduCloudViewModel: BaiduCloudViewModel,
    /** 139 网盘云盘浏览 ViewModel */
    c139CloudViewModel: C139CloudViewModel,
    /** 123 云盘浏览 ViewModel */
    pan123CloudViewModel: Pan123CloudViewModel,
    /** 115 网盘浏览 ViewModel */
    pan115CloudViewModel: Pan115CloudViewModel,
    /** 光鸭云盘浏览 ViewModel */
    guangyaCloudViewModel: GuangYaCloudViewModel,
    /** 蓝奏云优享版浏览 ViewModel */
    ilanzouCloudViewModel: ILanzouCloudViewModel,
    /** 蓝奏云浏览 ViewModel */
    lanzouCloudViewModel: LanzouCloudViewModel,
    /** 网盘空间详情 ViewModel（顶部空间总览） */
    driveQuotaViewModel: DriveQuotaViewModel,
    /** 多账号（列表 / 设主账号 / 改备注 / 退出）：10 个平台的账号弹窗共用 */
    multiAccountViewModel: MultiAccountViewModel,
    onQuarkLogin: () -> Unit,
    /** 账号弹窗里「添加账号」：复用该平台原来的登录入口（参数为平台标识） */
    onAddAccount: (String) -> Unit,
    /** 夸克云盘下载入队后切换到「下载」Tab */
    onDownloadStarted: () -> Unit = {},
    onUCLogin: () -> Unit,
    onXunleiLogin: () -> Unit,
    onBaiduLogin: () -> Unit,
    onC139Login: () -> Unit,
    onPan123Login: () -> Unit,
    onPan115Login: () -> Unit,
    onGuangYaLogin: () -> Unit,
    onILanzouLogin: () -> Unit,
    onLanzouLogin: () -> Unit,
    /** 是否已配置 GitHub Token（控制 GitHub 卡片副标题与登录态样式） */
    githubHasToken: Boolean = false,
    /** 点击 GitHub 卡片：已配置 Token 时进入「我的主页」（账号仓库浏览）；未配置时打开 Token 管理弹窗 */
    onGitHubTokenClick: () -> Unit = {},
    /** 已配置 Token 时点击卡片主体：进入当前 Token 账号的仓库列表 */
    onGitHubBrowseHome: () -> Unit = {},
    /** 「更多」菜单中清除 Token（二次确认后回调） */
    onGitHubClearToken: () -> Unit = {},
    /** 视频平台登录态（platform → 当前账号）：驱动「视频解析」区的状态与右侧按钮 */
    videoLoginState: Map<VideoPlatform, VideoAccountEntity> = emptyMap(),
    /** 全部平台的视频账号：用于「共 N 个账号」统计与账号弹窗列表 */
    videoAccounts: List<VideoAccountEntity> = emptyList(),
    /** 需要登录的视频平台点「登录」：上层打开该平台的登录页（普通登录，覆盖当前账号） */
    onVideoLogin: (VideoPlatform) -> Unit = {},
    /** 视频账号弹窗「添加账号」：上层打开登录页并让它落成新的一行 */
    onVideoAddAccount: (VideoPlatform) -> Unit = {},
    /** 视频账号弹窗操作：设为主账号 / 改备注 / 退出该账号 / 退出该平台全部账号 */
    onVideoSetPrimary: (VideoPlatform, String) -> Unit = { _, _ -> },
    onVideoRename: (String, String) -> Unit = { _, _ -> },
    onVideoRemove: (String) -> Unit = {},
    onVideoRemoveAll: (VideoPlatform) -> Unit = {},
    /** 顶栏「排序」弹窗是否打开 —— 按钮画在全局顶栏（MainScreen），状态由上层持有 */
    showSortDialog: Boolean = false,
    /** 关闭顶栏「排序」弹窗 */
    onDismissSortDialog: () -> Unit = {},
    /** 账号列表 ↔ 网盘浏览子页切换：上层据此只在账号列表上显示「排序」按钮 */
    onSubPageChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showQuarkSheet by remember { mutableStateOf(false) }
    var showUCSheet by remember { mutableStateOf(false) }
    var showXunleiSheet by remember { mutableStateOf(false) }
    var showBaiduSheet by remember { mutableStateOf(false) }
    var showC139Sheet by remember { mutableStateOf(false) }
    var showPan123Sheet by remember { mutableStateOf(false) }
    var showPan115Sheet by remember { mutableStateOf(false) }
    var showGuangYaSheet by remember { mutableStateOf(false) }
    var showILanzouSheet by remember { mutableStateOf(false) }
    var showLanzouSheet by remember { mutableStateOf(false) }
    // GitHub 卡片「更多」菜单（浏览主页 / 配置 Token / 清除 Token）
    var showGitHubSheet by remember { mutableStateOf(false) }
    // 视频平台账号弹窗：需要登录且已登录的平台点「管理」时记录平台（null = 不展示）
    var showVideoSheet by remember { mutableStateOf<VideoPlatform?>(null) }
    // 夸克云盘浏览：网盘 Tab 内切换（非全屏），切 Tab 再回来仍保留
    var showCloud by rememberSaveable { mutableStateOf(false) }
    // UC 网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showUCCloud by rememberSaveable { mutableStateOf(false) }
    // 迅雷网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showXunleiCloud by rememberSaveable { mutableStateOf(false) }
    // 百度网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showBaiduCloud by rememberSaveable { mutableStateOf(false) }
    // 139 网盘云盘浏览：网盘 Tab 内切换（非全屏）
    var showC139Cloud by rememberSaveable { mutableStateOf(false) }
    // 123 云盘浏览：网盘 Tab 内切换（非全屏）
    var showPan123Cloud by rememberSaveable { mutableStateOf(false) }
    // 115 网盘浏览：网盘 Tab 内切换（非全屏）
    var showPan115Cloud by rememberSaveable { mutableStateOf(false) }
    // 光鸭云盘浏览：网盘 Tab 内切换（非全屏）
    var showGuangYaCloud by rememberSaveable { mutableStateOf(false) }
    // 蓝奏云优享版浏览：网盘 Tab 内切换（非全屏）
    var showILanzouCloud by rememberSaveable { mutableStateOf(false) }
    // 蓝奏云浏览：网盘 Tab 内切换（非全屏）
    var showLanzouCloud by rememberSaveable { mutableStateOf(false) }

    // 网盘账号列表的滚动状态（与 show*Cloud 同在 DriveScreen 生命周期内；AnimatedContent 切页不会丢）
    val accountListState = rememberLazyListState()

    // 排序 / 隐藏未登录：和滚动态一样提升到本层（进网盘子页再回来也不丢），
    // 改动即时落盘、不做「确定/取消」——这个弹窗是设置面板，改完即所见。
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    var driveOrder by remember { mutableStateOf(settings.driveAccountOrder) }
    var hideUnloggedDrives by remember { mutableStateOf(settings.hideUnloggedDrives) }

    // 夸克：登录态由数据库驱动；已登录则副标题显示昵称
    val quark = DriveAccount(
        id = "quark",
        name = "夸克网盘",
        description = quarkAccount?.nickname ?: "点击登录，支持解析下载",
        avatarText = "夸",
        isLoggedIn = quarkAccount != null
    )
    val uc = DriveAccount(
        id = "uc",
        name = "UC网盘",
        description = ucAccount?.nickname ?: "点击登录，支持解析下载",
        avatarText = "UC",
        isLoggedIn = ucAccount != null
    )
    val xunlei = DriveAccount(
        id = "xunlei",
        name = "迅雷网盘",
        description = xunleiAccount?.nickname ?: "点击登录，支持解析下载",
        avatarText = "迅",
        isLoggedIn = xunleiAccount != null
    )
    val baidu = DriveAccount(
        id = "baidu",
        name = "百度网盘",
        description = baiduAccount?.nickname ?: "点击登录，支持解析下载",
        avatarText = "度",
        isLoggedIn = baiduAccount != null
    )
    val c139 = DriveAccount(
        id = "c139",
        name = "139网盘",
        description = c139Account?.nickname ?: "点击登录，支持解析下载",
        avatarText = "139",
        isLoggedIn = c139Account != null
    )
    val pan123 = DriveAccount(
        id = "pan123",
        name = "123云盘",
        description = pan123Account?.nickname ?: "点击登录，支持解析下载",
        avatarText = "123",
        isLoggedIn = pan123Account != null
    )
    val pan115 = DriveAccount(
        id = "pan115",
        name = "115网盘",
        description = pan115Account?.nickname ?: "点击登录，支持解析下载",
        avatarText = "115",
        isLoggedIn = pan115Account != null
    )
    val guangya = DriveAccount(
        id = "guangya",
        name = "光鸭云盘",
        description = guangyaAccount?.nickname ?: "点击登录，支持解析下载",
        avatarText = "光",
        isLoggedIn = guangyaAccount != null
    )
    val ilanzou = DriveAccount(
        id = "ilanzou",
        name = "蓝奏云优享版",
        description = ilanzouAccount?.nickname?.let { maskAccount(it) } ?: "点击登录，支持解析下载",
        avatarText = "蓝优",
        isLoggedIn = ilanzouAccount != null
    )
    val lanzou = DriveAccount(
        id = "lanzou",
        name = "蓝奏云",
        description = lanzouAccount?.nickname?.let { maskAccount(it) } ?: "点击登录，支持解析下载",
        avatarText = "蓝",
        isLoggedIn = lanzouAccount != null
    )
    // GitHub：把 GitHub 当网盘浏览下载；此处仅做 Token 管理（提升 API 限额），浏览入口在解析页
    val github = DriveAccount(
        id = "github",
        name = "GitHub",
        description = if (githubHasToken) "已配置 Token" else "点击配置 Token",
        avatarText = "GH",
        isLoggedIn = githubHasToken
    )
    // 空间额度统一在列表外层 collect：卡片先后顺序变了也不会重复/漏订阅
    val quarkQuota by driveQuotaViewModel.quarkQuota.collectAsState()
    val ucQuota by driveQuotaViewModel.ucQuota.collectAsState()
    val xunleiQuota by driveQuotaViewModel.xunleiQuota.collectAsState()
    val baiduQuota by driveQuotaViewModel.baiduQuota.collectAsState()
    val c139Quota by driveQuotaViewModel.c139Quota.collectAsState()
    val pan123Quota by driveQuotaViewModel.pan123Quota.collectAsState()
    val pan115Quota by driveQuotaViewModel.pan115Quota.collectAsState()
    val guangyaQuota by driveQuotaViewModel.guangyaQuota.collectAsState()
    val ilanzouQuota by driveQuotaViewModel.ilanzouQuota.collectAsState()

    // 网盘账号卡片：数据 + 点击行为一一绑定 —— 顺序只影响渲染先后，不改任何点击语义。
    // ★ GitHub 也参与「隐藏未登录」（未配置 Token 时算未登录）：它只是列表里普通的一行，
    //   隐藏后仍能在排序弹窗里看到并取消隐藏，不会丢入口。
    val driveItems = listOf(
        DriveAccountItem(
            account = quark,
            quota = quarkQuota,
            onClick = {
                if (quark.isLoggedIn) {
                    showCloud = true
                } else {
                    onQuarkLogin()
                }
            },
            onMoreClick = sheetAction(quark.isLoggedIn) { showQuarkSheet = true }
        ),
        DriveAccountItem(
            account = uc,
            quota = ucQuota,
            onClick = {
                if (uc.isLoggedIn) {
                    showUCCloud = true
                } else {
                    onUCLogin()
                }
            },
            onMoreClick = sheetAction(uc.isLoggedIn) { showUCSheet = true }
        ),
        DriveAccountItem(
            account = xunlei,
            quota = xunleiQuota,
            onClick = {
                if (xunlei.isLoggedIn) {
                    showXunleiCloud = true
                } else {
                    onXunleiLogin()
                }
            },
            onMoreClick = sheetAction(xunlei.isLoggedIn) { showXunleiSheet = true }
        ),
        DriveAccountItem(
            account = baidu,
            quota = baiduQuota,
            onClick = {
                if (baidu.isLoggedIn) {
                    showBaiduCloud = true
                } else {
                    onBaiduLogin()
                }
            },
            onMoreClick = sheetAction(baidu.isLoggedIn) { showBaiduSheet = true }
        ),
        DriveAccountItem(
            account = c139,
            quota = c139Quota,
            onClick = {
                if (c139.isLoggedIn) {
                    showC139Cloud = true
                } else {
                    onC139Login()
                }
            },
            onMoreClick = sheetAction(c139.isLoggedIn) { showC139Sheet = true }
        ),
        DriveAccountItem(
            account = pan123,
            quota = pan123Quota,
            onClick = {
                if (pan123.isLoggedIn) {
                    showPan123Cloud = true
                } else {
                    onPan123Login()
                }
            },
            onMoreClick = sheetAction(pan123.isLoggedIn) { showPan123Sheet = true }
        ),
        DriveAccountItem(
            account = pan115,
            quota = pan115Quota,
            onClick = {
                if (pan115.isLoggedIn) {
                    showPan115Cloud = true
                } else {
                    onPan115Login()
                }
            },
            onMoreClick = sheetAction(pan115.isLoggedIn) { showPan115Sheet = true }
        ),
        DriveAccountItem(
            account = guangya,
            quota = guangyaQuota,
            onClick = {
                if (guangya.isLoggedIn) {
                    showGuangYaCloud = true
                } else {
                    onGuangYaLogin()
                }
            },
            onMoreClick = sheetAction(guangya.isLoggedIn) { showGuangYaSheet = true }
        ),
        DriveAccountItem(
            account = ilanzou,
            quota = ilanzouQuota,
            onClick = {
                if (ilanzou.isLoggedIn) {
                    showILanzouCloud = true
                } else {
                    onILanzouLogin()
                }
            },
            onMoreClick = sheetAction(ilanzou.isLoggedIn) { showILanzouSheet = true }
        ),
        DriveAccountItem(
            account = lanzou,
            quota = null,
            onClick = {
                if (lanzou.isLoggedIn) {
                    showLanzouCloud = true
                } else {
                    onLanzouLogin()
                }
            },
            onMoreClick = sheetAction(lanzou.isLoggedIn) { showLanzouSheet = true }
        ),
        DriveAccountItem(
            account = github,
            quota = null,
            onClick = if (githubHasToken) onGitHubBrowseHome else onGitHubTokenClick,
            onMoreClick = sheetAction(githubHasToken) { showGitHubSheet = true }
        )
    )

    // 排序归一化：先按用户保存的名次排；不在保存顺序里的项（新增平台 / 从没排过）
    // 名次并到末尾，且保持它们在默认列表里的前后关系（稳定排序）。
    val rank = driveOrder.withIndex().associate { (index, id) -> id to index }
    // sortedBy 是稳定排序：没保存过名次的项（新增平台）都拿 Int.MAX_VALUE，
    // 会按默认顺序稳定地排在有名字的项后面，不需要再用第二关键字。
    val orderedDriveItems = driveItems.withIndex()
        .sortedBy { rank[it.value.account.id] ?: Int.MAX_VALUE }
        .map { it.value }
    val visibleDriveItems = if (hideUnloggedDrives) {
        orderedDriveItems.filter { it.account.isLoggedIn }
    } else {
        orderedDriveItems
    }
    val hiddenUnloggedCount = if (hideUnloggedDrives) {
        orderedDriveItems.count { !it.account.isLoggedIn }
    } else {
        0
    }
    // 视频解析区共用同一个「隐藏未登录」开关：需要登录且还没登录的平台会被藏起来；
    // 免登录平台不藏（藏了等于把可用功能删掉），所以这里也只统计 needsLogin 的。
    val hiddenVideoCount = if (hideUnloggedDrives) {
        VideoParsers.supportedPlatforms.count { it.needsLogin && videoLoginState[it] == null }
    } else {
        0
    }
    val hiddenHint = listOfNotNull(
        hiddenUnloggedCount.takeIf { it > 0 }?.let { "$it 个未登录的网盘" },
        hiddenVideoCount.takeIf { it > 0 }?.let { "$it 个未登录的视频平台" }
    ).joinToString("、")

    // 进入网盘页加载空间详情（仅已登录平台）
    LaunchedEffect(Unit) {
        driveQuotaViewModel.loadAll()
    }
    // 下拉刷新状态：绑定空间配额加载中状态
    val isRefreshing by driveQuotaViewModel.loading.collectAsState()

    // 当前展示第几页：0=账号列表，1..10=各网盘浏览。提成变量是因为要同时喂给
    // AnimatedContent 和「子页打开时隐藏顶栏排序按钮」的通知（见 onSubPageChange）
    val drivePage = when {
        showCloud -> 1
        showUCCloud -> 2
        showXunleiCloud -> 3
        showBaiduCloud -> 4
        showC139Cloud -> 5
        showPan123Cloud -> 6
        showPan115Cloud -> 7
        showGuangYaCloud -> 8
        showILanzouCloud -> 9
        showLanzouCloud -> 10
        else -> 0
    }
    LaunchedEffect(drivePage) { onSubPageChange(drivePage != 0) }

    // 账号列表 ↔ 夸克云盘 ↔ UC 云盘 ↔ 迅雷云盘 ↔ 百度云盘 ↔ 139 云盘 ↔ 123 云盘：平滑过渡（淡入 + 轻微缩放，不僵硬）
    AnimatedContent(
        targetState = drivePage,
        transitionSpec = {
            (fadeIn(effectsDefault()) + scaleIn(spatialDefault(), initialScale = 0.98f))
                .togetherWith(fadeOut(effectsFast()) + scaleOut(spatialFast(), targetScale = 0.98f))
        },
        label = "driveContent"
    ) { target ->
        when (target) {
            1 -> CloudDriveScreen(
                viewModel = quarkCloudViewModel,
                scrollBehavior = scrollBehavior,
                onExit = { showCloud = false },
                onDownloadStarted = onDownloadStarted
            )
            2 -> UCCoudScreen(
            viewModel = ucCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showUCCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        3 -> XunleiCloudScreen(
            viewModel = xunleiCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showXunleiCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        4 -> BaiduCloudScreen(
            viewModel = baiduCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showBaiduCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        5 -> C139CloudScreen(
            viewModel = c139CloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showC139Cloud = false },
            onDownloadStarted = onDownloadStarted
        )
        6 -> Pan123CloudScreen(
            viewModel = pan123CloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showPan123Cloud = false },
            onDownloadStarted = onDownloadStarted
        )
        7 -> Pan115CloudScreen(
            viewModel = pan115CloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showPan115Cloud = false },
            onDownloadStarted = onDownloadStarted
        )
        8 -> GuangYaCloudScreen(
            viewModel = guangyaCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showGuangYaCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        9 -> ILanzouCloudScreen(
            viewModel = ilanzouCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showILanzouCloud = false },
            onDownloadStarted = onDownloadStarted
        )
        10 -> LanzouCloudScreen(
            viewModel = lanzouCloudViewModel,
            scrollBehavior = scrollBehavior,
            onExit = { showLanzouCloud = false },
            onDownloadStarted = onDownloadStarted
        )
            else -> PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { driveQuotaViewModel.loadAll() },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                // ★ 列表状态**提升到本层**：进入某个网盘页时 AnimatedContent 会把账号列表移出组合，
                //   状态若留在列表里（隐式 rememberLazyListState）就丢了，返回账号页会回滚到顶部
                state = accountListState,
                modifier = modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    AccountSectionLabel("网盘账号")
                }
                item {
                    Text(
                        text = "登录后即可自动携带凭证解析与下载",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                // 顺序 / 隐藏未登录由顶栏「排序」弹窗控制：这里只按设置渲染，
                // 数据与点击行为在 driveItems 里一一绑定（改顺序不会改语义）。
                items(visibleDriveItems, key = { it.account.id }) { item ->
                    DriveAccountCard(
                        account = item.account,
                        quota = item.quota,
                        onClick = item.onClick,
                        onMoreClick = item.onMoreClick
                    )
                }
                if (hiddenHint.isNotEmpty()) {
                    // 别让账号「凭空消失」：明确告诉用户藏了什么、去哪恢复
                    item(key = "drive_hidden_hint") {
                        Text(
                            text = "已隐藏 $hiddenHint，点右上角排序可恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
                // 视频解析：平台列表与登录态全部由 VideoAccountSection 自己呈现，这里只透传数据与回调
                item(key = "video_section_label") {
                    AccountSectionLabel("视频解析")
                }
                item(key = "video_section") {
                    VideoAccountSection(
                        loginState = videoLoginState,
                        accounts = videoAccounts,
                        onLogin = onVideoLogin,
                        onManage = { showVideoSheet = it },
                        hideUnlogged = hideUnloggedDrives
                    )
                }
            }
            }
        }
    }

    // 已登录夸克：点击卡片弹出账号信息底部弹窗（含多账号管理）
    val quarkAccounts by multiAccountViewModel.accountsOf(DownloadPlatform.QUARK).collectAsState()
    if (showQuarkSheet && quarkAccount != null) {
        QuarkAccountSheet(
            account = quarkAccount,
            accounts = quarkAccounts,
            onSetPrimary = { multiAccountViewModel.setPrimary(DownloadPlatform.QUARK, it) },
            onRename = { id, remark -> multiAccountViewModel.rename(DownloadPlatform.QUARK, id, remark) },
            onRemove = { multiAccountViewModel.remove(DownloadPlatform.QUARK, it) },
            onAddAccount = { onAddAccount(DownloadPlatform.QUARK) },
            onDismiss = { showQuarkSheet = false }
        )
    }

    // 已登录 UC：点击卡片弹出账号信息底部弹窗（含多账号管理）
    val ucAccounts by multiAccountViewModel.accountsOf(DownloadPlatform.UC).collectAsState()
    if (showUCSheet && ucAccount != null) {
        UCAccountSheet(
            account = ucAccount,
            accounts = ucAccounts,
            onSetPrimary = { multiAccountViewModel.setPrimary(DownloadPlatform.UC, it) },
            onRename = { id, remark -> multiAccountViewModel.rename(DownloadPlatform.UC, id, remark) },
            onRemove = { multiAccountViewModel.remove(DownloadPlatform.UC, it) },
            onAddAccount = { onAddAccount(DownloadPlatform.UC) },
            onDismiss = { showUCSheet = false }
        )
    }

    // 已登录迅雷：点击卡片弹出账号信息底部弹窗（含多账号管理）
    val xunleiAccounts by multiAccountViewModel.accountsOf(DownloadPlatform.XUNLEI).collectAsState()
    if (showXunleiSheet && xunleiAccount != null) {
        XunleiAccountSheet(
            account = xunleiAccount,
            accounts = xunleiAccounts,
            onSetPrimary = { multiAccountViewModel.setPrimary(DownloadPlatform.XUNLEI, it) },
            onRename = { id, remark -> multiAccountViewModel.rename(DownloadPlatform.XUNLEI, id, remark) },
            onRemove = { multiAccountViewModel.remove(DownloadPlatform.XUNLEI, it) },
            onAddAccount = { onAddAccount(DownloadPlatform.XUNLEI) },
            onDismiss = { showXunleiSheet = false }
        )
    }

    // 已登录百度：点击卡片弹出账号信息底部弹窗（含多账号管理）
    val baiduAccounts by multiAccountViewModel.accountsOf(DownloadPlatform.BAIDU).collectAsState()
    if (showBaiduSheet && baiduAccount != null) {
        BaiduAccountSheet(
            account = baiduAccount,
            accounts = baiduAccounts,
            onSetPrimary = { multiAccountViewModel.setPrimary(DownloadPlatform.BAIDU, it) },
            onRename = { id, remark -> multiAccountViewModel.rename(DownloadPlatform.BAIDU, id, remark) },
            onRemove = { multiAccountViewModel.remove(DownloadPlatform.BAIDU, it) },
            onAddAccount = { onAddAccount(DownloadPlatform.BAIDU) },
            onDismiss = { showBaiduSheet = false }
        )
    }

    // 已登录 139：点击卡片弹出账号信息底部弹窗（含多账号管理）
    val c139Accounts by multiAccountViewModel.accountsOf(DownloadPlatform.C139).collectAsState()
    if (showC139Sheet && c139Account != null) {
        C139AccountSheet(
            account = c139Account,
            accounts = c139Accounts,
            onSetPrimary = { multiAccountViewModel.setPrimary(DownloadPlatform.C139, it) },
            onRename = { id, remark -> multiAccountViewModel.rename(DownloadPlatform.C139, id, remark) },
            onRemove = { multiAccountViewModel.remove(DownloadPlatform.C139, it) },
            onAddAccount = { onAddAccount(DownloadPlatform.C139) },
            onDismiss = { showC139Sheet = false }
        )
    }

    // 已登录 123：点击卡片弹出账号信息底部弹窗（含多账号管理）
    val pan123Accounts by multiAccountViewModel.accountsOf(DownloadPlatform.PAN123).collectAsState()
    if (showPan123Sheet && pan123Account != null) {
        Pan123AccountSheet(
            account = pan123Account,
            accounts = pan123Accounts,
            onSetPrimary = { multiAccountViewModel.setPrimary(DownloadPlatform.PAN123, it) },
            onRename = { id, remark -> multiAccountViewModel.rename(DownloadPlatform.PAN123, id, remark) },
            onRemove = { multiAccountViewModel.remove(DownloadPlatform.PAN123, it) },
            onAddAccount = { onAddAccount(DownloadPlatform.PAN123) },
            onDismiss = { showPan123Sheet = false }
        )
    }

    // 已登录 115：点击卡片弹出账号信息底部弹窗（含多账号管理）
    val pan115Accounts by multiAccountViewModel.accountsOf(DownloadPlatform.PAN115).collectAsState()
    if (showPan115Sheet && pan115Account != null) {
        Pan115AccountSheet(
            account = pan115Account,
            accounts = pan115Accounts,
            onSetPrimary = { multiAccountViewModel.setPrimary(DownloadPlatform.PAN115, it) },
            onRename = { id, remark -> multiAccountViewModel.rename(DownloadPlatform.PAN115, id, remark) },
            onRemove = { multiAccountViewModel.remove(DownloadPlatform.PAN115, it) },
            onAddAccount = { onAddAccount(DownloadPlatform.PAN115) },
            onDismiss = { showPan115Sheet = false }
        )
    }

    // 已登录光鸭：点击卡片弹出账号信息底部弹窗（含多账号管理）
    val guangyaAccounts by multiAccountViewModel.accountsOf(DownloadPlatform.GUANGYA).collectAsState()
    if (showGuangYaSheet && guangyaAccount != null) {
        GuangYaAccountSheet(
            account = guangyaAccount,
            accounts = guangyaAccounts,
            onSetPrimary = { multiAccountViewModel.setPrimary(DownloadPlatform.GUANGYA, it) },
            onRename = { id, remark -> multiAccountViewModel.rename(DownloadPlatform.GUANGYA, id, remark) },
            onRemove = { multiAccountViewModel.remove(DownloadPlatform.GUANGYA, it) },
            onAddAccount = { onAddAccount(DownloadPlatform.GUANGYA) },
            onDismiss = { showGuangYaSheet = false }
        )
    }

    // 已登录蓝奏优享：点击卡片弹出账号信息底部弹窗（含多账号管理）
    val ilanzouAccounts by multiAccountViewModel.accountsOf(DownloadPlatform.ILANZOU).collectAsState()
    if (showILanzouSheet && ilanzouAccount != null) {
        ILanzouAccountSheet(
            account = ilanzouAccount,
            accounts = ilanzouAccounts,
            onSetPrimary = { multiAccountViewModel.setPrimary(DownloadPlatform.ILANZOU, it) },
            onRename = { id, remark -> multiAccountViewModel.rename(DownloadPlatform.ILANZOU, id, remark) },
            onRemove = { multiAccountViewModel.remove(DownloadPlatform.ILANZOU, it) },
            onAddAccount = { onAddAccount(DownloadPlatform.ILANZOU) },
            onDismiss = { showILanzouSheet = false }
        )
    }

    // 已登录蓝奏云：点击卡片弹出账号信息底部弹窗（含多账号管理）
    val lanzouAccounts by multiAccountViewModel.accountsOf(DownloadPlatform.LANZOU).collectAsState()
    if (showLanzouSheet && lanzouAccount != null) {
        LanzouAccountSheet(
            account = lanzouAccount,
            accounts = lanzouAccounts,
            onSetPrimary = { multiAccountViewModel.setPrimary(DownloadPlatform.LANZOU, it) },
            onRename = { id, remark -> multiAccountViewModel.rename(DownloadPlatform.LANZOU, id, remark) },
            onRemove = { multiAccountViewModel.remove(DownloadPlatform.LANZOU, it) },
            onAddAccount = { onAddAccount(DownloadPlatform.LANZOU) },
            onDismiss = { showLanzouSheet = false }
        )
    }

    // 视频平台账号弹窗：需要登录且已登录的平台点「管理」打开
    showVideoSheet?.let { platform ->
        VideoAccountSheet(
            platform = platform,
            accounts = videoAccounts.filter { it.platform == platform.name },
            onSetPrimary = { onVideoSetPrimary(platform, it) },
            onRename = onVideoRename,
            onRemove = onVideoRemove,
            onRemoveAll = { onVideoRemoveAll(platform) },
            onAddAccount = { onVideoAddAccount(platform) },
            onDismiss = { showVideoSheet = null }
        )
    }

    // GitHub 卡片「更多」菜单：浏览我的主页 / 配置 Token / 清除 Token（清除由上层做二次确认）
    if (showGitHubSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showGitHubSheet = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "GitHub",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                // 浏览我的主页
                TextButton(
                    onClick = {
                        showGitHubSheet = false
                        onGitHubBrowseHome()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) { Text("浏览我的主页", modifier = Modifier.fillMaxWidth()) }
                // 配置 Token
                TextButton(
                    onClick = {
                        showGitHubSheet = false
                        onGitHubTokenClick()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) { Text("配置 Token", modifier = Modifier.fillMaxWidth()) }
                // 清除 Token
                TextButton(
                    onClick = {
                        showGitHubSheet = false
                        onGitHubClearToken()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text(
                        "清除 Token",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // 顶栏「排序」入口打开的弹窗：调整顺序 + 隐藏未登录。
    // ★ 传给弹窗的是 orderedDriveItems（含被隐藏的项）：隐藏开关不会把已排序项从弹窗里弄丢，
    //   否则一旦隐藏就再也点不到「恢复」。
    if (showSortDialog) {
        DriveSortDialog(
            accounts = orderedDriveItems.map { it.account },
            hideUnlogged = hideUnloggedDrives,
            onMove = { from, to ->
                val ids = orderedDriveItems.map { it.account.id }.toMutableList()
                if (from in ids.indices && to in ids.indices && from != to) {
                    val moved = ids.removeAt(from)
                    ids.add(to, moved)
                    driveOrder = ids
                    settings.driveAccountOrder = ids
                }
            },
            onToggleHide = { enabled ->
                hideUnloggedDrives = enabled
                settings.hideUnloggedDrives = enabled
            },
            onReset = {
                driveOrder = emptyList()
                settings.driveAccountOrder = emptyList()
            },
            onDismiss = onDismissSortDialog
        )
    }
}

@Composable
private fun DriveAccountCard(
    account: DriveAccount,
    /** 网盘空间详情（已登录且有数据时在卡片内显示进度条）；null 不显示 */
    quota: QuotaInfo? = null,
    onClick: (() -> Unit)? = null,
    /** 已登录时右侧「三个点」更多按钮（打开账号弹窗）；null 则不显示 */
    onMoreClick: (() -> Unit)? = null
) {
    val cardShape = MaterialTheme.shapes.large
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )
    val content: @Composable () -> Unit = {
        DriveAccountCardContent(
            account = account,
            quota = quota,
            clickable = onClick != null,
            onMoreClick = onMoreClick
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

@Composable
private fun DriveAccountCardContent(
    account: DriveAccount,
    quota: QuotaInfo? = null,
    clickable: Boolean,
    onMoreClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 品牌图标：**按 Logo 自身形状原样画**（不裁圆、不垫底色）——这些图标有的是带底色的圆角
        // 方块、有的是透明底图形，统一裁形会裁掉前者的边、给后者糊一圈假底色。
        // 未登录整体压暗，替代原来靠圆形底色区分登录态的做法。
        // ★ 尺寸 40dp = 同一页里视频平台图标的大小（见 VideoAccountSection）：两组混在一页里，
        //   48dp 会让「网盘」这组明显比「视频」那组大一圈（用户反馈「网盘图标有点大了」）。
        val logoRes = drivePlatformLogoRes(account.id)
        if (logoRes != null) {
            Image(
                painter = painterResource(logoRes),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .alpha(if (account.isLoggedIn) 1f else 0.45f),
                contentScale = ContentScale.Fit
            )
        } else {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (account.isLoggedIn) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = account.avatarText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = account.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 已登录且有空间数据：卡片内展示剩余空间进度条（出现时淡入 + 纵向展开，避免突兀）
            AnimatedVisibility(
                visible = account.isLoggedIn && quota != null,
                enter = fadeIn(effectsDefault()) + expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = spatialDefault()
                ),
                exit = fadeOut(effectsFast()) + shrinkVertically(animationSpec = spatialFast())
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    quota?.let { QuotaInlineBar(it) }
                }
            }
        }

        when {
            account.isLoggedIn && onMoreClick != null -> IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            account.isLoggedIn -> LoginBadge(isLoggedIn = true)
            clickable -> Text(
                text = "去登录",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            else -> LoginBadge(isLoggedIn = false)
        }
    }
}

/** 网盘卡片内空间进度条：已用 / 总容量 + 细进度条 */
@Composable
private fun QuotaInlineBar(quota: QuotaInfo) {
    val ratio = if (quota.total > 0) {
        (quota.used.toFloat() / quota.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column {
        Text(
            text = "已用 ${formatBytes(quota.used)} / ${formatBytes(quota.total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        YunXWavyProgress(
            progress = { ratio },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            // 容量占比是静态数据，不滚动波浪（滚动的波浪会让人误以为有任务在进行）
            waving = false
        )
    }
}

@Composable
private fun LoginBadge(isLoggedIn: Boolean) {
    val (label, color) = if (isLoggedIn) {
        "已登录" to MaterialTheme.colorScheme.primary
    } else {
        "未登录" to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 字节数格式化：B / KB / MB / GB / TB */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B"
    else String.format("%.1f %s", value, units[unit])
}

/**
 * 「排序与显示」弹窗：调整网盘账号的先后顺序，并控制是否隐藏未登录的网盘。
 *
 * ★ 用上移/下移箭头而不是拖拽：拖拽要在 AlertDialog 里手写 pointerInput + 逐行位移计算，
 *   而这里只有 11 行、每行都要求点得准，箭头方案的手指目标和可访问性都更稳，也不用引入拖拽库。
 * ★ 改动即时生效（调用方立刻写设置），所以弹窗只有「完成」一个按钮，没有确定/取消语义。
 *
 * @param accounts 全部网盘账号，顺序就是当前显示顺序
 * @param hideUnlogged 是否隐藏未登录的网盘
 * @param onMove 把 from 位置的行移动到 to 位置（相邻即上移/下移一位）
 * @param onToggleHide 切换「隐藏未登录」
 * @param onReset 恢复默认顺序（清空保存的顺序）
 */
@Composable
private fun DriveSortDialog(
    accounts: List<DriveAccount>,
    hideUnlogged: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    onToggleHide: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    // 列表高度跟着屏幕走：竖屏能多显示几行，横屏/小屏自动缩到约 42% 屏高，
    // 否则 11 行顶出去会把「完成」按钮挤出可视区。
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序与显示") },
        text = {
            Column {
                // 整行可点：Switch 本身触摸区不大，整行点能少一脚踩空
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleHide(!hideUnlogged) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "隐藏未登录的网盘", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "只显示已登录的账号，随时可以在这里恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = hideUnlogged, onCheckedChange = onToggleHide)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "顺序",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onReset) { Text("恢复默认") }
                }
                // 11 行会超出 AlertDialog 的默认高度：给高度上限再滚动，别把「完成」顶出屏幕
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    accounts.forEachIndexed { index, account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(22.dp)
                            )
                            Text(
                                text = account.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (!account.isLoggedIn) {
                                Text(
                                    text = "未登录",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                            // 首行禁用上移、末行禁用下移：边界点不动，避免「点了没反应」的疑惑
                            IconButton(
                                onClick = { onMove(index, index - 1) },
                                enabled = index > 0
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "上移 ${account.name}"
                                )
                            }
                            IconButton(
                                onClick = { onMove(index, index + 1) },
                                enabled = index < accounts.lastIndex
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "下移 ${account.name}"
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}
