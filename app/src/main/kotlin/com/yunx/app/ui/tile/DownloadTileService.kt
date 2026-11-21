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

package com.yunx.app.ui.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.yunx.app.MainActivity
import com.yunx.app.data.download.MagnetLink
import com.yunx.app.data.network.C139KoulingParser
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.data.network.GitHubLinkType
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.XunleiKouling
import com.yunx.app.data.network.video.VideoLinkParser

/**
 * 磁贴点击交互后的目标路由。
 */
sealed interface TileTarget {
    /** 直链 / 磁力：直接落「下载」页并预填添加下载任务弹窗 */
    data class AddDownload(val url: String) : TileTarget

    /** 网盘 / 视频 / GitHub 仓库 / 口令：交给解析页开始解析 */
    data class Resolve(val text: String) : TileTarget

    /** 剪贴板无有效链接：落「下载」页并弹出空的添加任务弹窗让用户手动输入 */
    data object EmptyAdd : TileTarget
}

/**
 * 磁贴剪贴板意图路由（纯数据逻辑，便于测试）。
 */
object TileIntentRouter {

    private val urlRegex = Regex("""https?://[^\s\u4e00-\u9fff]+""")

    fun target(text: String?): TileTarget {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return TileTarget.EmptyAdd
        if (MagnetLink.isMagnet(raw)) return TileTarget.AddDownload(raw)

        val url = urlRegex.find(raw)?.value
            ?.trimEnd('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'', '、', '！', '!', '？', '?', '…', '.')

        if (url != null) {
            val gh = GitHubLinkParser.parse(url)
            if (gh is GitHubLinkType.DirectFile) {
                return TileTarget.AddDownload(url)
            }
            if (gh != null) {
                return TileTarget.Resolve(raw)
            }
            if (ShareLinkParser.parse(url) != null || VideoLinkParser.looksLikeVideoLink(url)) {
                return TileTarget.Resolve(raw)
            }
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                return TileTarget.AddDownload(url)
            }
        }

        if (C139KoulingParser.parse(raw) != null || XunleiKouling.looksLikeKouling(raw)) {
            return TileTarget.Resolve(raw)
        }

        return TileTarget.EmptyAdd
    }
}

/**
 * 控制中心快捷下载磁贴。
 *
 * 点击后唤起悬浮窗（FloatResolveActivity）并收起控制中心面板；
 * 悬浮窗独立运行，不进入 App 完整主界面，在悬浮窗内完成剪贴板识别、解析、详情查看及后台下载。
 */
class DownloadTileService : TileService() {

    override fun onClick() {
        val intent = Intent().apply {
            setClassName(this@DownloadTileService, "com.yunx.app.ui.resolve.FloatResolveActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { launchAndCollapse(intent) }.isSuccess
        if (!launched) {
            runCatching { startActivity(intent) }
        }
    }

    private fun launchAndCollapse(intent: Intent): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
            true
        } else {
            startActivityAndCollapse(intent)
            true
        }
    }
}
