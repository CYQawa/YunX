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

package com.yunx.app.util

import android.content.Context
import android.util.Log
import com.yunx.app.data.prefs.SettingsRepository
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断日志（设置 → 关于云析 → 长按 → 开发调试 → 诊断模式）。
 *
 * 与 [LogExporter] 的区别：那边导的是 **logcat**（系统缓冲，重启/闪退后按 pid 过滤会丢历史），
 * 这边是**应用自己写文件**，落在私有目录、按模块分文件、跨重启保留，专门用来复现疑难问题。
 *
 * 设计要点（改之前先看这几条）：
 * ① 关的时候**一行都不写**（[isEnabled] 先短路再进队列），所以调用点可以随手埋，不用担心开销；
 * ② 写入在**独立守护线程 + 有界队列**里做，主线程只做一次 `queue.offer`，绝不阻塞、不会 ANR；
 *    队列满就丢弃并计数（这是限流的第一道闸门）；
 * ③ 全局限流 [MAX_LINES_PER_SECOND] 行/秒（第二道闸门）：高频进度日志刷不爆磁盘，丢弃量会定期记一笔；
 * ④ 轮转：单文件 2MB、每个模块最多 5 个文件（`xxx.log` + `xxx.1.log`…`xxx.4.log`），总量上限 10MB，
 *    超了删最旧的；**模块各管各的**，一个模块刷爆不会挤掉别的模块的历史；
 * ⑤ 时间戳在写线程里格式化（`SimpleDateFormat` 非线程安全，不放调用方线程）。
 */
object DiagnosticLog {

    // ---------- 模块：与文件名一一对应 ----------
    const val DB = "db"
    const val CRYPTO = "crypto"
    const val DOWNLOAD = "download"
    const val WEBVIEW = "webview"
    const val NETWORK = "network"
    const val OPERATION = "operation"

    private const val TAG = "YunX-Diag"
    private const val DIR_NAME = "diagnostic_logs"

    /** 单文件上限 2MB */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 每个模块最多保留 5 个文件（当前 + 4 份历史） */
    private const val MAX_FILE_COUNT = 5

    /** 目录总大小上限 10MB，超出从最旧的删起 */
    private const val MAX_TOTAL_BYTES = 10L * 1024 * 1024

    /** 队列容量：满了直接丢（宁可丢日志也不能拖住调用方） */
    private const val QUEUE_CAPACITY = 2000

    /** 全局写盘速率上限（行/秒），超出部分丢弃 */
    private const val MAX_LINES_PER_SECOND = 300

    /** 队列空转这么久就 flush 一次（保证崩溃前最后几行已落盘） */
    private const val FLUSH_IDLE_MS = 400L

    private const val LEVEL_INFO = "INFO"
    private const val LEVEL_WARN = "WARN"
    private const val LEVEL_ERROR = "ERROR"

    /** 一条待写日志（时间戳在写线程格式化，调用方只做一次 offer） */
    private class Entry(
        val module: String,
        val time: Long,
        val level: String,
        val line: String
    )

    @Volatile
    private var enabled = false

    @Volatile
    private var dir: File? = null

    private val queue = LinkedBlockingQueue<Entry>(QUEUE_CAPACITY)
    private val dropped = AtomicLong(0)
    private val written = AtomicLong(0)
    private val windowStart = AtomicLong(0)
    private val windowCount = AtomicLong(0)
    private val lastDropReport = AtomicLong(0)
    private val writers = HashMap<String, BufferedWriter>()
    private val sizes = HashMap<String, Long>()
    private val lock = Any()
    private var worker: Thread? = null

    /** 日志文件名可能被外部（导出 zip）遍历，别把格式藏在写线程里 */
    private val timeFormatLock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** 冷启动装配（`YunXApp.onCreate` 调）：读开关、定目录，开着就把写线程拉起来 */
    fun install(context: Context) {
        dir = resolveDir(context)
        enabled = SettingsRepository(context).diagnosticMode
        if (enabled) {
            startWorker()
            event(OPERATION, "diagnostic_session_start", "诊断日志已启用 dir=${dir?.absolutePath}")
        }
        Log.d(TAG, "诊断模式=${if (enabled) "开" else "关"} dir=${dir?.absolutePath}")
    }

    fun isEnabled(): Boolean = enabled

    /**
     * 开关（设置页调用）。开启后**动态生效**（下一行日志就写文件）；
     * 关闭时先补一条「已关闭」再 flush，保证最后几行不丢。
     */
    fun setEnabled(context: Context, value: Boolean) {
        if (value == enabled) return
        if (value) {
            dir = resolveDir(context)
            enabled = true
            startWorker()
            event(OPERATION, "diagnostic_mode_on", "诊断模式已开启 dir=${dir?.absolutePath}")
        } else {
            event(OPERATION, "diagnostic_mode_off", "诊断模式已关闭")
            enabled = false
            flush(2000)
        }
    }

    /** 日志目录（导出 zip 与「诊断日志」入口共用；外部存储不可用时回退私有目录） */
    fun dirOf(context: Context): File = dir ?: resolveDir(context)

    /** 目录里是否已经有诊断日志（决定「导出诊断日志」入口是否可点） */
    fun hasLogs(context: Context): Boolean =
        dirOf(context).listFiles()?.any { it.isFile && it.name.endsWith(".log") } == true

    /** 把队列里的日志写完并 flush（导出前必须调，否则最新几行还在内存里） */
    fun flush(timeoutMs: Long = 2000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        synchronized(lock) {
            writers.values.forEach { runCatching { it.flush() } }
        }
    }

    // ---------- 写入入口 ----------

    /** 通用入口：模块 / 级别 / 事件 + 可选的任务、状态、耗时、大小、错误码、摘要 */
    fun log(
        module: String,
        event: String,
        level: String = LEVEL_INFO,
        taskId: Any? = null,
        status: String? = null,
        costMs: Long? = null,
        size: Long? = null,
        code: Any? = null,
        summary: String? = null
    ) {
        if (!enabled) return
        if (!allow()) return
        val sb = StringBuilder(96)
        sb.append("event=").append(event)
        if (taskId != null) sb.append(" | task=").append(taskId)
        if (!status.isNullOrBlank()) sb.append(" | status=").append(status)
        if (costMs != null) sb.append(" | cost=").append(costMs).append("ms")
        if (size != null) sb.append(" | size=").append(size)
        if (code != null) sb.append(" | code=").append(code)
        if (!summary.isNullOrBlank()) sb.append(" | ").append(summary.replace('\n', ' ').take(600))
        queue.offer(Entry(module, System.currentTimeMillis(), level, sb.toString()))
    }

    /** 简写：INFO 级、无附加字段 */
    fun event(module: String, event: String, summary: String? = null) =
        log(module, event, summary = summary)

    fun warn(module: String, event: String, summary: String? = null) =
        log(module, event, level = LEVEL_WARN, summary = summary)

    fun error(module: String, event: String, code: Any? = null, summary: String? = null) =
        log(module, event, level = LEVEL_ERROR, code = code, summary = summary)

    /** 数据库读写：表名 / 操作 / 耗时 / 影响行数 / 错误 */
    fun db(table: String, op: String, costMs: Long, rows: Int = -1, error: String? = null, summary: String? = null) {
        log(
            DB,
            "db_$op",
            level = if (error != null) LEVEL_ERROR else LEVEL_INFO,
            costMs = costMs,
            size = rows.takeIf { it >= 0 }?.toLong(),
            code = error?.let { "SQL_ERROR" },
            summary = buildString {
                append("table=").append(table)
                append(" | rows=").append(if (rows >= 0) rows else "?")
                if (error != null) append(" | err=").append(error)
                if (!summary.isNullOrBlank()) append(" | ").append(summary)
            }
        )
    }

    /**
     * 包一层数据库调用：自动记「耗时 / 影响行数 / 异常」，异常照原样抛出（调用方行为不变）。
     * `rowsOf` 用来从返回值里取影响行数（Room 的 `@Update`/`@Delete` 返回 Int 时用得上）。
     */
    suspend fun <T> dbOp(table: String, op: String, rowsOf: (T) -> Int = { -1 }, block: suspend () -> T): T {
        if (!enabled) return block()
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            db(table, op, System.currentTimeMillis() - start, rowsOf(result))
            result
        } catch (e: Throwable) {
            db(table, op, System.currentTimeMillis() - start, -1, e.message ?: e.toString())
            throw e
        }
    }

    /** 网络请求：方法 / URL（已脱敏）/ 状态码 / 耗时 / 请求体与响应体摘要 */
    fun network(
        method: String,
        url: String,
        code: Int,
        costMs: Long,
        requestBody: String? = null,
        responseBody: String? = null,
        error: String? = null
    ) {
        log(
            NETWORK,
            if (error != null) "http_error" else "http",
            level = if (error != null || code !in 200..299) LEVEL_WARN else LEVEL_INFO,
            status = if (error != null) "failed" else "http_$code",
            costMs = costMs,
            code = code,
            summary = buildString {
                append(method).append(' ').append(LogRedactor.url(url))
                if (error != null) append(" | err=").append(error)
                // 请求体/响应体只在「解析错误、非 200/206」时记（见 DiagnosticNetworkInterceptor）
                if (!requestBody.isNullOrBlank()) append(" | req=").append(oneLine(requestBody))
                if (!responseBody.isNullOrBlank()) append(" | resp=").append(oneLine(responseBody))
            }
        )
    }

    /** WebView：加载 URL / 页面状态 / JS 桥调用 / 错误，全走这一个入口 */
    fun webview(event: String, url: String? = null, code: Any? = null, summary: String? = null) {
        log(
            WEBVIEW,
            event,
            level = if (code != null) LEVEL_WARN else LEVEL_INFO,
            code = code,
            summary = buildString {
                if (!url.isNullOrBlank()) append("url=").append(LogRedactor.url(url))
                if (!summary.isNullOrBlank()) append(if (url.isNullOrBlank()) "" else " | ").append(summary)
            }
        )
    }

    /** 导出 zip：`yunx_diagnostic_logs_yyyyMMdd_HHmmss.zip`（先 flush，确保最新日志在里面） */
    fun exportZip(context: Context): File? = runCatching {
        flush(3000)
        val src = dirOf(context)
        val files = src.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return@runCatching null
        val outDir = File(context.cacheDir, "logs").apply { mkdirs() }
        val stamp = synchronized(timeFormatLock) { fileTimeFormat.format(Date()) }
        val out = File(outDir, "yunx_diagnostic_logs_$stamp.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
            files.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        Log.d(TAG, "诊断日志已打包：${out.absolutePath}（${files.size} 个文件，${out.length()} 字节）")
        out
    }.onFailure { Log.w(TAG, "打包诊断日志失败：${it.message}", it) }.getOrNull()

    // ---------- 内部实现 ----------

    private fun resolveDir(context: Context): File {
        // 需求指定：优先外部私有目录（用户能直接拿文件管理器取走），拿不到再回退应用私有目录
        val external = runCatching { context.getExternalFilesDir(DIR_NAME) }.getOrNull()
        val target = external ?: File(context.filesDir, DIR_NAME)
        if (!target.isDirectory && !target.mkdirs()) {
            return File(context.filesDir, DIR_NAME).apply { mkdirs() }
        }
        return target
    }

    /** 限流：全局每秒最多 [MAX_LINES_PER_SECOND] 行，超出的丢掉并计数（每 5 秒把丢弃量记一笔） */
    private fun allow(): Boolean {
        val now = System.currentTimeMillis()
        val start = windowStart.get()
        if (now - start >= 1000L && windowStart.compareAndSet(start, now)) {
            val prev = windowCount.getAndSet(0)
            if (prev > 0) reportDropsIfAny(now)
        }
        if (windowCount.incrementAndGet() > MAX_LINES_PER_SECOND) {
            dropped.incrementAndGet()
            return false
        }
        return true
    }

    private fun reportDropsIfAny(now: Long) {
        val last = lastDropReport.get()
        val total = dropped.get()
        if (total > 0 && now - last > 5000L && lastDropReport.compareAndSet(last, now)) {
            queue.offer(
                Entry(OPERATION, now, LEVEL_WARN, "event=diagnostic_rate_limited | 限流丢弃累计=$total 条（>${MAX_LINES_PER_SECOND}/秒）")
            )
        }
    }

    private fun startWorker() {
        if (worker?.isAlive == true) return
        synchronized(lock) {
            if (worker?.isAlive == true) return
            worker = Thread({ runWorker() }, "yunx-diagnostic-log").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    private fun runWorker() {
        while (true) {
            try {
                val entry = queue.poll(FLUSH_IDLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (entry == null) {
                    synchronized(lock) { writers.values.forEach { runCatching { it.flush() } } }
                    continue
                }
                writeLine(entry)
            } catch (t: Throwable) {
                // 写日志本身绝不能把 App 搞崩：记一条 logcat 就算了，等下一轮
                Log.w(TAG, "诊断日志写入失败：${t.message}", t)
            }
        }
    }

    private fun writeLine(entry: Entry) {
        val target = dir ?: return
        if (!target.isDirectory && !target.mkdirs()) return
        val line = "${formatTime(entry.time)} | ${entry.level} | ${entry.module} | pid=${android.os.Process.myPid()} | ${entry.line}"
        synchronized(lock) {
            val current = File(target, "${entry.module}.log")
            // 全部按 Long 计算：sizes 是 Long 表，混进 Int 会直接编译不过
            val writtenBytes: Long = sizes[entry.module] ?: current.length()
            if (writtenBytes + line.length > MAX_FILE_BYTES) rotate(entry.module, target)
            val writer = writers.getOrPut(entry.module) {
                BufferedWriter(OutputStreamWriter(FileOutputStream(File(target, "${entry.module}.log"), true), Charsets.UTF_8))
            }
            writer.write(line)
            writer.newLine()
            sizes[entry.module] = writtenBytes + line.length + 1
            written.incrementAndGet()
        }
    }

    /** 轮转：`xxx.log` → `xxx.1.log` → … → `xxx.4.log`（最旧的被挤掉），再把总量压回 10MB 以内 */
    private fun rotate(module: String, target: File) {
        runCatching { writers.remove(module)?.close() }
        for (i in MAX_FILE_COUNT - 1 downTo 1) {
            val src = if (i == 1) File(target, "$module.log") else File(target, "$module.${i - 1}.log")
            if (!src.isFile) continue
            val dst = File(target, "$module.$i.log")
            runCatching { dst.delete() }
            runCatching { src.renameTo(dst) }
        }
        sizes[module] = 0L
        enforceTotal(target)
    }

    /** 总量兜底：目录超过 10MB 时从「序号最大（最旧）」的文件删起 */
    private fun enforceTotal(target: File) {
        val logs = target.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }.orEmpty()
        var total = logs.sumOf { it.length() }
        if (total <= MAX_TOTAL_BYTES) return
        // 按 lastModified 从旧到新删（文件名排序不可靠：「xxx.log」会排在「xxx.4.log」前面）
        logs.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= MAX_TOTAL_BYTES) return@forEach
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) total -= len
        }
    }

    private fun formatTime(time: Long): String = synchronized(timeFormatLock) { timeFormat.format(Date(time)) }

    private fun oneLine(text: String): String = text.replace('\n', ' ').replace('\r', ' ').take(1500)
}
