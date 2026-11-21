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

import android.content.Context
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.prefs.SettingsRepository

/**
 * 进程内单例 DownloadManager 持有者。
 *
 * 保证主界面（MainScreen）与控制中心快捷悬浮窗（FloatResolveActivity）
 * 共享同一个下载调度池、同一套网络并发限制以及相同的前台服务引用计数，
 * 避免各自多实例竞争 Room 数据库或协程错乱。
 */
object DownloadManagerHolder {

    @Volatile
    private var instance: DownloadManager? = null

    fun get(context: Context): DownloadManager {
        return instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    private fun create(appContext: Context): DownloadManager {
        val db = AppDatabase.get(appContext)
        val settings = SettingsRepository(appContext)
        return DownloadManager(
            context = appContext,
            dao = db.downloadTaskDao(),
            downloader = ChunkDownloader({ HttpClients.downloadClient() }),
            threadProvider = { platform -> settings.downloadThreadsFor(platform) },
            saveDirProvider = { settings.downloadDirUri },
            concurrencyProvider = { settings.maxConcurrentDownloads },
            speedLimitProvider = { settings.downloadSpeedLimit },
            retryCountProvider = { settings.downloadRetryCount },
            keepWhenLockedProvider = { settings.keepDownloadWhenLocked },
            showSpeedProvider = { settings.notificationShowSpeed }
        )
    }
}
