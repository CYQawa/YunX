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

package com.yunx.app.data.announcement

import android.util.Log
import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 应用内公告客户端（远程公告系统 v1.0.0）。
 *
 * 只用到两个**公开**接口（管理端 `/api/v1/admin/` 系列接口需要 ADMIN_TOKEN，客户端一律不碰）：
 * - `GET /api/v1/announcements?page=&pageSize=`：列表，**不含正文**；
 * - `GET /api/v1/announcements/{id}`：详情，含正文，且会让 `viewCount` +1（属于预期行为）。
 *
 * 三条硬约定（接口文档 §1.1 / §4 / §6）：
 * 1. 成功与否看响应体的 `success` 字段，**不要只看 HTTP 状态码**（业务失败与 HTTP 错误码是分离的）；
 * 2. 列表接口有副作用：每次成功调用都计入服务端当日「客户端启动数」，所以**不要轮询** ——
 *    只在启动检查与用户手动刷新/翻页时调用；
 * 3. 详情会让浏览量 +1，因此详情结果在 ViewModel 里按 id 缓存，避免重组 / 返回时重复请求。
 *
 * `content` 支持 Markdown / HTML（渲染交给 mikepenz 的 GFM 渲染器，与 README 预览同一套，
 * 不注入 WebView ⇒ 不存在脚本执行面）。
 */
object AnnouncementApi {

    /**
     * 公告服务地址（PHP + SQLite 虚拟主机版；接口契约与原 Cloudflare Workers 版完全一致，只有域名与协议变了）。
     *
     * ★ 当前部署是 **HTTP 明文**，而本项目的 `network_security_config.xml` 全局禁止明文 ⇒
     *   那里为这个域名单独开了一条 cleartext 例外，**两处必须一起改**：
     *   后端上 HTTPS 时把这里改回 `https://…` 并删掉那条 `<domain-config>`，
     *   否则要么请求被系统直接拦掉（`CLEARTEXT communication to … not permitted`），要么白白留个明文口子。
     *
     * 图床域名与 API 域名无关：图片直链由服务端给出、客户端直接加载，http 图床同样要在
     * network_security_config 里放行（见那里的注释），https 图床不受影响。
     */
    const val BASE_URL = "http://yunx.cyqawa.os.kg"

    /**
     * 列表分页大小 = 接口上限 100。
     * 启动时一次取满，于是「未读角标」「启动弹窗候选」的口径就是**全部公告**，而不是前 20 条；
     * 只有公告总数超过 100 条时列表页才需要「加载更多」翻第二页（仍用同一个 pageSize，分页口径一致）。
     */
    const val PAGE_SIZE = 100

    /** 公告相关日志统一走这个标签，失败原因一律 E 级打印，方便直接看 logcat 定位 */
    private const val TAG = "YunX-Announce"

    /** 发布者（服务端 `publisher` 对象） */
    data class Publisher(val name: String, val avatarUrl: String?)

    /**
     * 公告对象（公开字段，见接口文档 §2.4）。
     * [content] 只有详情接口返回；[coverImage] / [images] / [publisher.avatarUrl] 都是**完整直链**，可直接加载。
     */
    data class Announcement(
        val id: String,
        val title: String,
        val summary: String,
        val content: String?,
        val coverImage: String?,
        val images: List<String>,
        val publishAt: String?,
        val author: String,
        val viewCount: Long,
        val publisher: Publisher,
        val isPinned: Boolean,
        val pinnedAt: String?,
        val pinExpireAt: String?,
        val sortOrder: Int,
        val createdAt: String,
        val updatedAt: String,
    ) {
        /** 生效时间（毫秒）：`publishAt` 为空表示「立即发布」，退回 createdAt；两者都解析不出来才是 0 */
        val effectiveMillis: Long get() = parseIsoMillis(publishAt) ?: parseIsoMillis(createdAt) ?: 0L
    }

    /** 分页结果（接口文档 §1.3）：`hasMore` 等价于 `page * pageSize < total` */
    data class Page(
        val total: Int,
        val page: Int,
        val pageSize: Int,
        val totalPages: Int,
        val hasMore: Boolean,
        val list: List<Announcement>,
    )

    /** 请求结果：失败带上可直接展示的原因（服务端 `message` / HTTP 码 / 异常信息） */
    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    /** 获取公告列表（不含正文）；[page] 从 1 开始 */
    suspend fun fetchPage(page: Int = 1, pageSize: Int = PAGE_SIZE): Result<Page> =
        request("/api/v1/announcements?page=$page&pageSize=$pageSize") { data ->
            Page(
                total = data.optInt("total"),
                page = data.optInt("page", page),
                pageSize = data.optInt("pageSize", pageSize),
                totalPages = data.optInt("totalPages"),
                hasMore = data.optBoolean("hasMore"),
                list = data.optJSONArray("list")?.let { parseAnnouncementArray(it) } ?: emptyList()
            )
        }

    /**
     * 获取公告详情（含正文）。
     * 公告不存在 / 草稿 / 已下架 / 定时未到统一是 `404` + `code 40401`，服务端 message 可直接展示。
     */
    suspend fun fetchDetail(id: String): Result<Announcement> =
        request("/api/v1/announcements/${encodeId(id)}") { data -> parseAnnouncement(data) }

    /** 发 GET 请求并解析统一响应结构；所有失败路径都返回 [Result.Failure]（不抛异常） */
    private suspend fun <T> request(path: String, fromData: (JSONObject) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val url = BASE_URL + path
            val body = try {
                val call = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "YunX")
                    .get()
                    .build()
                HttpClients.apiClient().newCall(call).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (text.isBlank()) {
                        // 服务端异常时可能连 JSON 都没有：这里只留 HTTP 码，够定位
                        Log.e(TAG, "公告响应为空（HTTP ${resp.code}，$url）")
                        return@withContext Result.Failure("响应为空（HTTP ${resp.code}）")
                    }
                    // ★ 非 2xx 也可能带合法 JSON（业务失败），所以先取 body 再交给 parseEnvelope 判 success
                    text
                }
            } catch (e: Exception) {
                Log.e(TAG, "公告请求异常（$url）：${e.javaClass.simpleName}: ${e.message}", e)
                return@withContext Result.Failure("${e.javaClass.simpleName}: ${e.message ?: "网络异常"}")
            }
            parseEnvelope(body, url, fromData)
        }

    /** 解析统一响应结构：`success` 为 false 时把服务端 message 原样带回（接口文档 §1.2） */
    private fun <T> parseEnvelope(body: String, url: String, fromData: (JSONObject) -> T): Result<T> {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "公告响应不是合法 JSON（前 200 字=${body.take(200)}）", e)
            return Result.Failure("响应格式异常")
        }
        if (!json.optBoolean("success")) {
            val code = json.optInt("code")
            val message = json.stringOrNull("message") ?: "请求失败"
            Log.e(TAG, "公告接口业务失败：[$code] $message（$url）")
            return Result.Failure(message)
        }
        val data = json.optJSONObject("data") ?: return Result.Failure("响应数据为空")
        return try {
            Result.Success(fromData(data))
        } catch (e: Exception) {
            Log.e(TAG, "公告数据解析失败（$url）：${e.message}", e)
            Result.Failure("数据解析失败")
        }
    }

    private fun parseAnnouncementArray(arr: JSONArray): List<Announcement> {
        val out = ArrayList<Announcement>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val parsed = parseAnnouncement(item)
            // ★ id 是列表 key（LazyColumn 的 key 必须唯一）与已读记录的键：空 id 直接丢掉，
            //   否则一条脏数据就会让整个列表抛 "Key was already used"。
            if (parsed.id.isEmpty()) {
                Log.w(TAG, "公告列表第 $i 条缺少 id，已跳过（title=${parsed.title.take(30)}）")
                continue
            }
            out.add(parsed)
        }
        return out
    }

    private fun parseStringArray(arr: JSONArray): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            if (arr.isNull(i)) continue
            val text = arr.optString(i).trim()
            if (text.isNotEmpty()) out.add(text)
        }
        return out
    }

    private fun parseAnnouncement(json: JSONObject): Announcement = Announcement(
        id = json.stringOrNull("id").orEmpty(),
        title = json.stringOrNull("title").orEmpty(),
        summary = json.stringOrNull("summary").orEmpty(),
        content = json.stringOrNull("content"),
        coverImage = json.stringOrNull("coverImage"),
        images = json.optJSONArray("images")?.let { parseStringArray(it) } ?: emptyList(),
        publishAt = json.stringOrNull("publishAt"),
        author = json.stringOrNull("author").orEmpty(),
        viewCount = json.optLong("viewCount"),
        publisher = json.optJSONObject("publisher")?.let { p ->
            Publisher(
                name = p.stringOrNull("name").orEmpty(),
                avatarUrl = p.stringOrNull("avatarUrl")
            )
        } ?: Publisher(name = "", avatarUrl = null),
        isPinned = json.optBoolean("isPinned"),
        pinnedAt = json.stringOrNull("pinnedAt"),
        pinExpireAt = json.stringOrNull("pinExpireAt"),
        sortOrder = json.optInt("sortOrder"),
        createdAt = json.stringOrNull("createdAt").orEmpty(),
        updatedAt = json.stringOrNull("updatedAt").orEmpty()
    )

    /** 公告 ID 长度 <= 128 且允许客户端自定义，进路径前统一编码（正常 ann_xxx 编码后原样） */
    private fun encodeId(id: String): String = URLEncoder.encode(id.trim(), "UTF-8")
}

/**
 * `optString` 遇到 JSON null 会返回字符串 `"null"`（不是空串），可空字段一律走这里。
 * 「字段缺失」与「字段为 null」在接口里是两种含义（如 publishAt = null 表示立即发布），
 * 所以这里只做「取不到 / 为 null ⇒ null」，空串也算没值（避免把 "" 当时间/图片地址用）。
 */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
