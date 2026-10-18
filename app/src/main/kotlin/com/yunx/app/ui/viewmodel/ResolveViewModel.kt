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

package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.BookmarkDao
import com.yunx.app.data.db.BookmarkEntity
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPathPolicy
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.DownloadSource
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.GuangYaConstants
import com.yunx.app.data.network.ILanzouConstants
import com.yunx.app.data.network.LanzouConstants
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubCommitDateCache
import com.yunx.app.data.network.GitHubResponseCache
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.data.network.GitHubLinkType
import com.yunx.app.data.network.GitHubRepo
import com.yunx.app.data.network.GitHubRelease
import com.yunx.app.data.network.Pan115Constants
import com.yunx.app.data.network.Pan123Constants
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.QuarkCdn
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.StarSeaApi
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.XunleiKouling
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.data.network.video.VideoParseException
import com.yunx.app.data.network.video.VideoParseResult
import com.yunx.app.data.network.video.userMessage
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.data.repository.BaiduAccountRepository
import com.yunx.app.data.repository.BaiduResolveRepository
import com.yunx.app.data.repository.C139AccountRepository
import com.yunx.app.data.repository.C139ResolveRepository
import com.yunx.app.data.repository.GuangYaAccountRepository
import com.yunx.app.data.repository.GuangYaResolveRepository
import com.yunx.app.data.repository.LanzouAccountRepository
import com.yunx.app.data.repository.ILanzouAccountRepository
import com.yunx.app.data.repository.ILanzouResolveRepository
import com.yunx.app.data.repository.LanzouResolveRepository
import com.yunx.app.data.repository.Pan115AccountRepository
import com.yunx.app.data.repository.Pan115ResolveRepository
import com.yunx.app.data.repository.Pan123AccountRepository
import com.yunx.app.data.repository.Pan123ResolveRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.ShareResolveRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiAccountRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import com.yunx.app.data.repository.VideoParseRepository
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.preview.PreviewKind
import com.yunx.app.ui.preview.PreviewLauncher
import com.yunx.app.ui.preview.PreviewMedia
import com.yunx.app.ui.preview.PreviewTypes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val QUARK_GUEST_MAX_BYTES = 50L * 1024 * 1024

sealed interface ResolveUiState {
    data object Idle : ResolveUiState
    data object Loading : ResolveUiState
    data class Detail(val session: ShareSession, val files: List<ShareFile>) : ResolveUiState
    data class Error(val message: String) : ResolveUiState

    /** 视频解析结果（抖音/小红书/快手/微博/AcFun/B站/Pixiv，与网盘流程互不干扰） */
    data class Video(val result: VideoParseResult) : ResolveUiState
}

/**
 * 解析页 ViewModel：分享解析状态机 + 目录导航 + 下载直链。
 * 支持夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115，按链接自动路由到对应平台仓库与凭证。
 */
class ResolveViewModel(
    private val accountRepository: QuarkAccountRepository,
    private val resolveRepository: QuarkResolveRepository,
    private val ucAccountRepository: UCAccountRepository,
    private val ucResolveRepository: UCResolveRepository,
    private val xunleiAccountRepository: XunleiAccountRepository,
    private val xunleiResolveRepository: XunleiResolveRepository,
    private val baiduAccountRepository: BaiduAccountRepository,
    private val baiduResolveRepository: BaiduResolveRepository,
    private val c139AccountRepository: C139AccountRepository,
    private val c139ResolveRepository: C139ResolveRepository,
    private val pan123AccountRepository: Pan123AccountRepository,
    private val pan123ResolveRepository: Pan123ResolveRepository,
    private val pan115AccountRepository: Pan115AccountRepository,
    private val pan115ResolveRepository: Pan115ResolveRepository,
    private val guangyaAccountRepository: GuangYaAccountRepository,
    private val guangyaResolveRepository: GuangYaResolveRepository,
    private val ilanzouAccountRepository: ILanzouAccountRepository,
    private val ilanzouResolveRepository: ILanzouResolveRepository,
    private val lanzouAccountRepository: LanzouAccountRepository,
    private val lanzouResolveRepository: LanzouResolveRepository,
    private val downloadManager: DownloadManager,
    private val bookmarkDao: BookmarkDao,
    /** GitHub 解析器实例（null 表示未启用 GitHub 平台） */
    private val githubApi: GitHubApi? = null,
    /** GitHub 下载镜像前缀提供者（由上层从 SettingsRepository 注入用户配置） */
    private val mirrorPrefixProvider: () -> String = { UpdateChecker.MIRROR_PREFIX },
    /**
     * 取链方式提供者（由上层从 SettingsRepository 注入）：
     * true = 夸克免转存（直接换直链，不写入网盘，默认）；false = 先转存到临时目录再取链。
     * 用 lambda 而不是构造时取值，设置页改了立即生效。
     */
    private val noSaveDownloadProvider: () -> Boolean = { true },
    /**
     * 多账号下载（实验性 C1）开关：由上层从 SettingsRepository 注入，改设置即时生效。
     * 开启后批量下载会按文件轮换该网盘的不同账号取链，绕开单账号的并发数 / 限速
     * （每个文件仍只用一个账号下载），见 [credentialRotation]。
     */
    private val multiAccountDownloadProvider: () -> Boolean = { false },
    /**
     * 单文件多源分片（实验性 C2）开关：由上层从 SettingsRepository 注入
     * （工厂侧用 `multiSourceActive()` 判断，已保证 C2 生效时 C1 也开着）。
     * 开启后同一个文件用多个账号各取一条直链，分片轮流交给不同直链下载，见 [fetchMultiSources]。
     */
    private val multiSourceDownloadProvider: () -> Boolean = { false },
    /**
     * 视频解析仓库（抖音/小红书/快手/微博/AcFun/B站/Pixiv）。
     * null = 视频解析不可用（仅测试或未注入时）：解析入口会原样退回网盘/GitHub 流程。
     * 放在构造最后并带默认值，避免破坏既有构造点与测试。
     */
    private val videoParseRepository: VideoParseRepository? = null
) : ViewModel() {

    var uiState by mutableStateOf<ResolveUiState>(ResolveUiState.Idle)
        private set

    /**
     * 游客模式：本次解析没有携带任何登录凭据（列目录可用，下载/转存需登录）。
     * 7 个网盘的分享**列表**接口都允许匿名（123/139/百度/夸克/UC/115 直接匿名；迅雷走不带
     * Authorization 的分享接口），因此解析不再要求登录；下载/转存仍在各自入口要求登录。
     * 详见 Agent.md §3.19。
     */
    var isGuest by mutableStateOf(false)
        private set

    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set

    /**
     * 单文件多源分片（实验性 C2）预取的备选直链（直链 + 该账号凭证）。
     * 与 [downloadLink] 在同一时刻取回、由紧接着的那次 [startDownload] 消费后清空 ——
     * 直链签名有时效，不能等用户点「下载」时再去取。
     */
    private var pendingAltLinks: List<Pair<DownloadLink, String>> = emptyList()

    var downloadError by mutableStateOf<String?>(null)
    private set

    /** 获取下载直链中（UI 显示加载弹窗） */
    var isFetchingDownloadLink by mutableStateOf(false)
        private set

    /** 待转存文件（转存弹窗）；null 表示未在转存流程 */
    var saveTarget by mutableStateOf<ShareFile?>(null)
        private set

    /** 转存中（UI 显示加载） */
    var isSaving by mutableStateOf(false)
        private set

    /** 转存结果消息（Toast） */
    var saveMessage by mutableStateOf<String?>(null)
        private set

    /** 当前分享是否支持转存（夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭） */
    val canSave: Boolean
        get() = currentPlatform == SharePlatform.QUARK ||
            currentPlatform == SharePlatform.UC ||
            currentPlatform == SharePlatform.XUNLEI ||
            currentPlatform == SharePlatform.BAIDU ||
            currentPlatform == SharePlatform.C139 ||
            currentPlatform == SharePlatform.PAN123 ||
            currentPlatform == SharePlatform.PAN115 ||
            currentPlatform == SharePlatform.GUANGYA

    /** 当前分享是否为迅雷（UI 选择迅雷版转存目录选择器） */
    val isSaveXunlei: Boolean
        get() = currentPlatform == SharePlatform.XUNLEI

    /** 当前分享是否为百度（UI 选择百度版转存目录选择器） */
    val isSaveBaidu: Boolean
        get() = currentPlatform == SharePlatform.BAIDU

    /** 当前分享是否为百度（限速提示判断用） */
    val isBaidu: Boolean
        get() = currentPlatform == SharePlatform.BAIDU

    /** 当前分享是否为 139（UI 选择 139 版转存目录选择器） */
    val isSaveC139: Boolean
        get() = currentPlatform == SharePlatform.C139

    /** 当前分享是否为 UC（UI 选择 UC 版转存目录选择器） */
    val isSaveUC: Boolean
        get() = currentPlatform == SharePlatform.UC

    /** 当前分享是否为 123（UI 选择 123 版转存目录选择器） */
    val isSavePan123: Boolean
        get() = currentPlatform == SharePlatform.PAN123

    /** 当前分享是否为 115（UI 选择 115 版转存目录选择器） */
    val isSavePan115: Boolean
        get() = currentPlatform == SharePlatform.PAN115

    /** 当前分享是否为光鸭云盘（UI 选择光鸭版转存目录选择器） */
    val isSaveGuangYa: Boolean
        get() = currentPlatform == SharePlatform.GUANGYA

    /** 请求转存：记录目标文件并打开目录选择弹窗 */
    fun requestSave(file: ShareFile) {
        // 游客模式只开放列目录：转存需要登录，直接给提示，别让用户进到空的目录选择
        if (isGuest) {
            downloadError = "转存需要先登录${platformName()}（未登录仅能浏览文件列表）"
            return
        }
        saveTarget = file
        saveMessage = null
    }

    fun dismissSave() {
        saveTarget = null
        isSaving = false
    }

    fun consumeSaveMessage() {
        saveMessage = null
    }

    /** 转存到网盘指定目录（保存成功自动关闭弹窗；夸克 / 迅雷 / 百度分平台实现） */
    fun saveToCloud(toDirFid: String) {
        val file = saveTarget ?: return
        val s = session ?: return
        viewModelScope.launch {
            isSaving = true
            try {
                when (currentPlatform) {
                    SharePlatform.XUNLEI -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录迅雷网盘"
                            return@launch
                        }
                        xunleiResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到迅雷网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.BAIDU -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录百度网盘"
                            return@launch
                        }
                        baiduResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到百度网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.C139 -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录139网盘"
                            return@launch
                        }
                        c139ResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到139网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.UC -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录UC网盘"
                            return@launch
                        }
                        ucResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到UC网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.PAN123 -> {
                        // 123 保存到个人盘：copy/save（mshare 无需签名）+ 轮询 task
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录123云盘"
                            return@launch
                        }
                        pan123ResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到123云盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.PAN115 -> {
                        // 115 保存到个人盘：share/receive（不回传新 fid，仓库内部转存后回查）
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录115网盘"
                            return@launch
                        }
                        pan115ResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到115网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.GUANGYA -> {
                        // 光鸭保存到个人盘：restore_share（accessToken + fileIds + parentId）
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录光鸭云盘"
                            return@launch
                        }
                        guangyaResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到光鸭云盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    else -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录夸克网盘"
                            return@launch
                        }
                        resolveRepository.saveToCloud(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到夸克网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                }
            } finally {
                isSaving = false
            }
        }
    }

    /** 下载已入队事件：触发后由 UI 切换到下载页 */
    var downloadStarted by mutableStateOf(false)
        private set

    var updateFallbackToResolve by mutableStateOf(false)
        private set

    // ---------- 长按多选（解析页文件列表） ----------

    /** 多选模式（长按进入） */
    var multiSelectMode by mutableStateOf(false)
        private set

    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    /** 批量处理中（UI 显示加载弹窗） */
    var isBatchWorking by mutableStateOf(false)
        private set

    /** 批量下载进度（如 "2/5"）；null 表示未显示进度 */
    var batchProgress by mutableStateOf<String?>(null)
        private set

    /** 批量处理中断请求（UI 点「中断」后置 true，批量循环中检查并跳出） */
    private var batchCancelRequested = false

    /** 中断当前批量处理（批量下载/批量转存） */
    fun cancelBatch() {
        batchCancelRequested = true
    }

    fun enterMultiSelect(file: ShareFile) {
        multiSelectMode = true
        _selected.clear()
        _selected.add(file)
    }

    fun toggleSelect(file: ShareFile) {
        if (_selected.contains(file)) _selected.remove(file) else _selected.add(file)
    }

    fun toggleSelectAll(files: List<ShareFile>) {
        if (_selected.size == files.size) _selected.clear()
        else {
            _selected.clear()
            _selected.addAll(files)
        }
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        _selected.clear()
    }

    /** 批量转存到网盘根目录（夸克 / 迅雷 / 百度分平台；仅支持转存的平台） */
    fun batchSaveToCloud() {
        val files = _selected.toList()
        val s = session ?: return
        viewModelScope.launch {
            isBatchWorking = true
            batchCancelRequested = false
            try {
                val credential = currentCredential()
                if (credential.isNullOrBlank()) {
                    // 游客模式只开放列目录：转存需要登录
                    downloadError = "转存需要先登录${platformName()}（未登录仅能浏览文件列表）"
                    return@launch
                }
                var okCount = 0
                var interrupted = false
                for (file in files) {
                    // 用户点击「中断」：停止剩余项，已转存的不回滚
                    if (batchCancelRequested) {
                        interrupted = true
                        downloadError = "已中断批量转存"
                        break
                    }
                    runCatching {
                        when (currentPlatform) {
                            SharePlatform.XUNLEI -> {
                                // 迅雷批量转存到根目录（parent_id 为空）
                                xunleiResolveRepository.transferFile(s, file, "", credential)
                            }
                            SharePlatform.BAIDU -> {
                                // 百度批量转存到根目录（绝对路径 "/"）
                                baiduResolveRepository.transferFile(s, file, "/", credential)
                            }
                            SharePlatform.C139 -> {
                                // 139 批量转存到根目录（fileId "/"）
                                c139ResolveRepository.transferFile(s, file, "/", credential)
                            }
                            SharePlatform.UC -> {
                                // UC 批量转存到根目录（pdir_fid "0"）
                                ucResolveRepository.transferFile(s, file, UCConstants.DEFAULT_PDIR_FID, credential)
                            }
                            SharePlatform.PAN123 -> {
                                // 123 批量转存到根目录（fileId "0"）
                                pan123ResolveRepository.transferFile(s, file, "0", credential)
                            }
                            SharePlatform.PAN115 -> {
                                // 115 批量转存到根目录（cid "0"）
                                pan115ResolveRepository.transferFile(s, file, Pan115Constants.ROOT_CID, credential)
                            }
                            SharePlatform.GUANGYA -> {
                                // 光鸭批量转存到根目录（parentId ""）
                                guangyaResolveRepository.transferFile(s, file, "", credential)
                            }
                            SharePlatform.ILANZOU -> {
                                // 蓝奏优享批量转存到根目录（folderId "0"）
                                ilanzouResolveRepository.transferFile(s, file, "0", credential)
                            }
                            else -> {
                                resolveRepository.saveToCloud(s, file, QuarkConstants.DEFAULT_PDIR_FID, credential)
                            }
                        }
                    }.onSuccess { okCount++ }
                }
                if (!interrupted) {
                    downloadError = if (okCount > 0) "已转存 $okCount 项到${platformName()}" else "转存失败"
                }
                exitMultiSelect()
            } finally {
                isBatchWorking = false
                batchCancelRequested = false
            }
        }
    }

    /** 批量下载（多选栏）：逐个取直链入队（选中文件夹时递归下载整个文件夹并保持目录结构，全部获取完再统一切到下载页） */
    fun batchDownload() = downloadFiles(_selected.toList())

    /**
     * 解析页文件操作弹窗：下载单个文件夹（递归收集整个文件夹后逐个入队，与批量下载同一条链路）。
     * 弹窗里的单文件下载不走这里 —— 那条路径要弹「下载链接」弹窗（可复制直链），见 [fetchDownloadLink]。
     */
    fun downloadFolder(file: ShareFile) = downloadFiles(listOf(file))

    /** 下载给定的一批文件/文件夹（文件夹递归展开；全部取链完成后一次性切到下载页） */
    private fun downloadFiles(files: List<ShareFile>) {
        if (files.isEmpty()) return
        // GitHub 分支：无需凭证/取链 API，逐文件构造直链入队（代码文件夹递归收集 blob）
        if (currentPlatform == SharePlatform.GITHUB) {
            batchGitHubDownload(files)
            return
        }
        val s = session ?: return
        viewModelScope.launch {
            isBatchWorking = true
            batchProgress = "正在收集文件…"
            batchCancelRequested = false
            try {
                // 游客模式：夸克/UC 的小文件直链不要求登录（走游客取链），其余平台必须登录
                val credential = currentCredential().orEmpty()
                if (credential.isBlank() && !supportsGuestDownload()) {
                    downloadError = "下载需要先登录${platformName()}（未登录仅能浏览文件列表）"
                    return@launch
                }
                // 夸克/UC 共用 __puus：取链与下载必须用同一份已刷新 Cookie（直链签名绑定取链时刻的 __puus）
                val quarkCred = if (credential.isBlank()) {
                    ""
                } else {
                    when (currentPlatform) {
                        SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
                        SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
                        else -> credential
                    }
                }
                // 展开待下载项：文件直接加入，文件夹递归收集（相对路径 = 文件夹名/子/...）
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                for (file in files) {
                    if (file.isdir) {
                        collectShareFolder(s, file.fid, file.fname, quarkCred, tasks, 0)
                    } else {
                        tasks.add(file to "")
                    }
                }
                if (tasks.isEmpty()) {
                    downloadError = "所选文件夹为空"
                    exitMultiSelect()
                    return@launch
                }
                var okCount = 0
                var failCount = 0
                var firstFailReason: String? = null
                var interrupted = false
                for ((index, task) in tasks.withIndex()) {
                    // 用户点击「中断」：停止剩余项，已入队的任务保留下载
                    if (batchCancelRequested) {
                        interrupted = true
                        downloadError = "已中断批量下载"
                        break
                    }
                    val (file, relPath) = task
                    batchProgress = "${index + 1}/${tasks.size}"
                    runCatching {
                        // 游客态（凭据为空）走游客取链：夸克/UC 分享直链不要求账号，带游客 __pugs 即可下载；
                        // 登录态优先免转存，夸克失败时自动回退到转存取链
                        val linkResult = if (credential.isBlank()) {
                            currentRepo().getGuestShareDownloadLink(s, file)
                        } else {
                            resolveShareLink(currentRepo(), s, file, quarkCred)
                        }
                        // ★ 取链失败必须抛出（而不是 getOrNull() 后什么都不做）：批量下载里静默跳过会
                        //   表现为「整个文件夹只加入了几个文件，其余像没发生一样」，用户只会看到下载目录里
                        //   少文件 / 混着上一次的残留，根本不知道是失败了
                        val link = linkResult.getOrNull()
                            ?: throw IllegalStateException(linkResult.exceptionOrNull()?.message ?: "未获取到下载直链")
                        // 文件夹内文件用相对路径（保持目录结构）；根目录文件用取链返回的文件名
                        enqueueDownload(link, quarkCred, if (relPath.isBlank()) link.filename else relPath)
                        okCount++
                    }.onFailure { e ->
                        // 协程取消不是「失败」：原样抛出去，否则会被记成一条假的失败原因
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failCount++
                        val reason = e.message ?: e.javaClass.simpleName
                        if (firstFailReason == null) firstFailReason = reason
                        android.util.Log.w("YunX-Resolve", "批量下载跳过 ${file.fname}：$reason")
                    }
                }
                if (!interrupted) {
                    downloadError = when {
                        failCount == 0 -> if (okCount > 0) "已加入 $okCount 个下载任务" else "获取下载链接失败"
                        else -> "已加入 $okCount 个，$failCount 个失败" +
                            (firstFailReason?.let { "（原因：$it）" } ?: "")
                    }
                    // 全部获取完再一次性切到下载页
                    if (okCount > 0) downloadStarted = true
                }
                exitMultiSelect()
            } finally {
                isBatchWorking = false
                batchProgress = null
                batchCancelRequested = false
            }
        }
    }

    /**
     * 递归收集分享文件夹内所有文件（保持目录结构）。
     * @param dirFid 分享内目录 fid
     * @param prefix 相对路径前缀（如 "文件夹A/子目录"）
     * @param result 输出：文件 + 相对路径（"文件夹A/子目录/文件.mp4"）
     */
    private suspend fun collectShareFolder(
        s: ShareSession,
        dirFid: String,
        prefix: String,
        credential: String,
        result: MutableList<Pair<ShareFile, String>>,
        depth: Int
    ) {
        if (depth > 12) return
        val files = runCatching {
            currentRepo().listFiles(s, dirFid, credential).getOrNull() ?: emptyList()
        }.getOrDefault(emptyList())
        files.filter { !it.isdir }.forEach { result.add(it to "$prefix/${it.fname}") }
        files.filter { it.isdir }.forEach {
            collectShareFolder(s, it.fid, "$prefix/${it.fname}", credential, result, depth + 1)
        }
    }

    /**
     * GitHub 批量下载：传入的叶子文件直接入队；代码文件夹（github:code:*）递归 getTree 收集 blob；
     * Releases 文件夹展开为资产；其余（repo 根/账号列表）跳过。全部直链先经镜像前缀转换。
     */
    private fun batchGitHubDownload(files: List<ShareFile>) {
        viewModelScope.launch {
            isBatchWorking = true
            batchProgress = "正在收集文件…"
            batchCancelRequested = false
            try {
                val prefix = mirrorPrefixProvider()
                val tasks = mutableListOf<Triple<String, String, Long>>() // url, fileName, size
                // 收集文件
                for (file in files) {
                    if (batchCancelRequested) break
                    when {
                        // 叶子文件：直接构造 URL
                        file.fid == "github:zip" || file.fid.startsWith("github:file:") ||
                            file.fid.startsWith("github:asset:") || file.fid == "github:direct" -> {
                            githubUrlForFid(file.fid)?.let { url ->
                                tasks.add(Triple(url, file.fname, file.fsize))
                            }
                        }
                        // 代码文件夹：递归收集 blob（不 recursive，逐层 getTree）
                        file.fid.startsWith("github:code:") -> {
                            collectGitHubBlobs(file.fid.removePrefix("github:code:"), "", tasks, 0)
                        }
                        // Release 文件夹：展开为资产
                        file.fid.startsWith("github:release:") -> {
                            val tag = file.fid.removePrefix("github:release:")
                            currentGitHubReleases.firstOrNull { it.tagName == tag }?.assets?.forEach { a ->
                                tasks.add(Triple(a.downloadUrl, a.name, a.size))
                            }
                        }
                        // 其余文件夹（repo 根/账号列表/加载更多）：跳过
                        else -> {}
                    }
                }
                if (tasks.isEmpty()) {
                    downloadError = "所选内容无可下载文件"
                    exitMultiSelect()
                    return@launch
                }
                var okCount = 0
                for ((index, t) in tasks.withIndex()) {
                    if (batchCancelRequested) {
                        downloadError = "已中断批量下载"
                        break
                    }
                    batchProgress = "${index + 1}/${tasks.size}"
                    runCatching {
                        downloadManager.enqueue(
                            url = UpdateChecker.mirrorUrl(t.first, prefix),
                            fileName = t.second,
                            size = t.third,
                            platform = DownloadPlatform.GITHUB,
                            // 镜像挂掉时回退原始直连（t.first 为未镜像的 GitHub 直链）
                            fallbackUrl = t.first
                        )
                        okCount++
                    }
                }
                if (!batchCancelRequested) {
                    downloadError = if (okCount > 0) "已加入 $okCount 个下载任务" else "下载失败"
                    if (okCount > 0) downloadStarted = true
                }
                exitMultiSelect()
            } finally {
                isBatchWorking = false
                batchProgress = null
                batchCancelRequested = false
            }
        }
    }

    /**
     * 递归收集 GitHub 代码目录下所有 blob 的直链（逐层 getTree，不加 recursive=1）。
     * 用户在批量下载弹窗点取消后，正在进行的递归收集立即停止，已收集的 blob 不入队。
     */
    private suspend fun collectGitHubBlobs(
        sha: String,
        prefix: String,
        result: MutableList<Triple<String, String, Long>>,
        depth: Int
    ) {
        if (depth > 12) return
        // 取消响应：每层入口与循环中检查，选超大文件夹时取消可立即生效
        if (batchCancelRequested) return
        val repo = currentGitHubRepo ?: return
        val entries = githubApi?.getTree(repo.owner, repo.name, sha) ?: return
        val branch = repo.defaultBranch
        for (e in entries) {
            if (batchCancelRequested) return
            val displayName = e.path.substringAfterLast('/')
            if (e.type == "tree") {
                collectGitHubBlobs(e.sha, if (prefix.isBlank()) displayName else "$prefix/$displayName", result, depth + 1)
            } else {
                val url = "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/$branch/${encodePath(e.path)}"
                result.add(Triple(url, if (prefix.isBlank()) displayName else "$prefix/$displayName", e.size ?: -1))
            }
        }
    }

    fun consumeDownloadStarted() {
        downloadStarted = false
    }

    fun consumeUpdateFallbackToResolve() {
        updateFallbackToResolve = false
    }

    fun consumeDownloadError() {
        downloadError = null
    }

    private var session: ShareSession? = null
    private var currentDirFid = QuarkConstants.DEFAULT_PDIR_FID
    private val dirStack = ArrayDeque<String>()

    /** 当前解析的原始分享链接与提取码（收藏当前分享用） */
    private var currentLink: String? = null
    private var currentPwd: String? = null

    // ---------- GitHub 平台状态 ----------

    /** 当前浏览的 GitHub 仓库（仓库根目录/代码/Releases 时非空） */
    private var currentGitHubRepo: GitHubRepo? = null

    /** 当前浏览的 GitHub 账号/组织（账号仓库列表时非空） */
    private var currentGitHubOwner: String? = null

    /** 当前 owner 的类型（"User"/"Organization"），首次加载后缓存，避免分页每页都探测 */
    private var currentGitHubOwnerType: String? = null

    /** 已累加的 Releases 列表（分页累加） */
    private val currentGitHubReleases = mutableListOf<GitHubRelease>()

    /** 已累加的账号仓库列表（分页累加） */
    private val currentGitHubRepos = mutableListOf<GitHubRepo>()

    /** README 原文（仓库根目录加载；纯文本展示，无则 null） */
    var githubReadme: String? by mutableStateOf(null)
        private set

    /** forked from 上游全名（fork 仓库根目录显示；非 fork 为 null） */
    var githubParentFullName: String? by mutableStateOf(null)
        private set

    /** 文件徽章映射：fid -> 徽章文本（Releases 的 最新/预发布/草稿，账号 repo 的 Fork/语言） */
    var githubBadges: Map<String, String> by mutableStateOf(emptyMap())
        private set

    /** 目录令牌：每次进入 GitHub 代码目录自增，用于丢弃异步时间请求的过期结果（防串目录） */
    private var githubDirToken = 0

    /** 当前目录路径名栈（用于面包屑显示），如 [辅助工具, 专用模组] */
    var pathNames by mutableStateOf<List<String>>(emptyList())
        private set

    /** 当前解析平台（QUARK / UC / XUNLEI），由链接自动检测 */
    private var currentPlatform: SharePlatform = SharePlatform.QUARK

    /** 当前平台（只读暴露给 UI）：游客提示条等按平台给不同文案 */
    val sharePlatform: SharePlatform get() = currentPlatform

    /** 当前是否为 GitHub 平台（UI 据此渲染 README/forked from/徽章等额外内容） */
    val isGitHubPlatform: Boolean get() = currentPlatform == SharePlatform.GITHUB

    /**
     * 当前是否停在某个仓库的首页（仓库根目录）：fid 为 `github:root`（链接直达 / 跳上游仓库）
     * 或 `github:repo:owner/name`（从账号仓库列表点进来）。
     * README 与 forked from 只在仓库首页渲染：进「代码」「Releases」等子目录不显示，
     * 返回账号仓库列表（github:account_root）也不残留。
     */
    val githubAtRepoRoot: Boolean
        get() = isGitHubPlatform &&
            (currentDirFid == "github:root" || currentDirFid.startsWith("github:repo:"))

    /** GitHub 下拉刷新指示器状态 */
    var githubRefreshing by mutableStateOf(false)
        private set

    /**
     * 下拉刷新当前 GitHub 节点：先按前缀清空统一缓存（用户主动刷新要看最新），
     * 再走 loadGitHubDir 重新请求。代码目录→getTree(+README 仅根目录)、
     * Releases→重新分页、账号→重新拉仓库列表；失败保持现有数据不崩。非 GitHub 平台忽略。
     */
    fun refreshGitHubCurrentNode() {
        if (!isGitHubPlatform || githubRefreshing) return
        invalidateCurrentGitHubCache()
        viewModelScope.launch {
            githubRefreshing = true
            try {
                loadGitHubDir(currentDirFid)
            } finally {
                githubRefreshing = false
            }
        }
    }

    /** 按当前 GitHub 节点清空统一缓存前缀（下拉刷新绕缓存用） */
    private fun invalidateCurrentGitHubCache() {
        val repo = currentGitHubRepo
        when {
            // 仓库根 / 代码目录 / Releases：清该仓库 tree、releases、readme、repo 信息
            currentDirFid.startsWith("github:root") ||
                currentDirFid.startsWith("github:code") ||
                currentDirFid.startsWith("github:release") ||
                currentDirFid == "github:releases" -> {
                if (repo != null) {
                    // 注意：必须用 repo.name（GitHubRepo 是 data class，直接插值会得到 toString，前缀匹配不上缓存 key）
                    GitHubResponseCache.invalidatePrefix("tree:${repo.owner}/${repo.name}/")
                    GitHubResponseCache.invalidatePrefix("releases:${repo.owner}/${repo.name}:")
                    GitHubResponseCache.invalidatePrefix("readme:${repo.owner}/${repo.name}/")
                    GitHubResponseCache.invalidatePrefix("repo:${repo.owner}/${repo.name}")
                    // 代码目录时间缓存一并失效：用户主动刷新期望看到最新提交时间
                    GitHubCommitDateCache.invalidatePrefix("${repo.owner}/${repo.name}/")
                }
            }
            // 账号仓库列表：清该 owner 的 users/orgs 列表与类型
            currentDirFid == "github:account_root" -> {
                currentGitHubOwner?.let { owner ->
                    GitHubResponseCache.invalidatePrefix("userrepos:$owner:")
                    GitHubResponseCache.invalidatePrefix("orgrepos:$owner:")
                    GitHubResponseCache.invalidatePrefix("usertype:$owner")
                }
            }
        }
    }

    /** README 渲染用：当前仓库 owner（账号/组织浏览或无仓库时为 null） */
    val githubRepoOwner: String? get() = currentGitHubRepo?.owner
    /** README 渲染用：当前仓库名 */
    val githubRepoName: String? get() = currentGitHubRepo?.name
    /** README 渲染用：默认分支（用于相对链接补全） */
    val githubDefaultBranch: String? get() = currentGitHubRepo?.defaultBranch

    /** 当前平台凭证（夸克/UC/百度/139/115 用 cookie，迅雷/123 用 access_token；GitHub 无需凭证） */
    private suspend fun currentCredential(): String? = when (currentPlatform) {
        SharePlatform.UC -> ucAccountRepository.getAccount()?.cookie
        SharePlatform.XUNLEI -> xunleiAccountRepository.getAccount()?.accessToken
        SharePlatform.BAIDU -> baiduAccountRepository.getAccount()?.cookie
        SharePlatform.C139 -> c139AccountRepository.getAccount()?.cookie
        SharePlatform.PAN123 -> pan123AccountRepository.getAccount()?.accessToken
        SharePlatform.PAN115 -> pan115AccountRepository.getAccount()?.cookie
        SharePlatform.GUANGYA -> guangyaAccountRepository.ensureAccessToken()
        SharePlatform.ILANZOU -> ilanzouAccountRepository.getAccount()?.appToken
        SharePlatform.LANZOU -> lanzouAccountRepository.getAccount()?.cookie
        SharePlatform.GITHUB -> null
        else -> accountRepository.getAccount()?.cookie
    }

    /**
     * 多账号下载（实验性 C1）：本次批量下载可以轮换使用的凭证列表（主账号在前）。
     *
     * ★ 只覆盖「取链接口按参数收凭证」的平台：夸克 / UC / 迅雷 / 百度 / 139 / 115 / 蓝奏云。
     *   123 / 光鸭 / 蓝奏优享 的凭证由各自 ResolveRepository 内部的 provider 闭包绑定主账号
     *   （那条链路还负责 token 过期自动刷新），要换账号得改整条 provider 接线，
     *   本次不做 —— 它们退回单账号，行为与开启前一致。
     *
     * 关闭开关、平台只有一个账号、或游客链路时返回原来那一条。
     */
    private suspend fun credentialRotation(fallback: String): List<String> {
        if (!multiAccountDownloadProvider()) return listOf(fallback)
        val credentials = when (currentPlatform) {
            SharePlatform.QUARK -> accountRepository.allCredentials()
            SharePlatform.UC -> ucAccountRepository.allCredentials()
            SharePlatform.XUNLEI -> xunleiAccountRepository.allCredentials()
            SharePlatform.BAIDU -> baiduAccountRepository.allCredentials()
            SharePlatform.C139 -> c139AccountRepository.allCredentials()
            SharePlatform.PAN115 -> pan115AccountRepository.allCredentials()
            SharePlatform.LANZOU -> lanzouAccountRepository.allCredentials()
            else -> emptyList()
        }.filter { it.isNotBlank() }
        return credentials.ifEmpty { listOf(fallback) }
    }

    /**
     * 单文件多源分片（实验性 C2）支持轮换的四个主流平台（用户指定）。
     * 其余平台即使开着 C2 也退回单源：它们的取链要么走 provider 闭包（123/光鸭/蓝奏优享），
     * 要么没有独立的账号维度可用。
     */
    private val multiSourcePlatforms = setOf(
        SharePlatform.QUARK,
        SharePlatform.UC,
        SharePlatform.XUNLEI,
        SharePlatform.BAIDU
    )

    /**
     * 单文件多源分片（实验性 C2）：对同一个文件再用**其他账号**各取一条直链。
     *
     * 只在「C2 生效 + 平台受支持 + 确实还有第二个账号」时才返回非空；任一账号取链失败就跳过它
     * （多源只是提速手段，少一条照样能把文件下完）。
     * 返回「直链 + 该账号凭证」成对的数据：下载请求头必须按这个凭证配，不能配主账号的。
     */
    private suspend fun fetchAltLinks(
        file: ShareFile,
        primaryCredential: String
    ): List<Pair<DownloadLink, String>> {
        if (!multiSourceDownloadProvider() || currentPlatform !in multiSourcePlatforms) return emptyList()
        val s = session ?: return emptyList()
        return credentialRotation(primaryCredential)
            .filter { it != primaryCredential }
            .mapNotNull { credential ->
                runCatching { resolveShareLink(currentRepo(), s, file, credential).getOrNull() }
                    .getOrNull()
                    ?.let { link -> link to credential }
            }
    }

    private fun currentRepo(): ShareResolveRepository = when (currentPlatform) {
        SharePlatform.UC -> ucResolveRepository
        SharePlatform.XUNLEI -> xunleiResolveRepository
        SharePlatform.BAIDU -> baiduResolveRepository
        SharePlatform.C139 -> c139ResolveRepository
        SharePlatform.PAN123 -> pan123ResolveRepository
        SharePlatform.PAN115 -> pan115ResolveRepository
        SharePlatform.GUANGYA -> guangyaResolveRepository
        SharePlatform.ILANZOU -> ilanzouResolveRepository
        SharePlatform.LANZOU -> lanzouResolveRepository
        else -> resolveRepository
    }

    private fun currentDefaultDirFid(): String = when (currentPlatform) {
        SharePlatform.UC -> UCConstants.DEFAULT_PDIR_FID
        SharePlatform.XUNLEI -> "0"
        SharePlatform.BAIDU -> ""
        SharePlatform.C139 -> "0"
        SharePlatform.PAN123 -> "0"
        SharePlatform.PAN115 -> Pan115Constants.ROOT_CID
        SharePlatform.GUANGYA -> ""
        SharePlatform.ILANZOU -> ""
        SharePlatform.LANZOU -> ""
        SharePlatform.GITHUB -> "github:root"
        else -> QuarkConstants.DEFAULT_PDIR_FID
    }

    private fun platformName(): String = when (currentPlatform) {
        SharePlatform.UC -> "UC 网盘"
        SharePlatform.XUNLEI -> "迅雷网盘"
        SharePlatform.BAIDU -> "百度网盘"
        SharePlatform.C139 -> "139 网盘"
        SharePlatform.PAN123 -> "123云盘"
        SharePlatform.PAN115 -> "115网盘"
        SharePlatform.GUANGYA -> "光鸭云盘"
        SharePlatform.ILANZOU -> "蓝奏云优享版"
        SharePlatform.LANZOU -> "蓝奏云"
        SharePlatform.GITHUB -> "GitHub"
        else -> "夸克网盘"
    }

    // ---------- 视频解析（抖音/小红书/快手/微博/AcFun/B站/Pixiv） ----------

    /**
     * 视频解析入口：识别到视频链接就走视频流程，识别不到则原样退回网盘/GitHub 解析。
     * 这样 UI 三条入口（手动输入 / 深链接 / 主页快捷方式）都只需无条件调用本方法。
     *
     * @param pwd 网盘分享的提取码：仅在回退到网盘流程时透传，视频流程不使用。
     */
    fun startVideoResolve(text: String, pwd: String? = null) {
        val link = VideoLinkParser.parse(text)
        val repo = videoParseRepository
        // 识别不到视频链接 / 未注入视频仓库：退回原有网盘解析（startResolve 内部会先试 GitHub再试网盘）
        if (link == null || repo == null) {
            startResolve(text, pwd)
            return
        }
        // 视频结果不涉及网盘会话与凭证，因此**不设置** currentPlatform（那是网盘枚举）。
        // 需要展示「当前平台」文案时，UI 直接用 result.platform.displayName。
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            try {
                val result = repo.parse(link)
                // 解析成功但内容有降级（如小红书去水印原图不可用、已回退到平台地址）：弹一次非致命提示
                result.notice.takeIf { it.isNotBlank() }?.let { SnackbarController.show(it) }
                uiState = ResolveUiState.Video(result)
            } catch (e: VideoParseException) {
                uiState = ResolveUiState.Error(e.userMessage())
            } catch (e: Exception) {
                uiState = ResolveUiState.Error("解析失败，请稍后重试")
            }
        }
    }

    /**
     * 下载单个视频媒体。
     * HLS 无需特殊处理：[DownloadManager] 按 URL 是否含 `.m3u8` 自动走 HLS 路径。
     */
    fun downloadVideoMedia(media: VideoMedia) {
        val result = (uiState as? ResolveUiState.Video)?.result ?: return
        viewModelScope.launch {
            val index = result.medias.indexOf(media).takeIf { it >= 0 }?.plus(1) ?: 1
            try {
                downloadManager.enqueue(
                    url = media.url,
                    fileName = media.fileName.ifBlank { videoMediaFileName(result.title, index, media.kind) },
                    headers = media.headers,
                    size = media.size,
                    platform = DownloadPlatform.VIDEO,
                    // B站 DASH 高清是音视频分离的两路流：带上音轨地址，由下载器下完两轨再合轨
                    mergeAudioUrl = media.audioUrl,
                    mergeAudioHeaders = media.audioHeaders
                )
                downloadStarted = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadError = "加入下载任务失败，请稍后重试"
            }
        }
    }

    /**
     * 一键下载当前视频结果里的全部媒体：逐个入队，结束后用 [downloadError] 给出结果提示
     * （网盘批量下载同样用它当结果通道），入队成功则触发跳转到下载页。
     */
    fun downloadAllVideoMedia() {
        val result = (uiState as? ResolveUiState.Video)?.result ?: return
        downloadVideoMedias(result.medias)
    }

    /**
     * 批量下载一组媒体（图集的「全部下载」/多选后的「下载所选」共用）。
     *
     * ★ **多张时文件名带「作品标题/」前缀**：下载页是按 `fileName` 里的 `/` 把任务收纳成**可折叠的
     *   文件夹组**（就是网盘「整个文件夹下载」那一套机制，见 `DownloadScreen` 的 `folderGroups`）。
     *   于是图集批量下载在下载页是**一个分组**，而不是几十张散落各处的图片任务。
     *   **单张不加前缀** —— 一个只有一张图的「文件夹」没有意义。
     */
    fun downloadVideoMedias(medias: List<VideoMedia>) {
        val result = (uiState as? ResolveUiState.Video)?.result ?: return
        if (medias.isEmpty()) return
        val folder = if (medias.size > 1) DownloadPathPolicy.sanitizeName(result.title).take(40) else ""
        viewModelScope.launch {
            var okCount = 0
            var failReason: String? = null
            try {
                medias.forEachIndexed { i, media ->
                    // 序号只用于文件名兜底：优先用解析器给好的 fileName
                    val index = result.medias.indexOf(media).takeIf { it >= 0 }?.plus(1) ?: (i + 1)
                    val name = media.fileName.ifBlank {
                        videoMediaFileName(result.title, index, media.kind)
                    }
                    downloadManager.enqueue(
                        url = media.url,
                        fileName = if (folder.isBlank()) name else "$folder/$name",
                        headers = media.headers,
                        size = media.size,
                        platform = DownloadPlatform.VIDEO,
                        // B站 DASH 分轨：音轨一起交给下载器合轨（与逐条下载保持一致，
                        // 少了这个「全部下载」会下出一个没有声音的视频）
                        mergeAudioUrl = media.audioUrl,
                        mergeAudioHeaders = media.audioHeaders
                    )
                    okCount++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failReason = e.message
            }
            if (okCount > 0) {
                downloadError = "已加入 $okCount 个下载任务"
                downloadStarted = true
            } else {
                // 一个都没入队：把原因写出来，不要静默失败
                downloadError = failReason?.takeIf { it.isNotBlank() } ?: "加入下载任务失败，请稍后重试"
            }
        }
    }

    /** 视频媒体文件名的兜底：`标题_序号.扩展名`（扩展名按媒体类型推断） */
    private fun videoMediaFileName(title: String, index: Int, kind: VideoMediaKind): String {
        val ext = when (kind) {
            VideoMediaKind.VIDEO -> "mp4"
            VideoMediaKind.IMAGE -> "jpg"
            VideoMediaKind.AUDIO -> "mp3"
        }
        val base = title.ifBlank { "video" }.take(40)
        return "${base}_${index}.$ext"
    }

    /** 开始解析：链接 → token →（密码）→ 根目录列表 */
    fun startResolve(link: String, pwd: String?) {
        currentLink = link
        currentPwd = pwd
        // GitHub 链接统一识别：仓库 / 账号 / 文件直链 → GitHub 解析流程。
        // 放在网盘解析之前，使「收藏打开 / 剪贴板 / 解析页 / 后续系统分享」等所有
        // 走 startResolve 的入口都能解析 GitHub 链接，无需在 UI 层各处重复判断。
        val github = GitHubLinkParser.parse(link)
        if (github != null) {
            startGitHubResolve(github)
            return
        }
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            // 中文口令兜底（既不是分享链接、也不是 GitHub 链接）：**先迅雷，迅雷没给出可用链接再问星海**。
            // ① 迅雷口令（如「张三丰资源」）：口令是「分享链接 + 提取码」的打包形式，打 shoulei 跳转接口换链接；
            // ② 星海（夸克/UC 中文口令）：换回 pwd_id，再合成分享链接，后续提取码/登录/列表/取链全走既有链路。
            // 两个服务都用同一段原文，成功即止；都失败时把原因带出去（而不是只丢一句「无法识别」）。
            var effectiveLink = link
            var commandError: String? = null
            if (ShareLinkParser.parse(link) == null) {
                if (XunleiKouling.looksLikeKouling(link)) {
                    xunleiResolveRepository.resolveKouling(XunleiKouling.normalize(link))
                        .onSuccess { effectiveLink = it }
                        .onFailure { commandError = it.message }
                }
                if (ShareLinkParser.parse(effectiveLink) == null && StarSeaApi.looksLikeCommand(link)) {
                    StarSeaApi.resolveToShareLink(link)
                        .onSuccess { effectiveLink = it }
                        .onFailure { commandError = it.message }
                }
            }
            val parsed = ShareLinkParser.parse(effectiveLink)
            if (parsed == null) {
                // 两个口令服务都给不出结果时，用更靠后那次（星海）的原因——它对夸克/UC 口令最准
                uiState = ResolveUiState.Error(commandError ?: "无法识别分享链接")
                return@launch
            }
            // 口令已换成真实分享链接：收藏 / 复制 / 重新解析都用它，而不是原始口令
            currentLink = effectiveLink
            currentPlatform = parsed.platform
            isGuest = false
            // 游客模式：列目录不要求登录（7 个网盘的分享列表接口都允许匿名，详见 Agent.md §3.19）。
            // 凭据为空时传空串，是否放行由服务端决定；下载/转存仍在各自入口要求登录。
            val credential = currentCredential().orEmpty()
            isGuest = credential.isBlank()
            val repo = currentRepo()
            repo.createSession(effectiveLink, pwd, credential)
                .onSuccess { s ->
                    session = s
                    currentDirFid = currentDefaultDirFid()
                    dirStack.clear()
                    pathNames = emptyList()
                    loadFiles(s, currentDirFid, credential, repo)
                }
                .onFailure { e ->
                    val msg = e.message ?: "解析失败"
                    // 游客模式失败时补一句可执行的引导，而不是只抛服务端原文
                    uiState = ResolveUiState.Error(
                        if (isGuest) "$msg（当前未登录，可到「网盘」页登录${platformName()}后重试）" else msg
                    )
                }
        }
    }

    fun startUpdateDownload(link: String) {
        updateFallbackToResolve = false
        viewModelScope.launch {
            if (GitHubLinkParser.parse(link) != null) {
                fallbackToResolve(link, null)
                return@launch
            }
            val parsed = ShareLinkParser.parse(link)
            if (parsed == null) {
                fallbackToResolve(link, null)
                return@launch
            }
            currentPlatform = parsed.platform
            val stored = currentCredential().orEmpty()
            val guestDownloadable =
                parsed.platform == SharePlatform.QUARK || parsed.platform == SharePlatform.UC ||
                    parsed.platform == SharePlatform.ILANZOU
            if (stored.isBlank() && !guestDownloadable) {
                fallbackToResolve(link, parsed.pwd)
                return@launch
            }
            val credential = when (parsed.platform) {
                SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: stored
                SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: stored
                else -> stored
            }
            isGuest = credential.isBlank()
            val repo = currentRepo()
            val sessionResult = repo.createSession(link, parsed.pwd, credential)
            val s = sessionResult.getOrNull()
            if (s == null) {
                fallbackToResolve(link, parsed.pwd, sessionResult.exceptionOrNull()?.message)
                return@launch
            }
            val collected = mutableListOf<Pair<ShareFile, String>>()
            collectShareFolder(s, currentDefaultDirFid(), "", credential, collected, 0)
            val apk = collected
                .filter { it.first.fname.endsWith(".apk", ignoreCase = true) }
                .maxByOrNull { it.first.fsize }
            if (apk == null) {
                fallbackToResolve(link, parsed.pwd)
                return@launch
            }
            if (isGuest && parsed.platform == SharePlatform.QUARK && apk.first.fsize > QUARK_GUEST_MAX_BYTES) {
                fallbackToResolve(
                    link,
                    parsed.pwd,
                    "该更新包超出夸克游客下载上限（约 50MB），登录夸克网盘后可直接下载"
                )
                return@launch
            }
            // 登录态优先免转存（不往用户网盘里留临时转存），夸克失败时自动回退到转存取链
            val linkResult = if (isGuest) {
                repo.getGuestShareDownloadLink(s, apk.first)
            } else {
                resolveShareLink(repo, s, apk.first, credential)
            }
            val directLink = linkResult.getOrNull()
            if (directLink == null) {
                fallbackToResolve(link, parsed.pwd, linkResult.exceptionOrNull()?.message)
                return@launch
            }
            session = s
            currentDirFid = currentDefaultDirFid()
            dirStack.clear()
            pathNames = emptyList()
            enqueueDownload(directLink, credential)
            downloadStarted = true
        }
    }

    private fun fallbackToResolve(link: String, pwd: String?, error: String? = null) {
        updateFallbackToResolve = true
        if (!error.isNullOrBlank()) downloadError = error
        startResolve(link, pwd)
    }

    // ---------- GitHub 平台入口与导航 ----------

    /**
     * GitHub 链接解析入口：仓库 → 代码/Releases 根；账号 → 仓库列表；直链 → 直接弹下载确认。
     * 复用 ShareDetailScreen 框架（搜索/多选/批量/确认弹窗/收藏/面包屑），canSave=false（无转存）。
     */
    fun startGitHubResolve(linkType: GitHubLinkType) {
        currentPlatform = SharePlatform.GITHUB
        // GitHub 不需要登录，也不属于「游客模式」提示的适用范围
        isGuest = false
        currentPwd = null
        // 重置 GitHub 状态
        currentGitHubRepo = null
        currentGitHubOwner = null
        currentGitHubReleases.clear()
        currentGitHubRepos.clear()
        githubReadme = null
        githubParentFullName = null
        githubBadges = emptyMap()
        dirStack.clear()
        pathNames = emptyList()
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            when (linkType) {
                is GitHubLinkType.Repository -> {
                    currentLink = "https://github.com/${linkType.owner}/${linkType.repo}"
                    val repo = githubApi?.getRepo(linkType.owner, linkType.repo)
                    if (repo == null) {
                        uiState = ResolveUiState.Error("无法获取仓库信息：${linkType.owner}/${linkType.repo}")
                        return@launch
                    }
                    session = ShareSession(shareId = "github:${repo.fullName}", stoken = "", title = repo.fullName)
                    currentGitHubRepo = repo
                    currentDirFid = "github:root"
                    loadGitHubRoot()
                }
                is GitHubLinkType.Account -> {
                    currentLink = "https://github.com/${linkType.owner}"
                    session = ShareSession(shareId = "github:user:${linkType.owner}", stoken = "", title = linkType.owner)
                    currentGitHubOwner = linkType.owner
                    currentDirFid = "github:account_root"
                    loadGitHubAccountRepos(firstPage = true)
                }
                is GitHubLinkType.DirectFile -> {
                    currentLink = linkType.url
                    session = ShareSession(shareId = "github:direct", stoken = "", title = linkType.fileName)
                    // 直链：直接构造 DownloadLink 弹确认弹窗（不进入文件列表）
                    downloadLink = DownloadLink(
                        fid = "github:direct",
                        filename = linkType.fileName,
                        downloadUrl = linkType.url,
                        size = -1
                    )
                    uiState = ResolveUiState.Idle
                }
            }
        }
    }

    /** 点击「forked from」：跳转到上游仓库根目录 */
    fun openGitHubParentRepo() {
        val parent = githubParentFullName ?: return
        val owner = parent.substringBefore('/')
        val repoName = parent.substringAfter('/')
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            // 从父仓库进入：重置当前仓库上下文，回到顶层仓库根
            dirStack.clear()
            pathNames = emptyList()
            val repo = githubApi?.getRepo(owner, repoName)
            if (repo == null) {
                uiState = ResolveUiState.Error("无法打开上游仓库：$parent")
                return@launch
            }
            session = ShareSession(shareId = "github:${repo.fullName}", stoken = "", title = repo.fullName)
            currentGitHubRepo = repo
            currentDirFid = "github:root"
            loadGitHubRoot()
        }
    }

    /** 根目录：代码 + Releases 两个文件夹；并行加载 README 与 forked-from 标记 */
    private suspend fun loadGitHubRoot() {
        val repo = currentGitHubRepo ?: return
        githubParentFullName = if (repo.fork) repo.parentFullName else null
        githubBadges = emptyMap()
        // README 并行加载，不阻塞根目录列表
        viewModelScope.launch {
            githubReadme = githubApi?.getReadme(repo.owner, repo.name, repo.defaultBranch)
        }
        val files = listOf(
            ShareFile(
                fid = "github:code:${repo.defaultBranch}",
                fname = "代码",
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = "",
                modifyTime = repo.pushedAt.orEmpty()
            ),
            ShareFile(
                fid = "github:releases",
                fname = "Releases",
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = ""
            )
        )
        uiState = ResolveUiState.Detail(session!!, files)
    }

    /** 代码目录：tree（不 recursive）映射为文件夹/文件；根代码目录首项加源码 ZIP */
    private suspend fun loadGitHubCodeDir(sha: String) {
        val repo = currentGitHubRepo ?: return
        val entries = githubApi?.getTree(repo.owner, repo.name, sha)
        if (entries == null) {
            uiState = ResolveUiState.Error("加载目录失败")
            return
        }
        // 目录令牌：每次加载自增；异步时间请求回来时比对，防止切换目录后旧结果串层
        val dirToken = ++githubDirToken
        val files = mutableListOf<ShareFile>()
        // 根代码目录（sha == 默认分支）首项：下载完整源码 ZIP（时间用 pushedAt，无需额外请求）
        if (sha == repo.defaultBranch) {
            files.add(
                ShareFile(
                    fid = "github:zip",
                    fname = "下载完整源码 ZIP",
                    fsize = -1,
                    isdir = false,
                    pdirFid = "",
                    fidToken = "",
                    modifyTime = repo.pushedAt.orEmpty()
                )
            )
        }
        for (e in entries) {
            val displayName = e.path.substringAfterLast('/')
            if (e.type == "tree") {
                files.add(
                    ShareFile(
                        fid = "github:code:${e.sha}",
                        fname = displayName,
                        fsize = -1,
                        isdir = true,
                        pdirFid = "",
                        fidToken = ""
                    )
                )
            } else {
                files.add(
                    ShareFile(
                        fid = "github:file:${e.path}",
                        fname = displayName,
                        fsize = e.size ?: -1,
                        isdir = false,
                        pdirFid = "",
                        fidToken = ""
                    )
                )
            }
        }
        // 先渲染列表（含大小），再并行补时间
        uiState = ResolveUiState.Detail(session!!, files)
        // 并行请求每个条目最后提交时间（Semaphore 限流保护匿名 60/h）；失败静默留空
        viewModelScope.launch {
            val semaphore = Semaphore(8)
            val timeMap = java.util.concurrent.ConcurrentHashMap<String, String>()
            val api = githubApi
            coroutineScope {
                entries.forEach { e ->
                    launch {
                        semaphore.withPermit {
                            // 走内存缓存：命中不发请求，失败/限流结果缓存 1 分钟，in-flight 去重
                            if (api != null) {
                                GitHubCommitDateCache.get(repo.owner, repo.name, e.path, api)
                                    ?.let { iso -> timeMap[e.path] = iso }
                            }
                        }
                    }
                }
            }
            // 目录已切换则丢弃旧结果
            if (dirToken != githubDirToken) return@launch
            if (timeMap.isEmpty()) return@launch
            val updated = files.map { f ->
                val path = when {
                    f.fid.startsWith("github:file:") -> f.fid.removePrefix("github:file:")
                    // tree 条目：fid 是 github:code:{sha}，反查 path 用 entries 中 sha->path
                    f.fid.startsWith("github:code:") -> entries.firstOrNull { it.sha == f.fid.removePrefix("github:code:") }?.path
                    else -> null
                }
                if (path != null && timeMap[path] != null) f.copy(modifyTime = timeMap[path]!!) else f
            }
            uiState = ResolveUiState.Detail(session!!, updated)
        }
    }

    /** Releases 列表：分页累加；首个非预发布非草稿标记为「最新」；满页加「加载更多」 */
    private suspend fun loadGitHubReleases(firstPage: Boolean, page: Int = 1) {
        val repo = currentGitHubRepo ?: return
        if (firstPage) {
            currentGitHubReleases.clear()
        }
        val rels = githubApi?.getReleases(repo.owner, repo.name, page = page)
        if (rels == null && page == 1) {
            uiState = ResolveUiState.Error("加载 Releases 失败")
            return
        }
        if (rels != null) {
            // 并发/重放的加载（切 Tab 会重放 ShareDetailScreen 的 LaunchedEffect）会重复追加同一页，
            // fid 重复 → LazyColumn key 冲突崩溃；按 tagName 去重后追加
            val seen = currentGitHubReleases.mapTo(HashSet()) { it.tagName }
            currentGitHubReleases.addAll(rels.filter { seen.add(it.tagName) })
        }
        // 计算徽章：第一个非预发布非草稿 = 最新；其余按 prerelease/draft
        val badgeMap = mutableMapOf<String, String>()
        var latestMarked = false
        for (rel in currentGitHubReleases) {
            val fid = "github:release:${rel.tagName}"
            when {
                rel.draft -> badgeMap[fid] = "草稿"
                rel.prerelease -> badgeMap[fid] = "预发布"
                !latestMarked -> {
                    badgeMap[fid] = "最新"
                    latestMarked = true
                }
            }
        }
        githubBadges = badgeMap
        val files = currentGitHubReleases.map { rel ->
            ShareFile(
                fid = "github:release:${rel.tagName}",
                fname = rel.tagName,
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = "",
                modifyTime = rel.publishedAt ?: ""
            )
        }.toMutableList()
        // 满页（100 条）追加「加载更多」虚拟文件夹
        if (rels != null && rels.size == 100) {
            files.add(
                ShareFile(
                    fid = "github:more_rel:${page + 1}",
                    fname = "加载更多",
                    fsize = -1,
                    isdir = true,
                    pdirFid = "",
                    fidToken = ""
                )
            )
        }
        uiState = ResolveUiState.Detail(session!!, files)
    }

    /** 单个 Release 的资产列表 */
    private suspend fun loadGitHubReleaseAssets(tag: String) {
        val rel = currentGitHubReleases.firstOrNull { it.tagName == tag }
        if (rel == null) {
            uiState = ResolveUiState.Error("未找到 Release：$tag")
            return
        }
        githubBadges = emptyMap()
        val files = rel.assets.map { a ->
            ShareFile(
                fid = "github:asset:${a.downloadUrl}",
                fname = a.name,
                fsize = a.size,
                isdir = false,
                pdirFid = "",
                fidToken = "",
                modifyTime = a.updatedAt.orEmpty()
            )
        }
        uiState = ResolveUiState.Detail(session!!, files)
    }

    /** 账号/组织仓库列表：先用 /users/{owner} 的 type 字段一次选对端点，避免「users 失败再 orgs」冗余请求；分页累加；满页加「加载更多」 */
    private suspend fun loadGitHubAccountRepos(firstPage: Boolean, page: Int = 1) {
        val owner = currentGitHubOwner ?: return
        if (firstPage) {
            currentGitHubRepos.clear()
            currentGitHubOwnerType = null
        }
        // 首次加载先探测 owner 类型并缓存；type=Organization 走 orgs 端点，其余走 users
        if (currentGitHubOwnerType == null) {
            currentGitHubOwnerType = githubApi?.getUserType(owner)
        }
        var repos = if (currentGitHubOwnerType == "Organization") {
            githubApi?.getOrgRepos(owner, page = page)
        } else {
            // User 或类型探测失败：先走 users；仍失败兜底 orgs（保持对异常情况的兼容）
            githubApi?.getUserRepos(owner, page = page)
        }
        if (repos == null && currentGitHubOwnerType != "Organization") {
            repos = githubApi?.getOrgRepos(owner, page = page)
        }
        if (repos == null && page == 1) {
            uiState = ResolveUiState.Error("无法获取 $owner 的仓库列表")
            return
        }
        if (repos != null) {
            // 同上：按 fullName 去重，避免重复 fid 触发 LazyColumn key 冲突
            val seen = currentGitHubRepos.mapTo(HashSet()) { it.fullName }
            currentGitHubRepos.addAll(repos.filter { seen.add(it.fullName) })
        }
        // 徽章：fork 标记 + 主语言
        val badgeMap = mutableMapOf<String, String>()
        for (r in currentGitHubRepos) {
            val fid = "github:repo:${r.fullName}"
            val parts = buildList {
                if (r.fork) add("Fork")
                r.language?.let { add(it) }
            }
            if (parts.isNotEmpty()) badgeMap[fid] = parts.joinToString(" · ")
        }
        githubBadges = badgeMap
        val files = currentGitHubRepos.map { r ->
            ShareFile(
                fid = "github:repo:${r.fullName}",
                fname = r.name,
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = "",
                modifyTime = r.updatedAt.orEmpty()
            )
        }.toMutableList()
        if (repos != null && repos.size == 100) {
            files.add(
                ShareFile(
                    fid = "github:more_acct:${page + 1}",
                    fname = "加载更多",
                    fsize = -1,
                    isdir = true,
                    pdirFid = "",
                    fidToken = ""
                )
            )
        }
        uiState = ResolveUiState.Detail(session!!, files)
    }

    /** 按 fid 分发加载对应目录层 */
    private suspend fun loadGitHubDir(fid: String) {
        when {
            fid == "github:root" -> loadGitHubRoot()
            fid.startsWith("github:code:") -> loadGitHubCodeDir(fid.removePrefix("github:code:"))
            fid == "github:releases" -> loadGitHubReleases(firstPage = true)
            fid.startsWith("github:release:") -> loadGitHubReleaseAssets(fid.removePrefix("github:release:"))
            fid == "github:account_root" -> loadGitHubAccountRepos(firstPage = true)
            fid.startsWith("github:more_rel:") -> {
                val p = fid.removePrefix("github:more_rel:").toIntOrNull() ?: return
                loadGitHubReleases(firstPage = false, page = p)
            }
            fid.startsWith("github:more_acct:") -> {
                val p = fid.removePrefix("github:more_acct:").toIntOrNull() ?: return
                loadGitHubAccountRepos(firstPage = false, page = p)
            }
            fid.startsWith("github:repo:") -> {
                val fullName = fid.removePrefix("github:repo:")
                val owner = fullName.substringBefore('/')
                val repoName = fullName.substringAfter('/')
                val repo = githubApi?.getRepo(owner, repoName)
                if (repo == null) {
                    uiState = ResolveUiState.Error("无法打开仓库：$fullName")
                    return
                }
                currentGitHubRepo = repo
                githubReadme = null
                loadGitHubRoot()
            }
            else -> uiState = ResolveUiState.Error("未知目录：$fid")
        }
    }

    /** 根据 GitHub fid 计算下载直链（raw / codeload / asset url / 直链）；无法识别返回 null */
    private fun githubUrlForFid(fid: String): String? {
        val repo = currentGitHubRepo ?: return null
        val branch = repo.defaultBranch
        return when {
            fid == "github:zip" ->
                "https://codeload.github.com/${repo.owner}/${repo.name}/zip/refs/heads/$branch"
            fid.startsWith("github:file:") -> {
                val path = fid.removePrefix("github:file:")
                "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/$branch/${encodePath(path)}"
            }
            fid.startsWith("github:asset:") -> fid.removePrefix("github:asset:")
            // github:direct（直链）在 startGitHubResolve 已直接设置 downloadLink，不走此方法
            else -> null
        }
    }

    /**
     * 对 raw 直链路径逐段 URL 编码（保留 `/` 分隔符）。
     * 文件名含 `#`/`?`/空格/中文等字符时，未编码会导致 URL 在 `#`/`?` 处截断。
     */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

    /** 进入文件夹 */
    fun openFolder(file: ShareFile) {
        // GitHub 分支：fid 前缀分发，无需网盘凭证
        if (currentPlatform == SharePlatform.GITHUB) {
            // 「加载更多」虚拟项：原地追加分页，不入目录栈
            if (file.fid.startsWith("github:more_")) {
                viewModelScope.launch {
                    uiState = ResolveUiState.Loading
                    loadGitHubDir(file.fid)
                }
                return
            }
            dirStack.addLast(currentDirFid)
            pathNames = pathNames + file.fname
            currentDirFid = file.fid
            viewModelScope.launch {
                uiState = ResolveUiState.Loading
                loadGitHubDir(file.fid)
            }
            return
        }
        val s = session ?: return
        dirStack.addLast(currentDirFid)
        pathNames = pathNames + file.fname
        currentDirFid = file.fid
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            val credential = currentCredential().orEmpty()
            // 游客模式：子目录同样允许匿名列出（见 Agent.md §3.19）
            loadFiles(s, file.fid, credential, currentRepo())
        }
    }

    /** 返回上级目录 */
    fun goBack() {
        // GitHub 分支：无需凭证，按 fid 重新加载对应层
        if (currentPlatform == SharePlatform.GITHUB) {
            if (dirStack.isEmpty()) return
            currentDirFid = dirStack.removeLast()
            pathNames = pathNames.dropLast(1)
            viewModelScope.launch {
                uiState = ResolveUiState.Loading
                loadGitHubDir(currentDirFid)
            }
            return
        }
        val s = session ?: return
        if (dirStack.isEmpty()) return
        currentDirFid = dirStack.removeLast()
        pathNames = pathNames.dropLast(1)
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            // 游客模式：空凭据照常加载（见 Agent.md §3.19）
            loadFiles(s, currentDirFid, currentCredential().orEmpty(), currentRepo())
        }
    }

    /** 返回：在子目录则返回上一级，在根目录则返回输入页 */
    fun navigateBack() {
        if (dirStack.isEmpty()) {
            backToInput()
        } else {
            goBack()
        }
    }

    /** 返回输入页 */
    fun backToInput() {
        session = null
        downloadLink = null
        currentLink = null
        currentPwd = null
        pathNames = emptyList()
        // 重置 GitHub 平台状态
        currentGitHubRepo = null
        currentGitHubOwner = null
        currentGitHubReleases.clear()
        currentGitHubRepos.clear()
        githubReadme = null
        githubParentFullName = null
        githubBadges = emptyMap()
        isGuest = false
        uiState = ResolveUiState.Idle
    }

    /** 将当前分享链接收藏到指定分类（标题可自定义，为空时回退分享标题） */
    fun addCurrentToBookmark(title: String, category: String) {
        val link = currentLink?.takeIf { it.isNotBlank() }
        if (link == null) {
            SnackbarController.show("缺少分享链接")
            return
        }
        val cat = category.ifBlank { BookmarkEntity.DEFAULT_CATEGORY }
        val resolvedTitle = title.ifBlank { session?.title.orEmpty() }
        viewModelScope.launch {
            bookmarkDao.insert(
                BookmarkEntity(
                    link = link,
                    title = resolvedTitle,
                    platform = currentPlatform.name,
                    pwd = currentPwd.orEmpty(),
                    category = cat
                )
            )
            SnackbarController.show("已收藏到「$cat」")
        }
    }

    /**
     * 面包屑导航：点击第 level 级（0=分享根目录）回退到该目录并刷新列表。
     * 当前所在层（level == pathNames.size）无需操作。
     */
    fun navigateToLevel(level: Int) {
        if (level < 0 || level > pathNames.size) return
        if (level == pathNames.size) return
        // 弹出目录栈直到对应层级；level=0 时回到分享根目录
        while (dirStack.size > level) dirStack.removeLast()
        currentDirFid = if (dirStack.isEmpty()) currentDefaultDirFid() else dirStack.last()
        pathNames = pathNames.take(level)
        // GitHub 分支：无需凭证，按 fid 重新加载
        if (currentPlatform == SharePlatform.GITHUB) {
            viewModelScope.launch {
                uiState = ResolveUiState.Loading
                loadGitHubDir(currentDirFid)
            }
            return
        }
        val s = session ?: return
        viewModelScope.launch {
            // 游客模式：面包屑回退同样允许匿名列出目录
            val credential = currentCredential().orEmpty()
            loadFiles(s, currentDirFid, credential, currentRepo())
        }
    }

    /**
     * 平台是否支持「未登录下载」：
     * - 夸克/UC：分享直链不要求登录态（夸克仅约 50MB 以内，且直链要带游客态 __pugs）；
     * - 光鸭：分享列目录与取链本身匿名可用（受限分享返回 207 时会提示登录转存）；
     * - 蓝奏云：分享解析与取链完全匿名。
     * 其余平台仍要求登录。
     */
    private fun supportsGuestDownload(): Boolean =
        currentPlatform == SharePlatform.QUARK ||
            currentPlatform == SharePlatform.UC ||
            currentPlatform == SharePlatform.GUANGYA ||
            currentPlatform == SharePlatform.ILANZOU ||
            currentPlatform == SharePlatform.LANZOU

    /**
     * 登录态取分享直链：默认优先「免转存」——把分享凭证直接交给 file/download 换直链，
     * 不建临时目录、不转存（统一入口见 [ShareResolveRepository.getShareDownloadLinkWithoutSave]）。
     *
     * 两条退路：
     * - 用户在设置里关掉「免转存下载」（[noSaveDownloadProvider]）⇒ 直接走原来的转存流程；
     * - 开着免转存但夸克个别分享类型服务端仍要求「先转存再取链」⇒ 失败后自动回退转存。
     * 其余平台的默认实现就是原有取链，不存在第二条链路，不重复请求。
     */
    private suspend fun resolveShareLink(
        repo: ShareResolveRepository,
        session: ShareSession,
        file: ShareFile,
        credential: String
    ): Result<DownloadLink> {
        if (!noSaveDownloadProvider()) return repo.getShareDownloadLink(session, file, credential)
        val noSave = repo.getShareDownloadLinkWithoutSave(session, file, credential)
        if (noSave.isSuccess || currentPlatform != SharePlatform.QUARK) return noSave
        return repo.getShareDownloadLink(session, file, credential)
    }

    /** 获取文件下载直链（各平台实现不同：夸克免转存优先（失败回退转存）/ UC 直接取 / 迅雷转存后取详情直链；GitHub 直接构造 URL） */
    fun fetchDownloadLink(file: ShareFile) {
        // GitHub 分支：无需 API 取链，按 fid 直接构造下载 URL 弹确认弹窗
        if (currentPlatform == SharePlatform.GITHUB) {
            viewModelScope.launch {
                downloadLink = null
                downloadError = null
                isFetchingDownloadLink = true
                try {
                    // 直链文件（DirectFile）在 startGitHubResolve 已设置 downloadLink；这里仅处理列表内文件
                    val url = githubUrlForFid(file.fid)
                    if (url == null) {
                        downloadError = "无法获取下载链接"
                        return@launch
                    }
                    downloadLink = DownloadLink(
                        fid = file.fid,
                        filename = file.fname,
                        downloadUrl = url,
                        size = file.fsize
                    )
                } finally {
                    isFetchingDownloadLink = false
                }
            }
            return
        }
        viewModelScope.launch {
            downloadLink = null
            downloadError = null
            isFetchingDownloadLink = true
            try {
                val s = session
                if (s == null) {
                    downloadError = "请先解析分享"
                    return@launch
                }
                val credential = currentCredential()
                if (credential.isNullOrBlank()) {
                    // 游客模式：夸克/UC 的分享直链本身不要求登录（夸克约 50MB 以内，超出报 23018；
                    // UC 实测 4GB 文件都放行），走各自的游客取链；其余平台必须登录
                    if (!supportsGuestDownload()) {
                        downloadError = "下载需要先登录${platformName()}（未登录仅能浏览文件列表）"
                        return@launch
                    }
                    currentRepo().getGuestShareDownloadLink(s, file)
                        .onSuccess { downloadLink = it }
                        .onFailure { downloadError = it.message ?: "获取下载链接失败" }
                    // 游客链路只有一个身份，不存在多源
                    pendingAltLinks = emptyList()
                    return@launch
                }
                // 夸克/UC 共用 __puus：取链前确保新鲜（直链签名绑定取链时刻的 Cookie）
                val quarkCred = when (currentPlatform) {
                    SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
                    SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
                    else -> credential
                }
                // 优先免转存（不往用户网盘里转存）：夸克失败时自动回退到转存取链
                resolveShareLink(currentRepo(), s, file, quarkCred)
                    .onSuccess { link ->
                        downloadLink = link
                        // 单文件多源分片（实验性 C2）：同一时刻再用其他账号各取一条直链，
                        // 下载时由内置分片下载器按分片轮流使用（见 DownloadManager 的「多源分片」）
                        pendingAltLinks = fetchAltLinks(file, quarkCred)
                    }
                    .onFailure { downloadError = it.message ?: "获取下载链接失败" }
            } finally {
                isFetchingDownloadLink = false
            }
        }
    }

    fun dismissDownloadDialog() {
        val link = downloadLink
        downloadLink = null
        // 弹窗没开始下载就关掉：预取的多源直链一并作废（下次取链会重新预取，避免用到过期直链）
        pendingAltLinks = emptyList()
        // 弹窗被关闭（用户点关闭，未开始下载）：清理当前平台的云端临时转存，避免残留
        if (link?.cleanupDirFid != null) {
            viewModelScope.launch {
                val credential = currentCredential() ?: return@launch
                link.cleanupDirFid?.let { dirFid ->
                    currentRepo().cleanupTempDir(dirFid, credential)
                }
            }
        }
    }

    /**
     * 在线预览网盘文件：先取直链（与 [fetchDownloadLink] 同一套分支），再把直链 + 按平台组装好的
     * 请求头交给全局预览页 [PreviewLauncher]。
     *
     * ★ 为什么不复用 [fetchDownloadLink]：那条路径会把直链写进 [downloadLink] 并弹出「开始下载」弹窗，
     *   那是**下载入口**的交互；预览只需要一次性的直链，不能顺带弹下载弹窗，所以这里独立走一遍取链。
     *   但复用同一套加载态（[isFetchingDownloadLink]，由 ResolveScreen 显示加载弹窗）与错误反馈
     *   （[downloadError]），取链失败绝不静默。
     *
     * GitHub 的「直链」是按仓库路径构造出来的下载地址，属于仓库浏览流程，不提供在线预览。
     */
    fun openPreview(file: ShareFile) {
        if (currentPlatform == SharePlatform.GITHUB) return
        // 入口可见性已由 PreviewTypes 判断，这里再挡一次：不可预览的类型（压缩包/未知）不进取链流程
        val kind = PreviewTypes.kindOf(file.fname) ?: return
        val title = file.fname.ifBlank { "在线预览" }
        viewModelScope.launch {
            downloadError = null
            isFetchingDownloadLink = true
            try {
                val s = session
                if (s == null) {
                    downloadError = "请先解析分享"
                    return@launch
                }
                val credential = currentCredential().orEmpty()
                val link: DownloadLink
                val effectiveCredential: String
                if (credential.isBlank()) {
                    // 游客态：与 fetchDownloadLink 一致，仅夸克/UC/光鸭/蓝奏支持匿名取链，其余平台必须登录
                    if (!supportsGuestDownload()) {
                        downloadError = "下载需要先登录${platformName()}（未登录仅能浏览文件列表）"
                        return@launch
                    }
                    link = currentRepo().getGuestShareDownloadLink(s, file).getOrElse {
                        downloadError = it.message ?: "获取下载链接失败"
                        return@launch
                    }
                    // 游客链路只有一个身份：请求头走「游客态 Cookie」分支（guestCookie 非空）
                    effectiveCredential = ""
                } else {
                    // 夸克/UC 共用 __puus：取链前确保新鲜（直链签名绑定取链时刻的 Cookie），与 fetchDownloadLink 一致
                    val quarkCred = when (currentPlatform) {
                        SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
                        SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
                        else -> credential
                    }
                    link = resolveShareLink(currentRepo(), s, file, quarkCred).getOrElse {
                        downloadError = it.message ?: "获取下载链接失败"
                        return@launch
                    }
                    effectiveCredential = effectiveDownloadCredential(credential)
                }
                // 与下载完全同一份请求头：预览页的图片加载器/播放器都要用它，否则平台 403
                val headers = buildDownloadHeaders(
                    platform = currentPlatform,
                    credential = credential,
                    effectiveCredential = effectiveCredential,
                    link = link,
                    guestCookie = link.guestCookie
                )
                if (kind == PreviewKind.TEXT) {
                    // 文本按文本读取（Markdown 是否渲染由预览页决定）；url 用直链即可
                    PreviewLauncher.openText(
                        url = link.downloadUrl,
                        fileName = file.fname,
                        headers = headers,
                        title = title
                    )
                } else {
                    PreviewLauncher.openMedia(
                        items = listOf(
                            PreviewMedia(
                                url = link.downloadUrl,
                                kind = kind,
                                title = title,
                                headers = headers,
                                isHls = link.isHls
                            )
                        ),
                        title = title
                    )
                }
            } finally {
                isFetchingDownloadLink = false
            }
        }
    }

    /**
     * 取链 → 请求头这条链路上共用的「已刷新凭证」。
     *
     * 夸克/UC 共用 __puus：直链签名绑定取链那一刻的 Cookie，请求头里必须用刷新后的那一份，
     * 否则 CDN 直接 412/403（AlistGo/alist#830 类缺陷）。[QuarkAccountRepository.refreshIfPrimary]
     * 只在「传进来的就是主账号那一份」时才刷新（等价于 getFreshCookie），多账号下载轮换到非主账号时
     * 保留它自己的 Cookie（换成主账号的会让直链与身份配成两个账号）。其余平台凭证不由 __puus 决定，原样返回。
     */
    private suspend fun effectiveDownloadCredential(credential: String): String = when (currentPlatform) {
        SharePlatform.QUARK -> accountRepository.refreshIfPrimary(credential)
        SharePlatform.UC -> ucAccountRepository.refreshIfPrimary(credential)
        else -> credential
    }

    /**
     * 按平台组装直链请求头 —— **下载（[enqueueDownload]）与在线预览（[openPreview]）共用同一份**。
     *
     * ★ 抽出来是为了「改一处两处生效」：这些头（Cookie / UA / Referer / Origin）少一个，
     *   下载会被 CDN 拒（403/412），预览会图裂/黑屏，两边规则必须永远一致。
     *
     * @param credential 登录凭证原文（游客链路为空串）
     * @param effectiveCredential [credential] 经 __puus 刷新后的值（见 [effectiveDownloadCredential]）
     * @param guestCookie 游客态直链随取链响应下发的 Cookie（登录态为空串）
     */
    private fun buildDownloadHeaders(
        platform: SharePlatform,
        credential: String,
        effectiveCredential: String,
        link: DownloadLink,
        guestCookie: String
    ): Map<String, String> {
        val isGuestLink = guestCookie.isNotBlank()
        // 迅雷直链 URL 自带签名，无需 Cookie；夸克/UC/百度需 Cookie + UA；139 直链为 CDN 签名地址；123 直链需 Referer
        // 115 直链需「登录 Cookie + 取链响应下发的 900 秒 CDN Cookie」双 Cookie（缺一即 CDN 403 no cookie value）
        return when (platform) {
            SharePlatform.XUNLEI -> mapOf("User-Agent" to XunleiConstants.APP_UA) // 迅雷直链必须用官方 app UA，浏览器 UA 会触发 CDN 降级（200整文件）
            SharePlatform.BAIDU -> mapOf(
                "Cookie" to credential,
                "User-Agent" to BaiduConstants.UA_NETDISK
            )
            SharePlatform.C139 -> mapOf("User-Agent" to C139Constants.PC_UA)
            // 115：CDN 同时校验登录 Cookie 与取链响应下发的 900 秒 CDN Cookie，
            // 缺任一直接 403 no cookie value（文档 §4.3），因此两份 Cookie 拼接后一起发；
            // UA 必须是客户端串（与取链一致），浏览器 UA 会被判「网页端」拒发大文件直链（抓包 bug/115/）
            SharePlatform.PAN115 -> mapOf(
                "Cookie" to Pan115Constants.mergeCookies(effectiveCredential, guestCookie),
                "User-Agent" to Pan115Constants.CLIENT_UA,
                "Referer" to Pan115Constants.DOWNLOAD_REFERER
            )
            // 123 分享/个人盘直链为 CDN 签名地址，下载必须带 Referer（文档 §5.3.1）
            SharePlatform.PAN123 -> mapOf(
                "User-Agent" to Pan123Constants.WEB_UA,
                "Referer" to Pan123Constants.DOWNLOAD_REFERER
            )
            // 光鸭直链为签名 CDN 地址，下载带 UA + Referer，且不携带 Authorization/Cookie（文档 §5.6/§7.1）
            SharePlatform.GUANGYA -> mapOf(
                "User-Agent" to GuangYaConstants.WEB_UA,
                "Referer" to GuangYaConstants.DOWNLOAD_REFERER
            )
            // 蓝奏优享直链为 CDN 地址，下载带 UA + Referer + Origin，不带 Cookie（文档 §4.1）
            SharePlatform.ILANZOU -> mapOf(
                "User-Agent" to ILanzouConstants.WEB_UA,
                "Referer" to ILanzouConstants.DOWNLOAD_REFERER,
                "Origin" to ILanzouConstants.WEB_SITE
            )
            // 蓝奏云下载节点要求 Referer 为节点 origin，并注入 down_ip=1 Cookie（文档 §A4）
            SharePlatform.LANZOU -> {
                val lanzouOrigin = runCatching { java.net.URI(link.downloadUrl) }.getOrNull()
                    ?.let { "${it.scheme}://${it.host}" } ?: "https://pan.lanzoui.com"
                mapOf(
                    "User-Agent" to LanzouConstants.WEB_UA,
                    "Referer" to "$lanzouOrigin/",
                    "Cookie" to LanzouConstants.DOWN_IP_COOKIE
                )
            }
            // UC：OSS 直链按 Referer 档位限速（缺 Referer 被 Callback 限到 ~100 KB/s），
            // 补官方 Web 客户端同款 Referer/Origin 即满速
            SharePlatform.UC -> if (isGuestLink) mapOf(
                // 游客链路换 uc-cloud-drive 客户端 UA + Sec-Ch-Ua（参考实现的游客请求同款头），Referer 仍不可少
                "Cookie" to guestCookie,
                "User-Agent" to UCConstants.GUEST_UA,
                "Sec-Ch-Ua" to UCConstants.GUEST_SEC_CH_UA,
                "Referer" to UCConstants.DOWNLOAD_REFERER,
                "Origin" to UCConstants.WEB_ORIGIN
            ) else mapOf(
                "Cookie" to credential,
                "User-Agent" to UCConstants.USER_AGENT,
                "Referer" to UCConstants.DOWNLOAD_REFERER,
                "Origin" to UCConstants.WEB_ORIGIN
            )
            // 夸克（以及其余未单独列出的平台）：防盗链需固定 Referer（对齐 AList quark_uc）
            else -> if (isGuestLink) mapOf(
                "Cookie" to guestCookie,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            ) else mapOf(
                "Cookie" to effectiveCredential,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            )
        }
    }

    /**
     * 将直链加入下载队列（携带对应平台凭证与 UA；夸克直链做 CDN 节点优选）。
     * 不触发切页 —— 与 startDownload 的区别：批量下载全部入队后才统一切到下载页。
     */
    private suspend fun enqueueDownload(
        link: DownloadLink,
        credential: String,
        fileName: String = link.filename,
        /**
         * 单文件多源分片（实验性 C2）的备选直链（其他账号的直链 + 该账号凭证）；空 = 单源。
         * 由 [fetchAltLinks] 预取，来自取链弹窗那一次交互，见 [pendingAltLinks]。
         */
        altLinks: List<Pair<DownloadLink, String>> = emptyList()
    ) {
        val isUC = currentPlatform == SharePlatform.UC
        val isXunlei = currentPlatform == SharePlatform.XUNLEI
        val isBaidu = currentPlatform == SharePlatform.BAIDU
        val isC139 = currentPlatform == SharePlatform.C139
        val isPan123 = currentPlatform == SharePlatform.PAN123
        val isPan115 = currentPlatform == SharePlatform.PAN115
        val isGuangYa = currentPlatform == SharePlatform.GUANGYA
        val isILanzou = currentPlatform == SharePlatform.ILANZOU
        val isLanzou = currentPlatform == SharePlatform.LANZOU
        val isQuark = currentPlatform == SharePlatform.QUARK
        // 下载来源平台：按平台应用下载线程数设置
        val platform = when {
            isXunlei -> DownloadPlatform.XUNLEI
            isUC -> DownloadPlatform.UC
            isBaidu -> DownloadPlatform.BAIDU
            isC139 -> DownloadPlatform.C139
            isPan123 -> DownloadPlatform.PAN123
            isPan115 -> DownloadPlatform.PAN115
            isGuangYa -> DownloadPlatform.GUANGYA
            isILanzou -> DownloadPlatform.ILANZOU
            isLanzou -> DownloadPlatform.LANZOU
            else -> DownloadPlatform.QUARK
        }
        // 【关键修复】夸克/UC 共用 __puus：取链与下载必须用同一份已刷新 Cookie（AlistGo/alist#830 类缺陷）
        val effectiveCredential = effectiveDownloadCredential(credential)
        // 游客直链（夸克/UC 小文件）：只有服务端随取链响应下发的游客态 __pugs，没有登录 Cookie。
        // 夸克缺它 CDN 直接 412；UC 缺它 CDN 403（RequestDeniedByCallback: require login）
        val guestCookie = link.guestCookie
        val isGuestLink = guestCookie.isNotBlank()
        // 请求头组装见 buildDownloadHeaders：下载与在线预览共用同一份规则（避免两处漂移）
        val headers = buildDownloadHeaders(currentPlatform, credential, effectiveCredential, link, guestCookie)
        // 夸克直链：原样使用（关闭节点改写/探测，避免消耗直链额度与节点签名 412）
        val effectiveUrl = if (isQuark) {
            QuarkCdn.fastest(link.downloadUrl, if (isGuestLink) guestCookie else effectiveCredential)
        } else {
            link.downloadUrl
        }
        // 入队前先记住「哪个平台的仓库 + 哪份凭证」：下载完成时用户可能已经切到别的分享页
        val cleanupRepo = currentRepo()
        val cleanupCredential = credential
        // 单文件多源分片（实验性 C2）：备选源 = 其他账号的直链 + 「主源那一套请求头、只换 Cookie」。
        // ★ 受支持的四个平台（夸克 / UC / 迅雷 / 百度）的下载请求头差异只有 Cookie 一项
        //   （迅雷直链头里根本没有 Cookie，直链自带签名），所以这里沿用主源头再换 Cookie 即可；
        //   将来若给 115（双 Cookie）之类开多源，必须重新评估这一处。
        val altSources = altLinks.map { (altLink, altCred) ->
            val url = if (isQuark) {
                // 夸克每条备选直链同样做一次 CDN 节点优选（原样直链容易 412）
                QuarkCdn.fastest(altLink.downloadUrl, altCred)
            } else {
                altLink.downloadUrl
            }
            val headersForAlt = if (headers.containsKey("Cookie")) {
                headers + ("Cookie" to altCred)
            } else {
                headers
            }
            DownloadSource(url, headersForAlt)
        }
        downloadManager.enqueue(
            url = effectiveUrl,
            fileName = fileName,
            headers = headers,
            size = link.size,
            platform = platform,
            altSources = altSources
        ) {
            // 下载完成（master 版通过 onComplete 回调）：清理网盘临时转存目录；失败/取消不触发
            val dirFid = link.cleanupDirFid
            if (dirFid != null && cleanupCredential.isNotBlank()) {
                cleanupRepo.cleanupTempDir(dirFid, cleanupCredential)
            }
        }
    }

    /** 将直链加入下载队列（单文件下载：入队后立即切换到下载页） */
    fun startDownload(link: DownloadLink) {
        viewModelScope.launch {
            // 开始下载：先关闭弹窗（临时转存由下载完成 onComplete 清理，不在此时删）
            downloadLink = null
            // GitHub 分支：无需网盘凭证，直链先经镜像前缀转换，带 size 和 platform=github 入队
            if (currentPlatform == SharePlatform.GITHUB) {
                val prefix = mirrorPrefixProvider()
                downloadManager.enqueue(
                    url = UpdateChecker.mirrorUrl(link.downloadUrl, prefix),
                    fileName = link.filename,
                    size = link.size,
                    platform = DownloadPlatform.GITHUB,
                    // 镜像挂掉时回退原始直连（link.downloadUrl 为未镜像的 GitHub 直链）
                    fallbackUrl = link.downloadUrl
                )
                downloadStarted = true
                return@launch
            }
            // 游客模式：夸克/UC 的小文件直链不要求登录（link 里已带游客 __pugs）
            val credential = currentCredential().orEmpty()
            if (credential.isBlank() && !supportsGuestDownload()) {
                downloadError = "下载需要先登录${platformName()}（未登录仅能浏览文件列表）"
                return@launch
            }
            // 单文件多源分片（实验性 C2）：把取链时预取的备选源一起交给下载器；用完即弃
            // （下次打开取链弹窗会重新预取，避免用到过期直链）
            enqueueDownload(link, credential, altLinks = pendingAltLinks)
            pendingAltLinks = emptyList()
            downloadStarted = true
        }
    }

    private suspend fun loadFiles(
        s: ShareSession,
        dirFid: String,
        credential: String,
        repo: ShareResolveRepository
    ) {
        repo.listFiles(s, dirFid, credential)
            .onSuccess { files ->
                uiState = ResolveUiState.Detail(s, files)
            }
            .onFailure { e ->
                uiState = ResolveUiState.Error(e.message ?: "获取文件列表失败")
            }
    }

    class Factory(
        private val accountRepository: QuarkAccountRepository,
        private val resolveRepository: QuarkResolveRepository,
        private val ucAccountRepository: UCAccountRepository,
        private val ucResolveRepository: UCResolveRepository,
        private val xunleiAccountRepository: XunleiAccountRepository,
        private val xunleiResolveRepository: XunleiResolveRepository,
        private val baiduAccountRepository: BaiduAccountRepository,
        private val baiduResolveRepository: BaiduResolveRepository,
        private val c139AccountRepository: C139AccountRepository,
        private val c139ResolveRepository: C139ResolveRepository,
        private val pan123AccountRepository: Pan123AccountRepository,
        private val pan123ResolveRepository: Pan123ResolveRepository,
        private val pan115AccountRepository: Pan115AccountRepository,
        private val pan115ResolveRepository: Pan115ResolveRepository,
        private val guangyaAccountRepository: GuangYaAccountRepository,
        private val guangyaResolveRepository: GuangYaResolveRepository,
        private val ilanzouAccountRepository: ILanzouAccountRepository,
        private val ilanzouResolveRepository: ILanzouResolveRepository,
        private val lanzouAccountRepository: LanzouAccountRepository,
        private val lanzouResolveRepository: LanzouResolveRepository,
        private val downloadManager: DownloadManager,
        private val bookmarkDao: BookmarkDao,
        private val githubApi: GitHubApi? = null,
        private val mirrorPrefixProvider: () -> String = { UpdateChecker.MIRROR_PREFIX },
        private val noSaveDownloadProvider: () -> Boolean = { true },
        private val multiAccountDownloadProvider: () -> Boolean = { false },
        private val multiSourceDownloadProvider: () -> Boolean = { false },
        /** 视频解析仓库；null 时视频解析不可用（入口自动回退网盘/GitHub 流程） */
        private val videoParseRepository: VideoParseRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ResolveViewModel::class.java))
            return ResolveViewModel(
                accountRepository, resolveRepository,
                ucAccountRepository, ucResolveRepository,
                xunleiAccountRepository, xunleiResolveRepository,
                baiduAccountRepository, baiduResolveRepository,
                c139AccountRepository, c139ResolveRepository,
                pan123AccountRepository, pan123ResolveRepository,
                pan115AccountRepository, pan115ResolveRepository,
                guangyaAccountRepository, guangyaResolveRepository,
                ilanzouAccountRepository, ilanzouResolveRepository,
                lanzouAccountRepository, lanzouResolveRepository,
                downloadManager,
                bookmarkDao,
                githubApi,
                mirrorPrefixProvider,
                noSaveDownloadProvider,
                multiAccountDownloadProvider,
                multiSourceDownloadProvider,
                videoParseRepository
            ) as T
        }
    }
}
