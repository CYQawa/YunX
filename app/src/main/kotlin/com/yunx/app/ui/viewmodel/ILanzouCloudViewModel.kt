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
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.ILanzouApi
import com.yunx.app.data.network.ILanzouConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.repository.ILanzouAccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 蓝奏优享云盘浏览 UI 状态（根目录 dirId = "0"）。 */
sealed interface ILanzouCloudUiState {
    data object Loading : ILanzouCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        val dirId: String
    ) : ILanzouCloudUiState
    data class Error(val message: String) : ILanzouCloudUiState
}

private data class ILanzouCredential(val appToken: String, val uuid: String, val userId: String)

/**
 * 蓝奏云优享版云盘浏览 ViewModel（镜像 123 云盘浏览）：
 * 目录浏览 + 下拉刷新 + 下载/重命名/移动/删除 + 长按多选批量（该平台不支持创建分享）。
 * 认证走 appToken + uuid（ILanzouAccountEntity），目录用数字 folderId（根 = "0"）。
 */
class ILanzouCloudViewModel(
    private val api: ILanzouApi,
    private val accountRepository: ILanzouAccountRepository,
    private val downloadManager: DownloadManager,
    private val loginState: Flow<Boolean>
) : ViewModel() {

    private val _uiState = MutableStateFlow<ILanzouCloudUiState>(ILanzouCloudUiState.Loading)
    val uiState: StateFlow<ILanzouCloudUiState> = _uiState.asStateFlow()

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
    var multiSelectMode by mutableStateOf(false)
        private set
    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set
    private var pendingDownload: PendingDownload? = null

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    private val _moveUiState = MutableStateFlow<ILanzouCloudUiState>(ILanzouCloudUiState.Loading)
    val moveUiState: StateFlow<ILanzouCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
        viewModelScope.launch {
            loginState.drop(1).distinctUntilChanged().collect { loggedIn -> if (loggedIn) loadRoot() }
        }
    }

    private suspend fun credential(): ILanzouCredential {
        val acc = accountRepository.getAccount()
            ?: throw IllegalStateException("请先登录蓝奏云优享版")
        val token = acc.appToken.ifBlank {
            accountRepository.ensureAppToken()
                ?: throw IllegalStateException("蓝奏优享登录已过期，请重新登录")
        }
        // 下载取链的 downloadId 需要 userId：库中缺失时先拉一次账号信息补全
        val userId = acc.userId.ifBlank { accountRepository.ensureUserId().orEmpty() }
        return ILanzouCredential(token, acc.uuid, userId)
    }

    private fun folderNumeric(fid: String): String = fid.removePrefix(ILanzouConstants.FOLDER_PREFIX)
    private fun fileNumeric(fid: String): String = fid.removePrefix(ILanzouConstants.FILE_PREFIX)

    // ---------- 目录浏览 ----------

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load(ILanzouConstants.ROOT_FOLDER_ID, emptyList())
    }

    fun openFolder(file: ShareFile) {
        val dirId = folderNumeric(file.fid)
        dirStack.addLast(dirId)
        nameStack.addLast(file.fname)
        load(dirId, nameStack.toList())
    }

    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: ILanzouConstants.ROOT_FOLDER_ID, nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: ILanzouConstants.ROOT_FOLDER_ID, nameStack.toList())
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

    fun consumeDownloadTriggered() {
        downloadTriggered = 0
    }

    fun cancelDownload() {
        downloadCancelRequested = true
    }

    // ---------- 移动目标浏览 ----------

    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad(ILanzouConstants.ROOT_FOLDER_ID, emptyList())
    }

    fun openMoveFolder(file: ShareFile) {
        val dirId = folderNumeric(file.fid)
        moveDirStack.addLast(dirId)
        moveNameStack.addLast(file.fname)
        moveLoad(dirId, moveNameStack.toList())
    }

    fun moveBack() {
        if (moveNameStack.isEmpty()) return
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        moveLoad(moveDirStack.lastOrNull() ?: ILanzouConstants.ROOT_FOLDER_ID, moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: ILanzouConstants.ROOT_FOLDER_ID, moveNameStack.toList())
    }

    private fun moveLoad(dirId: String, pathNames: List<String>) {
        _moveUiState.value = ILanzouCloudUiState.Loading
        viewModelScope.launch {
            try {
                val cred = credential()
                val files = api.listFiles(cred.appToken, cred.uuid, dirId).filter { it.isdir }
                _moveUiState.value = ILanzouCloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _moveUiState.value = ILanzouCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 单文件操作 ----------

    private fun downloadHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ILanzouConstants.WEB_UA,
        "Origin" to ILanzouConstants.DOWNLOAD_ORIGIN,
        "Referer" to ILanzouConstants.DOWNLOAD_REFERER
    )

    private suspend fun collectFolderFiles(
        cred: ILanzouCredential,
        dirId: String,
        prefix: String,
        result: MutableList<Pair<ShareFile, String>>,
        depth: Int
    ) {
        if (depth > 12) return
        val list = runCatching { api.listFiles(cred.appToken, cred.uuid, dirId) }.getOrDefault(emptyList())
        list.filter { !it.isdir }.forEach { result.add(it to "$prefix/${it.fname}") }
        list.filter { it.isdir }.forEach {
            collectFolderFiles(cred, folderNumeric(it.fid), "$prefix/${it.fname}", result, depth + 1)
        }
    }

    private suspend fun fetchLink(cred: ILanzouCredential, file: ShareFile): DownloadLink? =
        api.getDownloadLink(cred.appToken, cred.uuid, fileNumeric(file.fid), cred.userId)

    fun downloadFolder() {
        val folder = actionFile ?: return
        if (!folder.isdir) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cred = credential()
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                collectFolderFiles(cred, folderNumeric(folder.fid), folder.fname, tasks, 0)
                if (tasks.isEmpty()) {
                    cloudMessage = "文件夹为空"
                    actionFile = null
                    return@launch
                }
                var okCount = 0
                tasks.forEachIndexed { index, (file, relPath) ->
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = fetchLink(cred, file) ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = relPath,
                            size = link.size,
                            platform = DownloadPlatform.ILANZOU,
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

    fun downloadFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val link = fetchLink(credential(), file)
                    ?: throw IllegalStateException("获取下载链接失败")
                pendingDownload = PendingDownload(
                    url = link.downloadUrl,
                    fileName = file.fname.ifBlank { link.filename },
                    size = link.size,
                    headers = downloadHeaders()
                )
                downloadLink = link
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载失败"
            } finally {
                isOperating = false
            }
        }
    }

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
                    platform = DownloadPlatform.ILANZOU,
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

    fun dismissDownloadDialog() {
        downloadLink = null
        pendingDownload = null
    }

    fun renameFile(newName: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = credential()
                if (file.isdir) {
                    api.renameFolder(cred.appToken, cred.uuid, folderNumeric(file.fid), newName)
                } else {
                    api.renameFile(cred.appToken, cred.uuid, fileNumeric(file.fid), newName)
                }
                cloudMessage = "已重命名"
                actionFile = null
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "重命名失败"
            } finally {
                isOperating = false
            }
        }
    }

    fun createFolder(name: String) {
        val newName = name.trim()
        if (newName.isEmpty()) return
        val parentId = (uiState.value as? ILanzouCloudUiState.Loaded)?.dirId ?: ILanzouConstants.ROOT_FOLDER_ID
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = credential()
                api.createDir(cred.appToken, cred.uuid, parentId, newName)
                cloudMessage = "已创建文件夹「$newName」"
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "新建文件夹失败"
            } finally {
                isOperating = false
            }
        }
    }

    fun moveFile(toDirId: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = credential()
                if (file.isdir) {
                    api.moveEntries(cred.appToken, cred.uuid, listOf(folderNumeric(file.fid)), emptyList(), toDirId)
                } else {
                    api.moveEntries(cred.appToken, cred.uuid, emptyList(), listOf(fileNumeric(file.fid)), toDirId)
                }
                cloudMessage = "已移动到目标目录"
                actionFile = null
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 蓝奏云优享版暂不提供创建分享接口。 */
    fun shareFile() {
        cloudMessage = "蓝奏云优享版暂不支持创建分享"
    }

    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = credential()
                if (file.isdir) {
                    api.deleteEntries(cred.appToken, cred.uuid, listOf(folderNumeric(file.fid)), emptyList())
                } else {
                    api.deleteEntries(cred.appToken, cred.uuid, emptyList(), listOf(fileNumeric(file.fid)))
                }
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

    // ---------- 批量操作 ----------

    fun downloadSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cred = credential()
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                for (file in files) {
                    if (file.isdir) {
                        collectFolderFiles(cred, folderNumeric(file.fid), file.fname, tasks, 0)
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
                tasks.forEachIndexed { index, (file, relPath) ->
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = fetchLink(cred, file) ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = if (relPath.contains('/')) relPath else file.fname.ifBlank { link.filename },
                            size = link.size,
                            platform = DownloadPlatform.ILANZOU,
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
                cloudMessage = "已加入 $okCount 个下载任务"
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

    /** 蓝奏云优享版暂不提供创建分享接口。 */
    fun shareSelected() {
        cloudMessage = "蓝奏云优享版暂不支持创建分享"
    }

    fun moveSelected(toDirId: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = credential()
                val folderIds = files.filter { it.isdir }.map { folderNumeric(it.fid) }
                val fileIds = files.filter { !it.isdir }.map { fileNumeric(it.fid) }
                api.moveEntries(cred.appToken, cred.uuid, folderIds, fileIds, toDirId)
                cloudMessage = "已移动 ${files.size} 项"
                exitMultiSelect()
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = credential()
                val folderIds = files.filter { it.isdir }.map { folderNumeric(it.fid) }
                val fileIds = files.filter { !it.isdir }.map { fileNumeric(it.fid) }
                api.deleteEntries(cred.appToken, cred.uuid, folderIds, fileIds)
                cloudMessage = "已删除 ${files.size} 项"
                exitMultiSelect()
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 内部 ----------

    fun refresh() {
        val current = uiState.value
        if (current !is ILanzouCloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            try {
                val cred = credential()
                val files = api.listFiles(cred.appToken, cred.uuid, current.dirId)
                _uiState.value = ILanzouCloudUiState.Loaded(files, current.pathNames, current.dirId)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is ILanzouCloudUiState.Loaded) {
            load(current.dirId, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirId: String, pathNames: List<String>) {
        _uiState.value = ILanzouCloudUiState.Loading
        viewModelScope.launch {
            try {
                val cred = credential()
                val files = api.listFiles(cred.appToken, cred.uuid, dirId)
                _uiState.value = ILanzouCloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _uiState.value = ILanzouCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    class Factory(
        private val api: ILanzouApi,
        private val accountRepository: ILanzouAccountRepository,
        private val downloadManager: DownloadManager,
        private val loginState: Flow<Boolean>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ILanzouCloudViewModel(api, accountRepository, downloadManager, loginState) as T
    }
}
