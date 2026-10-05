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
import com.yunx.app.data.db.ILanzouAccountEntity
import com.yunx.app.data.repository.ILanzouAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 蓝奏云优享版账号 ViewModel：账号密码登录并落库。
 */
class ILanzouAccountViewModel(
    private val repository: ILanzouAccountRepository
) : ViewModel() {

    val ilanzouAccount: StateFlow<ILanzouAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 账号密码登录；返回 (是否成功, 提示文案)。 */
    suspend fun login(account: String, password: String): Pair<Boolean, String> {
        if (account.isBlank() || password.isBlank()) {
            return false to "请输入蓝奏优享账号和密码"
        }
        // repository.login 内部用 runCatching，返回 Result（不会抛异常），必须按 Result 判定成败
        return repository.login(account, password).fold(
            onSuccess = { true to "登录成功" },
            onFailure = { false to (it.message ?: "蓝奏优享登录失败，请检查账号密码") }
        )
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: ILanzouAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ILanzouAccountViewModel::class.java))
            return ILanzouAccountViewModel(repository) as T
        }
    }
}
