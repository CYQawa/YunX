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
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import com.yunx.app.data.network.model.ShareToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UC 云盘浏览 UI 状态 */
sealed interface UCCloudUiState {
    data object Loading : UCCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        val dirFid: String
    ) : UCCloudUiState
    data class Error(val message: String) : UCCloudUiState
}

/**
 * UC 网盘云盘浏览 ViewModel（参考夸克 QuarkCloudViewModel）：
 * - 目录浏览（根/子目录/面包屑回退）
 * - 文件操作：下载 / 重命名 / 移动 / 创建分享 + 长按多选批量操作
 * 操作成功后自动刷新当前目录，结果通过 cloudMessage（Toast）反馈。
 */
class UCCoudViewModel(
    private val api: UCApi,
    private val cookieProvider: suspend () -> String?,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<UCCloudUiState>(UCCloudUiState.Loading)
    val uiState: StateFlow<UCCloudUiState> = _uiState.asStateFlow()

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

    /** 下拉刷新中 */
    var refreshing by mutableStateOf(false)
        private set

    /** 下载入队事件计数（UI 消费后切到下载页） */
    var downloadTriggered by mutableStateOf(0)
        private set

    /** 分享创建成功后的信息（弹窗展示链接+提取码） */
    var shareResult by mutableStateOf<ShareInfo?>(null)
        private set

    /** 多选模式 */
    var multiSelectMode by mutableStateOf(false)
        private set

    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    // ---------- 移动目标目录浏览（独立状态） ----------
    private val _moveUiState = MutableStateFlow<UCCloudUiState>(UCCloudUiState.Loading)
    val moveUiState: StateFlow<UCCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
    }

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("0", emptyList())
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
        load(dirStack.lastOrNull() ?: "0", nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "0", nameStack.toList())
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

    // ---------- 移动目标浏览 ----------

    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad("0", emptyList())
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
        moveLoad(moveDirStack.lastOrNull() ?: "0", moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: "0", moveNameStack.toList())
    }

    private fun moveLoad(dirFid: String, pathNames: List<String>) {
        _moveUiState.value = UCCloudUiState.Loading
        viewModelScope.launch {
            val cookie = cookieProvider()
            if (cookie.isNullOrBlank()) {
                _moveUiState.value = UCCloudUiState.Error("请先登录 UC 网盘")
                return@launch
            }
            try {
                val files = api.listCloudFiles(dirFid, cookie) ?: emptyList()
                _moveUiState.value = UCCloudUiState.Loaded(files, pathNames, dirFid)
            } catch (e: Exception) {
                _moveUiState.value = UCCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 单文件操作 ----------

    /** 常见视频扩展名（UC 非会员视频走 play 转码流绕过会员墙，代价是转码清晰度） */
    private val videoExts = setOf("mp4", "mkv", "mov", "avi", "webm", "flv", "ts", "m3u8", "wmv", "rmvb")

    private fun isVideo(name: String): Boolean =
        videoExts.contains(name.substringAfterLast('.', "").lowercase())

    /**
     * UC 网盘视频下载的**自动化流程**（绕开官方 VIP 广告视频）：
     * 直接取链（OSS/play）会把非会员视频换成 14.6MB 官方宣传片/转码流，因此改为：
     * ① 自动创建「1 天有效、无提取码」的分享链接；
     * ② 用分享解析流程（getShareToken → transfer_share/detail → video_preview）取**原画**直链；
     * ③ 拿到的直链走播放回调 checkplay 不换片，交给下载器。
     */
    private suspend fun ucVideoDownloadLinkViaShare(file: ShareFile, cookie: String): DownloadLink? {
        // ① 创建 1 天有效、无提取码分享（UCApi.createShare 内部已异步轮询拿 share_id）
        val shareId = api.createShare(
            fidList = listOf(file.fid),
            title = file.fname,
            urlType = 1,       // 1=无提取码
            passcode = "",
            expiredType = 2,   // 2=1 天
            cookie = cookie
        ) ?: return null
        // ② 查分享信息拿**对外分享码 pwd_id**（share_id 是内部 ID，直接当 pwd_id 调 token 会 41006 分享不存在）
        val pwdId = api.getShareInfo(shareId, cookie)?.pwdId?.takeIf { it.isNotBlank() } ?: shareId
        // ③ 分享解析流程：token → 文件列表 → video_preview 原画直链
        val token = api.getShareToken(pwdId, null, cookie) ?: return null
        val files = api.getTransferShareFiles(pwdId, token.stoken, "0", cookie) ?: return null
        val target = files.firstOrNull { it.fid == file.fid } ?: files.firstOrNull() ?: return null
        return api.getVideoPreview(
            pwdId = pwdId,
            stoken = token.stoken,
            fid = target.fid,
            fidToken = target.fidToken,
            cookie = cookie
        )?.copy(filename = file.fname)
    }

    /**
     * 取下载直链：视频优先走「创建分享 → video_preview 原画直链」自动化流程（绕过非会员视频被换成宣传片），
     * 失败回退 play 转码流；非视频走 entry=ft 高速通道（与分享解析同款 DOWNLOAD_URL），再回退个人云盘通道（cloudGetDownloadLink）。
     */
    private suspend fun ucDownloadLink(fid: String, cookie: String, file: ShareFile): DownloadLink? {
        if (isVideo(file.fname)) {
            // 优先：自动化分享流程取原画直链（绕会员墙，原画清晰度）
            ucVideoDownloadLinkViaShare(file, cookie)?.let { return it }
            // 回退：play 转码流
            api.getPlayLink(fid, cookie)?.let { play ->
                return DownloadLink(
                    fid = fid,
                    filename = file.fname,
                    downloadUrl = play.url,
                    size = -1L, // 转码流大小未知，下载器走流式/探测
                    isHls = play.isHls
                )
            }
        }
        return api.getDownloadLink(fid, cookie)
            ?: api.cloudGetDownloadLink(fid, cookie)
    }

    /** UC 下载直链的请求头（OSS 按 Referer 档位限速，必须带官方 Referer/Origin） */
    private fun downloadHeaders(cookie: String): Map<String, String> = mapOf(
        "Cookie" to cookie,
        "User-Agent" to UCConstants.USER_AGENT,
        "Referer" to UCConstants.DOWNLOAD_REFERER,
        "Origin" to UCConstants.WEB_ORIGIN
    )

    /**
     * 递归收集文件夹内所有文件（保持目录结构）。
     * @param dirFid 目录 fid
     * @param prefix 相对路径前缀（如 "文件夹A/子目录"）
     * @param result 输出：文件 + 相对路径（"文件夹A/子目录/文件.mp4"）
     * @param depth 递归深度（防极端深层目录）
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
        // 先文件后文件夹（与目录列表展示顺序一致）
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
                    cloudMessage = "请先登录 UC 网盘"
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
                        val link = ucDownloadLink(file.fid, cookie, file) ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = relPath, // 相对路径：Download/文件夹A/子目录/文件.mp4
                            size = link.size,
                            platform = DownloadPlatform.UC,
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

    /** 下载文件：取直链（带 Cookie+UA）→ 加入内置下载队列 */
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
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                val link = ucDownloadLink(file.fid, cookie, file)
                    ?: throw IllegalStateException("获取下载链接失败")
                pendingDownload = PendingDownload(
                    url = link.downloadUrl,
                    fileName = link.filename.ifBlank { file.fname },
                    size = link.size,
                    headers = mapOf(
                        "Cookie" to cookie,
                        // UC OSS 直链：必须带官方 Referer（否则被 Callback 限速 ~100KB/s）+ Origin，与解析页 UC 分支一致
                        "User-Agent" to UCConstants.USER_AGENT,
                        "Referer" to UCConstants.DOWNLOAD_REFERER,
                        "Origin" to UCConstants.WEB_ORIGIN
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
                    platform = DownloadPlatform.UC,
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
                    cloudMessage = "请先登录 UC 网盘"
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
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                api.moveFile(file.fid, toDirFid, cookie)
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

    /** 创建分享并查询链接 */
    fun shareFile(urlType: Int, passcode: String, expiredType: Int) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
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
                // 保留 actionFile：FileActionSheet 存活才能弹出 ShareResultDialog
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
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
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                // 展开选中项：文件直接加入，文件夹递归收集（相对路径 = 文件夹名/子目录/...）
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
                        val link = ucDownloadLink(file.fid, cookie, file) ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            // 文件夹内文件用相对路径（保持目录结构）；根目录文件用取链返回的文件名
                            fileName = if (relPath.contains('/')) relPath else link.filename.ifBlank { relPath },
                            size = link.size,
                            platform = DownloadPlatform.UC,
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
    fun shareSelected(urlType: Int, passcode: String, expiredType: Int) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
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

    /** 批量移动 */
    fun moveSelected(toDirFid: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
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

    /** 删除文件（二次确认由 UI 层负责） */
    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                api.deleteFile(file.fid, cookie)
                    ?: throw IllegalStateException("删除失败")
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

    /** 批量删除 */
    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
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

    /** 下拉刷新当前目录 */
    fun refresh() {
        val current = uiState.value
        if (current !is UCCloudUiState.Loaded) {
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
                _uiState.value = UCCloudUiState.Loaded(files, current.pathNames, current.dirFid)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is UCCloudUiState.Loaded) {
            load(current.dirFid, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirFid: String, pathNames: List<String>) {
        _uiState.value = UCCloudUiState.Loading
        viewModelScope.launch {
            val cookie = cookieProvider()
            if (cookie.isNullOrBlank()) {
                _uiState.value = UCCloudUiState.Error("请先登录 UC 网盘")
                return@launch
            }
            try {
                val files = api.listCloudFiles(dirFid, cookie) ?: emptyList()
                _uiState.value = UCCloudUiState.Loaded(files, pathNames, dirFid)
            } catch (e: Exception) {
                _uiState.value = UCCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    class Factory(
        private val api: UCApi,
        private val cookieProvider: suspend () -> String?,
        private val downloadManager: DownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UCCoudViewModel(api, cookieProvider, downloadManager) as T
    }
}