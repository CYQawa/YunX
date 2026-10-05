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
import com.yunx.app.data.db.Pan123AccountEntity
import com.yunx.app.data.repository.Pan123AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 123 云盘账号 ViewModel：两条登录路径（网页 authorToken / 原生账号密码）最终都落成同一份
 * JWT 凭证，这里只暴露登录态与两个入口，供主页/登录页/解析页共享。
 */
class Pan123AccountViewModel(
    private val repository: Pan123AccountRepository
) : ViewModel() {

    val pan123Account: StateFlow<Pan123AccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 网页登录凭证（authorToken）校验并落库；返回是否保存成功（登录页「保存」与自动检测共用同一入口） */
    suspend fun saveToken(token: String): Boolean = repository.saveToken(token)

    /**
     * 账号密码登录（123 原生 sign_in 接口）。
     * @return null = 登录成功；否则是可直接展示的错误文案
     */
    suspend fun login(account: String, password: String): String? =
        try {
            repository.loginWithPassword(account, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 登录接口自身已经把网络错误映射成文案了，这里是最后一道兜底（写库等非网络异常）
            "登录失败，请稍后重试"
        }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: Pan123AccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(Pan123AccountViewModel::class.java))
            return Pan123AccountViewModel(repository) as T
        }
    }
}
