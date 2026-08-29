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
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 迅雷云盘浏览 UI 状态 */
sealed interface XunleiCloudUiState {
    data object Loading : XunleiCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        val dirFid: String
    ) : XunleiCloudUiState
    data class Error(val message: String) : XunleiCloudUiState
}

/**
 * 迅雷云盘浏览 ViewModel（参考夸克/UC QuarkCloudViewModel）：
 * - 目录浏览（根/子目录/面包屑回退）+ 下拉刷新
 * - 文件操作：下载 / 重命名 / 移动 / 创建分享 / 删除 + 长按多选批量
 * 认证走 access_token（Bearer）+ 设备指纹 + captcha。
 */
class XunleiCloudViewModel(
    private val api: XunleiApi,
    private val tokenProvider: suspend () -> String?,
    private val deviceIdProvider: suspend () -> String?,
    private val captchaProvider: suspend () -> String?,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<XunleiCloudUiState>(XunleiCloudUiState.Loading)
    val uiState: StateFlow<XunleiCloudUiState> = _uiState.asStateFlow()

    var actionFile by mutableStateOf<ShareFile?>(null)
        private set
    var cloudMessage by mutableStateOf<String?>(null)
        private set
    var isOperating by mutableStateOf(false)
        private set
    var folderProgress by mutableStateOf<String?>(null)
        private set
    private var downloadCancelRequested = false
    var refreshing by mutableStateOf(false)
        private set
    var downloadTriggered by mutableStateOf(0)
        private set
    var shareResult by mutableStateOf<ShareInfo?>(null)
        private set
    var multiSelectMode by mutableStateOf(false)
        private set
    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    private val _moveUiState = MutableStateFlow<XunleiCloudUiState>(XunleiCloudUiState.Loading)
    val moveUiState: StateFlow<XunleiCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
    }

    private suspend fun creds(): Triple<String, String, String>? {
        val token = tokenProvider() ?: return null
        val deviceId = deviceIdProvider() ?: return null
        val captcha = captchaProvider() ?: ""
        api.cacheUserId(token)
        return Triple(token, deviceId, captcha)
    }

    // ---------- 目录浏览 ----------

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("", emptyList())
    }

    fun openFolder(file: ShareFile) {
        dirStack.addLast(file.fid)
        nameStack.addLast(file.fname)
        load(file.fid, nameStack.toList())
    }

    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: "", nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "", nameStack.toList())
    }

    // ---------- 多选 ----------

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

    fun consumeDownloadTriggered() {
        downloadTriggered = 0
    }

    /** 中断当前下载（批量下载/文件夹下载） */
    fun cancelDownload() {
        downloadCancelRequested = true
    }

    // ---------- 移动目标浏览 ----------

    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad("", emptyList())
    }

    fun openMoveFolder(file: ShareFile) {
        moveDirStack.addLast(file.fid)
        moveNameStack.addLast(file.fname)
        moveLoad(file.fid, moveNameStack.toList())
    }

    fun moveBack() {
        if (moveNameStack.isEmpty()) return
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        moveLoad(moveDirStack.lastOrNull() ?: "", moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: "", moveNameStack.toList())
    }

    private fun moveLoad(dirFid: String, pathNames: List<String>) {
        _moveUiState.value = XunleiCloudUiState.Loading
        viewModelScope.launch {
            val c = creds()
            if (c == null) {
                _moveUiState.value = XunleiCloudUiState.Error("请先登录迅雷网盘")
                return@launch
            }
            try {
                val files = api.getFiles(dirFid, c.first, c.second, c.third) ?: emptyList()
                _moveUiState.value = XunleiCloudUiState.Loaded(files, pathNames, dirFid)
            } catch (e: Exception) {
                _moveUiState.value = XunleiCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 单文件操作 ----------

    /** 迅雷下载直链的请求头（签名 URL；UA 必须用官方 app UA，浏览器 UA 会触发 CDN 降级为 200 整文件） */
    private fun downloadHeaders(): Map<String, String> = mapOf(
        "User-Agent" to XunleiConstants.APP_UA
    )

    /**
     * 递归收集文件夹内所有文件（保持目录结构）。
     */
    private suspend fun collectFolderFiles(
        dirFid: String,
        prefix: String,
        c: Triple<String, String, String>,
        result: MutableList<Pair<ShareFile, String>>,
        depth: Int
    ) {
        if (depth > 12) return
        val list = runCatching { api.getFiles(dirFid, c.first, c.second, c.third) ?: emptyList() }
            .getOrDefault(emptyList())
        list.filter { !it.isdir }.forEach { result.add(it to "$prefix/${it.fname}") }
        list.filter { it.isdir }.forEach {
            collectFolderFiles(it.fid, "$prefix/${it.fname}", c, result, depth + 1)
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
                val c = creds() ?: throw IllegalStateException("请先登录迅雷网盘")
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                collectFolderFiles(folder.fid, folder.fname, c, tasks, 0)
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
                        val link = api.getFileDetail(file.fid, c.first, c.second, c.third)
                            ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = relPath, // 相对路径：Download/文件夹A/子目录/文件.mp4
                            size = link.size,
                            platform = DownloadPlatform.XUNLEI,
                            headers = downloadHeaders()
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

    /** 下载：文件详情取直链（签名 URL，无需 Cookie）→ 内置下载队列 */
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
                val c = creds() ?: throw IllegalStateException("请先登录迅雷网盘")
                val link = api.getFileDetail(file.fid, c.first, c.second, c.third)
                    ?: throw IllegalStateException("获取下载链接失败")
                pendingDownload = PendingDownload(
                    url = link.downloadUrl,
                    fileName = link.filename.ifBlank { file.fname },
                    size = link.size,
                    headers = mapOf("User-Agent" to XunleiConstants.APP_UA)
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
                    platform = DownloadPlatform.XUNLEI,
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
                val c = creds() ?: throw IllegalStateException("请先登录迅雷网盘")
                if (api.renameFile(file.fid, newName, c.first, c.second, c.third)) {
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

    /** 移动 */
    fun moveFile(toDirFid: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val c = creds() ?: throw IllegalStateException("请先登录迅雷网盘")
                api.moveFile(listOf(file.fid), toDirFid, c.first, c.second, c.third)
                    ?: throw IllegalStateException("移动失败")
                cloudMessage = "已移动到目标目录"
                actionFile = null
                kotlinx.coroutines.delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 创建分享（迅雷必须带提取码；留空自动生成，可自定义 4 位）
     *  @param expiredType 1=永久 2=1天 3=7天 4=30天（内部映射为 API 的 "-1"/"1"/"7"/"30"）
     *  @param passCode 自定义提取码（4 位字母数字，留空则服务端自动生成）
     */
    fun shareFile(expiredType: Int, passCode: String = "") {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val c = creds() ?: throw IllegalStateException("请先登录迅雷网盘")
                val info = api.createShare(
                    listOf(file.fid), file.fname, expireDays(expiredType),
                    c.first, c.second, c.third, passCode
                ) ?: throw IllegalStateException("创建分享失败")
                shareResult = info.copy(expiredType = expiredType)
                // 保留 actionFile：弹窗存活才能显示分享结果
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 删除（回收站） */
    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val c = creds() ?: throw IllegalStateException("请先登录迅雷网盘")
                api.deleteFiles(listOf(file.fid), c.first, c.second, c.third)
                cloudMessage = "已删除「${file.fname}」"
                actionFile = null
                kotlinx.coroutines.delay(1200)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 批量操作 ----------

    /** 批量下载（不切页，保持处理中弹窗；选中文件夹时递归下载整个文件夹并保持目录结构） */
    fun downloadSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val c = creds() ?: throw IllegalStateException("请先登录迅雷网盘")
                // 展开选中项：文件直接加入，文件夹递归收集
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                for (file in files) {
                    if (file.isdir) {
                        collectFolderFiles(file.fid, file.fname, c, tasks, 0)
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
                        val link = api.getFileDetail(file.fid, c.first, c.second, c.third)
                            ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = if (relPath.contains('/')) relPath else link.filename.ifBlank { relPath },
                            size = link.size,
                            platform = DownloadPlatform.XUNLEI,
                            headers = downloadHeaders()
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
            } catch (e: Exception) {
                cloudMessage = e.message ?: "批量下载失败"
            } finally {
                isOperating = false
                folderProgress = null
                downloadCancelRequested = false
            }
        }
    }

    /** 批量分享 */
    fun shareSelected(expiredType: Int, passCode: String = "") {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val c = creds() ?: throw IllegalStateException("请先登录迅雷网盘")
                val info = api.createShare(
                    files.map { it.fid },
                    if (files.size == 1) files[0].fname else "分享 ${files.size} 个文件",
                    expireDays(expiredType), c.first, c.second, c.third, passCode
                ) ?: throw IllegalStateException("创建分享失败")
                shareResult = info.copy(expiredType = expiredType)
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 有效期类型 → API 天数：1=永久 2=1天 3=7天 4=30天 */
    private fun expireDays(type: Int): String = when (type) {
        2 -> "1"
        3 -> "7"
        4 -> "30"
        else -> "-1"
    }

    /** 批量移动 */
    fun moveSelected(toDirFid: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val c = creds() ?: throw IllegalStateException("请先登录迅雷网盘")
                api.moveFile(files.map { it.fid }, toDirFid, c.first, c.second, c.third)
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

    /** 批量删除 */
    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val c = creds() ?: throw IllegalStateException("请先登录迅雷网盘")
                api.deleteFiles(files.map { it.fid }, c.first, c.second, c.third)
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

    /** 下拉刷新 */
    fun refresh() {
        val current = uiState.value
        if (current !is XunleiCloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            val c = creds()
            if (c == null) {
                refreshing = false
                return@launch
            }
            try {
                val files = api.getFiles(current.dirFid, c.first, c.second, c.third) ?: emptyList()
                _uiState.value = XunleiCloudUiState.Loaded(files, current.pathNames, current.dirFid)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is XunleiCloudUiState.Loaded) {
            load(current.dirFid, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirFid: String, pathNames: List<String>) {
        _uiState.value = XunleiCloudUiState.Loading
        viewModelScope.launch {
            val c = creds()
            if (c == null) {
                _uiState.value = XunleiCloudUiState.Error("请先登录迅雷网盘")
                return@launch
            }
            try {
                val files = api.getFiles(dirFid, c.first, c.second, c.third) ?: emptyList()
                _uiState.value = XunleiCloudUiState.Loaded(files, pathNames, dirFid)
            } catch (e: Exception) {
                _uiState.value = XunleiCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    class Factory(
        private val api: XunleiApi,
        private val tokenProvider: suspend () -> String?,
        private val deviceIdProvider: suspend () -> String?,
        private val captchaProvider: suspend () -> String?,
        private val downloadManager: DownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            XunleiCloudViewModel(api, tokenProvider, deviceIdProvider, captchaProvider, downloadManager) as T
    }
}