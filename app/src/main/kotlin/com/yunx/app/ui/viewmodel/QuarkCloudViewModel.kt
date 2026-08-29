package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 夸克云盘浏览 UI 状态 */
sealed interface QuarkCloudUiState {
    data object Loading : QuarkCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        val dirFid: String
    ) : QuarkCloudUiState
    data class Error(val message: String) : QuarkCloudUiState
}

/**
 * 夸克云盘浏览 ViewModel：
 * - 目录浏览（根/子目录/面包屑回退）
 * - 文件操作：下载 / 重命名 / 移动 / 删除 / 创建分享
 * 操作成功后自动刷新当前目录，结果通过 cloudMessage（Toast）反馈。
 */
class QuarkCloudViewModel(
    private val api: QuarkApi,
    private val cookieProvider: suspend () -> String?,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<QuarkCloudUiState>(QuarkCloudUiState.Loading)
    val uiState: StateFlow<QuarkCloudUiState> = _uiState.asStateFlow()

    /** 当前操作的文件（更多按钮弹出操作菜单） */
    var actionFile by mutableStateOf<ShareFile?>(null)
        private set

    /** 操作结果消息（Toast） */
    var cloudMessage by mutableStateOf<String?>(null)
        private set

    /** 操作执行中（防止重复点击） */
    var isOperating by mutableStateOf(false)
        private set

    /** 文件夹下载/批量下载进度提示（如 "正在加入下载 3/10"）；null 不显示 */
    var folderProgress by mutableStateOf<String?>(null)
        private set

    /** 下载中断请求（UI 点「中断」置 true，下载循环检查后停止剩余项） */
    private var downloadCancelRequested = false

    /** 中断当前下载（批量下载/文件夹下载） */
    fun cancelDownload() {
        downloadCancelRequested = true
    }

    /** 下拉刷新中（不切换 Loading 遮罩，保持列表显示） */
    var refreshing by mutableStateOf(false)
        private set

    /** 下载入队事件计数（UI 监听后切换到下载页；对齐解析页行为） */
    var downloadTriggered by mutableStateOf(0)
        private set

    /** 消费下载事件（防止再次进入网盘页重复触发切页） */
    fun consumeDownloadTriggered() {
        downloadTriggered = 0
    }

    /** 分享创建成功后的信息（弹窗展示链接+提取码） */
    var shareResult by mutableStateOf<ShareInfo?>(null)
        private set

    /** 目录 fid 栈（不含根目录 "0"） */
    private val dirStack = ArrayDeque<String>()
    /** 目录名栈（与 dirStack 一一对应） */
    private val nameStack = ArrayDeque<String>()

    // ---------- 移动目标目录浏览（独立状态，避免影响主列表） ----------

    private val _moveUiState = MutableStateFlow<QuarkCloudUiState>(QuarkCloudUiState.Loading)
    val moveUiState: StateFlow<QuarkCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    /** 打开移动目标浏览（回到根目录） */
    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad("0", emptyList())
    }

    /** 移动目标：进入文件夹 */
    fun openMoveFolder(file: ShareFile) {
        moveDirStack.addLast(file.fid)
        moveNameStack.addLast(file.fname)
        moveLoad(file.fid, moveNameStack.toList())
    }

    /** 移动目标：返回上一级 */
    fun moveBack() {
        if (moveNameStack.isEmpty()) return
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        moveLoad(moveDirStack.lastOrNull() ?: "0", moveNameStack.toList())
    }

    /** 移动目标：面包屑回退 */
    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: "0", moveNameStack.toList())
    }

    private fun moveLoad(dirFid: String, pathNames: List<String>) {
        _moveUiState.value = QuarkCloudUiState.Loading
        viewModelScope.launch {
            val cookie = cookieProvider()
            if (cookie.isNullOrBlank()) {
                _moveUiState.value = QuarkCloudUiState.Error("请先登录夸克网盘")
                return@launch
            }
            try {
                val files = api.listCloudFiles(dirFid, cookie) ?: emptyList()
                _moveUiState.value = QuarkCloudUiState.Loaded(files, pathNames, dirFid)
            } catch (e: Exception) {
                _moveUiState.value = QuarkCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    init {
        loadRoot()
    }

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("0", emptyList())
    }

    /** 进入文件夹 */
    fun openFolder(file: ShareFile) {
        dirStack.addLast(file.fid)
        nameStack.addLast(file.fname)
        load(file.fid, nameStack.toList())
    }

    /** 返回上一级（根目录时重新加载根） */
    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: "0", nameStack.toList())
    }

    /** 面包屑回退到第 level 层（0=根目录） */
    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "0", nameStack.toList())
    }

    // ---------- 文件操作 ----------

    /** 多选模式（长按进入） */
    var multiSelectMode by mutableStateOf(false)
        private set

    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    /** 长按进入多选并选中该文件 */
    fun enterMultiSelect(file: ShareFile) {
        multiSelectMode = true
        _selected.clear()
        _selected.add(file)
    }

    /** 切换选中状态 */
    fun toggleSelect(file: ShareFile) {
        if (_selected.contains(file)) _selected.remove(file) else _selected.add(file)
    }

    /** 全选/取消全选当前目录 */
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

    /** 打开文件操作菜单 */
    fun openActions(file: ShareFile) {
        actionFile = file
    }

    fun dismissActions() {
        actionFile = null
    }

    fun consumeMessage() {
        cloudMessage = null
    }

    fun dismissShareResult() {
        shareResult = null
    }

    /** 夸克下载直链的请求头（Cookie + 网盘 UA + 防盗链 Referer，对齐 AList quark_uc） */
    private fun downloadHeaders(cookie: String): Map<String, String> = mapOf(
        "Cookie" to cookie,
        "User-Agent" to com.yunx.app.data.network.QuarkConstants.API_USER_AGENT,
        "Referer" to com.yunx.app.data.network.QuarkConstants.DOWNLOAD_REFERER
    )

    /**
     * 递归收集文件夹内所有文件（保持目录结构）。
     */
    private suspend fun collectFolderFiles(
        dirFid: String,
        prefix: String,
        cookie: String,
        result: MutableList<Pair<ShareFile, String>>,
        depth: Int
    ) {
        if (depth > 12) return
        val list = runCatching { api.listCloudFiles(dirFid, cookie) ?: emptyList() }
            .getOrDefault(emptyList())
        list.filter { !it.isdir }.forEach { result.add(it to "$prefix/${it.fname}") }
        list.filter { it.isdir }.forEach {
            collectFolderFiles(it.fid, "$prefix/${it.fname}", cookie, result, depth + 1)
        }
    }

    /** 下载整个文件夹（操作菜单）：递归收集所有文件，保持目录结构保存到 Download */
    fun downloadFolder() {
        val folder = actionFile ?: return
        if (!folder.isdir) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                collectFolderFiles(folder.fid, folder.fname, cookie, tasks, 0)
                if (tasks.isEmpty()) {
                    cloudMessage = "文件夹为空"
                    actionFile = null
                    return@launch
                }
                var okCount = 0
                tasks.forEachIndexed { index, (file, relPath) ->
                    // 用户点击「中断」：跳过剩余项（已入队任务保留下载）
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = api.getDownloadLink(file.fid, cookie) ?: return@runCatching
                        val effectiveUrl = com.yunx.app.data.network.QuarkCdn.fastest(link.downloadUrl, cookie)
                        downloadManager.enqueue(
                            url = effectiveUrl,
                            fileName = relPath, // 相对路径：Download/文件夹A/子目录/文件.mp4
                            size = link.size,
                            platform = DownloadPlatform.QUARK,
                            headers = downloadHeaders(cookie)
                        )
                        okCount++
                    }
                }
                if (downloadCancelRequested) {
                    cloudMessage = "已中断下载"
                    actionFile = null
                    return@launch
                }
                cloudMessage = "已加入 $okCount 个下载任务"
                actionFile = null
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载文件夹失败"
            } finally {
                isOperating = false
                folderProgress = null
                downloadCancelRequested = false
            }
        }
    }

    /** 待确认的下载直链（单文件下载弹窗展示用，长按链接可复制） */
    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set

    /** 与 downloadLink 配套的入队参数（弹窗确认后直接入队） */
    private var pendingDownload: PendingDownload? = null

    /** 下载文件：取直链 → 弹出下载确认弹窗（对齐解析页行为，确认后入队） */
    fun downloadFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                val link = api.getDownloadLink(file.fid, cookie)
                    ?: throw IllegalStateException("获取下载链接失败")
                // 直链原样使用（关闭 CDN 节点改写/探测，避免消耗直链额度与节点签名 412）
                val effectiveUrl = com.yunx.app.data.network.QuarkCdn.fastest(link.downloadUrl, cookie)
                pendingDownload = PendingDownload(
                    url = effectiveUrl,
                    fileName = link.filename.ifBlank { file.fname },
                    size = link.size,
                    headers = mapOf(
                        "Cookie" to cookie,
                        "User-Agent" to com.yunx.app.data.network.QuarkConstants.API_USER_AGENT,
                        "Referer" to com.yunx.app.data.network.QuarkConstants.DOWNLOAD_REFERER
                    )
                )
                downloadLink = link // 弹下载确认弹窗（长按直链可复制）
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 下载弹窗确认：用已生成的直链入队 */
    fun startDownload() {
        val pd = pendingDownload ?: return
        downloadLink = null
        pendingDownload = null
        viewModelScope.launch {
            isOperating = true
            try {
                downloadManager.enqueue(
                    url = pd.url,
                    fileName = pd.fileName,
                    size = pd.size,
                    platform = DownloadPlatform.QUARK,
                    headers = pd.headers
                )
                cloudMessage = "已加入下载：${pd.fileName}"
                actionFile = null
                downloadTriggered++
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 关闭下载弹窗（放弃下载） */
    fun dismissDownloadDialog() {
        downloadLink = null
        pendingDownload = null
    }

    /** 重命名 */
    fun renameFile(newName: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                if (api.renameFile(file.fid, newName, cookie)) {
                    cloudMessage = "已重命名"
                    actionFile = null
                    reloadCurrent()
                } else {
                    cloudMessage = "重命名失败"
                }
            } catch (e: Exception) {
                cloudMessage = e.message ?: "重命名失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 移动文件到指定目录 */
    fun moveFile(toDirFid: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                api.moveFile(file.fid, toDirFid, cookie)
                    ?: throw IllegalStateException("移动失败")
                cloudMessage = "已移动到目标目录"
                actionFile = null
                // 移动是异步任务（响应 finish 但服务端可能仍在处理），延迟后刷新当前目录
                kotlinx.coroutines.delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 删除文件（二次确认由 UI 层负责） */
    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                api.deleteFile(file.fid, cookie)
                    ?: throw IllegalStateException("删除失败")
                cloudMessage = "已删除「${file.fname}」"
                actionFile = null
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 创建分享并查询链接 */
    fun shareFile(urlType: Int, passcode: String, expiredType: Int) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                val shareId = api.createShare(
                    fidList = listOf(file.fid),
                    title = file.fname,
                    urlType = urlType,
                    passcode = passcode,
                    expiredType = expiredType,
                    cookie = cookie
                ) ?: throw IllegalStateException("创建分享失败")
                val info = api.getShareInfo(shareId, cookie)
                    ?: throw IllegalStateException("获取分享链接失败")
                shareResult = info
                // 注意：不置空 actionFile —— FileActionSheet 依赖它存活，
                // 才能在其内部弹出 ShareResultDialog（置空会导致弹窗销毁、分享结果延迟显示）
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 批量操作（多选） ----------

    /** 批量下载：逐个取直链加入下载队列（选中文件夹时递归下载整个文件夹并保持目录结构） */
    fun downloadSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                // 展开选中项：文件直接加入，文件夹递归收集
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                for (file in files) {
                    if (file.isdir) {
                        collectFolderFiles(file.fid, file.fname, cookie, tasks, 0)
                    } else {
                        tasks.add(file to file.fname)
                    }
                }
                if (tasks.isEmpty()) {
                    cloudMessage = "所选文件夹为空"
                    exitMultiSelect()
                    return@launch
                }
                var okCount = 0
                var failCount = 0
                tasks.forEachIndexed { index, (file, relPath) ->
                    // 用户点击「中断」：跳过剩余项（已入队任务保留下载）
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = api.getDownloadLink(file.fid, cookie) ?: return@runCatching
                        val effectiveUrl = com.yunx.app.data.network.QuarkCdn.fastest(link.downloadUrl, cookie)
                        downloadManager.enqueue(
                            url = effectiveUrl,
                            fileName = if (relPath.contains('/')) relPath else link.filename.ifBlank { relPath },
                            size = link.size,
                            platform = DownloadPlatform.QUARK,
                            headers = downloadHeaders(cookie)
                        )
                        okCount++
                    }
                }
                if (downloadCancelRequested) {
                    cloudMessage = "已中断批量下载"
                    exitMultiSelect()
                    return@launch
                }
                cloudMessage = if (failCount > 0) {
                    "已加入 $okCount 个下载任务（$failCount 个失败）"
                } else {
                    "已加入 $okCount 个下载任务"
                }
                exitMultiSelect()
                // 批量下载不自动切页：保持网盘页显示处理中弹窗（单文件下载才切到下载页）
            } catch (e: Exception) {
                cloudMessage = e.message ?: "批量下载失败"
            } finally {
                isOperating = false
                folderProgress = null
                downloadCancelRequested = false
            }
        }
    }

    /** 批量分享选中文件 */
    fun shareSelected(urlType: Int, passcode: String, expiredType: Int) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                val shareId = api.createShare(
                    fidList = files.map { it.fid },
                    title = if (files.size == 1) files[0].fname else "分享 ${files.size} 个文件",
                    urlType = urlType,
                    passcode = passcode,
                    expiredType = expiredType,
                    cookie = cookie
                ) ?: throw IllegalStateException("创建分享失败")
                val info = api.getShareInfo(shareId, cookie)
                    ?: throw IllegalStateException("获取分享链接失败")
                shareResult = info
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量移动到指定目录 */
    fun moveSelected(toDirFid: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                files.forEach { file ->
                    api.moveFile(file.fid, toDirFid, cookie)
                }
                cloudMessage = "已移动 ${files.size} 项"
                exitMultiSelect()
                kotlinx.coroutines.delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量删除（二次确认由 UI 层负责） */
    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                files.forEach { file ->
                    api.deleteFile(file.fid, cookie)
                }
                cloudMessage = "已删除 ${files.size} 项"
                exitMultiSelect()
                kotlinx.coroutines.delay(1200)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 内部 ----------

    /** 下拉刷新当前目录（不切 Loading 遮罩，完成后更新列表） */
    fun refresh() {
        val current = uiState.value
        if (current !is QuarkCloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            val cookie = cookieProvider()
            if (cookie.isNullOrBlank()) {
                refreshing = false
                return@launch
            }
            try {
                val files = api.listCloudFiles(current.dirFid, cookie) ?: emptyList()
                _uiState.value = QuarkCloudUiState.Loaded(files, current.pathNames, current.dirFid)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is QuarkCloudUiState.Loaded) {
            load(current.dirFid, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirFid: String, pathNames: List<String>) {
        _uiState.value = QuarkCloudUiState.Loading
        viewModelScope.launch {
            val cookie = cookieProvider()
            if (cookie.isNullOrBlank()) {
                _uiState.value = QuarkCloudUiState.Error("请先登录夸克网盘")
                return@launch
            }
            try {
                val files = api.listCloudFiles(dirFid, cookie) ?: emptyList()
                _uiState.value = QuarkCloudUiState.Loaded(files, pathNames, dirFid)
            } catch (e: Exception) {
                _uiState.value = QuarkCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    class Factory(
        private val api: QuarkApi,
        private val cookieProvider: suspend () -> String?,
        private val downloadManager: DownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            QuarkCloudViewModel(api, cookieProvider, downloadManager) as T
    }
}