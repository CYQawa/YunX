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

package com.yunx.app.data.update

import android.content.Context
import android.util.Log
import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * GitHub Release 更新检测。
 * 真实实现：GET https://api.github.com/repos/CYQawa/YunX/releases/latest
 */
object UpdateChecker {

    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/CYQawa/YunX/releases/latest"

    /** 更新检测日志标签：失败原因一律 E 级打印，方便直接看 logcat 定位（断网 / 限流 / 无 Release） */
    private const val TAG = "YunX-Update"

    /** GitHub 下载加速镜像站前缀（国内直连 GitHub 慢/失败时的兜底下载通道） */
    const val MIRROR_PREFIX = "https://cdn.gh-proxy.org/"

    /** 把 GitHub release 直链转成镜像站直链：https://cdn.gh-proxy.org/<原直链> */
    fun mirrorUrl(url: String): String = MIRROR_PREFIX + url

    /**
     * Release 说明里「网盘下载」条目的匹配规则，形如：
     * `[网盘下载](https://pan.quark.cn/s/a7287ee935cb)`
     * 命中时客户端把主按钮切成「网盘更新」（走内置解析下载），GitHub 直链退到次级入口。
     */
    private val NETDISK_LINK_REGEX = Regex("""\[网盘下载\]\s*\(\s*(https?://[^)\s]+?)\s*\)""")

    data class Asset(
        val name: String,
        val downloadUrl: String
    )

    data class Release(
        val tagName: String,
        val body: String,
        val assets: List<Asset>,
        val publishedAt: String,
        /** Release 页面地址（html_url），供「打开 GitHub 页面」跳浏览器 */
        val htmlUrl: String
    )

    /** 更新检测结果：失败时带上可读原因（HTTP 码 / 异常信息），既写 E 级日志也直接给用户提示 */
    sealed class CheckResult {
        data class Success(val release: Release) : CheckResult()
        data class Failure(val reason: String) : CheckResult()
    }

    /** 从 Release 说明正文里提取「网盘下载」链接；没有该条目时返回 null */
    fun netdiskDownloadUrl(body: String): String? =
        NETDISK_LINK_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** 比较两个版本号：v1 > v2 返回正数，v1 < v2 返回负数，相等返回 0 */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.trimStart('v').split(".")
        val parts2 = v2.trimStart('v').split(".")
        val maxLength = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLength) {
            val num1 = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val num2 = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    /** 当前应用版本号（packageManager.versionName） */
    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"

    /**
     * 请求 GitHub 最新 Release。
     * 任何失败（网络异常 / HTTP 非 2xx / 响应缺字段）都在这里打 E 级日志，并把原因带回调用方，
     * 这样界面能提示「检查更新失败：HTTP 403 ……」而不是统一一句「请检查网络」。
     */
    suspend fun fetchLatestRelease(): CheckResult = withContext(Dispatchers.IO) {
        runCatching { requestLatestRelease() }
            .onFailure { e ->
                Log.e(TAG, "获取最新 Release 异常：${e.javaClass.simpleName}: ${e.message}", e)
            }
            .getOrElse { e ->
                CheckResult.Failure("${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
            }
    }

    /** 真正发请求 + 解析；每个失败分支都带具体原因，同时写 E 级日志 */
    private fun requestLatestRelease(): CheckResult {
        val client = HttpClients.apiClient()
        val request = Request.Builder()
            .url(RELEASES_LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YunX")
            .get()
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val reason = when (resp.code) {
                    404 -> "仓库暂无 Release"
                    403, 429 -> "GitHub 接口限流（HTTP ${resp.code}），请稍后再试"
                    else -> "HTTP ${resp.code} ${resp.message}"
                }
                Log.e(TAG, "获取最新 Release 失败：$reason（$RELEASES_LATEST_URL）")
                return@use CheckResult.Failure(reason)
            }
            val text = resp.body?.string()
            if (text.isNullOrBlank()) {
                Log.e(TAG, "获取最新 Release 失败：响应体为空")
                return@use CheckResult.Failure("响应为空")
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                Log.e(TAG, "获取最新 Release 失败：响应不是合法 JSON（前 200 字=${text.take(200)}）", e)
                return@use CheckResult.Failure("响应格式异常")
            }
            val tag = json.optString("tag_name")
            if (tag.isBlank()) {
                Log.e(TAG, "获取最新 Release 失败：tag_name 为空（前 200 字=${text.take(200)}）")
                return@use CheckResult.Failure("Release 数据缺少版本号")
            }
            val assets = buildList {
                json.optJSONArray("assets")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        add(Asset(a.optString("name"), a.optString("browser_download_url")))
                    }
                }
            }
            val body = json.optString("body")
            Log.d(
                TAG,
                "获取最新 Release 成功：$tag（资产 ${assets.size} 个；说明 ${body.length} 字；" +
                    "网盘链接=${netdiskDownloadUrl(body) ?: "无"}）"
            )
            CheckResult.Success(
                Release(
                    tagName = tag,
                    body = body,
                    assets = assets,
                    publishedAt = json.optString("published_at"),
                    htmlUrl = json.optString("html_url")
                )
            )
        }
    }
}