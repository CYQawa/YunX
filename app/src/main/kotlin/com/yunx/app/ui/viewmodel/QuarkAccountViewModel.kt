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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.QuarkAccountEntity
import com.yunx.app.data.repository.QuarkAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 夸克账号 ViewModel：暴露登录态，供主页与登录页共享。
 */
class QuarkAccountViewModel(
    private val repository: QuarkAccountRepository
) : ViewModel() {

    val quarkAccount: StateFlow<QuarkAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 保存夸克 Cookie；返回是否保存成功 */
    suspend fun saveQuarkAccount(cookie: String): Boolean =
        repository.saveQuarkAccount(cookie)

    /** 退出登录：清除本地 Cookie */
    fun logout() {
        viewModelScope.launch { repository.logoutQuark() }
    }

    class Factory(
        private val repository: QuarkAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(QuarkAccountViewModel::class.java))
            return QuarkAccountViewModel(repository) as T
        }
    }
}