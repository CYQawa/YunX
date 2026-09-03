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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.XunleiAccountEntity
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiLoginStep
import com.yunx.app.data.repository.XunleiAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 迅雷账号 ViewModel：账号+密码登录（可能触发短信验证码）→ 换 token 落库。
 */
class XunleiAccountViewModel(
    private val repository: XunleiAccountRepository
) : ViewModel() {

    val xunleiAccount: StateFlow<XunleiAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 密码登录结果（needSms=true 时 UI 切到短信验证步骤） */
    var loginStep by androidx.compose.runtime.mutableStateOf<XunleiLoginStep?>(null)
        private set

    /** 登录错误信息 */
    var loginError by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    /** 短信验证码是否已发送（进入短信界面不会自动发送；区分「发送验证码」/「重新发送验证码」） */
    var smsSent by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** 最近一次密码登录凭据（WebView 验证成功后自动重试登录用，仅内存，不持久化） */
    private var lastUsername = ""
    private var lastPassword = ""

    fun consumeLoginError() {
        loginError = null
    }

    /** 账号密码登录 */
    fun login(username: String, password: String) {
        lastUsername = username.trim()
        lastPassword = password
        viewModelScope.launch {
            loginError = null
            loginStep = null
            smsSent = false
            val step = repository.loginWithPassword(username.trim(), password)
            if (step.needSms) {
                // 触发安全验证：优先用 reviewurl 里的 creditkey（风控响应自带），否则走自有 sendSms
                val reviewMap = XunleiApi.parseReviewUrl(step.reviewUrl)
                val creditKey = reviewMap["creditkey"].orEmpty()
                if (creditKey.isNotBlank()) {
                    // 直接用响应里的 creditkey/token 进入短信输入步骤（token 可能为空，sendSms 会补）；
                    // 进入界面不会自动发送验证码，smsSent 保持 false，UI 显示「发送验证码」
                    loginStep = step.copy(
                        smsCreditKey = creditKey,
                        smsToken = reviewMap["token"].orEmpty()
                    )
                } else {
                    val smsStep = repository.sendSms(username.trim())
                    if (smsStep.smsCreditKey.isNotBlank()) {
                        smsSent = true
                        loginStep = smsStep
                    } else {
                        // 不再丢外部链接：给明确失败提示 + 让用户重试
                        loginError = smsStep.message.ifBlank { "短信发送失败，请重试或检查网络" }
                        loginStep = step.copy(message = "短信发送失败")
                    }
                }
            } else if (step.sessionKey.isNotBlank() && step.sessionId.isNotBlank()) {
                val ok = repository.finishLogin(step, username.trim())
                if (!ok) loginError = "登录失败，无法换取凭证"
            } else {
                loginError = step.message.ifBlank { "登录失败，请检查账号密码" }
            }
        }
    }

    /** WebView 验证成功后自动重试登录（设备已验证受信任，密码登录应直接成功） */
    fun retryLoginAfterVerify() {
        if (lastUsername.isNotBlank() && lastPassword.isNotBlank()) {
            login(lastUsername, lastPassword)
        }
    }

    /** 发送短信验证码（密码登录触发验证后） */
    fun sendSms(mobile: String) {
        viewModelScope.launch {
            loginError = null
            val step = repository.sendSms(mobile.trim())
            if (step.smsCreditKey.isNotBlank()) smsSent = true
            loginStep = step
            if (step.smsCreditKey.isBlank()) loginError = step.message
        }
    }

    /** 短信验证码登录并完成 */
    fun loginWithSms(mobile: String, code: String, creditKey: String, smsToken: String) {
        viewModelScope.launch {
            loginError = null
            val ok = repository.loginWithSms(mobile.trim(), code.trim(), creditKey, smsToken)
            if (!ok) loginError = "验证码校验失败"
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: XunleiAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(XunleiAccountViewModel::class.java))
            return XunleiAccountViewModel(repository) as T
        }
    }
}