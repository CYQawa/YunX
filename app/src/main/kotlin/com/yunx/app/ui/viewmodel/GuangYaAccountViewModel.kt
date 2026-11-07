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
import com.yunx.app.R
import com.yunx.app.data.db.GuangYaAccountEntity
import com.yunx.app.data.repository.GuangYaAccountRepository
import com.yunx.app.util.AppStrings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

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

    /**
     * 「添加账号」标记：网盘页账号弹窗点「添加账号」时置位，登录页关闭时清掉。
     * 置位后这一次登录会落成新的账号行，而不是覆盖当前主账号（见 [resolveAccountSlot]）。
     */
    private var addNewAccount = false

    fun beginAddAccount() {
        addNewAccount = true
    }

    fun cancelAddAccount() {
        addNewAccount = false
    }

    /** 账号密码登录；返回 (是否成功, 提示文案)。 */
    suspend fun login(account: String, password: String): Pair<Boolean, String> = try {
        if (account.isBlank() || password.isBlank()) {
            false to AppStrings.get(R.string.vm_guangya_enter_account_password)
        } else {
            // ★ 必须按 Result 分流：登录失败时仓库返回的是 failure，不是抛异常。
            //   旧实现只看了 isSuccess 去清标记，然后无条件回「登录成功」——
            //   于是随便输个账号密码也提示成功（其实一条账号都没落库，自然没有登录态）。
            repository.login(account, password, newAccount = addNewAccount).fold(
                onSuccess = {
                    addNewAccount = false
                    true to AppStrings.get(R.string.vm_login_success)
                },
                onFailure = { e ->
                    false to (e.message ?: AppStrings.get(R.string.vm_guangya_login_failed))
                }
            )
        }
    } catch (e: Exception) {
        false to (e.message ?: AppStrings.get(R.string.vm_guangya_login_failed))
    }

    /** 发送短信验证码的结果：成功返回 verification_id 与是否老用户，供登录页倒计时/端点选择。 */
    sealed interface GuangYaSmsResult {
        data class Success(val verificationId: String, val isUser: Boolean, val expiresIn: Int) : GuangYaSmsResult
        data class Failure(val message: String) : GuangYaSmsResult
    }

    /** 发送短信验证码（文档 §2.6）。 */
    suspend fun sendSms(phoneNumber: String): GuangYaSmsResult = try {
        if (phoneNumber.isBlank()) {
            GuangYaSmsResult.Failure(AppStrings.get(R.string.vm_guangya_enter_phone))
        } else {
            val challenge = repository.sendSms(phoneNumber).getOrThrow()
            GuangYaSmsResult.Success(challenge.verificationId, challenge.isUser, challenge.expiresIn)
        }
    } catch (e: Exception) {
        GuangYaSmsResult.Failure(e.message ?: AppStrings.get(R.string.vm_guangya_send_sms_failed))
    }

    /** 短信验证码登录 / 注册（文档 §2.7/§2.8）：返回 (是否成功, 提示文案)。 */
    suspend fun smsLogin(
        phoneNumber: String,
        verificationId: String,
        code: String,
        isUser: Boolean
    ): Pair<Boolean, String> = try {
        if (phoneNumber.isBlank()) {
            false to AppStrings.get(R.string.vm_guangya_enter_phone)
        } else if (verificationId.isBlank()) {
            false to AppStrings.get(R.string.vm_guangya_get_code_first)
        } else if (code.isBlank()) {
            false to AppStrings.get(R.string.vm_guangya_enter_code)
        } else {
            // 同上：短信登录失败也是 Result.failure，不能无条件回「登录成功」
            repository.smsLogin(
                phoneNumber = phoneNumber,
                verificationId = verificationId,
                code = code,
                isUser = isUser,
                newAccount = addNewAccount
            ).fold(
                onSuccess = {
                    addNewAccount = false
                    true to AppStrings.get(R.string.vm_login_success)
                },
                onFailure = { e ->
                    false to (e.message ?: AppStrings.get(R.string.vm_guangya_sms_invalid))
                }
            )
        }
    } catch (e: Exception) {
        false to (e.message ?: AppStrings.get(R.string.vm_guangya_sms_invalid))
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
