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

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * GitHub 目录条目的「最后提交时间」内存缓存。
 *
 * 背景：进入代码目录时，对当前层每个条目调一次 commits API（[GitHubApi.getLastCommitDate]）。
 * 匿名限额仅 60 次/小时/IP，重复进出同一目录会重复请求，失败立即重试会加剧限流。
 *
 * 设计：
 * - **成功缓存 TTL = 10 分钟**：提交时间不会频繁变化，10 分钟内重复浏览同一目录不发请求；
 *   选 10 分钟而非永久，是为了新 push 后用户能在合理时间内看到更新。
 * - **失败也缓存 null，TTL = 1 分钟**：避免对 404/限流/网络错误的条目在短时间内反复打 API。
 * - **in-flight 去重**：同一 key 同时多个协程请求时只发一个网络请求，其余复用结果，
 *   防止列表渲染后并发补时间时对同一路径重复请求（实际目录层不会重复，但防御性设计）。
 * - **容量上限 512**：按写入顺序淘汰最旧条目，避免长时间使用后内存无限增长。
 *
 * 注意：仅会话内内存缓存，跨进程重启后失效（属预期，不做持久化）。
 */
object GitHubCommitDateCache {

    private data class CacheEntry(
        val fetchedAt: Long,
        val date: String?,
        val success: Boolean
    )

    /** key = "owner/repo/path" */
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** in-flight 请求：同一 key 合并为单个 Deferred */
    private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

    /** 保护容量淘汰与 in-flight 登记的互斥锁（细粒度，仅保护元数据操作） */
    private val mutex = Mutex()

    private const val SUCCESS_TTL_MS = 10 * 60 * 1000L   // 成功：10 分钟
    private const val FAILURE_TTL_MS = 1 * 60 * 1000L    // 失败：1 分钟
    private const val MAX_ENTRIES = 512

    private fun key(owner: String, repo: String, path: String) = "$owner/$repo/$path"

    /**
     * 获取某路径最后提交时间。命中未过期缓存直接返回；未命中发请求并写缓存。
     * 失败返回 null（与 [GitHubApi.getLastCommitDate] 语义一致）。
     */
    suspend fun get(owner: String, repo: String, path: String, api: GitHubApi): String? {
        val k = key(owner, repo, path)
        val now = System.currentTimeMillis()

        // 1) 查已完成缓存
        cache[k]?.let { entry ->
            val ttl = if (entry.success) SUCCESS_TTL_MS else FAILURE_TTL_MS
            if (now - entry.fetchedAt <= ttl) {
                return entry.date
            }
            // 过期，移除
            cache.remove(k)
        }

        // 2) in-flight 去重：已有请求在飞则复用
        inFlight[k]?.let { return it.await() }

        // 3) 发起新请求（用 coroutineScope 包一层，登记到 inFlight）
        val deferred = coroutineScope {
            val d = async {
                runCatching { api.getLastCommitDate(owner, repo, path) }.getOrNull()
            }
            // 登记 in-flight；若并发竞态已有同 key，则取消本协程并复用已有
            val existing = inFlight.putIfAbsent(k, d)
            if (existing != null) {
                d.cancel()
                return@coroutineScope existing.await()
            }
            try {
                val result = d.await()
                // 写缓存
                put(k, now = System.currentTimeMillis(), date = result, success = result != null)
                result
            } finally {
                inFlight.remove(k)
            }
        }
        return deferred
    }

    /** 写入缓存并做容量淘汰 */
    private suspend fun put(k: String, now: Long, date: String?, success: Boolean) = mutex.withLock {
        // 容量超限：按 fetchedAt 移除最旧条目（简单线性扫描，512 条规模可接受）
        if (cache.size >= MAX_ENTRIES) {
            cache.entries.minByOrNull { it.value.fetchedAt }?.let { oldest ->
                cache.remove(oldest.key)
            }
        }
        cache[k] = CacheEntry(fetchedAt = now, date = date, success = success)
    }

    /**
     * 删除所有 key 以 [prefix] 开头的条目（下拉刷新绕缓存用）。
     * key 形如 "owner/repo/path"，传 "owner/repo/" 即可清该仓库全部路径时间缓存，
     * 让用户主动刷新时重新拉取最新提交时间。
     */
    fun invalidatePrefix(prefix: String) {
        cache.keys.removeAll { it.startsWith(prefix) }
    }
}
