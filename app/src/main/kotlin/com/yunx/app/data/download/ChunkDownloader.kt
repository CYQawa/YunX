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

package com.yunx.app.data.download

import android.util.Log
import com.yunx.app.util.LogRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.min

private const val TAG = "YunX-DL"

/** 分片下载单次失败后的瞬时 IO 重试次数 */
private const val CHUNK_RETRIES = 3
/** 当服务器忽略 Range（返回 200 整文件）时，对单分片重试 Range 的次数（指数退避后重发，CDN 负载下降后常能拿到 206） */
private const val RANGE_RETRIES = 4
/** 网络读缓冲：256KB */
private const val BUFFER_SIZE = 256 * 1024

/**
 * 分片下载结果（结构化）：
 * - OK            : 该分片已正确写入「预期字节数」；
 * - RANGE_IGNORED : 服务器忽略 Range（返回 200 整文件）——上层应回退单流整文件，**绝不为单分片下载整文件**；
 * - FAILED        : 结构性失败（非 206/200、HTML 广告页、写入字节数不足等）。
 */
enum class ChunkResult { OK, RANGE_IGNORED, FAILED }

/**
 * OkHttp 分片下载器：
 * - Range 分片 + 多线程并行 + 断点续传；
 * - 服务器忽略 Range（200 整文件）时**不下载整文件**，交由上层回退单流；
 * - 写入后严格校验「已写字节 == 预期字节」，杜绝空洞文件（损坏）；
 * - 任务级取消：每个任务 OkHttp Call 统一登记，暂停/删除时主动 cancel() 立即中断阻塞 IO。
 */
class ChunkDownloader(private val clientProvider: () -> OkHttpClient) {
    /** 每次请求动态获取全局下载客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    /** 任务 id → 该任务当前所有分片请求 */
    private val activeCalls = ConcurrentHashMap<Long, MutableSet<Call>>()
    private fun newCallSet(): MutableSet<Call> =
        Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    fun cancelCalls(taskId: Long) {
        activeCalls.remove(taskId)?.forEach { call -> runCatching { call.cancel() } }
    }

    // ---------- 总大小探测 ----------

    suspend fun getTotalSize(url: String, headers: Map<String, String>): Long? = withContext(Dispatchers.IO) {
        val withRange = probeSize(url, headers, withRange = true)
        if (withRange != null) return@withContext withRange
        probeSize(url, headers, withRange = false)
    }

    private suspend fun probeSize(url: String, headers: Map<String, String>, withRange: Boolean): Long? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (withRange) header("Range", "bytes=0-0")
                    headers.forEach { (k, v) -> header(k, v) }
                }
                .get().build()
            val call = client.newCall(request)
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                runCatching {
                    call.execute().use { response ->
                        Log.d(TAG, "getTotalSize: range=$withRange code=${response.code} ct=${response.header("Content-Type")} origin=${LogRedactor.url(url)}")
                        // ★ 防盗链/过期/错误页（HTML）直接视为无法取大小，回退流式/单流
                        if (response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) {
                            return@use null
                        }
                        if (!response.isSuccessful) return@use null
                        if (withRange) {
                            if (response.code != 206) return@use null
                            val range = HttpRangePolicy.parse(response.header("Content-Range"))
                                ?: return@use null
                            if (range.start != 0L || range.end != 0L) return@use null
                            range.total
                        } else {
                            response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                        }
                    }
                }.getOrNull()
            } finally { cancelHandle?.dispose() }
        }

    // ---------- 分片下载 ----------

    /**
     * 下载一个分片到 partFile（断点续传）。
     * - 瞬时 IO 异常：指数退避重试（CHUNK_RETRIES）；
     * - 服务器忽略 Range（RANGE_IGNORED）：退避后重发 Range 至多 RANGE_RETRIES 次，仍被忽略则返回
     *   RANGE_IGNORED 交由 DownloadManager 回退单流整文件（**全程不下载整文件**）；
     * - 写入后校验「已写字节 == 预期字节」，不足按失败处理（避免空洞文件 = 损坏）。
     */
    suspend fun downloadChunk(
        taskId: Long,
        url: String,
        start: Long,
        end: Long,
        partFile: File,
        headers: Map<String, String>,
        onBytes: suspend (Long) -> Unit
    ): ChunkResult = withContext(Dispatchers.IO) {
        val attempts = CHUNK_RETRIES + RANGE_RETRIES
        repeat(attempts) { attempt ->
            if (!isActive) throw CancellationException("下载被取消")
            val existing = partFile.length()
            val from = start + existing
            val unknownTotal = end == Long.MAX_VALUE
            val expected = if (unknownTotal) -1L else end - start + 1
            // 分片已完整（含断点续传）：直接成功
            if (!unknownTotal && existing >= expected) return@withContext ChunkResult.OK

            val res = try {
                doChunkAttempt(taskId, url, from, end, unknownTotal, partFile, headers, existing, onBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "downloadChunk: task=$taskId 尝试${attempt + 1} IO异常: ${e.message}")
                if (!isActive) throw CancellationException("下载被取消", e)
                null
            }

            when (res) {
                ChunkResult.OK -> return@withContext ChunkResult.OK
                ChunkResult.FAILED -> return@withContext ChunkResult.FAILED
                ChunkResult.RANGE_IGNORED -> {
                    // ★ 不再在片内指数退避：退避期间占着信号量配额，所有分片周期性集体停顿 = "900KB 忽快忽慢"根源。
                    //   立即返回，由上层释放配额并领新片；只有「持续 RANGE_IGNORED」才由上层回退单流。
                    return@withContext ChunkResult.RANGE_IGNORED
                }
                null -> {
                    if (attempt < attempts - 1) delay((500L * (attempt + 1)).coerceAtMost(3000))
                }
            }
        }
        ChunkResult.FAILED
    }

    /** 单次分片请求（不重试）：成功/忽略Range/失败 三态；IO 异常向外抛出 */
    private suspend fun doChunkAttempt(
        taskId: Long,
        url: String,
        from: Long,
        end: Long,
        unknownTotal: Boolean,
        partFile: File,
        headers: Map<String, String>,
        existing: Long,
        onBytes: suspend (Long) -> Unit
    ): ChunkResult {
        val request = Request.Builder()
            .url(url)
            .header("Range", if (unknownTotal) "bytes=$from-" else "bytes=$from-$end")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get().build()

        val call = client.newCall(request)
        activeCalls.getOrPut(taskId) { newCallSet() }.add(call)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            return call.execute().use { response ->
                // 防盗链/广告回退页：直接判失败
                if (response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) {
                    Log.w(TAG, "downloadChunk: task=$taskId 返回 text/html（疑似广告/错误页），终止")
                    return@use ChunkResult.FAILED
                }
                when (val code = response.code) {
                    206 -> {
                        val requestedEnd = if (unknownTotal) null else end
                        if (!HttpRangePolicy.matches(response.header("Content-Range"), from, requestedEnd)) {
                            Log.w(TAG, "downloadChunk: task=$taskId Content-Range 与请求不一致")
                            return@use ChunkResult.FAILED
                        }
                        val body = response.body ?: return@use ChunkResult.FAILED
                        val expected = if (unknownTotal) -1L else end - from + 1
                        // 写入分片，严格截断到预期区间
                        val written = writeSlice(body.byteStream(), partFile, existing, expected, onBytes)
                        // ★ 校验：206 也必须写满预期字节，否则视为失败（防空洞/损坏）
                        if (!unknownTotal && written != expected) {
                            Log.w(TAG, "downloadChunk: task=$taskId 分片写入不足 written=$written 预期=$expected")
                            return@use ChunkResult.FAILED
                        }
                        ChunkResult.OK
                    }
                    200 -> {
                        Log.w(TAG, "downloadChunk: task=$taskId Range 请求返回 200，拒绝按分片写入")
                        ChunkResult.RANGE_IGNORED
                    }
                    else -> {
                        Log.w(TAG, "downloadChunk: task=$taskId 非预期状态码 $code")
                        ChunkResult.FAILED
                    }
                }
            }
        } finally {
            activeCalls[taskId]?.remove(call)
            cancelHandle?.dispose()
        }
    }

    /** 把响应流写入 partFile（seek 到 existing），截断到 expected 字节；返回实际写入字节数 */
    private suspend fun writeSlice(
        input: java.io.InputStream,
        partFile: File,
        existing: Long,
        expected: Long,
        onBytes: suspend (Long) -> Unit
    ): Long {
        var written = 0L
        RandomAccessFile(partFile, "rw").use { raf ->
            raf.seek(existing)
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                // 服务器可能忽略 end 返回超量 body：严格截断，避免文件膨胀
                val allow = if (expected < 0) read.toLong() else min(read.toLong(), expected - written)
                if (allow <= 0) break
                raf.write(buffer, 0, allow.toInt())
                written += allow
                onBytes(allow)
                if (expected >= 0 && written >= expected) break
            }
        }
        return written
    }

    // ---------- 单流整文件（回退） ----------

    suspend fun downloadFull(
        taskId: Long,
        url: String,
        partFile: File,
        headers: Map<String, String>,
        /** 已知总大小（字节）；-1/0 = 未知，不截断（流式场景） */
        total: Long = -1L,
        onBytes: suspend (Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // 完整 GET 的响应从字节 0 开始，必须丢弃任何旧前缀，禁止“旧前缀 + 完整响应”拼接损坏。
        val existing = 0L
        if (partFile.exists()) RandomAccessFile(partFile, "rw").use { it.setLength(0) }
        Log.d(TAG, "downloadFull: task=$taskId 完整下载 origin=${LogRedactor.url(url)} total=$total")
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get().build()
        val call = client.newCall(request)
        activeCalls.getOrPut(taskId) { newCallSet() }.add(call)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                // ★ 最终响应若是 HTML（防盗链/过期/错误页），直接失败，绝不存盘
                if (response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) {
                    Log.w(TAG, "downloadFull: task=$taskId 返回 text/html（疑似过期/防盗链/错误页），终止")
                    throw IllegalStateException("下载失败：链接已失效或需要 Referer（返回 HTML 页）")
                }
                if (!response.isSuccessful) throw IllegalStateException("下载失败 HTTP ${response.code}")
                val body = response.body ?: return@use false
                // 已知总大小时写时硬截断：服务器多给/Content-Range 偏差的字节直接丢弃，文件永不膨胀
                val expected = if (total > 0) (total - existing).coerceAtLeast(0) else -1L
                var written = 0L
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(existing)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            val allow = if (expected < 0) read.toLong()
                            else min(read.toLong(), expected - written)
                            if (allow <= 0) break
                            raf.write(buffer, 0, allow.toInt())
                            written += allow
                            onBytes(allow)
                            if (expected >= 0 && written >= expected) break
                        }
                    }
                }
                // 已知总大小：落盘必须恰好达到 total，否则视为失败（防空洞/截断损坏）
                if (total > 0 && existing + written < total) {
                    Log.w(TAG, "downloadFull: task=$taskId 写入不足 written=${existing + written} 预期=$total")
                    return@use false
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "downloadFull: task=$taskId IO异常: ${e.message}")
            if (!isActive) throw CancellationException("下载被取消", e)
            false
        } finally {
            activeCalls[taskId]?.remove(call)
            cancelHandle?.dispose()
        }
    }

    /**
     * 流式合并分片到输出流（**边写边删**）。
     *
     * 旧实现先把分片合并成私有缓存里的完整副本、再由 DownloadSaver 复制到公共目录，
     * 峰值占用 3 份（分片 + 合并副本 + 公共副本），6GB 级文件在预留 2.4 倍空间的设备上仍 ENOSPC。
     * 现在直接写向最终目标：分片与目标同处一个存储卷，每片写完立即删除，
     * 峰值占用 ≈ 文件本身大小 + 一个分片。
     *
     * 代价（有意取舍）：合并中途失败（含用户暂停）时已写出的分片已被删除，
     * 下次恢复按磁盘真实长度重算进度并重下这部分，不会出现区间错位。
     *
     * @return 实际写入的总字节数
     */
    suspend fun mergeChunksToStream(chunkFiles: List<File>, out: OutputStream): Long =
        withContext(Dispatchers.IO) {
            var total = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            chunkFiles.forEach { part ->
                FileInputStream(part).use { fis ->
                    while (true) {
                        // 阻塞式写入不响应协程取消，逐块自检：暂停/删除后最多再写一个缓冲块就退出，
                        // 避免"点停止/删除没反应"（幽灵任务）
                        if (!isActive) throw CancellationException("下载被取消")
                        val read = fis.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        total += read
                    }
                }
                // 该片已完整写入目标，立即释放分片空间（在 use 之后，确保 fd 已关闭）
                if (!part.delete()) Log.w(TAG, "mergeChunksToStream: 删除分片失败 $part")
            }
            out.flush()
            Log.d(TAG, "mergeChunksToStream: parts=${chunkFiles.size} bytes=$total")
            total
        }
}
