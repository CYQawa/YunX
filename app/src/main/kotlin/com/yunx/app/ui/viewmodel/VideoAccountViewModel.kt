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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.db.VideoAccountEntity
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.data.repository.VideoAccountRepository
import com.yunx.app.ui.SnackbarController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 视频解析账号 ViewModel：账号页的登录态 + 各平台账号管理（设主账号 / 改备注 / 退出 / 退出全部）。
 *
 * ★ 与网盘那套的区别：视频平台只需要「一个 Cookie」，没有平台专属的凭证字段，
 *   所以凭证读写和账号管理都收在同一个仓库（[VideoAccountRepository]）里，
 *   这里只做「暴露 Flow + 把挂起操作丢进 viewModelScope」两件事。
 *
 * 构造器是 internal 的：仓库本身是 internal（视频账号表结构与网盘不同，不对外暴露），
 * 用 internal 构造器可以避免 public 类在签名里暴露 internal 类型。
 */
class VideoAccountViewModel internal constructor(
    private val repository: VideoAccountRepository
) : ViewModel() {

    /** 各平台当前账号（platform → 当前账号，无则不在 Map 里）：账号页「登录 / 管理」由此驱动 */
    val loginState: StateFlow<Map<VideoPlatform, VideoAccountEntity>> = repository.observeLoginState()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    /** 全部平台的视频账号：账号页用它统计「共 N 个账号」，账号弹窗用它列出该平台的所有行 */
    val accounts: StateFlow<List<VideoAccountEntity>> = repository.observeAccounts()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * 保存登录态（先校验再落库），返回昵称；Cookie 无效返回 null。
     * 不加 Snackbar 提示：登录页自己要区分「自动检测」与「手动保存」两种语气的反馈。
     */
    suspend fun save(platform: VideoPlatform, cookie: String, newAccount: Boolean): String? =
        repository.save(platform, cookie, newAccount)

    fun setPrimary(platform: VideoPlatform, id: String) {
        viewModelScope.launch {
            repository.setPrimary(platform, id)
            SnackbarController.show("已设为主账号")
        }
    }

    fun updateRemark(id: String, remark: String) {
        viewModelScope.launch {
            repository.updateRemark(id, remark.trim())
            SnackbarController.show("备注已更新")
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            repository.remove(id)
            SnackbarController.show("已退出该账号")
        }
    }

    fun removeAll(platform: VideoPlatform) {
        viewModelScope.launch {
            repository.removeAll(platform)
            SnackbarController.show("已退出${platform.displayName}全部账号")
        }
    }

    class Factory(
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VideoAccountViewModel::class.java))
            return VideoAccountViewModel(
                VideoAccountRepository(AppDatabase.get(context).videoAccountDao())
            ) as T
        }
    }
}
