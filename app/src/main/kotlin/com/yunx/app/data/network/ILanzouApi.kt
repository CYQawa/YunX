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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 蓝奏优享账号信息。 */
data class ILanzouAccountInfo(val userId: String, val account: String, val nickname: String)

/** 登录失效标记。 */
private class ILanzouLoginExpired : Exception("蓝奏优享登录已过期，请重新登录")

/** 访问验证（409 + text/html）标记。 */
private class ILanzouAccessChallenge : Exception()

/**
 * 蓝奏云优享版 API 封装（文档 §1-§4）。
 * 认证参数 appToken + uuid 走 URL query；appToken 特殊编码（%3A 还原为冒号）。
 */
class ILanzouApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    private val client get() = clientProvider()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ---------- 认证 ----------

    /** 获取设备标识（文档 §2.1）。 */
    suspend fun getUuid(): String = withContext(Dispatchers.IO) {
        val url = buildUrl(
            ILanzouConstants.GET_UUID, uuid = "", appToken = null, extra = true, params = emptyList()
        )
        val json = exec(Request.Builder().url(url).headers(commonHeaders(false)).get().build())
        val uuid = json.optString("uuid")
        if (!Regex("^[A-Za-z0-9_-]{8,128}$").matches(uuid)) {
            throw IllegalStateException("蓝奏优享未返回有效设备标识")
        }
        uuid
    }

    /** 账号密码登录（文档 §2.2），返回 appToken。 */
    suspend fun login(loginName: String, loginPwd: String, uuid: String): String = withContext(Dispatchers.IO) {
        val name = loginName.trim()
        if (name.isEmpty() || loginPwd.isEmpty()) throw IllegalStateException("请输入蓝奏优享账号和密码")
        if (name.length > 254) throw IllegalStateException("蓝奏优享用户名过长")
        if (loginPwd.length > 256) throw IllegalStateException("蓝奏优享密码过长")
        val url = buildUrl(ILanzouConstants.LOGIN, uuid, appToken = null, extra = true, params = emptyList())
        val body = JSONObject().put("loginName", name).put("loginPwd", loginPwd).toString()
        val json = execLogin(
            Request.Builder().url(url).headers(commonHeaders(true)).post(body.toRequestBody(jsonMediaType)).build()
        )
        val token = json.optJSONObject("data")?.optString("appToken").orEmpty()
        if (token.length < 16 || !Regex("^[A-Za-z0-9._~+/=:!%&?\\-]+$").matches(token)) {
            throw IllegalStateException("蓝奏优享未返回登录凭据，请切换网页登录")
        }
        token
    }

    /** 账号信息（文档 §2.3）。 */
    suspend fun fetchAccountInfo(appToken: String, uuid: String): ILanzouAccountInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(ILanzouConstants.ACCOUNT_MAP, uuid, appToken, extra = true, params = emptyList())
            val json = exec(Request.Builder().url(url).headers(commonHeaders(false)).get().build())
            val map = json.optJSONObject("map") ?: return@runCatching null
            val userId = firstNonBlank(map.optString("userId"), map.optString("userId"))
                .ifBlank { map.optInt("userId", 0).takeIf { it != 0 }?.toString().orEmpty() }
            if (userId.isBlank()) return@runCatching null
            val nickname = firstNonBlank(
                map.optString("nickName"), map.optString("nickname"), map.optString("account")
            ).ifBlank { "蓝奏优享用户" }
            ILanzouAccountInfo(userId, map.optString("account"), nickname)
        }.getOrNull()
    }

    /** 容量信息（文档 §2.3）：KiB → 字节。 */
    suspend fun getQuota(appToken: String, uuid: String): QuotaInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(ILanzouConstants.ACCOUNT_MAP, uuid, appToken, extra = true, params = emptyList())
            val json = exec(Request.Builder().url(url).headers(commonHeaders(false)).get().build())
            val map = json.optJSONObject("map") ?: return@runCatching null
            val used = map.optLong("usedSize", 0L) * 1024L
            val total = (map.optLong("totalSize", 0L) + map.optLong("vipSize", 0L) + map.optLong("rewardSize", 0L)) * 1024L
            QuotaInfo(used = used, total = total)
        }.getOrNull()
    }

    // ---------- 文件管理 ----------

    /** 文件列表（文档 §3.1）：自动翻页，合并文件夹与文件。 */
    suspend fun listFiles(appToken: String, uuid: String, folderId: String): List<ShareFile> = withContext(Dispatchers.IO) {
        val all = mutableListOf<ShareFile>()
        val seen = mutableSetOf<String>()
        var page = 1
        while (page <= 1000) {
            val params = listOf(
                "offset" to page.toString(),
                "limit" to ILanzouConstants.PAGE_SIZE.toString(),
                "folderId" to folderId,
                "type" to "0"
            )
            val url = buildUrl(ILanzouConstants.FILE_LIST, uuid, appToken, extra = true, params = params)
            val json = exec(Request.Builder().url(url).headers(commonHeaders(false)).get().build())
            val list = json.optJSONArray("list")
            if (list == null || list.length() == 0) break
            var added = 0
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val file = parseItem(item)
                if (file != null && seen.add(file.fid)) {
                    all.add(file)
                    added++
                }
            }
            if (added == 0) throw IllegalStateException("蓝奏优享分页重复或不完整，请重新打开目录")
            val totalPage = json.optInt("totalPage", 0)
            if (totalPage > 0 && page >= totalPage) break
            if (totalPage <= 0 && list.length() < ILanzouConstants.PAGE_SIZE) break
            page++
        }
        all
    }

    /** 新建目录（文档 §3.2）。 */
    suspend fun createDir(appToken: String, uuid: String, parentId: String, name: String): String =
        withContext(Dispatchers.IO) {
            val url = buildUrl(ILanzouConstants.FOLDER_SAVE, uuid, appToken, extra = true, params = emptyList())
            val body = JSONObject()
                .put("folderDesc", "")
                .put("folderId", parentId.ifBlank { ILanzouConstants.ROOT_FOLDER_ID })
                .put("folderName", name)
                .toString()
            val json = exec(Request.Builder().url(url).headers(commonHeaders(true)).post(body.toRequestBody(jsonMediaType)).build())
            val id = json.optJSONArray("list")?.optJSONObject(0)?.let {
                firstNonBlank(it.optString("id"), it.optInt("id", 0).toString())
            }.orEmpty()
            if (id.isBlank() || id == "0") throw IllegalStateException("蓝奏优享未返回新文件夹信息，请刷新确认")
            id
        }

    /** 目录重命名（文档 §3.3）。 */
    suspend fun renameFolder(appToken: String, uuid: String, folderId: String, newName: String) = withContext(Dispatchers.IO) {
        val url = buildUrl(ILanzouConstants.FOLDER_EDIT, uuid, appToken, extra = true, params = emptyList())
        val body = JSONObject()
            .put("folderDesc", "")
            .put("folderId", folderId)
            .put("folderName", newName)
            .toString()
        exec(Request.Builder().url(url).headers(commonHeaders(true)).post(body.toRequestBody(jsonMediaType)).build())
    }

    /** 文件重命名（文档 §3.3）。 */
    suspend fun renameFile(appToken: String, uuid: String, fileId: String, newName: String) = withContext(Dispatchers.IO) {
        val url = buildUrl(ILanzouConstants.FILE_EDIT, uuid, appToken, extra = true, params = emptyList())
        val body = JSONObject()
            .put("fileDesc", "")
            .put("fileId", fileId)
            .put("fileName", newName)
            .toString()
        exec(Request.Builder().url(url).headers(commonHeaders(true)).post(body.toRequestBody(jsonMediaType)).build())
    }

    /** 移动（文档 §3.4）：folderIds / fileIds 逗号连接。 */
    suspend fun moveEntries(
        appToken: String,
        uuid: String,
        folderIds: List<String>,
        fileIds: List<String>,
        targetId: String
    ) = withContext(Dispatchers.IO) {
        val url = buildUrl(ILanzouConstants.FOLDER_MOVE, uuid, appToken, extra = true, params = emptyList())
        val body = JSONObject()
            .put("folderIds", folderIds.joinToString(","))
            .put("fileIds", fileIds.joinToString(","))
            .put("targetId", targetId.ifBlank { ILanzouConstants.ROOT_FOLDER_ID })
            .toString()
        exec(Request.Builder().url(url).headers(commonHeaders(true)).post(body.toRequestBody(jsonMediaType)).build())
    }

    /** 删除（文档 §3.5）。 */
    suspend fun deleteEntries(
        appToken: String,
        uuid: String,
        folderIds: List<String>,
        fileIds: List<String>
    ) = withContext(Dispatchers.IO) {
        val url = buildUrl(ILanzouConstants.FILE_DELETE, uuid, appToken, extra = true, params = emptyList())
        val body = JSONObject()
            .put("folderIds", folderIds.joinToString(","))
            .put("fileIds", fileIds.joinToString(","))
            .put("status", 0)
            .toString()
        exec(Request.Builder().url(url).headers(commonHeaders(true)).post(body.toRequestBody(jsonMediaType)).build())
    }

    // ---------- 下载（文档 §4.1） ----------

    /** 获取下载地址：302 Location 或 JSON url / data.url；expectedSize 未知（0，交给下载引擎探测）。 */
    suspend fun getDownloadLink(
        appToken: String,
        uuid: String,
        fileId: String,
        userId: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val encTs = aesHex(ts.toString())
        // 参考实现（wenxi ilanzou.dart）：downloadId = encrypt('<fileId>|<userId>')，auth = encrypt('<fileId>|<毫秒时间戳>')
        val params = listOf(
            "enable" to "1",
            "downloadId" to aesHex("$fileId|$userId"),
            "auth" to aesHex("$fileId|$ts"),
            "timestamp" to encTs
        )
        // 该接口不带 extra=2，且 timestamp 与 auth 使用同一 ts
        val url = buildUrl(ILanzouConstants.FILE_REDIRECT, uuid, appToken, extra = false, params = params, timestamp = encTs)
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        noRedirectClient.newCall(Request.Builder().url(url).headers(commonHeaders(false)).get().build()).execute().use { resp ->
            val location = resp.header("Location")
            if (resp.code in 300..399 && !location.isNullOrBlank()) {
                // Location 可能是相对路径，按 API 基址解析为绝对地址后返回
                val resolved = runCatching {
                    java.net.URI(ILanzouConstants.API_BASE).resolve(location).toString()
                }.getOrDefault(location)
                return@withContext DownloadLink(
                    fid = ILanzouConstants.FILE_PREFIX + fileId,
                    filename = "",
                    downloadUrl = resolved,
                    size = 0L
                )
            }
            val body = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IllegalStateException("蓝奏优享未返回下载地址")
            checkCode(json)
            val link = firstNonBlank(
                json.optString("url"),
                json.optJSONObject("data")?.optString("url").orEmpty()
            )
            if (link.isBlank()) throw IllegalStateException("蓝奏优享未返回下载地址，请检查文件权限")
            DownloadLink(
                fid = ILanzouConstants.FILE_PREFIX + fileId,
                filename = "",
                downloadUrl = link,
                size = 0L
            )
        }
    }

    // ---------- 内部 ----------

    private fun parseItem(item: JSONObject): ShareFile? {
        val fileType = item.optInt("fileType", 0)
        return if (fileType == 2) {
            val fid = firstNonBlank(item.optString("folderId"), item.optInt("folderId", 0).toString())
            if (fid.isBlank() || fid == "0") null
            else ShareFile(
                fid = ILanzouConstants.FOLDER_PREFIX + fid,
                fname = item.optString("folderName"),
                fsize = 0L,
                isdir = true,
                pdirFid = "",
                fidToken = "",
                modifyTime = firstNonBlank(item.optString("updTime"), item.optString("addTime"))
            )
        } else {
            val fid = firstNonBlank(item.optString("fileId"), item.optInt("fileId", 0).toString())
            if (fid.isBlank() || fid == "0") null
            else ShareFile(
                fid = ILanzouConstants.FILE_PREFIX + fid,
                fname = item.optString("fileName"),
                fsize = item.optLong("fileSize", 0L) * 1024L,
                isdir = false,
                pdirFid = "",
                fidToken = "",
                modifyTime = firstNonBlank(item.optString("updTime"), item.optString("addTime"))
            )
        }
    }

    private fun buildUrl(
        path: String,
        uuid: String,
        appToken: String?,
        extra: Boolean,
        params: List<Pair<String, String>>,
        timestamp: String = aesHex(System.currentTimeMillis().toString())
    ): String {
        val parts = mutableListOf<Pair<String, String>>()
        parts += "uuid" to uuid
        parts += "devType" to ILanzouConstants.DEV_TYPE
        parts += "devCode" to uuid
        parts += "devModel" to ILanzouConstants.DEV_MODEL
        parts += "devVersion" to ILanzouConstants.DEV_VERSION
        parts += "appVersion" to ""
        parts += "timestamp" to timestamp
        if (!appToken.isNullOrBlank()) parts += "appToken" to appToken
        if (extra) parts += "extra" to "2"
        parts += params
        val query = parts.joinToString("&") { (k, v) -> "$k=${encodeToken(v)}" }
        return "$path?$query"
    }

    /** appToken 特殊编码：标准 query 编码后把 %3A 还原为字面冒号。 */
    private fun encodeToken(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("%3A", ":")

    private fun commonHeaders(json: Boolean): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
            .add("User-Agent", ILanzouConstants.WEB_UA)
            .add("Origin", ILanzouConstants.WEB_SITE)
            .add("Referer", ILanzouConstants.DOWNLOAD_REFERER)
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "zh-CN,zh;q=0.9")
            // 该站 CDN/WAF（ESA + acw_tc）会对浏览器式请求放行、对“裸”HTTP 客户端返回空列表，
            // 故补全 Chrome 客户端提示头（与网页端一致），否则 share/list 恒为空。
            .add("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            .add("sec-ch-ua-mobile", "?0")
            .add("sec-ch-ua-platform", "\"Windows\"")
            .add("sec-fetch-dest", "empty")
            .add("sec-fetch-mode", "cors")
            .add("sec-fetch-site", "same-site")
        if (json) builder.add("Content-Type", "application/json; charset=utf-8")
        return builder.build()
    }

    // ---------- 分享（本站分享页协议，见 wenxi 之外由 web JS 反推：/unproved/share/list） ----------

    /**
     * 分享文件列表（匿名可用）。`folderId` 为空时返回分享根条目；
     * 传入数字目录 id 时返回该目录内容。自动翻页（页大小 60）。
     */
    suspend fun shareList(shareId: String, folderId: String?, uuid: String): List<ShareFile> =
        withContext(Dispatchers.IO) {
            val all = mutableListOf<ShareFile>()
            val seen = mutableSetOf<String>()
            var page = 1
            while (page <= 1000) {
                val params = mutableListOf<Pair<String, String>>()
                if (!folderId.isNullOrBlank()) params += "folderId" to folderId
                params += "offset" to page.toString()
                params += "limit" to ILanzouConstants.PAGE_SIZE.toString()
                val url = shareUrl(ILanzouConstants.SHARE_LIST, uuid, shareId, params)
                val json = execShare(Request.Builder().url(url).headers(commonHeaders(false)).get().build())
                val list = json.optJSONArray("list") ?: break
                if (list.length() == 0) break
                var added = 0
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val file = parseItem(item)
                    if (file != null && seen.add(file.fid)) {
                        all.add(file)
                        added++
                    }
                }
                if (added == 0) break
                val totalPage = json.optInt("totalPage", 0)
                if (totalPage > 0 && page >= totalPage) break
                if (totalPage <= 0 && list.length() < ILanzouConstants.PAGE_SIZE) break
                page++
            }
            all
        }

    /**
     * 分享文件下载取链（匿名可用）：`/unproved/file/redirect` 返回 302 Location 或 JSON url。
     * downloadId = AES('<fileId>|<userId>')、auth = AES('<fileId>|<ts>')（userId 未登录为空串）。
     */
    suspend fun shareDownload(shareId: String, fileId: String, userId: String, uuid: String): DownloadLink =
        withContext(Dispatchers.IO) {
            val ts = System.currentTimeMillis()
            val params = listOf(
                "downloadId" to aesHex("$fileId|$userId"),
                "enable" to "1",
                "devType" to ILanzouConstants.DEV_TYPE,
                "uuid" to uuid,
                "timestamp" to aesHex(ts.toString()),
                "auth" to aesHex("$fileId|$ts"),
                "shareId" to shareId
            )
            val query = params.joinToString("&") { (k, v) -> "$k=${encodeToken(v)}" }
            val url = "${ILanzouConstants.SHARE_REDIRECT}?$query"
            val noRedirectClient = client.newBuilder().followRedirects(false).build()
            noRedirectClient.newCall(
                Request.Builder().url(url).headers(commonHeaders(false)).get().build()
            ).execute().use { resp ->
                val location = resp.header("Location")
                if (resp.code in 300..399 && !location.isNullOrBlank()) {
                    val resolved = runCatching {
                        java.net.URI(ILanzouConstants.SHARE_API_BASE).resolve(location).toString()
                    }.getOrDefault(location)
                    return@withContext DownloadLink(
                        fid = ILanzouConstants.FILE_PREFIX + fileId,
                        filename = "",
                        downloadUrl = resolved,
                        size = 0L
                    )
                }
                val body = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: throw IllegalStateException("蓝奏优享未返回下载地址")
                val link = firstNonBlank(
                    json.optString("url"),
                    json.optJSONObject("data")?.optString("url").orEmpty()
                )
                if (link.isBlank()) {
                    throw IllegalStateException(firstNonBlank(json.optString("msg"), "蓝奏优享未返回下载地址，请检查文件权限"))
                }
                DownloadLink(fid = ILanzouConstants.FILE_PREFIX + fileId, filename = "", downloadUrl = link, size = 0L)
            }
        }

    /** 分享转存（需登录）：返回 transferKey（可能为空，表示同步完成）。 */
    suspend fun transferShare(
        appToken: String,
        uuid: String,
        shareId: String,
        fileIds: List<String>,
        folderIds: List<String>,
        targetFolderId: String
    ): String = withContext(Dispatchers.IO) {
        val url = buildUrl(ILanzouConstants.FILE_TRANSFER, uuid, appToken, extra = true, params = emptyList())
        val body = JSONObject()
            .put("targetFileId", fileIds.joinToString(","))
            .put("targetFolderId", folderIds.joinToString(","))
            .put("folderId", targetFolderId.ifBlank { ILanzouConstants.ROOT_FOLDER_ID })
            .put("shareId", shareId)
            .toString()
        val json = exec(Request.Builder().url(url).headers(commonHeaders(true)).post(body.toRequestBody(jsonMediaType)).build())
        firstNonBlank(
            json.optJSONObject("data")?.optString("transferKey").orEmpty(),
            json.optJSONObject("map")?.optString("transferKey").orEmpty(),
            json.optString("transferKey"),
            json.optString("data")
        )
    }

    /** 分享转存进度：map.num == 1 表示完成。 */
    suspend fun transferCount(appToken: String, uuid: String, transferKey: String): Int =
        withContext(Dispatchers.IO) {
            val url = buildUrl(
                ILanzouConstants.FILE_TRANSFER_NUM, uuid, appToken, extra = true,
                params = listOf("transferKey" to transferKey)
            )
            val json = exec(Request.Builder().url(url).headers(commonHeaders(false)).get().build())
            json.optJSONObject("map")?.optInt("num", 0) ?: 0
        }

    /** 分享专用 URL 组装：公共参数 + shareId + 业务参数（不带 appToken）。 */
    private fun shareUrl(
        path: String,
        uuid: String,
        shareId: String,
        params: List<Pair<String, String>>
    ): String {
        val parts = mutableListOf<Pair<String, String>>()
        parts += "uuid" to uuid
        parts += "devType" to ILanzouConstants.DEV_TYPE
        parts += "devCode" to uuid
        parts += "devModel" to ILanzouConstants.SHARE_DEV_MODEL
        parts += "devVersion" to ILanzouConstants.DEV_VERSION
        parts += "appVersion" to ""
        parts += "timestamp" to aesHex(System.currentTimeMillis().toString())
        parts += "extra" to "2"
        parts += "shareId" to shareId
        parts += params
        val query = parts.joinToString("&") { (k, v) -> "$k=${encodeToken(v)}" }
        return "$path?$query"
    }

    /** 分享接口执执行：非 200 时用服务端 msg（如「分享id不能为空」「参数缺失」），不当成登录失效。 */
    private fun execShare(request: Request): JSONObject {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("蓝奏优享分享请求失败（${resp.code}）")
            }
            val code = json.optInt("code", -1)
            if (code != 200) {
                throw IllegalStateException(firstNonBlank(json.optString("msg"), "蓝奏优享分享请求失败（$code）"))
            }
            return json
        }
    }

    /** 执行请求并校验业务码；409 + text/html 访问验证自动重试一次。 */
    private fun exec(request: Request): JSONObject {
        return try {
            doExec(request)
        } catch (e: ILanzouAccessChallenge) {
            // 访问验证：按文档「设置 Cookie 后最多重放一次」；无 JS 计算 acw_sc__v2，二次仍失败即提示
            try {
                doExec(request)
            } catch (retry: ILanzouAccessChallenge) {
                throw IllegalStateException("蓝奏优享的访问验证未通过，请稍后重试或使用网页登录")
            }
        }
    }

    /**
     * 登录专用执行：[exec] 对 code -1/-2 统一报「登录已过期」，但登录失败（如「用户账号不存在」「密码错误」）
     * 应原样透出服务端 msg，故此处单独处理；仍保留 409 访问验证重试。
     */
    private fun execLogin(request: Request): JSONObject {
        return try {
            doExecLogin(request)
        } catch (e: ILanzouAccessChallenge) {
            try {
                doExecLogin(request)
            } catch (retry: ILanzouAccessChallenge) {
                throw IllegalStateException("蓝奏优享的访问验证未通过，请稍后重试")
            }
        }
    }

    private fun doExecLogin(request: Request): JSONObject {
        client.newCall(request).execute().use { resp ->
            val ct = resp.header("Content-Type").orEmpty()
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 409 && ct.contains("text/html")) throw ILanzouAccessChallenge()
                throw IllegalStateException("蓝奏优享请求失败（${resp.code}）")
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                if (ct.contains("text/html")) throw ILanzouAccessChallenge()
                throw IllegalStateException("蓝奏优享请求失败（${resp.code}）")
            }
            if (json.optInt("code", -1) != 200) {
                throw IllegalStateException(
                    json.optString("msg").ifBlank { "蓝奏优享登录失败，请检查账号密码" }
                )
            }
            return json
        }
    }

    private fun doExec(request: Request): JSONObject {
        client.newCall(request).execute().use { resp ->
            val ct = resp.header("Content-Type").orEmpty()
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) throw ILanzouLoginExpired()
                if (resp.code == 409 && ct.contains("text/html")) throw ILanzouAccessChallenge()
                throw IllegalStateException("蓝奏优享请求失败（${resp.code}），请检查登录信息或稍后重试")
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                if (ct.contains("text/html")) throw ILanzouAccessChallenge()
                throw IllegalStateException("蓝奏优享请求失败（${resp.code}），请检查登录信息或稍后重试")
            }
            checkCode(json)
            return json
        }
    }

    private fun checkCode(json: JSONObject) {
        val code = json.optInt("code", -1)
        if (code == 200) return
        if (code == -1 || code == -2) throw ILanzouLoginExpired()
        throw IllegalStateException("蓝奏优享请求失败（$code），请检查登录信息或稍后重试")
    }

    private fun aesHex(plain: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val key = SecretKeySpec(ILanzouConstants.AES_KEY.toByteArray(Charsets.UTF_8), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return out.joinToString("") { "%02x".format(it) }
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}
