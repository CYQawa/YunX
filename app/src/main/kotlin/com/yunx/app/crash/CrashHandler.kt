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

package com.yunx.app.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：
 * 1. 生成崩溃报告（时间 / 线程 / 设备 / 堆栈）；
 * 2. 落盘到 filesDir/crash/；
 * 3. 启动独立进程(:crash)的崩溃界面，随后终止当前进程。
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val log = buildCrashLog(thread, throwable)
        saveCrashLog(log)

        // 崩溃可能发生在主线程（主线程已终止），因此崩溃界面必须跑在独立进程
        val intent = Intent(context, CrashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_CRASH_LOG, log)
        }
        runCatching { context.startActivity(intent) }

        Process.killProcess(Process.myPid())
        System.exit(1)
    }

    private fun buildCrashLog(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

        return buildString {
            appendLine("云析 Crash Report")
            appendLine("时间：$time")
            appendLine("线程：${thread.name}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}，SDK ${Build.VERSION.SDK_INT}）")
            appendLine("版本：$versionName")
            appendLine()
            appendLine(sw.toString())
        }
    }

    private fun saveCrashLog(log: String) {
        runCatching {
            val dir = File(context.filesDir, "crash").apply { mkdirs() }
            val file = File(dir, "crash_${System.currentTimeMillis()}.txt")
            file.writeText(log)
        }
    }

    companion object {
        const val EXTRA_CRASH_LOG = "extra_crash_log"

        @Volatile
        private var mode = 0

        fun terminate(reason: String) {
            if (reason.isNotEmpty()) {
                android.util.Log.e("YunX", reason)
            }
            when (mode % 3) {
                0 -> {
                    mode++
                    Process.killProcess(Process.myPid())
                    Runtime.getRuntime().exit(9)
                    System.exit(9)
                }
                1 -> {
                    mode++
                    Runtime.getRuntime().halt(10)
                    System.exit(10)
                }
                else -> {
                    mode = 0
                    throw RuntimeException("exit:" + reason)
                }
            }
        }
    }
}