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
import com.yunx.app.data.db.GuangYaAccountEntity
import com.yunx.app.data.repository.GuangYaAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 光鸭云盘账号 ViewModel：账号密码登录并落库，暴露登录态供主页 / 登录页 / 解析页共享。
 */
class GuangYaAccountViewModel(
    private val repository: GuangYaAccountRepository
) : ViewModel() {

    val guangyaAccount: StateFlow<GuangYaAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 账号密码登录；返回 (是否成功, 提示文案)。 */
    suspend fun login(account: String, password: String): Pair<Boolean, String> = try {
        if (account.isBlank() || password.isBlank()) {
            false to "请输入光鸭账号和密码"
        } else {
            repository.login(account, password)
            true to "登录成功"
        }
    } catch (e: Exception) {
        false to (e.message ?: "光鸭登录失败，请检查账号密码，或切换网页登录")
    }

    /** 发送短信验证码的结果：成功返回 verification_id 与是否老用户，供登录页倒计时/端点选择。 */
    sealed interface GuangYaSmsResult {
        data class Success(val verificationId: String, val isUser: Boolean, val expiresIn: Int) : GuangYaSmsResult
        data class Failure(val message: String) : GuangYaSmsResult
    }

    /** 发送短信验证码（文档 §2.6）。 */
    suspend fun sendSms(phoneNumber: String): GuangYaSmsResult = try {
        if (phoneNumber.isBlank()) {
            GuangYaSmsResult.Failure("请输入手机号")
        } else {
            val challenge = repository.sendSms(phoneNumber).getOrThrow()
            GuangYaSmsResult.Success(challenge.verificationId, challenge.isUser, challenge.expiresIn)
        }
    } catch (e: Exception) {
        GuangYaSmsResult.Failure(e.message ?: "发送验证码失败")
    }

    /** 短信验证码登录 / 注册（文档 §2.7/§2.8）：返回 (是否成功, 提示文案)。 */
    suspend fun smsLogin(
        phoneNumber: String,
        verificationId: String,
        code: String,
        isUser: Boolean
    ): Pair<Boolean, String> = try {
        if (phoneNumber.isBlank()) {
            false to "请输入手机号"
        } else if (verificationId.isBlank()) {
            false to "请先获取验证码"
        } else if (code.isBlank()) {
            false to "请输入验证码"
        } else {
            repository.smsLogin(phoneNumber, verificationId, code, isUser)
            true to "登录成功"
        }
    } catch (e: Exception) {
        false to (e.message ?: "短信验证码错误或已过期，请重新获取")
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: GuangYaAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GuangYaAccountViewModel::class.java))
            return GuangYaAccountViewModel(repository) as T
        }
    }
}
