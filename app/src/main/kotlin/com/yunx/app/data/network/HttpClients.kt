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

package com.yunx.app.data.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 全局 HTTP 客户端管理：
 * - [apiClient]：平台 API（登录/解析/直链）、HLS 下载、更新检查共用，超时宽松；
 * - [downloadClient]：分片下载专用，大 Dispatcher 保障分片并发（默认实例 maxRequestsPerHost=5 会锁死并发）。
 * 所有构建都使用系统证书链和 OkHttp 主机名校验，不提供进程内绕过开关。
 */
object HttpClients {

    private val lock = Any()

    @Volatile
    private var apiCache: OkHttpClient? = null

    @Volatile
    private var downloadCache: OkHttpClient? = null

    /** 当前生效的 HTTP 代理主机；null 表示直连 */
    @Volatile
    private var proxyHost: String? = null

    /** 当前生效的 HTTP 代理端口；0 表示直连 */
    @Volatile
    private var proxyPort: Int = 0

    /**
     * 配置全局 HTTP 代理。host 为 null 或 port 不在 1-65535 时视为不使用代理（直连）。
     * 调用后清空已构建的客户端缓存，使下次获取时按新代理重建，立即生效。
     * 本对象不持有 Context，仅依赖 java.net 标准库与 OkHttp 内置代理支持。
     */
    fun setProxy(host: String?, port: Int) {
        synchronized(lock) {
            proxyHost = host
            proxyPort = port
            // 使既有客户端失效：下次 apiClient()/downloadClient() 时重建，代理立即生效
            apiCache = null
            downloadCache = null
        }
    }

    /** 构建代理实例；仅在代理主机与端口均有效时返回非 null，否则返回 null（直连） */
    private fun currentProxy(): Proxy? {
        val host = proxyHost ?: return null
        if (proxyPort !in 1..65535) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, proxyPort))
    }

    /** 普通 API 客户端（各平台 API、HLS、更新检查） */
    fun apiClient(): OkHttpClient {
        apiCache?.let { return it }
        synchronized(lock) {
            apiCache?.let { return it }
            return buildApi().also { apiCache = it }
        }
    }

    /** 下载专用客户端：大 Dispatcher + 长超时，不锁死分片并发 */
    fun downloadClient(): OkHttpClient {
        downloadCache?.let { return it }
        synchronized(lock) {
            downloadCache?.let { return it }
            return buildDownload().also { downloadCache = it }
        }
    }

    private fun buildApi(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                // 配置了有效代理时注入；否则保持默认直连，行为与改动前完全一致
                currentProxy()?.let { proxy -> proxy(proxy) }
            }
            .build()
    }

    private fun buildDownload(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 512
            maxRequestsPerHost = 512 // 与设置页线程数上限（512）对齐，不锁死并发
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 64,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES
                )
            )
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                // 配置了有效代理时注入；否则保持默认直连，行为与改动前完全一致
                currentProxy()?.let { proxy -> proxy(proxy) }
            }
            .build()
    }
}
