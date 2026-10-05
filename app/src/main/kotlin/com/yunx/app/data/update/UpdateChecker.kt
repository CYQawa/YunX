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
import org.json.JSONArray
import org.json.JSONObject

/**
 * GitHub Release 更新检测。
 * 正式版通道（默认）：GET https://api.github.com/repos/CYQawa/YunX/releases/latest
 * 预发布通道（设置页开启「接受预发布版更新」后）：GET https://api.github.com/repos/CYQawa/YunX/releases
 *
 * ★ 仓库路径参数化了（[repo]）：内核包下载走的是另一个仓库（`CYQawa/yunx_gopeed_build`，
 *   见 `KernelProvisioner.KERNEL_REPO`）。Release 的抓取/解析**只此一份实现**，
 *   不要为了第二个仓库再写一个平行的请求器。
 */
object UpdateChecker {

    /** 本应用自己的仓库（更新检测默认走它） */
    const val APP_REPO = "CYQawa/YunX"

    private fun latestUrl(repo: String) = "https://api.github.com/repos/$repo/releases/latest"

    /** Release 列表（按发布时间倒序，含 Pre-release）：开启「接受预发布版更新」时用它取最新一条 */
    private fun listUrl(repo: String) = "https://api.github.com/repos/$repo/releases"

    /** 更新检测日志标签：失败原因一律 E 级打印，方便直接看 logcat 定位（断网 / 限流 / 无 Release） */
    private const val TAG = "YunX-Update"

    /** GitHub 下载加速镜像站前缀（国内直连 GitHub 慢/失败时的兜底下载通道） */
    const val MIRROR_PREFIX = "https://cdn.gh-proxy.org/"

    /** 把 GitHub release 直链转成镜像站直链：<prefix><原直链>；默认使用内置镜像前缀 */
    fun mirrorUrl(url: String, prefix: String = MIRROR_PREFIX): String = prefix + url

    /**
     * Release 说明里「网盘下载」条目的匹配规则，形如：
     * `[网盘下载](https://pan.quark.cn/s/a7287ee935cb)`
     * 命中时客户端把主按钮切成「网盘更新」（走内置解析下载），GitHub 直链退到次级入口。
     */
    private val NETDISK_LINK_REGEX = Regex("""\[网盘下载\]\s*\(\s*(https?://[^)\s]+?)\s*\)""")

    data class Asset(
        val name: String,
        val downloadUrl: String,
        /** 资产大小（GitHub API 的 size，字节）；缺省 0 = 未知（旧调用点不传） */
        val size: Long = 0L,
        /** 资产摘要，形如 `sha256:xxxx`；缺省空串 = 未知（GitHub 只在较新的响应里给） */
        val digest: String = ""
    )

    data class Release(
        val tagName: String,
        val body: String,
        val assets: List<Asset>,
        val publishedAt: String,
        /** Release 页面地址（html_url），供「打开 GitHub 页面」跳浏览器 */
        val htmlUrl: String,
        /** 是否为 GitHub Pre-release：正式版通道恒为 false，预发布通道可能为 true */
        val prerelease: Boolean = false
    )

    /** 更新检测结果：失败时带上可读原因（HTTP 码 / 异常信息），既写 E 级日志也直接给用户提示 */
    sealed class CheckResult {
        data class Success(val release: Release) : CheckResult()
        data class Failure(val reason: String) : CheckResult()
    }

    /** HTTP 请求结果：成功带回响应体文本，失败带回可读原因（直接透传给界面提示） */
    private sealed class BodyResult {
        data class Ok(val text: String) : BodyResult()
        data class Error(val reason: String) : BodyResult()
    }

    /** 从 Release 说明正文里提取「网盘下载」链接；没有该条目时返回 null */
    fun netdiskDownloadUrl(body: String): String? =
        NETDISK_LINK_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** capsule-render 头图（波浪 banner）：只要 `<img>` 里出现这个域名就整段删掉（不依赖属性顺序） */
    private val CAPSULE_BANNER_REGEX =
        Regex("""<img\b[^>]*capsule-render\.vercel\.app[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * QQ 群徽章（带外链的形式）：`[![任意文字](https://img.shields.io/badge/...)](http://qm.qq.com/...)`
     *
     * ★ 必须排在裸图片规则**前面**（见 [DECORATION_REGEXES]）：先整体吃掉外层链接，
     *   否则裸图片规则会把里面那截删掉，只剩一个 `[](http://qm.qq.com/...)` 的空链接。
     */
    private val QQ_BADGE_LINKED_REGEX = Regex(
        """\[!\[[^\]]*]\(\s*https://img\.shields\.io/[^)\s]*\)]\(\s*https?://[^)\s]*qm\.qq\.com[^)\s]*\s*\)"""
    )

    /** QQ 群徽章（裸图片形式）：`![任意文字](https://img.shields.io/badge/...&logo=qq...)` */
    private val QQ_BADGE_BARE_REGEX =
        Regex("""!\[[^\]]*]\(\s*https://img\.shields\.io/[^)\s]*logo=qq[^)\s]*\)""")

    /** Release 说明里对纯文本展示毫无意义、只该整段丢掉的装饰片段（顺序即执行顺序，别调换前两条） */
    private val DECORATION_REGEXES =
        listOf(CAPSULE_BANNER_REGEX, QQ_BADGE_LINKED_REGEX, QQ_BADGE_BARE_REGEX)

    /**
     * 过滤 Release 说明里对用户没用的装饰，只去这两类（**别扩大范围**，其余内容一字不动）：
     *
     * ① capsule-render 的头图 `<img src="https://capsule-render.vercel.app/api?..." />`；
     * ② QQ 群徽章那一行 `[![QQ交流群](https://img.shields.io/badge/QQ%E7%BE%A4-...?logo=qq)](http://qm.qq.com/...)`。
     *
     * 为什么要在客户端去：`UpdateSheet` 是用**纯文本** `Text()` 显示说明正文的（没上 markdown/HTML 渲染），
     * 这两样在 GitHub Release 页是图片，到我们这儿只会显示成一长串 URL，白占那 220dp 的高度。
     * ★ 因此这里只认「capsule-render 的 img」和「指向 qm.qq.com / 带 logo=qq 的 shields.io 徽章」，
     *   不认 shields.io 的其它徽章（别的徽章可能是有信息量的）。
     *
     * 整行只剩装饰时**整行删掉**（这两样都是独占一行），否则会在说明开头留下一片空行；
     * 顺带把连续空行压成一个 —— markdown 本来就把多个空行当一个，压缩不改语义，只影响观感。
     */
    fun cleanReleaseNotes(body: String): String {
        if (body.isBlank()) return body
        val kept = ArrayList<String>()
        for (line in body.lines()) {
            val stripped = DECORATION_REGEXES.fold(line) { acc, regex -> regex.replace(acc, "") }.trim()
            // 整行都是装饰 → 丢掉整行；本来就是空行的原样留着（后面统一压缩连续空行）
            if (stripped.isEmpty() && line.isNotBlank()) continue
            if (stripped.isEmpty()) {
                if (kept.isNotEmpty() && kept.last().isEmpty()) continue
                kept.add("")
            } else {
                // 行内还夹着装饰（如「文字 <img ...>」）时只去掉片段，其余文字原样保留
                kept.add(stripped)
            }
        }
        // trim() 顺手去掉说明开头/结尾的空行（正文第一个字符往往就是换行）
        return kept.joinToString("\n").trim()
    }

    /**
     * 比较两个版本号：v1 > v2 返回正数，v1 < v2 返回负数，相等返回 0。
     * 兼容 fork 构建后缀（如 "1.2.6-gh1"）：每段取数字前缀比较，后缀（-gh<n> 等）不影响主版本比较。
     * 数字段完全相同时，只有两边都带后缀（预发布版，如 "1.3.0-beta1"）才继续比后缀：先比后缀里第一段数字
     * （beta2 > beta1），再按字符串比较；一边没有后缀则视为相等，免得把 fork 构建（1.2.6-gh1）判成比同号正式版旧。
     */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.trimStart('v').split(".")
        val parts2 = v2.trimStart('v').split(".")
        val maxLength = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLength) {
            val num1 = parts1.getOrNull(i)?.let { DIGITS.find(it)?.value?.toIntOrNull() } ?: 0
            val num2 = parts2.getOrNull(i)?.let { DIGITS.find(it)?.value?.toIntOrNull() } ?: 0
            if (num1 != num2) return num1 - num2
        }
        if (v1 == v2) return 0
        val suffix1 = v1.trimStart('v').substringAfter('-', "")
        val suffix2 = v2.trimStart('v').substringAfter('-', "")
        if (suffix1.isEmpty() || suffix2.isEmpty()) return 0
        val pre1 = DIGITS.find(suffix1)?.value?.toIntOrNull() ?: 0
        val pre2 = DIGITS.find(suffix2)?.value?.toIntOrNull() ?: 0
        if (pre1 != pre2) return pre1 - pre2
        return suffix1.compareTo(suffix2)
    }

    private val DIGITS = Regex("\\d+")

    /** 当前应用版本号（packageManager.versionName） */
    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"

    /**
     * 请求 GitHub 最新 Release。
     * [includePrerelease] = true 时改走预发布通道（Release 列表接口，按发布时间倒序取第一条非 Draft 版本），
     * 这样标了 Pre-release 的版本也会被当成可更新版本；false 时走 `/releases/latest`（GitHub 只给正式版）。
     * 任何失败（网络异常 / HTTP 非 2xx / 响应缺字段）都在这里打 E 级日志，并把原因带回调用方，
     * 这样界面能提示「检查更新失败：HTTP 403 ……」而不是统一一句「请检查网络」。
     */
    suspend fun fetchLatestRelease(
        includePrerelease: Boolean = false,
        repo: String = APP_REPO
    ): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            if (includePrerelease) requestReleaseList(repo) else requestLatestRelease(repo)
        }
            .onFailure { e ->
                Log.e(TAG, "获取最新 Release 异常（$repo）：${e.javaClass.simpleName}: ${e.message}", e)
            }
            .getOrElse { e ->
                CheckResult.Failure("${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
            }
    }

    /** 正式版通道：GET /releases/latest，GitHub 保证返回最新的非 Pre-release、非 Draft 版本 */
    private fun requestLatestRelease(repo: String): CheckResult {
        return when (val body = fetchBody(latestUrl(repo))) {
            is BodyResult.Error -> CheckResult.Failure(body.reason)
            is BodyResult.Ok -> {
                val json = try {
                    JSONObject(body.text)
                } catch (e: Exception) {
                    Log.e(TAG, "获取最新 Release 失败：响应不是合法 JSON（前 200 字=${body.text.take(200)}）", e)
                    return CheckResult.Failure("响应格式异常")
                }
                parseRelease(json)
            }
        }
    }

    /** 预发布通道：GET /releases（数组、按发布时间倒序），取第一条非 Draft 且带 tag_name 的版本 */
    private fun requestReleaseList(repo: String): CheckResult {
        return when (val body = fetchBody(listUrl(repo))) {
            is BodyResult.Error -> CheckResult.Failure(body.reason)
            is BodyResult.Ok -> {
                val array = try {
                    JSONArray(body.text)
                } catch (e: Exception) {
                    Log.e(TAG, "获取最新 Release 失败：响应不是合法 JSON 数组（前 200 字=${body.text.take(200)}）", e)
                    return CheckResult.Failure("响应格式异常")
                }
                val json = (0 until array.length())
                    .mapNotNull { array.optJSONObject(it) }
                    .firstOrNull { !it.optBoolean("draft") && it.optString("tag_name").isNotBlank() }
                if (json == null) {
                    Log.e(TAG, "获取最新 Release 失败：列表里没有可用版本（全为 Draft 或缺 tag_name）")
                    return CheckResult.Failure("仓库暂无 Release")
                }
                parseRelease(json)
            }
        }
    }

    /** 发 GitHub API GET 请求；非 2xx / 空响应转成带可读原因的失败 */
    private fun fetchBody(url: String): BodyResult {
        val client = HttpClients.apiClient()
        val request = Request.Builder()
            .url(url)
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
                Log.e(TAG, "获取最新 Release 失败：$reason（$url）")
                BodyResult.Error(reason)
            } else {
                val text = resp.body?.string()
                if (text.isNullOrBlank()) {
                    Log.e(TAG, "获取最新 Release 失败：响应体为空")
                    BodyResult.Error("响应为空")
                } else {
                    BodyResult.Ok(text)
                }
            }
        }
    }

    /** 解析单个 Release 对象；缺 tag_name 等硬性字段时返回失败 */
    private fun parseRelease(json: JSONObject): CheckResult {
        val tag = json.optString("tag_name")
        if (tag.isBlank()) {
            Log.e(TAG, "获取最新 Release 失败：Release 数据缺少版本号")
            return CheckResult.Failure("Release 数据缺少版本号")
        }
        val assets = buildList {
            json.optJSONArray("assets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    add(
                        Asset(
                            name = a.optString("name"),
                            downloadUrl = a.optString("browser_download_url"),
                            size = a.optLong("size"),
                            digest = a.optString("digest")
                        )
                    )
                }
            }
        }
        val rawBody = json.optString("body")
        // 说明正文只在这一处清洗（UpdateSheet / 网盘链接提取读到的都是清洗后的那份，别再各清一遍）
        val body = cleanReleaseNotes(rawBody)
        val prerelease = json.optBoolean("prerelease")
        Log.d(
            TAG,
            "获取最新 Release 成功：$tag（${if (prerelease) "预发布版；" else ""}资产 ${assets.size} 个；" +
                "说明 ${rawBody.length} 字 → 过滤装饰后 ${body.length} 字；网盘链接=${netdiskDownloadUrl(body) ?: "无"}）"
        )
        return CheckResult.Success(
            Release(
                tagName = tag,
                body = body,
                assets = assets,
                publishedAt = json.optString("published_at"),
                htmlUrl = json.optString("html_url"),
                prerelease = prerelease
            )
        )
    }
}