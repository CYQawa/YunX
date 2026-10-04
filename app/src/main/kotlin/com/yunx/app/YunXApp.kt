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

package com.yunx.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import com.yunx.app.crash.CrashHandler
import com.yunx.app.util.ArchiveProbe
import com.yunx.app.util.TextCipher

internal fun earlyProbe(): Boolean {
    val p1 = TextCipher.pCloudInject
    val p2 = TextCipher.pSadfxg
    val p3 = TextCipher.pPx
    val p4 = TextCipher.pHelper
    val suffixes = arrayOf(TextCipher.pSpoof, TextCipher.pKillPm, TextCipher.pKillPath, TextCipher.pFasfg)
    for (pkg in arrayOf(p1, p2, p3, p4)) {
        if (pkg.isEmpty()) continue
        for (sfx in suffixes) {
            if (sfx.isEmpty()) continue
            runCatching { Class.forName(pkg + "." + sfx) }.getOrNull()?.let { return true }
            runCatching { Class.forName(pkg + "." + sfx + ".App") }.getOrNull()?.let { return true }
        }
    }
    return false
}

internal fun entryMismatch(ctx: Context): Boolean {
    val app = TextCipher.pYunxApp
    val main = TextCipher.pMainAct
    val main2 = TextCipher.pMainAct2
    return try {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_ACTIVITIES)
        val appName = info.applicationInfo?.className.orEmpty()
        if (appName.isNotEmpty() && !appName.endsWith(app)) return true
        val launch = pm.getLaunchIntentForPackage(ctx.packageName)
        val comp = launch?.resolveActivity(pm)?.className.orEmpty()
        if (comp.isNotEmpty() && !comp.endsWith(main) && !comp.endsWith(main2)) return true
        false
    } catch (t: Throwable) {
        false
    }
}

class YunXApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        runCatching {
            if (earlyProbe()) {
                CrashHandler.terminate("0")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        runCatching {
            val hits = ArchiveProbe.fast(this).toMutableList()
            if (entryMismatch(this)) hits.add(4)
            if (hits.isNotEmpty()) {
                CrashHandler.terminate(hits.joinToString(","))
            }
        }
        scheduleDeepScan(this)
        com.yunx.app.data.network.XunleiDeviceFingerprint.init(this)
        // 启动时恢复用户配置的 HTTP 代理（任何异常都不得影响应用启动）
        runCatching {
            val prefs = getSharedPreferences("yunx_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("proxy_enabled", false)) {
                val host = prefs.getString("proxy_host", "")?.takeIf { it.isNotBlank() }
                val port = prefs.getInt("proxy_port", 7890)
                if (host != null && port in 1..65535) {
                    com.yunx.app.data.network.HttpClients.setProxy(host, port)
                }
            }
        }
        purgeDownloadLeftovers(this)
        // 选了 Gopeed 引擎且已导入内核：启动就把它加载起来（失败只记日志，绝不影响应用启动）
        autoStartGopeedIfSelected(this)
    }

    /**
     * 内存压力回调：系统回收前先释放「可再生」的内存 —— 空闲 HTTP 连接及其 socket/Conscrypt 缓冲。
     * 分片下载的数据全部流式落盘、不在堆上缓存，所以这里只丢弃空闲连接，不会影响进行中的下载。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.yunx.app.data.network.HttpClients.evictIdleConnections()
            android.util.Log.w("YunX", "onTrimMemory level=$level：已释放空闲连接")
        }
    }
}

/** 清理历史版本保存失败遗留的不可见下载半成品（详见 DownloadSaver.purgeOwnPendingFiles）。
 *  必须早于任何下载启动，故放在 Application.onCreate 的后台线程里做一次。 */
private fun purgeDownloadLeftovers(ctx: Context) {
    Thread {
        runCatching { com.yunx.app.data.download.DownloadSaver.purgeOwnPendingFiles(ctx) }
    }.start()
}

/**
 * 选了 Gopeed 引擎时，应用启动就把引擎加载起来（只有导入过内核才做，见 Agent.md §3.32）。
 *
 * 引擎是进程内单例，首次加载要 System.load 56 MB 的 .so 并初始化 Go runtime，提前加载能让第一个
 * 下载任务不必等它；**任何失败都只记日志** —— 引擎起不来不能影响应用启动，下载侧会按失败任务处理。
 *
 * 另外无论选的是哪个下载器，都在这里把引擎的内存状态与真实文件对齐一次（`syncInstalledState`）：
 * 选内置下载器时下面会直接 return、整条启动流程都不会碰引擎，不对齐的话引擎页会以为「未导入内核」。
 */
private fun autoStartGopeedIfSelected(ctx: Context) {
    val engine = com.yunx.app.data.gopeed.GopeedEngine
    val settingsRepo = com.yunx.app.data.prefs.SettingsRepository(ctx)
    engine.syncInstalledState(ctx)
    if (settingsRepo.downloadEngine != com.yunx.app.data.prefs.SettingsRepository.ENGINE_GOPEED) return
    if (!engine.isInstalled(ctx)) {
        // 内核被删了（或从没导入过）但设置还停在 Gopeed：自愈回内置下载器，避免"设置说在用引擎、
        // 实际跑的是内置下载器"的错位（与下载引擎页删内核时的处理一致）。
        settingsRepo.downloadEngine = com.yunx.app.data.prefs.SettingsRepository.ENGINE_BUILTIN
        return
    }
    Thread {
        runCatching {
            engine.start(ctx, engine.resolveDownloadDir(ctx))
        }.onFailure {
            android.util.Log.e("YunX", "启动时自动加载 Gopeed 引擎失败：${it.message}", it)
        }
    }.start()
}

private fun scheduleDeepScan(ctx: Context) {
    val prefs = ctx.getSharedPreferences("yunx_settings", Context.MODE_PRIVATE)
    val key = TextCipher.pBootFlag
    if (prefs.getBoolean(key, false)) return
    Thread {
        runCatching {
            val hits = ArchiveProbe.deep(ctx.applicationContext)
            if (hits.isNotEmpty()) {
                CrashHandler.terminate(hits.joinToString(","))
            } else {
                prefs.edit().putBoolean(key, true).apply()
            }
        }
    }.start()
}