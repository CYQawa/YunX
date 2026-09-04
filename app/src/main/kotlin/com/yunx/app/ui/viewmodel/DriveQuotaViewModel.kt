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
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.model.QuotaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 网盘空间详情 ViewModel：并发加载 5 个平台的容量使用情况（仅已登录平台请求）。
 * 网盘页顶部「空间总览」展示用。
 */
class DriveQuotaViewModel(
    private val quarkApi: QuarkApi,
    private val quarkCookie: suspend () -> String?,
    private val ucApi: UCApi,
    private val ucCookie: suspend () -> String?,
    private val xunleiApi: XunleiApi,
    private val xunleiToken: suspend () -> String?,
    private val xunleiDeviceId: suspend () -> String?,
    private val xunleiCaptcha: suspend () -> String?,
    private val baiduApi: BaiduApi,
    private val baiduCookie: suspend () -> String?,
    private val c139Api: C139Api,
    private val c139Cookie: suspend () -> String?,
    private val pan123Api: Pan123Api,
    private val pan123Token: suspend () -> String?
) : ViewModel() {

    private val _quarkQuota = MutableStateFlow<QuotaInfo?>(null)
    val quarkQuota: StateFlow<QuotaInfo?> = _quarkQuota.asStateFlow()

    private val _ucQuota = MutableStateFlow<QuotaInfo?>(null)
    val ucQuota: StateFlow<QuotaInfo?> = _ucQuota.asStateFlow()

    private val _xunleiQuota = MutableStateFlow<QuotaInfo?>(null)
    val xunleiQuota: StateFlow<QuotaInfo?> = _xunleiQuota.asStateFlow()

    private val _baiduQuota = MutableStateFlow<QuotaInfo?>(null)
    val baiduQuota: StateFlow<QuotaInfo?> = _baiduQuota.asStateFlow()

    private val _c139Quota = MutableStateFlow<QuotaInfo?>(null)
    val c139Quota: StateFlow<QuotaInfo?> = _c139Quota.asStateFlow()

    private val _pan123Quota = MutableStateFlow<QuotaInfo?>(null)
    val pan123Quota: StateFlow<QuotaInfo?> = _pan123Quota.asStateFlow()

    /** 是否加载中 */
    val loading = MutableStateFlow(false)

    /** 并发加载全部已登录平台的空间（各平台独立请求，互不阻塞；未登录平台自动跳过） */
    fun loadAll() {
        if (loading.value) return // 防止下拉刷新与进入页面初始化重复触发
        loading.value = true
        viewModelScope.launch {
            coroutineScope {
                // 夸克
                launch {
                    val qc = quarkCookie()
                    if (qc != null) {
                        _quarkQuota.value = runCatching { quarkApi.getQuota(qc) }.getOrNull()
                    }
                }
                // UC
                launch {
                    val uc = ucCookie()
                    if (uc != null) {
                        _ucQuota.value = runCatching { ucApi.getQuota(uc) }.getOrNull()
                    }
                }
                // 迅雷
                launch {
                    val xl = xunleiToken()
                    if (xl != null) {
                        val deviceId = xunleiDeviceId() ?: ""
                        val captcha = xunleiCaptcha() ?: ""
                        _xunleiQuota.value = runCatching { xunleiApi.getQuota(xl, deviceId, captcha) }.getOrNull()
                    }
                }
                // 百度
                launch {
                    val bd = baiduCookie()
                    if (bd != null) {
                        _baiduQuota.value = runCatching { baiduApi.getQuota(bd) }.getOrNull()
                    }
                }
                // 139
                launch {
                    val c139 = c139Cookie()
                    if (c139 != null) {
                        _c139Quota.value = runCatching { c139Api.getQuota(c139) }.getOrNull()
                    }
                }
                // 123
                launch {
                    val p123 = pan123Token()
                    if (p123 != null) {
                        _pan123Quota.value = runCatching { pan123Api.getQuota(p123) }.getOrNull()
                    }
                }
            }
            loading.value = false
        }
    }

    class Factory(
        private val quarkApi: QuarkApi,
        private val quarkCookie: suspend () -> String?,
        private val ucApi: UCApi,
        private val ucCookie: suspend () -> String?,
        private val xunleiApi: XunleiApi,
        private val xunleiToken: suspend () -> String?,
        private val xunleiDeviceId: suspend () -> String?,
        private val xunleiCaptcha: suspend () -> String?,
        private val baiduApi: BaiduApi,
        private val baiduCookie: suspend () -> String?,
        private val c139Api: C139Api,
        private val c139Cookie: suspend () -> String?,
        private val pan123Api: Pan123Api,
        private val pan123Token: suspend () -> String?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DriveQuotaViewModel(
                quarkApi, quarkCookie,
                ucApi, ucCookie,
                xunleiApi, xunleiToken, xunleiDeviceId, xunleiCaptcha,
                baiduApi, baiduCookie,
                c139Api, c139Cookie,
                pan123Api, pan123Token
            ) as T
    }
}