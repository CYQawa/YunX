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

package com.yunx.app.data.prefs

import android.content.Context
import com.yunx.app.data.download.DownloadPlatform

/**
 * 应用设置（SharedPreferences 持久化）。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("yunx_settings", Context.MODE_PRIVATE)

    /** 下载线程数（通用/手动添加，分片并发数），默认 32，上限 512（真实并发另见 [MAX_DOWNLOAD_THREADS] 的说明） */
    var downloadThreads: Int
        get() = downloadThreadsFor(DownloadPlatform.GENERIC)
        set(value) = setDownloadThreads(DownloadPlatform.GENERIC, value)

    /** 获取指定平台的下载线程数；迅雷固定 8，其余默认 32、上限 512 */
    fun downloadThreadsFor(platform: String): Int {
        if (platform == DownloadPlatform.XUNLEI) return XUNLEI_DOWNLOAD_THREADS
        return prefs.getInt(prefsKey(platform), DEFAULT_DOWNLOAD_THREADS)
            .coerceIn(1, MAX_DOWNLOAD_THREADS)
    }

    /** 设置指定平台的下载线程数；迅雷不可修改（上限见 [MAX_DOWNLOAD_THREADS]） */
    fun setDownloadThreads(platform: String, value: Int) {
        if (platform == DownloadPlatform.XUNLEI) return
        prefs.edit().putInt(prefsKey(platform), value.coerceIn(1, MAX_DOWNLOAD_THREADS)).apply()
    }

    private fun prefsKey(platform: String): String =
        if (platform.isBlank() || platform == DownloadPlatform.GENERIC) "download_threads"
        else "download_threads_$platform"

    /** 自定义下载保存目录（SAF tree Uri，content://...）；null/空 = 系统默认 Download 目录 */
    var downloadDirUri: String?
        get() = prefs.getString("download_dir_uri", null)
        set(value) {
            prefs.edit().putString("download_dir_uri", value).apply()
        }

    /**
     * Gopeed 引擎的下载目录（**真实文件系统路径**，如 `/storage/emulated/0/Download/YunX`）；
     * 空 = 默认目录（公共 `Download` 根目录，与内置下载器同一口径，见 `StorageDirs`）。
     *
     * 为什么不复用 [downloadDirUri]：引擎是原生核心，写不了 SAF 的 `content://` 目录，只能拿真实路径。
     * 用户在设置里选目录时走 SAF（从 tree Uri 反解真实路径），反解不到才让他手输，见 Agent.md §3.33。
     */
    var engineDownloadDir: String
        get() = prefs.getString("engine_download_dir", "") ?: ""
        set(value) {
            prefs.edit().putString("engine_download_dir", value.trim()).apply()
        }

    /**
     * 最大同时下载任务数（默认 3，超出的任务排队等待）。
     *
     * ★ 这一个值**同时**管两条下载路径，改默认值时两边一起走：
     *   ① 内置分片下载器：`DownloadManager` 的并发闸门按它轮询等空位；
     *   ② Gopeed 引擎：引擎自己的调度器按配置顶层的 `maxRunning` 排队（见 `GopeedEngine`）。
     */
    var maxConcurrentDownloads: Int
        get() = prefs.getInt("max_concurrent_downloads", DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        set(value) {
            prefs.edit().putInt("max_concurrent_downloads", value.coerceIn(1, 10)).apply()
        }

    /** 下载速度限制（字节/秒；0 = 不限速） */
    var downloadSpeedLimit: Long
        get() = prefs.getLong("download_speed_limit", 0L)
        set(value) {
            prefs.edit().putLong("download_speed_limit", value.coerceAtLeast(0L)).apply()
        }

    /** 下载失败后自动重试次数（默认 3，范围 0-10） */
    var downloadRetryCount: Int
        get() = prefs.getInt("download_retry_count", DEFAULT_DOWNLOAD_RETRY_COUNT)
        set(value) {
            prefs.edit().putInt("download_retry_count", value.coerceIn(0, 10)).apply()
        }

    /** 锁屏后保持下载：开启后下载时获取 WakeLock，并可引导加入「忽略电池优化」白名单（默认开启） */
    var keepDownloadWhenLocked: Boolean
        get() = prefs.getBoolean("keep_download_when_locked", true)
        set(value) {
            prefs.edit().putBoolean("keep_download_when_locked", value).apply()
        }

    /** 通知栏进度样式：true=完整通知（进度条+下载速度）；false=仅显示通知（隐藏速度） */
    var notificationShowSpeed: Boolean
        get() = prefs.getBoolean("notification_show_speed", true)
        set(value) {
            prefs.edit().putBoolean("notification_show_speed", value).apply()
        }

    /**
     * 下载引擎：`ENGINE_BUILTIN`（默认，项目自带的 Kotlin 分片下载器）
     * 或 `ENGINE_GOPEED`（内置 Gopeed 引擎，需先在「下载引擎」页导入 AAR）。
     *
     * 取值非法（手改 prefs 等）时按内置下载器处理；引擎没就绪时 DownloadManager 也会自动回退，
     * 不会因为设置项把下载功能弄坏。
     */
    var downloadEngine: String
        get() = prefs.getString("download_engine", ENGINE_BUILTIN)?.takeIf {
            it == ENGINE_BUILTIN || it == ENGINE_GOPEED
        } ?: ENGINE_BUILTIN
        set(value) {
            prefs.edit().putString("download_engine", value).apply()
        }

    /** 夸克取链方式：true=免转存（直接换下载直链，不写入网盘，默认）；false=先转存到临时目录再取链 */
    var quarkNoSaveDownload: Boolean
        get() = prefs.getBoolean("quark_no_save_download", true)
        set(value) {
            prefs.edit().putBoolean("quark_no_save_download", value).apply()
        }

    /** 桌面图标样式：0=经典图标(icon)，1=新图标(icon2)；切换经 activity-alias 动态生效 */
    var appIconVariant: Int
        get() = prefs.getInt("app_icon_variant", 0)
        set(value) {
            prefs.edit().putInt("app_icon_variant", value.coerceIn(0, 1)).apply()
        }

    /** 文件名显示方式：false=单行跑马灯滚动（默认，保持原有观感），true=多行折行显示 */
    var fileNameMultiLine: Boolean
        get() = prefs.getBoolean("file_name_multi_line", false)
        set(value) {
            prefs.edit().putBoolean("file_name_multi_line", value).apply()
        }

    /** 自动识别剪贴板分享链接：关闭后完全不读取剪贴板（默认开启） */
    var clipboardSuggestEnabled: Boolean
        get() = prefs.getBoolean("clipboard_suggest_enabled", true)
        set(value) {
            prefs.edit().putBoolean("clipboard_suggest_enabled", value).apply()
        }

    /** 接受预发布版更新：检查更新时把 GitHub Pre-release 也算作新版本（默认关闭） */
    var acceptPrereleaseUpdate: Boolean
        get() = prefs.getBoolean("accept_prerelease_update", false)
        set(value) {
            prefs.edit().putBoolean("accept_prerelease_update", value).apply()
        }

    /**
     * 诊断模式（设置 → 关于云析 → 长按 → 开发调试）：默认关，开启后把 db / crypto / download /
     * webview / network / operation 六个模块的详细日志写进私有目录（见 `DiagnosticLog`）。
     * ★ 这里只存开关值，运行态由 `DiagnosticLog` 自己缓存（改完立刻生效，不必重启）。
     */
    var diagnosticMode: Boolean
        get() = prefs.getBoolean("diagnostic_mode", false)
        set(value) {
            prefs.edit().putBoolean("diagnostic_mode", value).apply()
        }

    /**
     * 下载调试（设置 → 关于云析 → 长按 → 开发调试）：默认关。
     * 开启后按下载任务记录事件流（主要变更节点 / 错误节点），下载页长按任务可进「调试信息」页查看并导出；
     * **关闭会清空全部日志**（见 `DownloadDebugLog`），所以设置页关开关前有二次确认弹窗。
     */
    var downloadDebug: Boolean
        get() = prefs.getBoolean("download_debug", false)
        set(value) {
            prefs.edit().putBoolean("download_debug", value).apply()
        }

    /**
     * 全量 SHA-256 校验（开发调试菜单，**默认关**）：开启后保存文件时顺带算出整文件摘要，
     * 写进下载调试日志（「全量校验」事件）。合并那一遍本来就在读所有字节，所以不额外读盘，只多一点 CPU。
     */
    var fullFileSha256: Boolean
        get() = prefs.getBoolean("download_full_sha256", false)
        set(value) {
            prefs.edit().putBoolean("download_full_sha256", value).apply()
        }

    /** 忽略 SSL 证书校验（抓包调试用，隐藏菜单开启；默认关闭） */
    var ignoreSslCert: Boolean
        get() = prefs.getBoolean("ignore_ssl_cert", false)
        set(value) {
            prefs.edit().putBoolean("ignore_ssl_cert", value).apply()
        }

    /** 百度网盘大文件限速提示：是否已选择「不再显示」 */
    var baiduLimitHintDismissed: Boolean
        get() = prefs.getBoolean("baidu_limit_hint_dismissed", false)
        set(value) {
            prefs.edit().putBoolean("baidu_limit_hint_dismissed", value).apply()
        }

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode: Int
        get() = prefs.getInt("dark_mode", 0)
        set(value) {
            prefs.edit().putInt("dark_mode", value.coerceIn(0, 2)).apply()
        }

    /** 主题色模式：0=动态色彩（Android12+ 壁纸取色，低版本回退默认蓝），1=默认蓝色，2=自定义种子色 */
    var themeColorMode: Int
        get() = prefs.getInt("theme_color_mode", 0)
        set(value) {
            prefs.edit().putInt("theme_color_mode", value.coerceIn(0, 2)).apply()
        }

    /** 自定义主题种子色（ARGB 值） */
    var themeSeedColor: Long
        get() = prefs.getLong("theme_seed_color", DEFAULT_SEED_COLOR)
        set(value) {
            prefs.edit().putLong("theme_seed_color", value).apply()
        }

    /**
     * 自定义 GitHub 下载镜像前缀（如 "https://gh.dpik.top/"）。
     * null/空字符串表示使用内置默认镜像（UpdateChecker.MIRROR_PREFIX）。
     */
    var githubMirrorPrefix: String?
        get() = prefs.getString("github_mirror_prefix", null)
        set(value) {
            prefs.edit().putString("github_mirror_prefix", value).apply()
        }

    /** 是否启用 HTTP 代理（默认关闭，直连） */
    var proxyEnabled: Boolean
        get() = prefs.getBoolean("proxy_enabled", false)
        set(value) {
            prefs.edit().putBoolean("proxy_enabled", value).apply()
        }

    /** 代理主机地址（如 "127.0.0.1"），空串表示未配置 */
    var proxyHost: String
        get() = prefs.getString("proxy_host", "") ?: ""
        set(value) {
            prefs.edit().putString("proxy_host", value).apply()
        }

    /** 代理端口（默认 7890，范围 1-65535） */
    var proxyPort: Int
        get() = prefs.getInt("proxy_port", DEFAULT_PROXY_PORT)
        set(value) {
            prefs.edit().putInt("proxy_port", value.coerceIn(1, 65535)).apply()
        }

    /**
     * 某个深链接注册项是否启用（默认开）。
     *
     * ★ 这里只存「用户意愿」，真正的生效方式是启停清单里对应的 activity-alias ——
     *   改完必须立刻调 [com.yunx.app.util.LinkOpener.apply]，否则要等下次启动才同步。
     */
    fun linkOpen(target: LinkOpenTarget): Boolean = prefs.getBoolean(target.prefKey, true)

    fun setLinkOpen(target: LinkOpenTarget, enabled: Boolean) {
        prefs.edit().putBoolean(target.prefKey, enabled).apply()
    }

    /**
     * 多账号下载（**实验性**，代号 C1）：默认关。
     *
     * 开启后，同一个网盘登录了多个账号时，批量下载会把每个文件轮换交给不同账号去取直链，
     * 绕开单账号的并发数 / 限速限制（每个文件仍只用一个账号下载）。
     * ★ 只有「账号列表里不止一个账号」的平台才有实际效果；开启前设置页有二次风险确认。
     */
    var multiAccountDownload: Boolean
        get() = prefs.getBoolean("multi_account_download", false)
        set(value) {
            prefs.edit().putBoolean("multi_account_download", value).apply()
        }

    /**
     * 单文件多源分片（**实验性**，代号 C2）：默认关，**依赖 [multiAccountDownload]**。
     *
     * 开启后，同一个文件用多个账号各取一条直链，分片轮流交给不同直链下载（真正提速单个大文件）。
     * 目前只覆盖夸克 / UC / 迅雷 / 百度；其他平台即使开着也退回单源。
     * ★ 关掉 C1 时必须一起关掉它（设置页与读取处都按「C1 关 ⇒ C2 无效」处理），
     *   否则会出现「多账号下载关了、单文件还在多账号取链」的不一致状态。
     */
    var multiSourceDownload: Boolean
        get() = prefs.getBoolean("multi_source_download", false)
        set(value) {
            prefs.edit().putBoolean("multi_source_download", value).apply()
        }

    /** C2 是否真正生效：开关开着且 C1 也开着（C2 依赖 C1，见 [multiSourceDownload]） */
    fun multiSourceActive(): Boolean = multiAccountDownload && multiSourceDownload

    /** 关闭 C1 时强制关掉 C2（C2 依赖 C1）：设置页切 C1 与读取端都用这一处口径 */
    fun disableMultiSource() {
        prefs.edit().putBoolean("multi_source_download", false).apply()
    }

    companion object {
        const val DEFAULT_DOWNLOAD_THREADS = 32

        /** 下载引擎标识（[downloadEngine] 的取值）：内置 Kotlin 分片下载器 */
        const val ENGINE_BUILTIN = "builtin"

        /** 下载引擎标识：内置 Gopeed 引擎（gomobile 核心，需用户导入 AAR） */
        const val ENGINE_GOPEED = "gopeed"
        /**
         * 线程数上限 = 512（与设置页档位一致）。
         * 真正同时在飞的请求数另由 `DownloadManager.MAX_INFLIGHT_CHUNKS`（按最大堆预算推导、同样封顶 512）
         * 与全进程 `inflightLimiter` 钉住：下载客户端已固定 HTTP/1.1，每路只占一条连接 + 64KB 读缓冲
         * （256MB 堆 → 512 × 64KB = 32MB = 堆的 1/8，本机 FD 软限 32768），
         * 所以这里放开到 512 不会再像 HTTP/2 时代那样把堆撑满（见 Agent.md §5.1.1）。
         * 旧版本存过 128/256/512 的用户现在能真正用上这些档位。
         */
        const val MAX_DOWNLOAD_THREADS = 512
        const val XUNLEI_DOWNLOAD_THREADS = 8
        const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 3
        const val DEFAULT_DOWNLOAD_RETRY_COUNT = 3
        const val DEFAULT_PROXY_PORT = 7890

        /** 默认主题种子色：Material Blue（与内置默认方案一致） */
        const val DEFAULT_SEED_COLOR = 0xFF415F91L
    }
}
