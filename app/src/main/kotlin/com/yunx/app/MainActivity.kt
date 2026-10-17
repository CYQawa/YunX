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

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.yunx.app.crash.CrashHandler
import com.yunx.app.data.download.MagnetLink
import com.yunx.app.ui.MainScreen
import com.yunx.app.ui.navigation.MainTab
import com.yunx.app.ui.screens.SafetyNoticeDialog
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.util.ArchiveProbe
import com.yunx.app.util.DeepLinks

class MainActivity : ComponentActivity() {

    /**
     * 外部来源（实况通知点击 / 深链接）请求直接打开的 Tab；null = 不指定，落在默认「解析」页。
     * 用 mutableStateOf 而不是 rememberSaveable：Compose 读它会自动重组，onNewIntent 里改它即可切页。
     */
    private val pendingTab = mutableStateOf<MainTab?>(null)

    /** 深链接拿到、等待解析页接手的文本（分享链接 / 中文口令 / 带链接的文案） */
    private val pendingLink = mutableStateOf<String?>(null)

    /**
     * 深链接拿到、等待下载页接手的直链或磁力。
     * 磁力不走解析链路（它没有分享页，只有 Gopeed 引擎能下），所以落成下载页的「添加下载任务」弹窗预填。
     */
    private val pendingAddUrl = mutableStateOf<String?>(null)

    // ★ 通知权限不再在启动时申请/引导：统一收到引导页第 3 页（见 ui/screens/OnboardingPermissionPage.kt），
    //   之后只有设置页里的手动入口（「通知栏下载进度」）会再申请，避免每次启动都弹窗打扰。
    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用内主题设置：始终深色/浅色时提前切换窗口主题，避免冷启动闪错背景色
        // （values-night 只跟随系统；应用内「始终深色」但系统浅色时，需显式使用深色窗口主题）
        val darkModePref = getSharedPreferences("yunx_settings", android.content.Context.MODE_PRIVATE)
            .getInt("dark_mode", 0)
        when (darkModePref) {
            1 -> setTheme(R.style.Theme_ComposeEmptyActivity_Light)
            2 -> setTheme(R.style.Theme_ComposeEmptyActivity_Dark)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching {
            val hits = ArchiveProbe.fast(this).toMutableList()
            if (entryMismatch(this)) hits.add(4)
            if (hits.isNotEmpty()) {
                CrashHandler.terminate(hits.joinToString(","))
            }
        }
        // ★ 只在「真正新建」时处理外部 intent：旋转/低内存重建时 getIntent() 仍会返回同一个深链接，
        //   不判 savedInstanceState 会把同一个链接又解析一遍。
        if (savedInstanceState == null) {
            // 实况通知点击进来：直接把初始 Tab 落成「下载」页
            pendingTab.value = tabFromIntent(intent)
            handleDeepLink(intent)
        }
        setContent {
            ComposeEmptyActivityTheme {
                MainScreen(
                    openTab = pendingTab.value,
                    onOpenTabConsumed = { pendingTab.value = null },
                    incomingLink = pendingLink.value,
                    onIncomingLinkConsumed = { pendingLink.value = null },
                    incomingAddUrl = pendingAddUrl.value,
                    onIncomingAddUrlConsumed = { pendingAddUrl.value = null }
                )
                SafetyNoticeDialog()
            }
        }
    }

    /**
     * 应用已在前台时又来了外部 intent：singletask + SINGLE_TOP 让系统把 intent 送到这里
     * （而不是再起一个实例），更新 pending* 后由 MainScreen / 各页消费。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabFromIntent(intent)?.let { pendingTab.value = it }
        handleDeepLink(intent)
    }

    /** 实况通知（EXTRA_OPEN_TAB=download）→ 下载 Tab；其余情况不指定 Tab */
    private fun tabFromIntent(intent: Intent?): MainTab? =
        if (intent?.getStringExtra(EXTRA_OPEN_TAB) == TAB_DOWNLOAD) MainTab.Download else null

    /**
     * 深链接 / 分享 / 选中文本进来：取出文本并决定去哪一页。
     * 取不到（启动器图标、内部跳转等）就什么都不做，保持原有行为。
     */
    private fun handleDeepLink(intent: Intent?) {
        val text = DeepLinks.textFrom(
            action = intent?.action,
            data = intent?.dataString,
            extraText = extraText(intent)
        ) ?: return
        if (MagnetLink.isMagnet(text)) {
            pendingAddUrl.value = text
            pendingTab.value = MainTab.Download
        } else {
            pendingLink.value = text
            pendingTab.value = MainTab.Resolve
        }
    }

    /**
     * 文本类 action 带的文本：ACTION_SEND 在 `EXTRA_TEXT` 里，ACTION_PROCESS_TEXT 在 `EXTRA_PROCESS_TEXT` 里。
     * 选中文本解析不返回处理结果（我们是跳去解析页，不是原地改写选中内容），所以不带 result 回去。
     */
    private fun extraText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    companion object {
        /** 「点击后打开哪个 Tab」的 intent extra 键（由 DownloadService 的通知 contentIntent 写入） */
        const val EXTRA_OPEN_TAB = "com.yunx.app.extra.OPEN_TAB"

        /** [EXTRA_OPEN_TAB] 的取值：打开「下载」Tab */
        const val TAB_DOWNLOAD = "download"
    }
}
