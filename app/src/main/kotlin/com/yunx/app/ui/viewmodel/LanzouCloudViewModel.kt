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
import com.yunx.app.data.network.LanzouApi
import com.yunx.app.data.network.LanzouConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import com.yunx.app.data.repository.LanzouAccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.net.URI

/** 蓝奏云浏览 UI 状态（根目录 dirId = "-1"）。 */
sealed interface LanzouCloudUiState {
    data object Loading : LanzouCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        val dirId: String
    ) : LanzouCloudUiState
    data class Error(val message: String) : LanzouCloudUiState
}

private data class LanzouCreds(val cookie: String, val uid: String, val vei: String)

/**
 * 蓝奏云个人盘浏览 ViewModel（镜像 123 云盘浏览）：
 * 目录浏览 + 下拉刷新 + 下载/重命名/移动/删除/创建分享 + 长按多选批量。
 * 认证走 Cookie（ylogin + phpdisk_info）；目录用 folder_id（根 = "-1"，文件夹 id 前缀 d:）。
 */
class LanzouCloudViewModel(
    private val api: LanzouApi,
    private val accountRepository: LanzouAccountRepository,
    private val downloadManager: DownloadManager,
    private val loginState: Flow<Boolean>
) : ViewModel() {

    private val _uiState = MutableStateFlow<LanzouCloudUiState>(LanzouCloudUiState.Loading)
    val uiState: StateFlow<LanzouCloudUiState> = _uiState.asStateFlow()

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

    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set
    private var pendingDownload: PendingDownload? = null

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    private val _moveUiState = MutableStateFlow<LanzouCloudUiState>(LanzouCloudUiState.Loading)
    val moveUiState: StateFlow<LanzouCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    private var cachedCreds: LanzouCreds? = null

    init {
        loadRoot()
        viewModelScope.launch {
            loginState.drop(1).distinctUntilChanged().collect { loggedIn ->
                cachedCreds = null
                if (loggedIn) loadRoot()
            }
        }
    }

    private suspend fun creds(): LanzouCreds {
        val acc = accountRepository.getAccount() ?: throw IllegalStateException("请先登录蓝奏云")
        cachedCreds?.let { if (it.cookie == acc.cookie) return it }
        val params = api.fetchLoginParams(acc.cookie)
            ?: throw IllegalStateException("蓝奏登录态已失效，请重新登录")
        return LanzouCreds(acc.cookie, params.uid, params.vei).also { cachedCreds = it }
    }

    private fun folderNumeric(fid: String): String = fid.removePrefix(LanzouConstants.FOLDER_PREFIX)
    private fun fileNumeric(fid: String): String = fid.removePrefix(LanzouConstants.FILE_PREFIX)

    private fun downloadHeaders(url: String): Map<String, String> {
        val origin = runCatching { URI(url) }.getOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://pan.lanzoui.com"
        return mapOf(
            "User-Agent" to LanzouConstants.WEB_UA,
            "Referer" to "$origin/",
            "Cookie" to LanzouConstants.DOWN_IP_COOKIE
        )
    }

    // ---------- 目录浏览 ----------

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load(LanzouConstants.ROOT_FOLDER_ID, emptyList())
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
        load(dirStack.lastOrNull() ?: LanzouConstants.ROOT_FOLDER_ID, nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: LanzouConstants.ROOT_FOLDER_ID, nameStack.toList())
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

    fun cancelDownload() {
        downloadCancelRequested = true
    }

    // ---------- 移动目标浏览 ----------

    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad(LanzouConstants.ROOT_FOLDER_ID, emptyList())
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
        moveLoad(moveDirStack.lastOrNull() ?: LanzouConstants.ROOT_FOLDER_ID, moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: LanzouConstants.ROOT_FOLDER_ID, moveNameStack.toList())
    }

    private fun moveLoad(dirId: String, pathNames: List<String>) {
        _moveUiState.value = LanzouCloudUiState.Loading
        viewModelScope.launch {
            try {
                val cred = creds()
                val files = api.listCloudFiles(cred.cookie, cred.uid, cred.vei, dirId).filter { it.isdir }
                _moveUiState.value = LanzouCloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _moveUiState.value = LanzouCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 下载 ----------

    private suspend fun enqueueFile(cred: LanzouCreds, file: ShareFile, fileName: String): Boolean =
        runCatching {
            val link = api.getPersonalDownloadLink(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid), file.fname)
            downloadManager.enqueue(
                url = link.downloadUrl,
                fileName = fileName,
                size = link.size,
                platform = DownloadPlatform.LANZOU,
                headers = downloadHeaders(link.downloadUrl)
            )
            true
        }.getOrDefault(false)

    private suspend fun downloadFolderFiles(
        cred: LanzouCreds,
        folder: ShareFile,
        prefix: String,
        out: MutableList<Triple<ShareFile, String, ShareInfo>>,
        depth: Int
    ) {
        if (depth > 6) return
        val info = runCatching {
            api.createFolderShare(cred.cookie, cred.uid, cred.vei, folderNumeric(folder.fid))
        }.getOrNull() ?: return
        val page = api.resolveShare(info.shareUrl, info.passcode)
        val files = api.listShareFolder(page, info.passcode.takeIf { it.isNotBlank() }, LanzouConstants.ROOT_FOLDER_ID)
        files.forEach { out.add(Triple(it, "$prefix/${it.fname}", info)) }
    }

    fun downloadFolder() {
        val folder = actionFile ?: return
        if (!folder.isdir) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cred = creds()
                val tasks = mutableListOf<Triple<ShareFile, String, ShareInfo>>()
                downloadFolderFiles(cred, folder, folder.fname, tasks, 0)
                if (tasks.isEmpty()) {
                    cloudMessage = "文件夹为空"
                    actionFile = null
                    return@launch
                }
                var okCount = 0
                tasks.forEachIndexed { index, (file, relPath, info) ->
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val page = api.resolveShare(info.shareUrl, info.passcode)
                        val link = api.getShareDownloadLink(page, file, info.passcode.takeIf { it.isNotBlank() })
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = relPath,
                            size = link.size,
                            platform = DownloadPlatform.LANZOU,
                            headers = downloadHeaders(link.downloadUrl)
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
                val cred = creds()
                val link = api.getPersonalDownloadLink(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid), file.fname)
                pendingDownload = PendingDownload(
                    url = link.downloadUrl,
                    fileName = file.fname.ifBlank { link.filename },
                    size = link.size,
                    headers = downloadHeaders(link.downloadUrl)
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
                    platform = DownloadPlatform.LANZOU,
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

    // ---------- 单文件操作 ----------

    fun renameFile(newName: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                if (file.isdir) {
                    api.renameFolder(cred.cookie, cred.uid, cred.vei, folderNumeric(file.fid), newName)
                } else {
                    api.renameFile(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid), newName)
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
        val parentId = (uiState.value as? LanzouCloudUiState.Loaded)?.dirId ?: LanzouConstants.ROOT_FOLDER_ID
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                api.createFolder(cred.cookie, cred.uid, cred.vei, parentId, newName)
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
        if (file.isdir) {
            cloudMessage = "蓝奏云暂不支持移动文件夹"
            return
        }
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                api.moveFile(cred.cookie, cred.uid, cred.vei, toDirId, fileNumeric(file.fid))
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

    /** 创建分享（蓝奏由服务端生成提取码，忽略 UI 有效期/提取码选择）。 */
    fun shareFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                val info = if (file.isdir) {
                    api.createFolderShare(cred.cookie, cred.uid, cred.vei, folderNumeric(file.fid))
                } else {
                    api.createFileShare(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid))
                }
                shareResult = info.copy(title = file.fname)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                if (file.isdir) {
                    api.deleteFolder(cred.cookie, cred.uid, cred.vei, folderNumeric(file.fid))
                } else {
                    api.deleteFile(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid))
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
                val cred = creds()
                val tasks = mutableListOf<Triple<ShareFile, String, ShareInfo?>>()
                for (file in files) {
                    if (file.isdir) {
                        val folderTasks = mutableListOf<Triple<ShareFile, String, ShareInfo>>()
                        downloadFolderFiles(cred, file, file.fname, folderTasks, 0)
                        folderTasks.forEach { tasks.add(Triple(it.first, it.second, it.third)) }
                    } else {
                        tasks.add(Triple(file, file.fname, null))
                    }
                }
                if (tasks.isEmpty()) {
                    cloudMessage = "所选文件夹为空"
                    exitMultiSelect()
                    return@launch
                }
                var okCount = 0
                tasks.forEachIndexed { index, (file, relPath, info) ->
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = if (info == null) {
                            api.getPersonalDownloadLink(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid), file.fname)
                        } else {
                            val page = api.resolveShare(info.shareUrl, info.passcode)
                            api.getShareDownloadLink(page, file, info.passcode.takeIf { it.isNotBlank() })
                        }
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = if (relPath.contains('/')) relPath else file.fname.ifBlank { link.filename },
                            size = link.size,
                            platform = DownloadPlatform.LANZOU,
                            headers = downloadHeaders(link.downloadUrl)
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

    fun shareSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        val file = files.first()
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                val info = if (file.isdir) {
                    api.createFolderShare(cred.cookie, cred.uid, cred.vei, folderNumeric(file.fid))
                } else {
                    api.createFileShare(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid))
                }
                shareResult = info.copy(title = file.fname)
                if (files.size > 1) cloudMessage = "蓝奏一次只能分享一个文件或文件夹，已分享第一项"
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    fun moveSelected(toDirId: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                val movable = files.filter { !it.isdir }
                if (movable.isEmpty()) {
                    cloudMessage = "蓝奏云暂不支持移动文件夹"
                    return@launch
                }
                movable.forEach { api.moveFile(cred.cookie, cred.uid, cred.vei, toDirId, fileNumeric(it.fid)) }
                cloudMessage = "已移动 ${movable.size} 项"
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
                val cred = creds()
                files.forEach {
                    if (it.isdir) {
                        api.deleteFolder(cred.cookie, cred.uid, cred.vei, folderNumeric(it.fid))
                    } else {
                        api.deleteFile(cred.cookie, cred.uid, cred.vei, fileNumeric(it.fid))
                    }
                }
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
        if (current !is LanzouCloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            try {
                val cred = creds()
                val files = api.listCloudFiles(cred.cookie, cred.uid, cred.vei, current.dirId)
                _uiState.value = LanzouCloudUiState.Loaded(files, current.pathNames, current.dirId)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is LanzouCloudUiState.Loaded) {
            load(current.dirId, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirId: String, pathNames: List<String>) {
        _uiState.value = LanzouCloudUiState.Loading
        viewModelScope.launch {
            try {
                val cred = creds()
                val files = api.listCloudFiles(cred.cookie, cred.uid, cred.vei, dirId)
                _uiState.value = LanzouCloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _uiState.value = LanzouCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    class Factory(
        private val api: LanzouApi,
        private val accountRepository: LanzouAccountRepository,
        private val downloadManager: DownloadManager,
        private val loginState: Flow<Boolean>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LanzouCloudViewModel(api, accountRepository, downloadManager, loginState) as T
    }
}
