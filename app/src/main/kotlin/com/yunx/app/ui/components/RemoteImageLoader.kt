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

package com.yunx.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * 全项目唯一的远程图片加载器：**用项目已有的 OkHttp 自己加载，刻意不引入 Coil**
 * （这条约定从 README 图片渲染开始，公告的封面 / 头像 / 正文配图继续沿用，见 Agent.md §3.35）。
 *
 * 两个调用方：
 * - [GitHubMarkdownImageTransformer]：mikepenz Markdown 渲染器的 ImageTransformer（README + 公告正文内嵌图）；
 * - [RemoteImage]：普通网络图片（公告封面 / 发布者头像 / 正文图集）。
 *
 * 能力与保护：
 * - 内存 LRU 缓存（[MAX_ENTRIES] 张）防重复请求，[cached] 让「已经加载过的图」在重组时立即出图、不闪占位；
 * - [Semaphore] 限制同时在飞的请求数，滚动长列表时不会瞬间打满连接；
 * - 降采样：先读尺寸按目标宽算 inSampleSize，避免大图撑爆内存；
 * - 镜像：显式设置 [mirrorPrefix] 时先试镜像 URL，失败回退直连（仅 GitHub 系域名生效）；
 * - SVG 直接跳过（BitmapFactory 不支持，README badge 大量是 svg），任何异常返回 null 由调用方显示占位。
 */
object RemoteImageLoader {

    private const val MAX_ENTRIES = 128

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) =
                size > MAX_ENTRIES
        }
    )

    private val semaphore = Semaphore(4)

    /** 镜像前缀（由调用方在读取设置后设置；null / 空 = 直连）。只对 GitHub 系域名生效 */
    @Volatile
    var mirrorPrefix: String? = null

    /** 取已缓存的图（没有则 null）：调用方用它做首帧直出，避免闪一下占位色 */
    fun cached(url: String): Bitmap? = cache[url.trim()]

    /** 加载远程图片；失败（网络 / 非 2xx / 不是图片 / 不支持 svg）返回 null，不抛异常 */
    suspend fun load(url: String): Bitmap? {
        val link = url.trim()
        if (link.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            cache[link]?.let { return@withContext it }
            // SVG（README badge、部分图床图标）BitmapFactory 不支持，提前跳过省一次网络请求；
            // 带 query 的 svg（如 badge.svg?raw=1）必须先去 query/fragment 再取后缀，否则会误取成 "raw=1"
            val ext = link.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase()
            if (ext == "svg") return@withContext null
            // 镜像前缀仅对 GitHub 域生效；外链图（图床 / imgur 等）直接直连，避免无谓的失败镜像请求
            val useMirror = !mirrorPrefix.isNullOrBlank() && isGitHubDomain(link)
            val candidates = buildList {
                if (useMirror) add(UpdateChecker.mirrorUrl(link, mirrorPrefix!!))
                add(link)
            }
            val client = HttpClients.downloadClient()
            for (candidate in candidates) {
                val bytes: ByteArray? = runCatching {
                    semaphore.withPermit {
                        val req = okhttp3.Request.Builder().url(candidate)
                            .header("User-Agent", "YunX").build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@withPermit null
                            resp.body?.bytes()
                        }
                    }
                }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = decodeSampled(bytes)
                    if (bmp != null) {
                        cache[link] = bmp
                        return@withContext bmp
                    }
                }
            }
            null
        }
    }

    /** 是否为 GitHub 系域名（仅这些域套镜像前缀才有意义）；严格匹配防止 evilgithub.com 等仿冒域被误套 */
    private fun isGitHubDomain(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "github.com" || host.endsWith(".github.com") ||
            host == "raw.githubusercontent.com" || host.endsWith(".githubusercontent.com")
    }

    /**
     * 降采样解码：先读边界尺寸，再按「总像素上限」算 inSampleSize（2 的幂）。
     * 手机屏阅读为主，约 200 万像素（1080×1920 级别）足够清晰；
     * 对超高长图（如 2160×10000）按总像素缩放，避免一次性解码出超大 Bitmap 导致 OOM。
     * 小图不放大：inSampleSize 最小为 1。
     */
    private fun decodeSampled(bytes: ByteArray): Bitmap? = runCatching {
        val maxPixels = 1080 * 1920   // 约 200 万像素上限
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample * (bounds.outHeight / sample) > maxPixels) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}
