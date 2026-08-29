package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadStats
import com.yunx.app.ui.SnackbarController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 下载页 ViewModel：任务列表（Room Flow → StateFlow）+ 实时统计 + 操作转发。
 */
class DownloadViewModel(private val manager: DownloadManager) : ViewModel() {

    val tasks: StateFlow<List<DownloadTaskEntity>> = manager.tasks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** 实时下载统计：任务 id → 速度/剩余时间/线程数 */
    val stats: StateFlow<Map<Long, DownloadStats>> = manager.stats

    /** 添加下载任务（headers 可携带 Referer/Cookie 等；platform 用于按平台应用下载线程数） */
    fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        platform: String = ""
    ) {
        viewModelScope.launch { manager.enqueue(url, fileName, headers, platform = platform) }
    }

    fun pause(id: Long) = manager.pause(id)

    fun resume(id: Long) = manager.start(id)

    fun remove(id: Long, deleteLocal: Boolean = false) = manager.remove(id, deleteLocal)

    /** 重新下载：校验直链有效性后新建任务（直链过期时提示） */
    fun redownload(task: DownloadTaskEntity) {
        viewModelScope.launch {
            val ok = manager.redownload(task.id)
            SnackbarController.show(if (ok) "已重新加入下载" else "直链已过期，请重新获取下载链接")
        }
    }

    /** 全部暂停：暂停所有正在下载（含等待中）的任务 */
    fun pauseAll() {
        tasks.value.filter {
            it.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                it.status == DownloadTaskEntity.STATUS_PENDING
        }.forEach { manager.pause(it.id) }
    }

    /** 全部开始：恢复所有已暂停/失败的任务（断点续传） */
    fun resumeAll() {
        tasks.value.filter {
            it.status == DownloadTaskEntity.STATUS_PAUSED ||
                it.status == DownloadTaskEntity.STATUS_FAILED
        }.forEach { manager.start(it.id) }
    }

    /** 删除全部任务（可同时删除已保存到本地的文件） */
    fun removeAll(deleteLocal: Boolean = false) {
        tasks.value.toList().forEach { manager.remove(it.id, deleteLocal) }
    }

    class Factory(private val manager: DownloadManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DownloadViewModel::class.java))
            return DownloadViewModel(manager) as T
        }
    }
}