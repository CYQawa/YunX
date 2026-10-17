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

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.MultiAccountEntity
import com.yunx.app.data.repository.AccountDirectory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 多账号的账号维度操作：列表 / 设主账号 / 改备注 / 退出。
 *
 * ★ 为什么单独一个 ViewModel：这四件事对 10 个平台是同构的（见 [AccountDirectory]），
 *   塞进 10 个平台的 `*AccountViewModel` 只会得到 10 份一样的代码，
 *   还要给网盘页加几十个回调参数。平台专属的凭证读写仍留在各自的 Repository。
 */
class MultiAccountViewModel(
    private val directory: AccountDirectory
) : ViewModel() {

    /** 按平台缓存的账号列表：Compose 重组时不能每次都新建 Flow（会丢状态、重复查库） */
    private val caches = mutableMapOf<String, StateFlow<List<MultiAccountEntity>>>()

    /** 该平台全部已登录账号（主账号在前） */
    fun accountsOf(platform: String): StateFlow<List<MultiAccountEntity>> =
        caches.getOrPut(platform) {
            directory.observe(platform).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )
        }

    fun setPrimary(platform: String, id: String) {
        viewModelScope.launch { directory.setPrimary(platform, id) }
    }

    fun rename(platform: String, id: String, remark: String) {
        viewModelScope.launch { directory.setRemark(platform, id, remark.trim()) }
    }

    /**
     * 准备「添加账号」：先清一次 WebView 登录态，上层随后打开该平台的登录页。
     *
     * ★ 不清的话，网页登录型平台（夸克 / UC / 百度 / 139 / 123 / 115）的登录页会把 WebView 里
     *   **已有的**登录态自动检测出来并保存 —— 于是「添加账号」加进来的还是同一个账号，
     *   只会多出一行重复记录。清 WebView 只影响网页登录态，已落库的账号不受影响
     *   （各平台 API 用的是库里的 Cookie，不是 WebView）。
     */
    fun prepareAddAccount() = clearWebSession()

    /**
     * 退出某个账号（只删这一条，其他账号照常可用）。
     *
     * ★ 该平台最后一个账号被删掉时，顺手清一次 WebView 登录态：网页登录页里残留的
     *   Cookie / localStorage 会在下次打开登录页时把用户自动登回旧账号，让「退出」形同虚设
     *   （与各仓库原来的 logout 口径一致，只是这里统一做，不必按平台分 10 套）。
     *   这些方法要求有 Looper，本方法由 viewModelScope（Main）执行，没问题。
     */
    fun remove(platform: String, id: String) {
        viewModelScope.launch {
            directory.remove(platform, id)
            if (directory.list(platform).isEmpty()) clearWebSession()
        }
    }

    /** 清空 WebView 的 Cookie 与 localStorage/DOM 存储（须在主线程调用） */
    private fun clearWebSession() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
    }

    class Factory(
        private val directory: AccountDirectory
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MultiAccountViewModel::class.java))
            return MultiAccountViewModel(directory) as T
        }
    }
}
