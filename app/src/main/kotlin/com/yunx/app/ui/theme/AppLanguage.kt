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

package com.yunx.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunx.app.R
import com.yunx.app.data.prefs.SettingsRepository
import java.util.Locale

/**
 * 应用内可选语言。[tag] 为 BCP47 语言标签，空串表示「跟随系统」。
 *
 * ★ 语言自身的名称（简体中文 / 繁體中文 / English / 日本語 / Français）在任何语言下都显示原文，
 *   这样用户即使误切到看不懂的语言，也能在列表里找回自己的语言。
 */
enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    System("", R.string.language_follow_system),
    SimplifiedChinese("zh-Hans", R.string.language_zh_hans),
    TraditionalChinese("zh-Hant", R.string.language_zh_hant),
    English("en", R.string.language_en),
    Japanese("ja", R.string.language_ja),
    French("fr", R.string.language_fr);

    companion object {
        fun fromTag(tag: String): AppLanguage = values().firstOrNull { it.tag == tag } ?: System
    }
}

/**
 * 应用内语言控制器（单例）：
 * - 内存中持有当前语言（mutableStateOf），改完 Compose 自动重组；
 * - 持久化到 SharedPreferences；
 * - [wrap] 按语言包装 Context，Android 的字符串资源会在新 Configuration 下解析到对应 `values-*`。
 *
 * ★ 没有用 AppCompatDelegate.setApplicationLocales：本项目的 Activity 继承自 ComponentActivity
 *   （非 AppCompatActivity），也没有引入 AppCompat，故直接用 createConfigurationContext 手动生效。
 */
object LocaleController {

    /** 当前语言（跟随系统时为 [AppLanguage.System]） */
    var language by mutableStateOf(AppLanguage.System)
        private set

    /** 从持久化存储加载（幂等；由 ComposeEmptyActivityTheme 调用） */
    fun init(context: Context) {
        language = AppLanguage.fromTag(SettingsRepository(context).appLanguage)
    }

    /**
     * 设置语言并持久化。
     * ★ 只改状态与存储，**不会**让已经渲染的界面换语言——调用方需要随后重建 Activity
     *   （`(context as? Activity)?.recreate()`），让 [wrap] 在新的 Configuration 下重新解析资源。
     */
    fun setLanguage(context: Context, value: AppLanguage) {
        language = value
        SettingsRepository(context).appLanguage = value.tag
    }

    /**
     * 按已保存语言包装 Context（供 `Activity.attachBaseContext` 使用）。
     * 「跟随系统」或读取失败时原样返回，此时行为与改动前完全一致。
     *
     * ★ 这里直接读 SharedPreferences 而不依赖 [init]：attachBaseContext 发生在
     *   Application / Compose 主题初始化之前，那时 [language] 还是默认值。
     */
    fun wrap(base: Context): Context {
        val tag = runCatching {
            base.getSharedPreferences("yunx_settings", Context.MODE_PRIVATE)
                .getString("app_language", "")
        }.getOrNull().orEmpty()
        if (tag.isEmpty()) return base
        val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull() ?: return base
        if (locale.language.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
