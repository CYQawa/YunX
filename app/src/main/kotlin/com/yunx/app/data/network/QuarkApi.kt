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

package com.yunx.app.data.network

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import com.yunx.app.data.network.model.ShareToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 夸克 Cookie 工具：合并/剥离 __puus、__pus（对应 AList pkg/cookie + quark_uc requestWithCookie）。
 * __puus 约 3 小时过期，是下载直链签名校验的关键字段（AlistGo/alist#830）。
 */
object QuarkCookieUtil {
    private val TRACKED = setOf("__puus", "__pus")

    /** 把响应 Set-Cookie 列表里的最新 __puus/__pus 合并回原 Cookie 串 */
    fun mergeFromSetCookies(original: String, setCookies: List<String>): String {
        var cookie = original
        for (sc in setCookies) {
            val kv = sc.substringBefore(';').trim()
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val name = kv.substring(0, eq)
            if (name in TRACKED) cookie = setOrReplace(cookie, name, kv.substring(eq + 1))
        }
        return cookie
    }

    /** 去掉 __puus，用于触发服务端重新下发（AList refreshPuus） */
    fun withoutPuus(cookie: String): String =
        cookie.split(";").map { it.trim() }
            .filter { !it.startsWith("__puus=") }
            .joinToString("; ")

    private fun setOrReplace(cookie: String, name: String, value: String): String {
        val parts = cookie.split(";").map { it.trim() }.toMutableList()
        val idx = parts.indexOfFirst { it.startsWith("$name=") }
        val kv = "$name=$value"
        if (idx >= 0) parts[idx] = kv else parts.add(kv)
        return parts.joinToString("; ")
    }
}

/**
 * 夸克 API 封装（OkHttp）：账号验证 + 分享解析 + 下载直链。
 */
class QuarkApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    /**
     * Cookie 回写接收器（推荐由 QuarkAccountRepository 注入并落库）：
     * 每次响应把 Set-Cookie 合并后的最新 Cookie 回调，保持 __puus/__pus 始终新鲜。
     */
    var cookieSink: ((String) -> Unit)? = null

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ---------- 账号 ----------

    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(QuarkConstants.ACCOUNT_INFO_URL)
            .header("Cookie", cookie)
            .header("User-Agent", QuarkConstants.API_USER_AGENT)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                // 该接口无 status 字段，成功标志为 success:true / code:"OK"
                if (json.optBoolean("success", false)) {
                    json.optJSONObject("data")
                        ?.optString("nickname")
                        ?.takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull()
    }

    // ---------- 分享解析 ----------

    /** 4.1 获取分享 Token（请求体携带 pwd_id/passcode） */
    suspend fun getShareToken(shareId: String, pwd: String?, cookie: String): ShareToken? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("passcode", pwd ?: "")
            .put("support_visit_limit_private_share", true)
            .toString()
        val request = postJson(QuarkConstants.SHARE_TOKEN_URL, cookie, body)
        parseData(request) { data ->
            ShareToken(
                stoken = data.optString("stoken"),
                title = data.optString("title"),
                firstFid = data.optString("first_fid")
            )
        }
    }

    /** 4.3 验证分享提取码 */
    suspend fun verifySharePassword(shareId: String, passcode: String, cookie: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("share_id", shareId)
                .put("passcode", passcode)
                .toString()
            val request = postJson(QuarkConstants.SHARE_PASSWORD_URL, cookie, body)
            runCatching {
                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optInt("status") == 200
                }
            }.getOrDefault(false)
        }

    /** 4.2 获取分享文件列表（sharepage/detail）
 *  官方字段：file_name / size / dir(boolean) / share_fid_token，
 *  与 kkdo.md 文档中的 fname/fsize/isdir/fid_token 不同，以抓包为准。
 */
    suspend fun getShareFiles(
        shareId: String,
        stoken: String,
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 100
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        // 参数名必须为 pwd_id（值=分享链接短码），并追加 ver=2 / _page / _size 等固定参数
        val url = buildString {
            append(QuarkConstants.SHARE_DETAIL_URL)
            append("&pwd_id=").append(shareId)
            append("&stoken=").append(URLEncoder.encode(stoken, "UTF-8"))
            append("&pdir_fid=").append(pdirFid)
            append("&ver=2")
            append("&force=0")
            append("&_page=").append(page)
            append("&_size=").append(size)
            append("&_fetch_banner=0")
            append("&_fetch_share=0")
            append("&fetch_relate_conversation=0")
            append("&_fetch_total=1")
            append("&_sort=file_type:asc,file_name:asc")
        }
        // 该接口需携带 Origin / Referer，否则可能返回 400
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", QuarkConstants.API_USER_AGENT)
            .header("Origin", "https://pan.quark.cn")
            .header("Referer", "https://pan.quark.cn/")
            .get()
            .build()
        parseData(request) { data ->
            val array = data.optJSONArray("list") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        ShareFile(
                            fid = item.optString("fid"),
                            fname = item.optString("file_name"),
                            fsize = item.optLong("size"),
                            isdir = item.optBoolean("dir", false),
                            pdirFid = item.optString("pdir_fid"),
                            fidToken = item.optString("share_fid_token"),
                            modifyTime = item.optString("updated_at")
                        )
                    )
                }
            }
        }
    }

    // ---------- 个人网盘 / 转存 ----------

    /** 7.1 个人网盘文件列表（用于查找/确认临时目录）
     *  注意：个人网盘列表字段为 file_name / size / dir(boolean)，
     *  与分享列表的 fname / fsize / isdir(int) 不同，需做兼容映射。
     */
    suspend fun getFileList(
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 100
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
    val url = "${QuarkConstants.FILE_URL}&pdir_fid=$pdirFid&page=$page&size=$size"
    val request = get(url, cookie)
    parseData(request) { data ->
        val array = data.optJSONArray("list") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    ShareFile(
                        fid = item.optString("fid"),
                        fname = item.optString("file_name").ifEmpty { item.optString("fname") },
                        fsize = if (item.has("size")) item.optLong("size") else item.optLong("fsize"),
                        isdir = item.optBoolean("dir", false) || item.optInt("isdir") == 1,
                        pdirFid = item.optString("pdir_fid"),
                        fidToken = item.optString("fid_token"),
                        modifyTime = item.optString("modify_time")
                    )
                )
            }
        }
    }
}

    /** 云盘文件列表（网盘页浏览；抓包 /1/clouddrive/file/sort，pdir_fid=0 根目录）
     *  响应 data.list[]，字段：fid / file_name / size / dir(boolean) / pdir_fid / updated_at。
     *  自动翻页：单页满 size 继续，封顶 100 页防异常死循环；默认单页 100（接口支持）。
     */
    suspend fun listCloudFiles(
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 100
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val all = mutableListOf<ShareFile>()
        var p = page
        while (true) {
            val url = buildString {
                append(QuarkConstants.CLOUD_FILE_SORT_URL)
                append("&uc_param_str=")
                append("&pdir_fid=").append(pdirFid)
                append("&_page=").append(p)
                append("&_size=").append(size)
                append("&_fetch_total=1")
                append("&_fetch_sub_dirs=0")
                append("&_sort=file_type:asc,updated_at:desc")
                append("&fetch_all_file=1")
                append("&fetch_risk_file_name=1")
            }
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", QuarkConstants.API_USER_AGENT)
                .header("Origin", "https://pan.quark.cn")
                .header("Referer", "https://pan.quark.cn/")
                .get()
                .build()
            val files = parseData(request) { data ->
                val array = data.optJSONArray("list") ?: JSONArray()
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        add(
                            ShareFile(
                                fid = item.optString("fid"),
                                fname = item.optString("file_name").ifEmpty { item.optString("fname") },
                                fsize = item.optLong("size"),
                                isdir = item.optBoolean("dir", false),
                                pdirFid = item.optString("pdir_fid"),
                                fidToken = "",
                                modifyTime = item.optString("updated_at")
                            )
                        )
                    }
                }
            }
            all += files
            // 本页未满 size → 已是最后一页；封顶 100 页防止异常死循环
            if (files.size < size || p >= 100) break
            p++
        }
        all.ifEmpty { null }
    }

    /** 创建目录（个人网盘），返回新目录 fid */
    suspend fun createFolder(name: String, parentFid: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("pdir_fid", parentFid)
                .put("file_name", name)
                .put("dir_path", "")
                .put("dir_init_lock", false)
                .toString()
            val request = postJson(QuarkConstants.FILE_URL, cookie, body)
            parseData(request) { data -> data.optString("fid") }
        }

    /** 5. 转存分享文件到个人网盘目录，返回异步任务 id（可能为空）
     *  注意：pwd_id 必须为分享链接短码（非空），并携带 pdir_fid/scene，
     *  否则接口返回 400 Bad Parameter: [pwd_id为空]。
     */
    suspend fun saveShareFile(
        shareId: String,
        stoken: String,
        pdirFid: String,
        fid: String,
        fidToken: String,
        toPdirFid: String,
        cookie: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("stoken", stoken)
            .put("pdir_fid", pdirFid)
            .put("to_pdir_fid", toPdirFid)
            .put("fid_list", JSONArray().put(fid))
            .put("fid_token_list", JSONArray().put(fidToken))
            .put("scene", "link")
            .toString()
        val request = postJson(QuarkConstants.SAVE_URL, cookie, body)
        parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
    }

    /** 轮询异步转存任务，直到完成或超时（最多 10 次 × 1s）
     *  官方轮询响应：data.status == 2（完成）且带 finished_at；
     *  转存后的新 fid 在 data.save_as.save_as_top_fids[0]（download 必须用它）。
     *  @return 转存后的新 fid；null 表示超时/失败。
     */
    suspend fun pollTask(taskId: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val url = "${QuarkConstants.TASK_URL}&task_id=${URLEncoder.encode(taskId, "UTF-8")}&retry_index=0"
        for (i in 0 until 10) {
            val savedFid = runCatching {
                client.newCall(get(url, cookie)).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("status") != 200) return@use null
                    val data = json.optJSONObject("data") ?: return@use null
                    // 完成：finished_at > 0 或 status/task_status == 2
                    val finished = data.optLong("finished_at") > 0 ||
                        data.optInt("status") == 2 ||
                        data.optInt("task_status") == 2
                    if (!finished) return@use null
                    data.optJSONObject("save_as")
                        ?.optJSONArray("save_as_top_fids")
                        ?.optString(0)
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (savedFid != null) return@withContext savedFid
            delay(1000)
        }
        null
    }
    // ---------- 网盘空间详情 ----------

    /** 网盘空间详情（/1/clouddrive/member：total_capacity / use_capacity） */
    suspend fun getQuota(cookie: String): QuotaInfo? = withContext(Dispatchers.IO) {
        val url = "https://drive-pc.quark.cn/1/clouddrive/member?pr=ucpro&fr=pc&fetch_subscribe=true&_ch=home"
        runCatching {
            val response = client.newCall(get(url, cookie)).execute()
            val body = response.use { it.body?.string() ?: return@runCatching null }
            val data = JSONObject(body).optJSONObject("data") ?: return@runCatching null
            QuotaInfo(
                used = data.optLong("use_capacity"),
                total = data.optLong("total_capacity")
            )
        }.getOrNull()
    }

    // ---------- 下载直链 ----------

    /**
     * 刷新会话 Cookie（对应 AList quark_uc refreshPuus，修复 AlistGo/alist#830）：
     * 剥离 __puus 后请求任意接口（/config），服务端会在 Set-Cookie 中重新下发 __puus/__pus。
     * @return 合并了最新 __puus/__pus 的 Cookie；失败返回 null（调用方应回退原 Cookie）。
     */
    suspend fun refreshSession(cookie: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(QuarkConstants.CONFIG_URL)
            .header("Cookie", QuarkCookieUtil.withoutPuus(cookie))
            .header("User-Agent", QuarkConstants.API_USER_AGENT)
            .header("Referer", QuarkConstants.DOWNLOAD_REFERER)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val merged = QuarkCookieUtil.mergeFromSetCookies(cookie, resp.headers("Set-Cookie"))
                if (merged != cookie) cookieSink?.invoke(merged)
                merged
            }
        }.getOrNull()
    }

    /** 6.1 获取下载直链 */
    suspend fun getDownloadLink(fid: String, cookie: String): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("fids", JSONArray().put(fid)).toString()
        val request = postJson(QuarkConstants.DOWNLOAD_URL, cookie, body)
        val response = client.newCall(request).execute()
        val bodyStr = response.use {
            mergeCookieFromResponse(request, it)
            it.body?.string() ?: throw QuarkApiException("获取下载链接失败：响应为空")
        }
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            throw QuarkApiException("响应解析失败")
        }
        if (json.optInt("status") != 200 && json.optInt("code") != 0) {
            // 失败响应无 status 字段（默认0），用 code 识别（如 21001 file not found）
            throw QuarkApiException(
                json.optString("message").ifBlank { "获取下载链接失败" },
                json.optInt("code")
            )
        }
        val array = json.optJSONArray("data") ?: throw QuarkApiException("响应缺少 data")
        if (array.length() == 0) throw QuarkApiException("未返回下载链接")
        val item = array.optJSONObject(0) ?: throw QuarkApiException("未返回下载链接")
        DownloadLink(
            fid = item.optString("fid"),
            filename = item.optString("file_name").ifEmpty { item.optString("filename") },
            downloadUrl = item.optString("download_url"),
            size = item.optLong("size")
        )
    }

    /** 6.2 删除文件（取链成功后清理临时转存；对齐抓包：action_type=2 + filelist + exclude_fids）
     *  返回异步 task_id（删除为异步任务，无需轮询；失败返回 null）。
     */
    suspend fun deleteFile(fid: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action_type", 2)
            .put("filelist", JSONArray().put(fid))
            .put("exclude_fids", JSONArray())
            .toString()
        val request = postJson(QuarkConstants.DELETE_URL, cookie, body)
        parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
    }

    // ---------- 云盘文件管理 ----------

    /** 重命名文件（云盘功能抓包：POST file/rename） */
    suspend fun renameFile(fid: String, newName: String, cookie: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("fid", fid)
                .put("file_name", newName)
                .toString()
            val request = postJson(QuarkConstants.RENAME_URL, cookie, body)
            runCatching {
                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optInt("status") == 200
                }
            }.getOrDefault(false)
        }

    /** 移动文件（云盘功能抓包：action_type=1 + to_pdir_fid + filelist）；返回 task_id */
    suspend fun moveFile(fid: String, toPdirFid: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("action_type", 1)
                .put("to_pdir_fid", toPdirFid)
                .put("filelist", JSONArray().put(fid))
                .put("exclude_fids", JSONArray())
                .toString()
            val request = postJson(QuarkConstants.MOVE_URL, cookie, body)
            parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
        }

    /**
     * 创建分享（云盘功能抓包：POST /1/clouddrive/share）。
     * 注意：分享创建是**异步任务**——响应只有 data.task_id，必须轮询 /1/clouddrive/task 直到完成拿到 share_id。
     * @param urlType 1=链接无提取码 2=链接+提取码
     * @param expiredType 1=永久 2=一天 3=七天 4=三十天
     * @return 分享 share_id
     */
    suspend fun createShare(
        fidList: List<String>,
        title: String,
        urlType: Int,
        passcode: String,
        expiredType: Int,
        cookie: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("fid_list", JSONArray().apply { fidList.forEach { put(it) } })
            .put("title", title.ifBlank { "分享文件" })
            .put("url_type", urlType)
            .apply { if (passcode.isNotBlank()) put("passcode", passcode) }
            .put("expired_type", expiredType)
            .put("support_error_code", JSONArray().put("41060"))
            .toString()
        val request = postJson(QuarkConstants.SHARE_CREATE_URL, cookie, body)
        // 1) 创建分享 → task_id（异步，须轮询等待完成）
        val taskId = parseData(request) { data ->
            data.optString("task_id").takeIf { it.isNotBlank() }
        } ?: return@withContext null
        // 2) 轮询 task 直到完成，取 share_id（官方响应 status=2 + share_id）
        pollShareTask(taskId, cookie)
    }

    /** 轮询分享创建任务（GET /1/clouddrive/task），返回 share_id；超时返回 null */
    private suspend fun pollShareTask(taskId: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val url = "${QuarkConstants.TASK_URL}&task_id=${URLEncoder.encode(taskId, "UTF-8")}&retry_index=0"
            for (i in 0 until 15) {
                val shareId = runCatching {
                    client.newCall(get(url, cookie)).execute().use { resp ->
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        if (json.optInt("status") != 200) return@use null
                        val data = json.optJSONObject("data") ?: return@use null
                        val finished = data.optLong("finished_at") > 0 || data.optInt("status") == 2
                        if (!finished) return@use null
                        data.optString("share_id").takeIf { it.isNotBlank() }
                    }
                }.getOrNull()
                if (shareId != null) return@withContext shareId
                delay(1000)
            }
            null
        }

    /** 查询分享信息（云盘功能抓包：POST share/password body={share_id} → 链接/提取码/标题） */
    suspend fun getShareInfo(shareId: String, cookie: String): ShareInfo? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("share_id", shareId).toString()
        val request = postJson(QuarkConstants.SHARE_INFO_URL, cookie, body)
        parseData(request) { data ->
            ShareInfo(
                shareUrl = data.optString("share_url"),
                passcode = data.optString("passcode"),
                pwdId = data.optString("pwd_id"),
                title = data.optString("title"),
                expiredType = data.optInt("expired_type")
            )
        }
    }

    // ---------- 请求构造与响应解析 ----------

    private fun get(url: String, cookie: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", QuarkConstants.API_USER_AGENT)
            .get()
            .build()

    private fun postJson(url: String, cookie: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", QuarkConstants.API_USER_AGENT)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

    private fun <T> parseData(request: Request, parser: (JSONObject) -> T): T {
        val response = client.newCall(request).execute()
        val body = response.use {
            mergeCookieFromResponse(request, it)
            it.body?.string() ?: throw QuarkApiException("请求失败：响应为空")
        }
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw QuarkApiException("响应解析失败")
        }
        if (json.optInt("status") != 200) {
            // 透传服务端 message，如「提取码错误」「分享已失效」等
            throw QuarkApiException(json.optString("message").ifBlank { "请求失败" })
        }
        return parser(json.optJSONObject("data") ?: throw QuarkApiException("响应缺少 data"))
    }

    /** 从响应 Set-Cookie 合并 __puus/__pus 回原 Cookie 并回调 cookieSink（保持会话新鲜，对齐 AList requestWithCookie） */
    private fun mergeCookieFromResponse(request: Request, response: okhttp3.Response) {
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isEmpty()) return
        val original = request.header("Cookie").orEmpty()
        if (original.isBlank()) return
        val merged = QuarkCookieUtil.mergeFromSetCookies(original, setCookies)
        if (merged != original) cookieSink?.invoke(merged)
    }
}
