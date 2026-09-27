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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * GitHub API 统一内存缓存（原始响应文本）。
 *
 * - 成功条目 TTL 10 分钟（GitHub 数据不会秒级变化，浏览/返回时命中省请求）；
 * - 失败条目 TTL 1 分钟（避免对限流/404 路径反复打 API）；
 * - 容量上限 256 条，超限淘汰最旧条目；
 * - in-flight 去重：同一 key 并发只发一次网络请求，其余协程等待同一份结果；
 * - [invalidatePrefix] 供下拉刷新使用，按前缀清空（用户主动刷新要看最新数据）。
 *
 * 仅进程内缓存，不持久化；换 Token 后带 token 指纹的 key 自然不命中旧缓存。
 */
object GitHubResponseCache {
    private data class Entry(
        /** 响应原文；失败时为 null（作为失败哨兵） */
        val value: String?,
        val fetchedAt: Long,
        val success: Boolean
    )

    private const val SUCCESS_TTL_MS = 10 * 60 * 1000L   // 成功 10 分钟
    private const val FAIL_TTL_MS = 1 * 60 * 1000L       // 失败 1 分钟
    private const val MAX_ENTRIES = 256

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val evictMutex = Mutex()

    /**
     * 命中有效缓存直接返回；否则执行 [fetch] 取网络结果并写入缓存。
     * 同 key 并发只发一次请求，其余等待同一 Deferred。
     * @param maxBytes 成功结果超过此大小则不写缓存（防大 README 占内存）；<=0 不限制。
     */
    suspend fun getOrFetch(key: String, maxBytes: Int = 0, fetch: suspend () -> String?): String? {
        // 1) 命中（含失败哨兵在短 TTL 内）直接返回
        cache[key]?.let { entry ->
            val ttl = if (entry.success) SUCCESS_TTL_MS else FAIL_TTL_MS
            if (System.currentTimeMillis() - entry.fetchedAt <= ttl) {
                return entry.value
            }
            // 过期淘汰
            cache.remove(key)
        }
        // 2) in-flight 去重：已有同 key 请求在飞，等待其结果
        inFlight[key]?.let { return it.await() }
        // 3) 自己发起请求
        val deferred = CompletableDeferred<String?>()
        inFlight[key] = deferred
        val result = try {
            fetch()
        } catch (ce: CancellationException) {
            inFlight.remove(key, deferred)
            deferred.complete(null)
            throw ce
        } catch (_: Exception) {
            null
        }
        // 超过大小上限的成功结果不写缓存（如超大 README），直接返回
        if (result != null && maxBytes > 0 && result.length > maxBytes) {
            // 不写缓存
        } else {
            put(key, result, result != null)
        }
        deferred.complete(result)
        inFlight.remove(key, deferred)
        return result
    }

    /** 写入条目（成功/失败）；超容量淘汰最旧 */
    suspend fun put(key: String, value: String?, success: Boolean) {
        cache[key] = Entry(value = value, fetchedAt = System.currentTimeMillis(), success = success)
        evictIfNeeded()
    }

    /** 删除所有 key 以 [prefix] 开头的条目（下拉刷新绕缓存用） */
    fun invalidatePrefix(prefix: String) {
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    /** 容量超限按 fetchedAt 淘汰最旧条目 */
    private suspend fun evictIfNeeded() = evictMutex.withLock {
        if (cache.size <= MAX_ENTRIES) return@withLock
        val toRemove = cache.entries
            .sortedBy { it.value.fetchedAt }
            .take(cache.size - MAX_ENTRIES)
            .map { it.key }
        toRemove.forEach { cache.remove(it) }
    }
}
