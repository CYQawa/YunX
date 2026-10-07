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
import com.yunx.app.ui.MainScreen
import com.yunx.app.ui.navigation.MainTab
import com.yunx.app.ui.screens.SafetyNoticeDialog
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.util.ArchiveProbe

class MainActivity : ComponentActivity() {

    /**
     * 外部来源（目前是实况通知点击）请求直接打开的 Tab；null = 不指定，落在默认「解析」页。
     * 用 mutableStateOf 而不是 rememberSaveable：Compose 读它会自动重组，onNewIntent 里改它即可切页。
     */
    private val pendingTab = mutableStateOf<MainTab?>(null)

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
        // 实况通知点击进来：直接把初始 Tab 落成「下载」页
        pendingTab.value = tabFromIntent(intent)
        setContent {
            ComposeEmptyActivityTheme {
                MainScreen(
                    openTab = pendingTab.value,
                    onOpenTabConsumed = { pendingTab.value = null }
                )
                SafetyNoticeDialog()
            }
        }
    }

    /**
     * 应用已在前台时再点实况通知：SINGLE_TOP 让系统把 intent 送到这里（而不是再起一个实例），
     * 更新 pendingTab 后由 MainScreen 消费并切到「下载」Tab。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTab.value = tabFromIntent(intent)
    }

    /** 实况通知（EXTRA_OPEN_TAB=download）→ 下载 Tab；其余情况不指定 Tab */
    private fun tabFromIntent(intent: Intent?): MainTab? =
        if (intent?.getStringExtra(EXTRA_OPEN_TAB) == TAB_DOWNLOAD) MainTab.Download else null

    companion object {
        /** 「点击后打开哪个 Tab」的 intent extra 键（由 DownloadService 的通知 contentIntent 写入） */
        const val EXTRA_OPEN_TAB = "com.yunx.app.extra.OPEN_TAB"

        /** [EXTRA_OPEN_TAB] 的取值：打开「下载」Tab */
        const val TAB_DOWNLOAD = "download"
    }
}
