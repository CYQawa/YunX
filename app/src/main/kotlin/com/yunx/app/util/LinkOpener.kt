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

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.yunx.app.data.prefs.LinkOpenTarget
import com.yunx.app.data.prefs.SettingsRepository

/**
 * 深链接注册项的启停。
 *
 * 清单里的 `intent-filter` 运行时改不了，所以每种链接挂一个 activity-alias，
 * 用户在设置里关掉某个平台 = 把对应 alias 置为 DISABLED，系统就不会再把那种链接派给云析
 * （与桌面动态图标同一套机制，见 `ThemeScreen`）。
 *
 * ★ 用 `DONT_KILL_APP`：改组件状态不该把用户正在跑的下载杀掉。
 * ★ 组件类名对不上（清单与 [LinkOpenTarget.alias] 不一致）时只会记日志，不影响其它项。
 */
object LinkOpener {

    private const val TAG = "YunX-Link"

    /** 用户改开关后立刻生效 */
    fun apply(context: Context, target: LinkOpenTarget, enabled: Boolean) {
        setComponent(context, target, enabled)
    }

    /**
     * 把设置里的意愿全部刷到组件状态。
     *
     * 应用启动时调用一次：安装后第一批组件状态还没写过（是清单里的默认值），
     * 以及用户改过开关又重装/清数据时，都靠它对齐。
     */
    fun syncAll(context: Context) {
        val settings = SettingsRepository(context)
        LinkOpenTarget.entries.forEach { target ->
            setComponent(context, target, settings.linkOpen(target))
        }
    }

    private fun setComponent(context: Context, target: LinkOpenTarget, enabled: Boolean) {
        runCatching {
            val pm = context.packageManager
            val desire = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val name = ComponentName(context, target.alias)
            // 已经是目标状态就不再写：每次写都让 PackageManager 落盘并重算链接归属，没必要
            if (pm.getComponentEnabledSetting(name) == desire) return
            pm.setComponentEnabledSetting(name, desire, PackageManager.DONT_KILL_APP)
        }.onFailure {
            Log.w(TAG, "设置链接唤起组件失败：${target.alias} enabled=$enabled（${it.message}）")
        }
    }
}
