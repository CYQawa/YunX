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

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 日志导出工具：
 * 1. 头部写入应用 / 设备信息（uid、pid 只作为**内容字段**，不参与文件命名与筛选）；
 * 2. `logcat -d -v time` —— **不加任何过滤**，dump 运行日志；
 * 3. 合并写入 cacheDir/logs/ 下文本文件，通过 FileProvider + 系统分享导出。
 *
 * ★ 别给 logcat 加过滤条件（前后改过两次，别再来第三次）：
 *   - `--pid=${Process.myPid()}` 只能捞到**当前这一次**进程的日志：用户复现闪退后重开 App 再导出，
 *     最该看的那段崩溃日志正好被过滤掉了（旧日志「找不到 / 导不全」的根因）；
 *   - `--uid=<uid>` 在 vivo / Android 10 这类老 logcat 上**根本不认**：它先吐 `Unrecognized Option`
 *     + 整段 `Usage: logcat`，被 `redirectErrorStream(true)` 混进日志流，导出文件里就只剩 usage 文本。
 *   不加过滤时 logd 本来就按 uid 隔离（应用只读得到自己的日志），拿到的正好是本应用**所有进程**
 *   （含闪退那一次）的日志——最全也最简单。
 *   文件名 `yunx_log_<yyyyMMdd_HHmmss>.txt` 只按时间命名，与进程无关。
 */
object LogExporter {

    private const val EXPORT_DIR = "logs"

    /** 单缓冲区最多保留的行数（防止超大 buffer 导致内存/文件过大） */
    private const val MAX_LINES = 30000

    /** 生成日志文件（cacheDir 内）并返回；失败返回 null（不抛异常） */
    fun export(context: Context): File? = runCatching {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val out = File(dir, "yunx_log_${timestamp()}.txt")
        FileOutputStream(out).use { exportTo(context, it) }
        out
    }.getOrNull()

    /**
     * 直接保存日志到公共「下载」目录；成功返回 true。
     * - Android 10+（API 29+）：MediaStore.Downloads 直写，无需任何权限；
     * - Android 9-（API 21-28）：写公共 Download 目录（需 WRITE_EXTERNAL_STORAGE）。
     * 不经过 FileProvider / 跨进程分享，彻底规避「保存到下载」时系统 UI 读取 uri 被拒的问题。
     */
    fun saveToDownloads(context: Context): Boolean = runCatching {
        val fileName = "yunx_log_${timestamp()}.txt"
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri: Uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                exportTo(context, out)
            } ?: false
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { out -> exportTo(context, out) }
        }
        ok
    }.getOrDefault(false)

    /** 把头部信息 + logcat 运行/崩溃日志写入指定输出流 */
    private fun exportTo(context: Context, output: OutputStream): Boolean = runCatching {
        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            // ---------- 头部：应用与设备信息 ----------
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            writer.write("云析（YunX）日志导出\n")
            writer.write("导出时间：${now()}\n")
            writer.write("应用版本：${pkg.versionName}（${pkg.versionCode}）\n")
            writer.write("设备：${Build.MANUFACTURER} ${Build.MODEL}\n")
            writer.write("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）\n")
            writer.write("诊断模式：${if (DiagnosticLog.isEnabled()) "已开启" else "关闭"}\n")
            writer.write("本应用 uid=${Process.myUid()}，本次 pid=${Process.myPid()}\n")
            writer.write("\n")

            exportRuntimeLog(writer)
        }
        true
    }.getOrDefault(false)

    /**
     * 运行日志：**直接 `logcat -d -v time`，不加任何过滤**。
     *
     * 不加过滤拿到的就是本应用**所有进程**（含闪退那一次）的日志：logd 按 uid 隔离，应用只能读到
     * 自己 uid 的日志。所以既不用 `--pid`（会丢掉历史进程），也不用 `--uid`（老 logcat 不认，
     * 反而会把 Usage 文本写进导出文件）。
     */
    private fun exportRuntimeLog(writer: OutputStreamWriter) {
        writer.write("========== 运行日志（logcat -d -v time）==========\n")
        val lines = query(listOf("logcat", "-d", "-v", "time"))
        if (lines == null) {
            writer.write("（读取日志失败：本机 logcat 不可用）\n")
            return
        }
        writeLogLines(writer, lines)
    }

    /** 清空 logcat 缓冲（便于复现后只导出本次操作日志） */
    fun clearLogcat(): Boolean = runCatching {
        ProcessBuilder("logcat", "-c").start().waitFor()
        true
    }.getOrDefault(false)

    /**
     * 打包全部诊断日志（`yunx_diagnostic_logs_yyyyMMdd_HHmmss.zip`）；诊断模式没开或还没写过返回 null。
     *
     * 真正的打包在 [DiagnosticLog.exportZip]（它先 flush 再压，保证最后几行也在包里），
     * 这里只是把「日志导出」这套对外 API 收在同一个对象里，调用方不用同时认识两个工具。
     */
    fun exportDiagnosticZip(context: Context): File? = DiagnosticLog.exportZip(context)

    /** 执行 logcat 命令并返回输出行；起不来或读失败返回 null（调用方写一行提示，不抛异常） */
    private fun query(command: List<String>): List<String>? {
        var process: java.lang.Process? = null
        return try {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            val reader =
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))

            // 环形缓冲：只保留最近 MAX_LINES 行
            val lines = ArrayDeque<String>()
            var line: String? = reader.readLine()
            while (line != null) {
                lines.addLast(line)
                if (lines.size > MAX_LINES) lines.removeFirst()
                line = reader.readLine()
            }
            process.waitFor()
            lines.toList()
        } catch (e: Exception) {
            null
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /** 写入日志行：脱敏 + 丢掉混进来的 NUL（否则整个导出文件会被当成二进制，打开是乱码） */
    private fun writeLogLines(writer: OutputStreamWriter, lines: List<String>) {
        if (lines.isEmpty()) {
            writer.write("（无输出）\n")
            return
        }
        lines.forEach {
            writer.write(LogRedactor.line(it).replace('\u0000', ' '))
            writer.write("\n")
        }
    }

    /** 通过系统分享导出日志文件；成功返回 true（zip 走 application/zip，文本走 text/plain） */
    fun share(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = if (file.name.endsWith(".zip", true)) "application/zip" else "text/plain"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // chooser 外层也带上读权限 flag：部分接收者（文件管理器/系统 UI）通过
        // 自己的 Intent 读取 uri 时需要授权，否则报 Permission Denial
        val chooser = Intent.createChooser(intent, "分享日志").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
        true
    }.getOrElse {
        false
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
