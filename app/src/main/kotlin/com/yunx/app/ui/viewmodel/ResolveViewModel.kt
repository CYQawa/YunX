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
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubCommitDateCache
import com.yunx.app.data.network.GitHubResponseCache
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.data.network.GitHubLinkType
import com.yunx.app.data.network.GitHubRepo
import com.yunx.app.data.network.GitHubRelease
import com.yunx.app.data.network.Pan123Constants
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.QuarkCdn
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.data.repository.BaiduAccountRepository
import com.yunx.app.data.repository.BaiduResolveRepository
import com.yunx.app.data.repository.C139AccountRepository
import com.yunx.app.data.repository.C139ResolveRepository
import com.yunx.app.data.repository.Pan123AccountRepository
import com.yunx.app.data.repository.Pan123ResolveRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.ShareResolveRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiAccountRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import com.yunx.app.ui.SnackbarController
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface ResolveUiState {
    data object Idle : ResolveUiState
    data object Loading : ResolveUiState
    data class Detail(val session: ShareSession, val files: List<ShareFile>) : ResolveUiState
    data class Error(val message: String) : ResolveUiState
}

/**
 * 解析页 ViewModel：分享解析状态机 + 目录导航 + 下载直链。
 * 支持夸克 / UC / 迅雷，按链接自动路由到对应平台仓库与凭证。
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
    private val downloadManager: DownloadManager,
    private val bookmarkDao: BookmarkDao,
    /** GitHub 解析器实例（null 表示未启用 GitHub 平台） */
    private val githubApi: GitHubApi? = null,
    /** GitHub 下载镜像前缀提供者（由上层从 SettingsRepository 注入用户配置） */
    private val mirrorPrefixProvider: () -> String = { UpdateChecker.MIRROR_PREFIX }
) : ViewModel() {

    var uiState by mutableStateOf<ResolveUiState>(ResolveUiState.Idle)
        private set

    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set

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

    /** 当前分享是否支持转存（夸克 / UC / 迅雷 / 百度 / 139 / 123） */
    val canSave: Boolean
        get() = currentPlatform == SharePlatform.QUARK ||
            currentPlatform == SharePlatform.UC ||
            currentPlatform == SharePlatform.XUNLEI ||
            currentPlatform == SharePlatform.BAIDU ||
            currentPlatform == SharePlatform.C139 ||
            currentPlatform == SharePlatform.PAN123

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

    /** 请求转存：记录目标文件并打开目录选择弹窗 */
    fun requestSave(file: ShareFile) {
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
                    downloadError = "请先登录${platformName()}"
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

    /** 批量下载：逐个取直链入队（选中文件夹时递归下载整个文件夹并保持目录结构，全部获取完再统一切到下载页） */
    fun batchDownload() {
        // GitHub 分支：无需凭证/取链 API，逐文件构造直链入队（代码文件夹递归收集 blob）
        if (currentPlatform == SharePlatform.GITHUB) {
            batchGitHubDownload()
            return
        }
        val files = _selected.toList()
        val s = session ?: return
        viewModelScope.launch {
            isBatchWorking = true
            batchProgress = "正在收集文件…"
            batchCancelRequested = false
            try {
                val credential = currentCredential()
                if (credential.isNullOrBlank()) {
                    downloadError = "请先登录网盘"
                    return@launch
                }
                // 夸克/UC 共用 __puus：取链与下载必须用同一份已刷新 Cookie（直链签名绑定取链时刻的 __puus）
                val quarkCred = when (currentPlatform) {
                    SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
                    SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
                    else -> credential
                }
                // 展开选中项：文件直接加入，文件夹递归收集（相对路径 = 文件夹名/子/...）
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
                        currentRepo().getShareDownloadLink(s, file, quarkCred).getOrNull()?.let { link ->
                            // 文件夹内文件用相对路径（保持目录结构）；根目录文件用取链返回的文件名
                            enqueueDownload(link, quarkCred, if (relPath.isBlank()) link.filename else relPath)
                            okCount++
                        }
                    }
                }
                if (!interrupted) {
                    downloadError = if (okCount > 0) "已加入 $okCount 个下载任务" else "获取下载链接失败"
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
     * GitHub 批量下载：选中的叶子文件直接入队；代码文件夹（github:code:*）递归 getTree 收集 blob；
     * Releases 文件夹展开为资产；其余（repo 根/账号列表）跳过。全部直链先经镜像前缀转换。
     */
    private fun batchGitHubDownload() {
        val selected = _selected.toList()
        viewModelScope.launch {
            isBatchWorking = true
            batchProgress = "正在收集文件…"
            batchCancelRequested = false
            try {
                val prefix = mirrorPrefixProvider()
                val tasks = mutableListOf<Triple<String, String, Long>>() // url, fileName, size
                // 收集文件
                for (file in selected) {
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

    /** 当前是否为 GitHub 平台（UI 据此渲染 README/forked from/徽章等额外内容） */
    val isGitHubPlatform: Boolean get() = currentPlatform == SharePlatform.GITHUB

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

    /** 当前平台凭证（夸克/UC/百度/139 用 cookie，迅雷/123 用 access_token；GitHub 无需凭证） */
    private suspend fun currentCredential(): String? = when (currentPlatform) {
        SharePlatform.UC -> ucAccountRepository.getAccount()?.cookie
        SharePlatform.XUNLEI -> xunleiAccountRepository.getAccount()?.accessToken
        SharePlatform.BAIDU -> baiduAccountRepository.getAccount()?.cookie
        SharePlatform.C139 -> c139AccountRepository.getAccount()?.cookie
        SharePlatform.PAN123 -> pan123AccountRepository.getAccount()?.accessToken
        SharePlatform.GITHUB -> null
        else -> accountRepository.getAccount()?.cookie
    }

    private fun currentRepo(): ShareResolveRepository = when (currentPlatform) {
        SharePlatform.UC -> ucResolveRepository
        SharePlatform.XUNLEI -> xunleiResolveRepository
        SharePlatform.BAIDU -> baiduResolveRepository
        SharePlatform.C139 -> c139ResolveRepository
        SharePlatform.PAN123 -> pan123ResolveRepository
        else -> resolveRepository
    }

    private fun currentDefaultDirFid(): String = when (currentPlatform) {
        SharePlatform.UC -> UCConstants.DEFAULT_PDIR_FID
        SharePlatform.XUNLEI -> "0"
        SharePlatform.BAIDU -> ""
        SharePlatform.C139 -> "0"
        SharePlatform.PAN123 -> "0"
        SharePlatform.GITHUB -> "github:root"
        else -> QuarkConstants.DEFAULT_PDIR_FID
    }

    private fun platformName(): String = when (currentPlatform) {
        SharePlatform.UC -> "UC 网盘"
        SharePlatform.XUNLEI -> "迅雷网盘"
        SharePlatform.BAIDU -> "百度网盘"
        SharePlatform.C139 -> "139 网盘"
        SharePlatform.PAN123 -> "123云盘"
        SharePlatform.GITHUB -> "GitHub"
        else -> "夸克网盘"
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
            val parsed = ShareLinkParser.parse(link)
            if (parsed == null) {
                uiState = ResolveUiState.Error("无法识别分享链接")
                return@launch
            }
            currentPlatform = parsed.platform
            val credential = currentCredential()
            if (credential.isNullOrBlank()) {
                uiState = ResolveUiState.Error("请先在「网盘」页登录${platformName()}")
                return@launch
            }
            val repo = currentRepo()
            repo.createSession(link, pwd, credential)
                .onSuccess { s ->
                    session = s
                    currentDirFid = currentDefaultDirFid()
                    dirStack.clear()
                    pathNames = emptyList()
                    loadFiles(s, currentDirFid, credential, repo)
                }
                .onFailure { e ->
                    uiState = ResolveUiState.Error(e.message ?: "解析失败")
                }
        }
    }

    // ---------- GitHub 平台入口与导航 ----------

    /**
     * GitHub 链接解析入口：仓库 → 代码/Releases 根；账号 → 仓库列表；直链 → 直接弹下载确认。
     * 复用 ShareDetailScreen 框架（搜索/多选/批量/确认弹窗/收藏/面包屑），canSave=false（无转存）。
     */
    fun startGitHubResolve(linkType: GitHubLinkType) {
        currentPlatform = SharePlatform.GITHUB
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
        if (rels != null) currentGitHubReleases.addAll(rels)
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
        if (repos != null) currentGitHubRepos.addAll(repos)
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
            val credential = currentCredential()
            if (credential.isNullOrBlank()) {
                uiState = ResolveUiState.Error("登录已失效，请重新登录")
                return@launch
            }
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
            val credential = currentCredential()
            if (credential.isNullOrBlank()) return@launch
            loadFiles(s, currentDirFid, credential, currentRepo())
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
            val credential = currentCredential() ?: return@launch
            loadFiles(s, currentDirFid, credential, currentRepo())
        }
    }

    /** 获取文件下载直链（各平台实现不同：夸克转存后取 / UC 直接取 / 迅雷转存后取详情直链；GitHub 直接构造 URL） */
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
                    downloadError = "登录已失效，请重新登录"
                    return@launch
                }
                // 夸克/UC 共用 __puus：取链前确保新鲜（直链签名绑定取链时刻的 Cookie）
                val quarkCred = when (currentPlatform) {
                    SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
                    SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
                    else -> credential
                }
                currentRepo().getShareDownloadLink(s, file, quarkCred)
                    .onSuccess { downloadLink = it }
                    .onFailure { downloadError = it.message ?: "获取下载链接失败" }
            } finally {
                isFetchingDownloadLink = false
            }
        }
    }

    fun dismissDownloadDialog() {
        val link = downloadLink
        downloadLink = null
        // 弹窗被关闭（用户点管壁/「关闭」，未开始下载）：清理夸克临时转存，避免云端残留
        if (link?.cleanupDirFid != null) {
            viewModelScope.launch {
                val credential = accountRepository.getAccount()?.cookie ?: return@launch
                link.cleanupDirFid?.let { dirFid ->
                    resolveRepository.cleanupTempDir(dirFid, credential)
                }
            }
        }
    }

    /**
     * 将直链加入下载队列（携带对应平台凭证与 UA；夸克直链做 CDN 节点优选）。
     * 不触发切页 —— 与 startDownload 的区别：批量下载全部入队后才统一切到下载页。
     */
    private suspend fun enqueueDownload(
        link: DownloadLink,
        credential: String,
        fileName: String = link.filename
    ) {
        val isUC = currentPlatform == SharePlatform.UC
        val isXunlei = currentPlatform == SharePlatform.XUNLEI
        val isBaidu = currentPlatform == SharePlatform.BAIDU
        val isC139 = currentPlatform == SharePlatform.C139
        val isPan123 = currentPlatform == SharePlatform.PAN123
        val isQuark = currentPlatform == SharePlatform.QUARK
        // 下载来源平台：按平台应用下载线程数设置
        val platform = when {
            isXunlei -> DownloadPlatform.XUNLEI
            isUC -> DownloadPlatform.UC
            isBaidu -> DownloadPlatform.BAIDU
            isC139 -> DownloadPlatform.C139
            isPan123 -> DownloadPlatform.PAN123
            else -> DownloadPlatform.QUARK
        }
        // 【关键修复】夸克/UC 共用 __puus：取链与下载必须用同一份已刷新 Cookie（AlistGo/alist#830 类缺陷）
        // getFreshCookie 有 90 分钟间隔保护，与取链处调用幂等，得到的是同一份。
        val effectiveCredential = when (currentPlatform) {
            SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
            SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
            else -> credential
        }
        // 迅雷直链 URL 自带签名，无需 Cookie；夸克/UC/百度需 Cookie + UA；139 直链为 CDN 签名地址；123 直链需 Referer
        val headers = when {
            isXunlei -> mapOf("User-Agent" to XunleiConstants.APP_UA) // 迅雷直链必须用官方 app UA，浏览器 UA 会触发 CDN 降级（200整文件）
            isBaidu -> mapOf(
                "Cookie" to credential,
                "User-Agent" to BaiduConstants.UA_NETDISK
            )
            isC139 -> mapOf("User-Agent" to C139Constants.PC_UA)
            // 123 分享/个人盘直链为 CDN 签名地址，下载必须带 Referer（文档 §5.3.1）
            isPan123 -> mapOf(
                "User-Agent" to Pan123Constants.WEB_UA,
                "Referer" to Pan123Constants.DOWNLOAD_REFERER
            )
            // UC：OSS 直链按 Referer 档位限速（缺 Referer 被 Callback 限到 ~100 KB/s），
            // 补官方 Web 客户端同款 Referer/Origin 即满速
            isUC -> mapOf(
                "Cookie" to credential,
                "User-Agent" to UCConstants.USER_AGENT,
                "Referer" to UCConstants.DOWNLOAD_REFERER,
                "Origin" to UCConstants.WEB_ORIGIN
            )
            // 夸克：防盗链需固定 Referer（对齐 AList quark_uc）
            else -> mapOf(
                "Cookie" to effectiveCredential,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            )
        }
        // 夸克直链：原样使用（关闭节点改写/探测，避免消耗直链额度与节点签名 412）
        val effectiveUrl = if (isQuark) {
            QuarkCdn.fastest(link.downloadUrl, effectiveCredential)
        } else {
            link.downloadUrl
        }
        downloadManager.enqueue(
            url = effectiveUrl,
            fileName = fileName,
            headers = headers,
            size = link.size,
            platform = platform
        ) {
            // 下载完成（master 版通过 onComplete 回调）：清理网盘临时转存目录；失败/取消不触发
            val dirFid = link.cleanupDirFid
            if (dirFid != null) {
                val credential = currentCredential()
                if (!credential.isNullOrBlank()) {
                    resolveRepository.cleanupTempDir(dirFid, credential)
                }
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
            val credential = currentCredential()
            if (credential.isNullOrBlank()) {
                downloadError = "请先登录网盘"
                return@launch
            }
            enqueueDownload(link, credential)
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
        private val downloadManager: DownloadManager,
        private val bookmarkDao: BookmarkDao,
        private val githubApi: GitHubApi? = null,
        private val mirrorPrefixProvider: () -> String = { UpdateChecker.MIRROR_PREFIX }
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
                downloadManager,
                bookmarkDao,
                githubApi,
                mirrorPrefixProvider
            ) as T
        }
    }
}
