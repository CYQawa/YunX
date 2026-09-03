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
import com.yunx.app.data.network.model.PlayLink
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
 * UC Cookie 工具：合并/剥离 __puus、__pus（与夸克共用，对应 AList pkg/cookie）。
 * __puus 约 3 小时过期，是取链接口（/file/download 等）必须携带的有效会话字段。
 */
object UCCookieUtil {
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
 * UC 网盘 API 封装（OkHttp）：账号验证 + 分享解析 + 下载直链。
 * 与夸克 API 结构一致，仅域名/UA/pr 参数不同。
 */
class UCApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    /**
     * Cookie 回写接收器（推荐由 UCAccountRepository 注入并落库）：
     * 每次响应把 Set-Cookie 合并后的最新 Cookie 回调，保持 __puus/__pus 始终新鲜。
     */
    var cookieSink: ((String) -> Unit)? = null

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ---------- 账号 ----------

    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(UCConstants.ACCOUNT_INFO_URL)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                if (json.optBoolean("success", false)) {
                    json.optJSONObject("data")
                        ?.optString("nickname")
                        ?.takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull()
    }

    // ---------- 分享解析 ----------

    suspend fun getShareToken(shareId: String, pwd: String?, cookie: String): ShareToken? = withContext(Dispatchers.IO) {
        // 官方抓包：body 为 pwd_id/passcode/share_for_transfer（用于转存/下载场景）
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("passcode", pwd ?: "")
            .put("share_for_transfer", true)
            .toString()
        val request = postJson(UCConstants.SHARE_TOKEN_URL, cookie, body)
        parseData(request) { data ->
            ShareToken(
                stoken = data.optString("stoken"),
                title = data.optString("title"),
                firstFid = data.optString("first_fid")
            )
        }
    }

    /**
     * 获取分享文件列表（sharepage/v2/detail，UC 官方为 POST + JSON body）。
     * 官方抓包：body 携带 pwd_id/passcode/page/size/fetch_banner 等，不携带 stoken；
     * 进入子目录时 body 追加 pdir_fid。
     */
    /**
     * 获取转存分享文件列表（transfer_share/detail，官方下载流程）。
     * GET + query 携带 stoken → 返回的 share_fid_token 与 stoken 绑定，download 才能通过校验。
     */
    suspend fun getTransferShareFiles(
        shareId: String,
        stoken: String,
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 50
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(UCConstants.TRANSFER_SHARE_DETAIL_URL)
            append("&pwd_id=").append(shareId)
            append("&pdir_fid=").append(pdirFid)
            append("&fetch_file_list=1")
            append("&passcode=")
            append("&_page=").append(page)
            append("&_size=").append(size)
            append("&_fetch_total=1")
            append("&_fetch_task=1")
            append("&_fetch_share=1")
            append("&_sort=")
            append("&stoken=").append(URLEncoder.encode(stoken, "UTF-8"))
        }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Origin", "https://fast.uc.cn")
            .header("Referer", "https://fast.uc.cn/")
            .get()
            .build()
        parseData(request) { data ->
            // 兼容 data.list 或 data.detail_info.list 两种结构
            val array = data.optJSONArray("list")
                ?: data.optJSONObject("detail_info")?.optJSONArray("list")
                ?: JSONArray()
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

    suspend fun getShareFiles(
        shareId: String,
        pwd: String?,
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 50
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("passcode", pwd ?: "")
            .put("force", 0)
            .put("page", page)
            .put("size", size)
            .put("fetch_banner", 1)
            .put("fetch_share", 1)
            .put("fetch_total", 1)
            .put("sort", "file_type:asc,file_name:asc")
            .put("banner_platform", "other")
            .put("web_platform", "windows")
            .put("fetch_error_background", 1)
        // 子目录时追加 pdir_fid（根目录官方不传）
        if (pdirFid.isNotBlank() && pdirFid != UCConstants.DEFAULT_PDIR_FID) {
            body.put("pdir_fid", pdirFid)
        }
        val request = Request.Builder()
            .url("${UCConstants.SHARE_DETAIL_URL}&ve=2.5.20")
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Origin", "https://drive.uc.cn")
            .header("Referer", "https://drive.uc.cn/")
            .header("Content-Type", "application/json;charset=UTF-8")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        parseData(request) { data ->
            // UC v2/detail：文件列表在 data.detail_info.list（不是 data.list）
            val detailInfo = data.optJSONObject("detail_info")
            val array = detailInfo?.optJSONArray("list") ?: JSONArray()
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

    suspend fun getFileList(
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 100
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val url = "${UCConstants.FILE_URL}&pdir_fid=$pdirFid&page=$page&size=$size"
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

    suspend fun createFolder(name: String, parentFid: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("pdir_fid", parentFid)
                .put("file_name", name)
                .put("dir_path", "")
                .put("dir_init_lock", false)
                .toString()
            val request = postJson(UCConstants.FILE_URL, cookie, body)
            parseData(request) { data -> data.optString("fid") }
        }

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
        val request = postJson(UCConstants.SAVE_URL, cookie, body)
        parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
    }

    suspend fun pollTask(taskId: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val url = "${UCConstants.TASK_URL}&task_id=${URLEncoder.encode(taskId, "UTF-8")}&retry_index=0"
        for (i in 0 until 10) {
            val savedFid = runCatching {
                client.newCall(get(url, cookie)).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("status") != 200) return@use null
                    val data = json.optJSONObject("data") ?: return@use null
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

    // ---------- 下载直链 ----------

    /**
     * 刷新会话 Cookie（对应 AList refreshPuus，修复与夸克同源的 #830 类缺陷）：
     * 剥离 __puus 后请求任意接口（/config），服务端会在 Set-Cookie 中重新下发 __puus/__pus。
     * @return 合并了最新 __puus/__pus 的 Cookie；失败返回 null（调用方应回退原 Cookie）。
     */
    suspend fun refreshSession(cookie: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(UCConstants.CONFIG_URL)
            .header("Cookie", UCCookieUtil.withoutPuus(cookie))
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Referer", UCConstants.DOWNLOAD_REFERER)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val merged = UCCookieUtil.mergeFromSetCookies(cookie, resp.headers("Set-Cookie"))
                if (merged != cookie) cookieSink?.invoke(merged)
                merged
            }
        }.getOrNull()
    }

    /**
     * UC 官方下载流程（抓包）：不需要先转存！
     * POST file/download?entry=ft&fr=pc&pr=UCBrowser
     * body: {"fids":[分享fid],"pwd_id":短码,"stoken":token接口返回,"fids_token":[分享fid_token]}
     */
    suspend fun getShareDownloadLink(
        fid: String,
        fidToken: String,
        stoken: String,
        pwdId: String,
        cookie: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("fids", JSONArray().put(fid))
            .put("pwd_id", pwdId)
            .put("stoken", stoken)
            .put("fids_token", JSONArray().put(fidToken))
            .toString()
        val request = postJson(UCConstants.DOWNLOAD_URL, cookie, body)
        val response = client.newCall(request).execute()
        val bodyStr = response.use {
            it.body?.string() ?: throw QuarkApiException("获取下载链接失败：响应为空")
        }
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            throw QuarkApiException("响应解析失败")
        }
        if (json.optInt("status") != 200) {
            throw QuarkApiException(json.optString("message").ifBlank { "获取下载链接失败" })
        }
        val item = json.optJSONArray("data")?.optJSONObject(0)
            ?: throw QuarkApiException("未返回下载链接")
        DownloadLink(
            fid = item.optString("fid"),
            filename = item.optString("file_name").ifEmpty { item.optString("filename") },
            downloadUrl = item.optString("download_url"),
            size = item.optLong("size")
        )
    }
suspend fun getDownloadLink(fid: String, cookie: String): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("fids", JSONArray().put(fid)).toString()
        val request = postJson(UCConstants.DOWNLOAD_URL, cookie, body)
        val response = client.newCall(request).execute()
        val bodyStr = response.use {
            it.body?.string() ?: throw QuarkApiException("获取下载链接失败：响应为空")
        }
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            throw QuarkApiException("响应解析失败")
        }
        if (json.optInt("status") != 200 && json.optInt("code") != 0) {
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

    // ---------- 云盘文件管理（UC 网盘功能） ----------

    /** 网盘空间详情（/1/clouddrive/member：total_capacity / use_capacity，CLOUD_UA） */
    suspend fun getQuota(cookie: String): QuotaInfo? = withContext(Dispatchers.IO) {
        val url = "https://pc-api.uc.cn/1/clouddrive/member?pr=UCBrowser&fr=pc&fetch_subscribe=true&_ch=home"
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", UCConstants.CLOUD_UA)
                .header("Origin", "https://drive.uc.cn")
                .header("Referer", "https://drive.uc.cn/")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.use { it.body?.string() ?: return@runCatching null }
            val data = JSONObject(body).optJSONObject("data") ?: return@runCatching null
            QuotaInfo(
                used = data.optLong("use_capacity"),
                total = data.optLong("total_capacity")
            )
        }.getOrNull()
    }

    /** 云盘下载直链（抓包：个人云盘文件用 ?pr=UCBrowser&fr=pc&sys=win32&ve=1.6.1，非 entry=ft 分享通道） */
    suspend fun cloudGetDownloadLink(fid: String, cookie: String): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("fids", JSONArray().put(fid)).toString()
        val request = Request.Builder()
            .url(UCConstants.CLOUD_DOWNLOAD_URL)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.CLOUD_UA)
            .header("Origin", "https://drive.uc.cn")
            .header("Referer", "https://drive.uc.cn/")
            .header("Content-Type", "application/json;charset=UTF-8")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val bodyStr = response.use {
            it.body?.string() ?: throw QuarkApiException("获取下载链接失败：响应为空")
        }
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            throw QuarkApiException("响应解析失败")
        }
        if (json.optInt("status") != 200 && json.optInt("code") != 0) {
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

        /**
     * 分享视频预览（原画直链，绕过非会员视频下载被换成宣传片的问题）。
     * GET share/sharepage/video_preview？pwd_id/stoken/fid/fid_token →
     * data.play_info.url（原画 OSS 直链，走播放回调 checkplay 不换片）+ size（原画大小，可校验）。
     * 仅对分享态视频有意义；链接约 3 小时有效（x-ttl=10800）。
     */
    suspend fun getVideoPreview(
        pwdId: String,
        stoken: String,
        fid: String,
        fidToken: String,
        cookie: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(UCConstants.VIDEO_PREVIEW_URL)
            append("?pr=UCBrowser&fr=h5")
            append("&pwd_id=").append(URLEncoder.encode(pwdId, "UTF-8"))
            append("&stoken=").append(URLEncoder.encode(stoken, "UTF-8"))
            append("&fid=").append(URLEncoder.encode(fid, "UTF-8"))
            append("&fid_token=").append(URLEncoder.encode(fidToken, "UTF-8"))
        }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Origin", UCConstants.WEB_ORIGIN)
            .header("Referer", UCConstants.DOWNLOAD_REFERER)
            .header("Content-Type", "application/json")
            .get()
            .build()
        runCatching {
            val resp = client.newCall(request).execute()
            val json = JSONObject(resp.use { it.body?.string() } ?: "{}")
            if (json.optInt("status") != 200 && json.optInt("code") != 0) return@runCatching null
            val data = json.optJSONObject("data") ?: return@runCatching null
            val playInfo = data.optJSONObject("play_info") ?: return@runCatching null
            val directUrl = playInfo.optString("url").takeIf { it.isNotBlank() } ?: return@runCatching null
            DownloadLink(
                fid = fid,
                filename = "",
                downloadUrl = directUrl,
                size = playInfo.optLong("size")
            )
        }.getOrNull()
    }

    /**
     * UC 转码播放流（绕过非会员视频下载被换成宣传片的问题）。
     * POST file/v2/play/project → data.video_list[].video_info.url（m3u8/fmp4）。
     * 仅对视频有意义；返回首个非空播放地址 + 其清晰度。
     * 先试带 pr/fr 的主路径；失败则用裸路径重试（Alist getTranscodingLink 方式，对 UC 也可通）。
     */
    suspend fun getPlayLink(fid: String, cookie: String): PlayLink? = withContext(Dispatchers.IO) {
        playProject(UCConstants.PLAY_URL, fid, cookie)
            ?: playProject("${UCConstants.API_BASE}/1/clouddrive/file/v2/play/project", fid, cookie)
    }

    private fun playProject(url: String, fid: String, cookie: String): PlayLink? {
        val body = JSONObject()
            .put("fid", fid)
            .put("resolutions", "low,normal,high,super,2k,4k")
            .put("supports", "fmp4_av,m3u8,dolby_vision")
            .toString()
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Origin", UCConstants.WEB_ORIGIN)
            .header("Referer", UCConstants.DOWNLOAD_REFERER)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return runCatching {
            val resp = client.newCall(request).execute()
            val json = JSONObject(resp.use { it.body?.string() } ?: "{}")
            if (json.optInt("status") != 200 && json.optInt("code") != 0) return@runCatching null
            val list = json.optJSONObject("data")?.optJSONArray("video_list") ?: return@runCatching null
            for (i in 0 until list.length()) {
                val info = list.optJSONObject(i)?.optJSONObject("video_info") ?: continue
                val u = info.optString("url").takeIf { it.isNotBlank() } ?: continue
                return@runCatching PlayLink(
                    url = u,
                    resolution = info.optString("resolution"),
                    format = info.optString("format"),
                    isHls = u.contains(".m3u8") || info.optString("format").contains("m3u8", true)
                )
            }
            null
        }.getOrNull()
    }

    /** 删除文件（抓包：action_type=2 + filelist + exclude_fids）；返回 task_id */
    suspend fun deleteFile(fid: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("action_type", 2)
                .put("filelist", JSONArray().put(fid))
                .put("exclude_fids", JSONArray())
                .toString()
            val request = postJson(UCConstants.DELETE_URL, cookie, body)
            parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
        }

    /** 云盘文件列表（抓包 /1/clouddrive/file/sort，pdir_fid=0 根目录） */
    suspend fun listCloudFiles(
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 50
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(UCConstants.CLOUD_FILE_SORT_URL)
            append("&pdir_fid=").append(pdirFid)
            append("&_page=").append(page)
            append("&_size=").append(size)
            append("&_fetch_total=1")
            append("&_fetch_sub_dirs=0")
            append("&_sort=file_type%3Aasc%2Cupdated_at%3Adesc")
        }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.CLOUD_UA)
            .header("Origin", "https://drive.uc.cn")
            .header("Referer", "https://drive.uc.cn/")
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
    }

    /** 重命名（抓包：POST file/rename） */
    suspend fun renameFile(fid: String, newName: String, cookie: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("fid", fid)
                .put("file_name", newName)
                .toString()
            val request = postJson(UCConstants.RENAME_URL, cookie, body)
            runCatching {
                client.newCall(request).execute().use { response ->
                    JSONObject(response.body?.string() ?: "{}").optInt("status") == 200
                }
            }.getOrDefault(false)
        }

    /** 移动（抓包：action_type=1 + to_pdir_fid + filelist）；返回 task_id */
    suspend fun moveFile(fid: String, toPdirFid: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("action_type", 1)
                .put("to_pdir_fid", toPdirFid)
                .put("filelist", JSONArray().put(fid))
                .put("exclude_fids", JSONArray())
                .toString()
            val request = postJson(UCConstants.MOVE_URL, cookie, body)
            parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
        }

    /** 创建分享（抓包：POST /1/clouddrive/share，url_type 1=无提取码 2=带提取码，expired_type 1永久/2一天/3七天/4三十天）。
 * 注意：分享创建是**异步任务**——响应只有 data.task_id，必须轮询 /1/clouddrive/task 直到完成拿到 share_id。 */
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
            .put("expired_type", expiredType)
            .put("public_search", if (passcode.isNotBlank()) 0 else 1)
            .apply { if (passcode.isNotBlank()) put("passcode", passcode) }
            .toString()
        val request = postJson(UCConstants.SHARE_CREATE_URL, cookie, body)
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
            val url = "${UCConstants.TASK_URL}&task_id=${URLEncoder.encode(taskId, "UTF-8")}&retry_index=0"
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

    /** 查询分享信息（抓包：POST share/password body={share_id} → 链接/提取码/标题） */
    suspend fun getShareInfo(shareId: String, cookie: String): ShareInfo? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("share_id", shareId).toString()
        val request = postJson(UCConstants.SHARE_INFO_URL, cookie, body)
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
            .header("User-Agent", UCConstants.USER_AGENT)
            .get()
            .build()

    private fun postJson(url: String, cookie: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
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
        val merged = UCCookieUtil.mergeFromSetCookies(original, setCookies)
        if (merged != original) cookieSink?.invoke(merged)
    }
}
