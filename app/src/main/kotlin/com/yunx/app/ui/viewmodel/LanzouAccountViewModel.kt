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
import com.yunx.app.data.db.LanzouAccountEntity
import com.yunx.app.data.repository.LanzouAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 蓝奏云账号 ViewModel：网页登录 Cookie（ylogin + phpdisk_info）校验落库。
 */
class LanzouAccountViewModel(
    private val repository: LanzouAccountRepository
) : ViewModel() {

    val lanzouAccount: StateFlow<LanzouAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 校验并保存 Cookie；返回是否成功（登录页「保存」与自动检测共用）。 */
    suspend fun saveCookie(cookie: String): Boolean = repository.saveCookie(cookie)

    /** 原生账号密码登录（官网接口 + 人机校验）；成功返回账号实体。 */
    suspend fun login(account: String, password: String): Result<LanzouAccountEntity> =
        repository.login(account, password)

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: LanzouAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LanzouAccountViewModel::class.java))
            return LanzouAccountViewModel(repository) as T
        }
    }
}
