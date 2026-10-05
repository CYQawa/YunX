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

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.announcement.AnnouncementApi
import com.yunx.app.data.announcement.AnnouncementReadStore
import com.yunx.app.ui.SnackbarController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 应用内公告 ViewModel：列表（远程）+ 详情（按 id 缓存）+ 未读数 + 启动弹窗候选。
 *
 * 数据源只有一份：启动检查 / 列表页 / 下拉刷新 / 翻页都改同一个 [list]，
 * 所以「未读角标」的口径始终等于「已加载到的公告里未读的条数」——
 * 启动时用 [AnnouncementApi.PAGE_SIZE]（接口上限 100）一次取满，实际就是全部公告。
 *
 * 已读口径见 [AnnouncementReadStore]：**打开详情** 或 **关掉启动弹窗** 都算已读（服务端没有已读接口）。
 */
class AnnouncementViewModel(private val readStore: AnnouncementReadStore) : ViewModel() {

    /** 列表状态（唯一真源） */
    data class ListUiState(
        val items: List<AnnouncementApi.Announcement> = emptyList(),
        /** 已加载到第几页（0 = 还没成功加载过） */
        val page: Int = 0,
        val hasMore: Boolean = false,
        /** 首屏加载：列表页打开且一条数据都没有时 */
        val loading: Boolean = false,
        /** 下拉刷新 / 手动刷新 */
        val refreshing: Boolean = false,
        /** 翻页（加载更多） */
        val loadingMore: Boolean = false,
        /** 最近一次失败原因（只在「一条数据都没有」时占满页面，否则只走 Snackbar） */
        val error: String? = null,
    )

    /** 详情状态：一次只展示一条，退出动画期间仍要保持 Loaded 内容可用 */
    sealed interface DetailUiState {
        object Idle : DetailUiState
        data class Loading(val id: String) : DetailUiState
        data class Loaded(val item: AnnouncementApi.Announcement) : DetailUiState
        data class Failed(val id: String, val message: String) : DetailUiState
    }

    private val _list = MutableStateFlow(ListUiState())
    val list: StateFlow<ListUiState> = _list.asStateFlow()

    private val _detail = MutableStateFlow<DetailUiState>(DetailUiState.Idle)
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    /** 启动弹窗要展示的公告（null = 不弹） */
    private val _popup = MutableStateFlow<AnnouncementApi.Announcement?>(null)
    val popup: StateFlow<AnnouncementApi.Announcement?> = _popup.asStateFlow()

    /** 已读 id（本地记录，供列表页画未读小圆点） */
    val readIds: StateFlow<Set<String>> = readStore.readIds

    /** 顶栏红点角标的数字：已加载到的公告里未读的条数 */
    val unreadCount: StateFlow<Int> = combine(_list, readStore.readIds) { state, read ->
        state.items.count { it.id.isNotEmpty() && it.id !in read }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 详情缓存：详情接口会让 viewCount +1，同一条公告在一次会话里只请求一次 */
    private val detailCache = mutableMapOf<String, AnnouncementApi.Announcement>()

    private var startupChecked = false

    /**
     * 启动检查：整个会话只跑一次（列表 + 未读角标 + 弹窗候选都由这一次请求的结果决定）。
     * ★ 失败静默：启动阶段断网很常见，不值得为一条公告打扰用户（原因已由 AnnouncementApi 打 E 级日志）；
     *   用户点进公告页时会再拉一次，那时才把错误显示出来。
     */
    fun checkStartup() {
        if (startupChecked) return
        startupChecked = true
        viewModelScope.launch {
            when (val result = AnnouncementApi.fetchPage(page = 1, pageSize = AnnouncementApi.PAGE_SIZE)) {
                is AnnouncementApi.Result.Failure -> Log.w(TAG, "启动检查公告失败：${result.message}")
                is AnnouncementApi.Result.Success -> {
                    val page = result.data
                    // 列表页可能已经在本次请求返回前自己拉过了（用户手快）：不要覆盖它的结果
                    if (_list.value.items.isEmpty()) {
                        _list.value = ListUiState(
                            items = page.list,
                            page = page.page,
                            hasMore = page.hasMore
                        )
                    }
                    _popup.value = pickPopupCandidate(page.list, readStore.readIds.value)
                }
            }
        }
    }

    /** 列表页首次展示：没有数据时补拉一次（启动检查已拉成功过就直接用那份，不会再请求） */
    fun ensureLoaded() {
        val state = _list.value
        if (state.items.isNotEmpty() || state.loading || state.refreshing || state.error != null) return
        loadFirstPage(refreshing = false)
    }

    /** 手动刷新 / 下拉刷新：重新拉第一页并覆盖列表 */
    fun refresh() = loadFirstPage(refreshing = true)

    private fun loadFirstPage(refreshing: Boolean) {
        val state = _list.value
        if (state.loading || state.refreshing) return
        _list.value = state.copy(loading = !refreshing, refreshing = refreshing, error = null)
        viewModelScope.launch {
            when (val result = AnnouncementApi.fetchPage(page = 1, pageSize = AnnouncementApi.PAGE_SIZE)) {
                is AnnouncementApi.Result.Failure -> {
                    _list.update { it.copy(loading = false, refreshing = false, error = result.message) }
                    SnackbarController.show("公告加载失败：${result.message}")
                }
                is AnnouncementApi.Result.Success -> {
                    val page = result.data
                    _list.update {
                        it.copy(
                            items = page.list,
                            page = page.page,
                            hasMore = page.hasMore,
                            loading = false,
                            refreshing = false,
                            error = null
                        )
                    }
                }
            }
        }
    }

    /** 加载下一页（只有公告总数超过 [AnnouncementApi.PAGE_SIZE] 时才用得上） */
    fun loadMore() {
        val state = _list.value
        if (!state.hasMore || state.loadingMore || state.loading || state.refreshing || state.page <= 0) return
        val next = state.page + 1
        _list.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val result = AnnouncementApi.fetchPage(page = next, pageSize = AnnouncementApi.PAGE_SIZE)) {
                is AnnouncementApi.Result.Failure -> {
                    _list.update { it.copy(loadingMore = false, error = result.message) }
                    SnackbarController.show("加载更多失败：${result.message}")
                }
                is AnnouncementApi.Result.Success -> {
                    val page = result.data
                    _list.update { s ->
                        // 按 id 去重合并：翻页期间若有新公告插入，服务端分页可能把同一条返回两次
                        val existing = s.items.map { it.id }.toSet()
                        s.copy(
                            items = s.items + page.list.filter { it.id !in existing },
                            page = page.page,
                            hasMore = page.hasMore,
                            loadingMore = false,
                            error = null
                        )
                    }
                }
            }
        }
    }

    /** 打开详情（进入详情页 / 从启动弹窗点「查看详情」）：命中缓存则直接出内容，不重复请求 */
    fun openDetail(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return
        detailCache[trimmed]?.let { cached ->
            _detail.value = DetailUiState.Loaded(cached)
            readStore.markRead(trimmed)
            return
        }
        val current = _detail.value
        if (current is DetailUiState.Loading && current.id == trimmed) return
        _detail.value = DetailUiState.Loading(trimmed)
        viewModelScope.launch {
            when (val result = AnnouncementApi.fetchDetail(trimmed)) {
                is AnnouncementApi.Result.Failure -> _detail.value = DetailUiState.Failed(trimmed, result.message)
                is AnnouncementApi.Result.Success -> {
                    val item = result.data
                    detailCache[trimmed] = item
                    _detail.value = DetailUiState.Loaded(item)
                    // 打开详情即已读（已读口径完全由本地记录维护）
                    readStore.markRead(trimmed)
                    // 详情返回的是 +1 之后的真实浏览量：回填列表项，返回列表时数字是新的
                    _list.update { s ->
                        if (s.items.none { it.id == trimmed }) {
                            s
                        } else {
                            s.copy(items = s.items.map { if (it.id == trimmed) item else it })
                        }
                    }
                }
            }
        }
    }

    /** 详情加载失败后重试（清掉可能存在的坏缓存） */
    fun retryDetail() {
        val failed = _detail.value as? DetailUiState.Failed ?: return
        detailCache.remove(failed.id)
        openDetail(failed.id)
    }

    /** 关掉启动弹窗（「知道了」/ 点空白）：标记已读，避免每次启动都被同一条置顶公告拦住 */
    fun consumePopup() {
        _popup.value?.let { readStore.markRead(it.id) }
        _popup.value = null
    }

    /** 「全部标为已读」：针对已加载到的公告（启动时一次取满 100 条，实际等于全部） */
    fun markAllRead() {
        val count = readStore.markAllRead(_list.value.items.map { it.id })
        SnackbarController.show(if (count > 0) "已将 $count 条公告标为已读" else "没有未读公告")
    }

    /**
     * 启动弹窗候选（需求口径）：
     * 1. 有**未读的置顶公告** → 取它（服务端已把置顶排在最前，组内按 sortOrder、publishAt 倒序，取第一条未读置顶即可）；
     * 2. 否则取**最新的一条未读** —— 不能直接拿列表第一条：首条可能是「已读的置顶公告」，
     *    这时「最新未读」应按生效时间（publishAt，缺失时 createdAt）取最大值；
     * 3. 全部已读 → 不弹（null）。
     */
    private fun pickPopupCandidate(
        items: List<AnnouncementApi.Announcement>,
        read: Set<String>
    ): AnnouncementApi.Announcement? {
        val unread = items.filter { it.id.isNotEmpty() && it.id !in read }
        if (unread.isEmpty()) return null
        unread.firstOrNull { it.isPinned }?.let { return it }
        return unread.maxByOrNull { it.effectiveMillis } ?: unread.first()
    }

    class Factory(private val readStore: AnnouncementReadStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AnnouncementViewModel::class.java))
            return AnnouncementViewModel(readStore) as T
        }
    }

    companion object {
        private const val TAG = "YunX-Announce"
    }
}
