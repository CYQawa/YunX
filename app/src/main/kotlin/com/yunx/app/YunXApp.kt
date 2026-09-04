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
    }
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