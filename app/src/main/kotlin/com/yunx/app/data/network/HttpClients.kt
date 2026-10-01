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

    /** 下载客户端排队 Call 上限：真实并发受 DownloadManager 的在飞上限约束，见 buildDownload 注释 */
    private const val MAX_QUEUED_CALLS = 64

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

    /**
     * 释放两个客户端的空闲连接（socket + Conscrypt/HTTP2 缓冲），由 Application.onTrimMemory
     * 在系统内存压力下调用。只影响空闲连接，进行中的请求不受影响；任何异常都不得抛给调用方。
     */
    fun evictIdleConnections() {
        runCatching { apiCache?.connectionPool?.evictAll() }
        runCatching { downloadCache?.connectionPool?.evictAll() }
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
            // 排队 Call 上限：下载分片走同步 call.execute()，根本不经过这个队列 —— 真实并发由
            // DownloadManager 的 inflightLimiter 与 chunkIoDispatcher 决定（见 Agent.md §5.1.1）
            maxRequests = MAX_QUEUED_CALLS
            maxRequestsPerHost = MAX_QUEUED_CALLS
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            // 空闲连接池收紧：默认 64 条 × 5 分钟会常驻 socket + Conscrypt 缓冲，
            // 下载是突发式，1 分钟足够复用
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 8,
                    keepAliveDuration = 1,
                    timeUnit = TimeUnit.MINUTES
                )
            )
            // ★ 下载客户端固定只用 HTTP/1.1（2026-10-01）：
            //   OkHttp 的 HTTP/2 客户端对外声明「每流 16MB 接收窗口」（OKHTTP_CLIENT_WINDOW_SIZE，见 Agent.md §5.1.1），
            //   消费端一慢（写盘慢/限速挂起），读线程仍会把数据堆进该流的 readBuffer，几十路叠加即撑满 256MB 堆
            //   —— log/oom 两份崩溃的栈（Http2Stream$FramingSource.receive → SegmentPool.take）正在这里。
            //   HTTP/1.1 没有应用层流窗口，读多少由 TCP 背压决定，堆占用只剩每路 64KB 读缓冲。
            //   代价：分片不再多路复用，每路各占一条连接。若实测总速明显变差，把下面一行改回
            //   listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) 即可；协议是否生效看 ChunkDownloader 的「协议诊断」日志。
            .protocols(listOf(Protocol.HTTP_1_1))
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
