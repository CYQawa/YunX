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

import com.yunx.app.data.network.model.FileThumbnail
import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.upload.UploadIo
import com.yunx.app.data.upload.UploadPhase
import com.yunx.app.data.upload.UploadSource
import com.yunx.app.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 百度网盘分享解析数据（xpan/share list 响应）。
 */
data class BaiduShareList(
    val title: String,
    val shareId: String,
    val uk: String,
    val files: List<ShareFile>
)

/**
 * 百度转存结果：转存后的新 fs_id + 新路径（locatedownload 用路径）。
 */
data class BaiduTransferResult(
    val fsId: String,
    val path: String
)

/** locatedownload 返回的一条 CDN/HTTP 候选。 */
data class BaiduCdnUrl(
    val url: String,
    val rank: Int,
    val encrypt: Int
)

/** locatedownload 的完整响应。 */
data class BaiduLocatedownloadInfo(
    /** 客户端发起 locatedownload 时使用的 Unix 秒（TPDATA 字段 16）。 */
    val localTime: Long,
    /** locatedownload 服务端返回的 Unix 秒（TPDATA 字段 17）。 */
    val timestamp: Long,
    val urls: List<BaiduCdnUrl>,
    val encryptKey: BaiduEncryptKey?,
    val encryptKeyList: List<BaiduEncryptKey>,
    val sl: Long,
    val fsl: Long,
    val maxContinuousFailure: Int,
    val bakRankSliceNum: Int
) {
    /**
     * 明文 CDN 候选：HTTP Range 可以直接写盘，不需要在下载器里再做一层 AES 解密。
     * 只接受 https：应用禁止全局明文，http CDN 即使返回也会被系统直接拦下。
     */
    fun bestPlainCdnUrl(): String? {
        return urls.asSequence()
            .filter { it.encrypt == 0 && it.url.startsWith("https://", ignoreCase = true) }
            .sortedBy { it.rank }
            .firstOrNull()
            ?.url
    }
}

/** `/cms/fgid` 返回：P2P Seed 必需的文件全局 ID 与真实大小。 */
data class BaiduFgidInfo(
    val fgid: String,
    val size: Long
)

/**
 * 百度网盘 API 封装（OkHttp + Cookie 认证）：
 * 登录态 BDUSS 经 Cookie 携带；分享解析链路 share/verify → xpan/share → share/transfer → filemetas。
 * 全部基于抓包字段，错误码用 errno（0 表示成功）判定。
 */
class BaiduApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() },
    /** 百度下载方式：CDN / P2P（设置页实时生效） */
    private val downloadModeProvider: () -> String = { BaiduConstants.DOWNLOAD_MODE_CDN }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    /** bdstoken 缓存（登录态内长期有效） */
    @Volatile
    private var cachedBdstoken: String? = null

    private data class BaiduIdentity(
        val uid: String,
        val uinfo: String,
        val sk: ByteArray
    )

    /** Cookie -> UID/uinfo/sk；uinfo 只在内存里缓存，不落库、不打印。 */
    private val identityCache = ConcurrentHashMap<String, BaiduIdentity>()

    // ---------- 账号 ----------

    /**
     * 获取昵称（gettemplatevariable 的 username 字段）。
     * 会话无效 / 请求失败返回 null；接口成功但服务端未给昵称时返回空串
     * （调用方不能把「校验失败」与「没有昵称」混为一谈）。
     */
    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        val result = templateVariable(cookie, """["username"]""") ?: return@withContext null
        result.optString("username").trim().takeUnless { it == "null" }.orEmpty()
    }

    /** 获取 bdstoken（gettemplatevariable，带缓存） */
    suspend fun getBdstoken(cookie: String): String? = withContext(Dispatchers.IO) {
        cachedBdstoken?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val result = templateVariable(cookie, """["bdstoken"]""") ?: return@withContext null
        val token = result.optString("bdstoken").takeIf { it.isNotBlank() } ?: return@withContext null
        cachedBdstoken = token
        token
    }

    private suspend fun templateVariable(cookie: String, fields: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val url = "https://pan.baidu.com/api/gettemplatevariable" +
                "?clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1&fields=" +
                URLEncoder.encode(fields, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .get()
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("errno") != 0) return@use null
                    json.optJSONObject("result")
                }
            }.getOrNull()
        }

    /** 获取当前账号 UID（gettemplatevariable 的 uk）。 */
    suspend fun fetchUid(cookie: String): String? = withContext(Dispatchers.IO) {
        val result = templateVariable(cookie, """["uk"]""") ?: return@withContext null
        result.optString("uk").trim().takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * 获取登录上报接口的 uinfo。
     *
     * 这是 BrowserEngine 的 sign_key，也是内核 encode_sk 的原文；
     * 不能把它当成 psign_sk，也不能把它直接当文本拼进 rand。
     */
    suspend fun fetchUinfo(cookie: String): String? = withContext(Dispatchers.IO) {
        val body = buildString {
            append("action=pc_uniyunguanjia_login&timestamp=")
            append(System.currentTimeMillis() / 1000)
        }
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/report/user")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: "{}")
                if (json.optInt("errno") != 0) return@use null
                json.optString("uinfo").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /**
     * 查询文件 fgid（P2P Seed/TPDATA 的必需字段）。
     *
     * 实测接口只需 fsid、uk、path 三项，未签名的 GET 即可返回：
     * `{"error_code":0,"fgid":"...","fsize":...}`。
     */
    suspend fun fetchFgid(uid: String, fsId: String, path: String, cookie: String): BaiduFgidInfo? =
        withContext(Dispatchers.IO) {
            if (uid.isBlank() || fsId.isBlank() || path.isBlank()) return@withContext null
            val url = "https://pan.baidu.com/cms/fgid?method=query" +
                "&fsid=${urlEncode(fsId)}&uk=${urlEncode(uid)}&path=${urlEncode(path)}"
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .get()
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("error_code", -1) != 0) return@use null
                    val fgid = json.optString("fgid").takeIf { it.isNotBlank() }
                        ?: return@use null
                    BaiduFgidInfo(
                        fgid = fgid,
                        size = json.optLong("fsize", -1L)
                    )
                }
            }.getOrNull()
        }

    private suspend fun identity(cookie: String): BaiduIdentity? {
        identityCache[cookie]?.let { return it }
        val uid = fetchUid(cookie) ?: return null
        val uinfo = fetchUinfo(cookie) ?: return null
        val sk = BaiduDownloadCrypto.decodeUinfo(uinfo, uid) ?: return null
        return BaiduIdentity(uid, uinfo, sk).also { identityCache[cookie] = it }
    }

    /**
     * 账号稳定设备标识。不采集硬件标识，只从 BDUSS 派生：
     * devuid 为 32 位大写十六进制，deviceid 为十进制数值。
     */
    private fun deviceIdentity(cookie: String): Pair<String, String> {
        val bduss = cookieValue(cookie, "BDUSS").orEmpty()
        val digest = BaiduDownloadCrypto.sha256("yunx|download|$bduss".toByteArray())
        val devuid = BaiduDownloadCrypto.hex(digest).take(32).uppercase()
        var numeric = 0L
        for (i in 0 until 8) {
            numeric = (numeric shl 8) or (digest[i].toLong() and 0xFF)
        }
        numeric = (numeric and Long.MAX_VALUE).coerceAtLeast(1L)
        return devuid to numeric.toString()
    }

    // ---------- 分享解析 ----------

    /**
     * 验证提取码：POST /share/verify，返回 randsk（URL 编码形式，直接作为 sekey 使用）。
     * @return sekey；失败抛异常（携带服务端 errmsg）
     */
    suspend fun verifyShare(surl: String, pwd: String, cookie: String): String =
        withContext(Dispatchers.IO) {
            val body = "pwd=${urlEncode(pwd)}&vcode_str=&vcode="
            val request = Request.Builder()
                .url("https://pan.baidu.com/share/verify?surl=$surl")
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .header("Referer", "https://pan.baidu.com/s/$surl")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body.toRequestBody(formMediaType))
                .build()
            val json = executeJson(request)
            checkErrno(json, "验证提取码失败")
            json.optString("randsk").takeIf { it.isNotBlank() }
                ?: throw BaiduApiException("未返回分享密钥")
        }

    /**
 * 列出分享文件：GET xpan/share?method=list。
 * 修复（文档《百度网盘解析问题修复》）：
 *  - 顶层 root=1，子目录 root=0（root=1 下百度忽略 dir → 子文件夹进不去）；
 *  - 子目录（root=0）必须携带 BDCLND cookie（= verify 返回的 randsk），否则 errno=2；
 *  - sekey 为空（公共分享）时省略 &sekey= 参数；
 *  - 无 sekey 却 errno!=0 → 实为加密分享，抛"该分享需要提取码"。
 * @param dir 分享内目录路径，根目录传 "/"（子目录传 "/folder"）
 * @return 文件列表 + share_id/uk（转存需要）
 */
suspend fun listShare(surl: String, sekey: String, dir: String, cookie: String, page: Int = 1): BaiduShareList =
    withContext(Dispatchers.IO) {
        val isRoot = dir.isBlank() || dir == "/"
        val root = if (isRoot) "1" else "0"
        val sekeyPart = if (sekey.isNotBlank()) "&sekey=$sekey" else ""
        val url = "https://pan.baidu.com/rest/2.0/xpan/share?method=list" +
            "&shorturl=$surl&page=$page&num=100&root=$root&dir=" +
            URLEncoder.encode(if (dir.isBlank()) "/" else dir, "UTF-8") +
            sekeyPart
        // 子目录(root=0)必须携带 BDCLND（= verify 的 randsk），否则 errno=2；顶层(root=1)无需
        val authCookie = if (sekey.isNotBlank() && !cookie.contains("BDCLND="))
            "$cookie; BDCLND=$sekey" else cookie
        val request = Request.Builder()
            .url(url)
            .header("Cookie", authCookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Referer", "https://pan.baidu.com/s/$surl")
            .get()
            .build()
        val json = executeJson(request)
        val errno = json.optInt("errno")
        if (errno != 0) {
            // 无 sekey 却失败 → 多数情况是加密分享，提示用户索取提取码。
            // 但 errno=-6 是「未登录/身份验证失败」（游客模式下常见），此时光提示提取码会让人白找密码，单独区分。
            if (sekey.isBlank()) {
                throw BaiduApiException(
                    if (errno == -6) "该分享需要提取码，或需要登录百度网盘（errno=$errno）"
                    else "该分享需要提取码（errno=$errno）"
                )
            }
            checkErrno(json, "获取分享文件列表失败")
        }
        val array = json.optJSONArray("list") ?: org.json.JSONArray()
        val files = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val isdir = item.optString("isdir") == "1"
                val path = item.optString("path")
                    add(
                        ShareFile(
                            // 目录用 path 作 fid（导航传参），文件用 fs_id（转存传参）
                            fid = if (isdir) path else item.optString("fs_id"),
                            fname = item.optString("server_filename"),
                            fsize = item.optLong("size"),
                            isdir = isdir,
                            pdirFid = path,
                            fidToken = "",
                            modifyTime = item.optString("server_mtime")
                        )
                    )
                }
            }
            BaiduShareList(
                title = json.optString("title"),
                shareId = json.optString("share_id"),
                uk = json.optString("uk"),
                files = files
            )
        }

    // ---------- 个人网盘 / 转存 ----------

    /** 创建目录（个人网盘根目录下），返回是否成功 */
    suspend fun createDir(path: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        // 官方新建文件夹用的是 api/create?a=commit（对齐抓包）：
        // filemanager?opera=mkdir 在纯 Cookie 认证下恒 errno=2（接口校验路径不同）。
        // UA 用 netdisk 客户端 + Referer yun.baidu.com/disk/main + body 完整参数
        val body = "path=${urlEncode(path)}&isdir=1&size&block_list=%5B%5D&method=post&dataType=json"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/create?a=commit&channel=chunlei&web=1" +
                "&app_id=${BaiduConstants.APP_ID}&clienttype=0&bdstoken=$bdstoken")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Referer", "https://yun.baidu.com/disk/main")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    // ---------- 上传（预上传 → 秒传/分片 → 合并） ----------

    /**
     * 上传一个文件到 [parentPath] 目录（百度按**绝对路径**而不是 fid），返回 fs_id。
     *
     * 流程：整文件/每 4 MiB 分片/前 256 KiB 的 MD5 一次读全 → `api/precreate`（带上一版块哈希，
     * 命中秒传时 `return_type=2` 直接结束）→ `d.pcs.baidu.com` 取上传节点 →
     * 逐个分片 POST `superfile2`（每片回 md5，要与本地算的一致）→ `api/create?a=commit` 合并。
     *
     * ★ 分片顺序由服务端 `block_list` 决定，不能按本地顺序猜；
     * ★ 上传节点只认 `*.pcs.baidu.com`，响应里给别的域一律不用。
     */
    suspend fun upload(
        io: UploadIo,
        file: UploadSource,
        parentPath: String,
        cookie: String
    ): String? = withContext(Dispatchers.IO) {
        if (file.size <= 0L) throw IllegalStateException("百度网盘不支持上传空文件")
        val bdstoken = getBdstoken(cookie)
            ?: throw IllegalStateException("获取百度会话令牌失败，请重新登录")
        io.progress(UploadPhase.PREPARING)
        val path = parentPath.trimEnd('/') + "/" + file.name
        val chunkSize = 4L * 1024 * 1024
        val count = maxOf(1L, (file.size + chunkSize - 1) / chunkSize)
        val blocks = (0 until count).map { index ->
            io.digest(
                "MD5",
                index * chunkSize,
                minOf(file.size, (index + 1) * chunkSize)
            )
        }
        val blockJson = JSONArray(blocks).toString()
        val params = "channel=chunlei&web=1&app_id=${BaiduConstants.APP_ID}&clienttype=0&bdstoken=$bdstoken"
        val preBody = buildString {
            append("path=").append(urlEncode(path))
            append("&isdir=0&size=").append(file.size)
            append("&autoinit=1&rtype=0")
            append("&block_list=").append(urlEncode(blockJson))
            append("&content-md5=").append(io.digest("MD5"))
            append("&slice-md5=").append(io.digest("MD5", 0, minOf(file.size, 256L * 1024)))
        }
        val pre = executeJson(
            Request.Builder()
                .url("https://pan.baidu.com/api/precreate?$params")
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_NETDISK)
                .header("Referer", "https://pan.baidu.com/disk/main")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .post(preBody.toRequestBody(formMediaType))
                .build()
        )
        checkErrno(pre, "创建上传任务失败")
        if (pre.optInt("return_type") == 2) {
            // 秒传：服务端已有同内容文件
            io.progress(UploadPhase.FINISHING, file.size)
            return@withContext pre.optJSONObject("info")?.optString("fs_id")?.takeIf { it.isNotBlank() }
        }
        val uploadId = pre.optString("uploadid")
        if (uploadId.isBlank()) throw IllegalStateException("百度未创建上传任务")
        val needed = pre.optJSONArray("block_list")
            ?: throw IllegalStateException("百度未返回待上传分段")
        val servers = if (needed.length() == 0) emptyList() else uploadServers(cookie, bdstoken, pre.optString("uploadsign"))
        if (needed.length() > 0 && servers.isEmpty()) throw IllegalStateException("百度未返回可用上传节点，请稍后重试")
        val uploaded = mutableSetOf<Int>()
        for (position in 0 until needed.length()) {
            val index = needed.optInt(position, -1)
            if (index < 0 || index >= count) throw IllegalStateException("百度返回的上传分段序号无效")
            if (!uploaded.add(index)) throw IllegalStateException("百度返回的上传分段序号无效")
            val response = io.send(
                url = "${servers.first()}/rest/2.0/pcs/superfile2?$params" +
                    "&method=upload&type=tmpfile&path=${urlEncode(path)}" +
                    "&uploadid=$uploadId&partseq=$index",
                method = "POST",
                start = index * chunkSize,
                end = minOf(file.size, (index + 1) * chunkSize),
                headers = mapOf(
                    "Cookie" to cookie,
                    "User-Agent" to BaiduConstants.UA_NETDISK
                ),
                retries = 1
            )
            if (!response.isSuccessful) throw IllegalStateException("文件上传失败（HTTP ${response.code}）")
            val part = runCatching { response.json() }.getOrNull()
                ?: throw IllegalStateException("百度上传分段响应异常，请重试")
            val remoteMd5 = part.optString("md5")
            if (!remoteMd5.equals(blocks[index], ignoreCase = true)) {
                throw IllegalStateException("百度上传分段校验失败，请重试")
            }
        }
        io.progress(UploadPhase.FINISHING, file.size)
        val commitBody = buildString {
            append("path=").append(urlEncode(path))
            append("&isdir=0&size=").append(file.size)
            append("&uploadid=").append(urlEncode(uploadId))
            append("&block_list=").append(urlEncode(blockJson))
            append("&rtype=0")
            append("&local_mtime=").append(System.currentTimeMillis() / 1000)
        }
        val committed = executeJson(
            Request.Builder()
                .url("https://pan.baidu.com/api/create?a=commit&$params")
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_NETDISK)
                .header("Referer", "https://yun.baidu.com/disk/main")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .post(commitBody.toRequestBody(formMediaType))
                .build()
        )
        checkErrno(committed, "合并上传分段失败")
        committed.optString("fs_id").takeIf { it.isNotBlank() }
    }

    /** 取上传节点：只接受 https + `*.pcs.baidu.com`，去重后取前 3 个 */
    private fun uploadServers(cookie: String, bdstoken: String, sign: String): List<String> {
        val query = buildString {
            append("https://d.pcs.baidu.com/rest/2.0/pcs/file")
            append("?channel=chunlei&web=1&app_id=${BaiduConstants.APP_ID}")
            append("&clienttype=0&bdstoken=$bdstoken")
            append("&method=locateupload&upload_version=2.0")
            if (sign.isNotBlank()) append("&uploadsign=").append(urlEncode(sign))
        }
        val json = runCatching {
            executeJson(
                Request.Builder()
                    .url(query)
                    .header("Cookie", cookie)
                    .header("User-Agent", BaiduConstants.UA_NETDISK)
                    .get()
                    .build()
            )
        }.getOrNull() ?: return emptyList()
        val servers = linkedSetOf<String>()
        val arrays = listOf(json.optJSONArray("servers"), json.optJSONArray("bak_servers"))
        for (array in arrays) {
            if (array == null) continue
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val uri = runCatching { java.net.URI(item.optString("server")) }.getOrNull() ?: continue
                val host = uri.host.orEmpty()
                if (uri.scheme != "https" || !host.endsWith(".pcs.baidu.com")) continue
                servers.add("https://$host")
                if (servers.size >= 3) return servers.toList()
            }
        }
        val host = json.optString("host")
        if (servers.isEmpty() && host.endsWith(".pcs.baidu.com")) servers.add("https://$host")
        return servers.toList()
    }

    /**
     * 列出个人网盘目录（检查临时转存目录是否存在），返回子项 path 集合。
     *
     * 只是 [listCloudFiles] 的轻量包装：通道选择、分页、失效判定都只有一处实现，
     * 免得「转存目录检查」和「列表页」跑在不同通道上、一个能读一个读不了。
     */
    suspend fun listDir(dir: String, cookie: String): List<String> =
        catching { listCloudFiles(dir, cookie).map { it.fidToken } }.getOrDefault(emptyList())

    /**
     * 转存分享文件到指定目录（同步返回结果）。
     * @return 转存后的新 fs_id + 新路径
     */
    suspend fun transfer(
        shareId: String,
        uk: String,
        sekey: String,
        fsId: String,
        toDir: String,
        cookie: String
    ): BaiduTransferResult = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
            ?: throw BaiduApiException("获取 bdstoken 失败，请重新登录")
        val url = "https://pan.baidu.com/share/transfer?shareid=$shareId&from=$uk" +
            "&channel=chunlei&sekey=$sekey&ondup=newcopy&web=1&app_id=${BaiduConstants.APP_ID}" +
            "&bdstoken=$bdstoken&clienttype=0"
        val body = "fsidlist=%5B%22$fsId%22%5D&path=${urlEncode(toDir)}"
        // verify 响应会 Set-Cookie: BDCLND=<randsk>，transfer 必须携带（分享验证标识），
        // 缺失会 errno=2；BDCLND 值即 sekey（randsk），手动补齐
        val authCookie = if (cookie.contains("BDCLND=")) cookie else "$cookie; BDCLND=$sekey"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", authCookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Origin", "https://pan.baidu.com")
            .header("Referer", "https://pan.baidu.com/s/")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(body.toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        checkErrno(json, "转存失败")
        val extra = json.optJSONObject("extra")
        val list = extra?.optJSONArray("list")
        val first = list?.optJSONObject(0)
        val fsIdNew = first?.optString("to_fs_id")?.takeIf { it.isNotBlank() }
            ?: throw BaiduApiException("转存失败：未返回新文件")
        val pathNew = first?.optString("to")?.takeIf { it.isNotBlank() }
            ?: "$toDir/"
        BaiduTransferResult(fsId = fsIdNew, path = pathNew)
    }

    // ---------- 下载直链 ----------

    /**
     * 获取下载地址。
     *
     * CDN 模式返回明文 HTTP 直链；P2P 模式返回 `baidu-p2p://` 快照。
     * `rand`/`time`/`devuid`/`deviceid`/`psign` 全部按当前登录态动态生成，
     * 不再使用任何固定抓包常量（固定值正是触发风控的根因）。
     *
     * @param fsId 转存后的 fs_id；P2P 必填，CDN 模式可空
     * @param fileSize 列表已知的文件大小；P2P 缺失时再问 `/cms/fgid?method=query`
     * @param forceCdn 在线预览必须走 CDN，ExoPlayer 不认识 P2P 描述符
     */
    suspend fun locateDownload(
        path: String,
        fsId: String,
        cookie: String,
        fileSize: Long = -1L,
        forceCdn: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val info = fetchLocatedownloadInfo(path, cookie)
        val cdn = info.bestPlainCdnUrl()
        val wantP2p = !forceCdn && downloadModeProvider() == BaiduConstants.DOWNLOAD_MODE_P2P
        if (!wantP2p || fsId.isBlank()) {
            return@withContext cdn
                ?: throw BaiduApiException("未返回可用明文 CDN 下载链接")
        }
        val account = identity(cookie)
        if (account == null) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK, "baidu_p2p_fallback",
                "无法取得 uid/uinfo，已回退 CDN"
            )
            return@withContext cdn
                ?: throw BaiduApiException("获取百度下载身份失败，请重新登录")
        }
        val fgid = fetchFgid(account.uid, fsId, path, cookie)
        if (fgid == null) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK, "baidu_p2p_fallback",
                "fgid 查询失败，已回退 CDN"
            )
            return@withContext cdn
                ?: throw BaiduApiException("百度 P2P 资源初始化失败，且没有可回退的 CDN 链接")
        }
        val size = fileSize.takeIf { it > 0L } ?: fgid.size
        if (size <= 0L) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK, "baidu_p2p_fallback",
                "文件大小未知，已回退 CDN"
            )
            return@withContext cdn
                ?: throw BaiduApiException("百度 P2P 缺少文件大小")
        }
        val (devuid, deviceId) = deviceIdentity(cookie)
        val keys = info.encryptKeyList.ifEmpty {
            listOfNotNull(info.encryptKey)
        }
        BaiduP2pSpec(
            fgid = fgid.fgid,
            fsId = fsId,
            uid = account.uid,
            path = path,
            fileSize = size,
            encryptKeys = keys,
            sl = info.sl,
            fsl = info.fsl,
            localTime = info.localTime,
            serverTime = info.timestamp,
            devuid = devuid,
            deviceId = deviceId,
            cdnUrls = info.urls
                .asSequence()
                .filter { it.encrypt == 0 && it.url.startsWith("https://", ignoreCase = true) }
                .sortedBy { it.rank }
                .map { it.url }
                .distinct()
                .take(3)
                .toList()
        ).toUrl()
    }

    /**
     * 动态签名 locatedownload，并解析 CDN 候选与 P2P 需要的加密 key。
     *
     * 签名顺序严格遵循内核：
     * 1. 所有值先做 URL 编码；
     * 2. 按 query key 字典序排序，只拼 value，两级 MD5 生成 psign；
     * 3. 重建 query，并把 psign 放最后。
     */
    private suspend fun fetchLocatedownloadInfo(
        path: String,
        cookie: String
    ): BaiduLocatedownloadInfo {
        val bduss = cookieValue(cookie, "BDUSS")
            ?: throw BaiduApiException("百度登录态缺少 BDUSS，请重新登录")
        val account = identity(cookie)
            ?: throw BaiduApiException("获取百度下载身份失败，请重新登录")
        val time = System.currentTimeMillis() / 1000
        val (devuid, deviceId) = deviceIdentity(cookie)
        val rand = BaiduDownloadCrypto.makeRand(
            bduss = bduss,
            uid = account.uid,
            sk = account.sk,
            time = time,
            devuid = devuid
        )
        val params = linkedMapOf(
            "ant" to "1",
            "app_id" to BaiduConstants.APP_ID,
            "channel" to "00000000000000000000000040000001",
            "check_blue" to "1",
            "clienttype" to "9",
            "deviceid" to deviceId,
            "devuid" to devuid,
            "dpkg" to "1",
            "dsize" to "4194304",
            "dtype" to "1",
            "eck" to "1",
            "ehps" to "1",
            "err_ver" to "1.0",
            "es" to "1",
            "esl" to "1",
            "method" to "locatedownload",
            "open_pflag" to "0",
            "path" to urlEncode(path),
            "rand" to rand,
            "sd" to "0",
            "time" to time.toString(),
            "ver" to "4.0",
            "version" to BaiduConstants.P2P_SDK_VERSION,
            "version_app" to "8.9.7.101",
            "vip" to "0"
        ).toSortedMap()
        val psign = BaiduDownloadCrypto.makePsign(params)
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" } +
            "&psign=$psign"
        val request = Request.Builder()
            .url("https://d.pcs.baidu.com/rest/2.0/pcs/file?$query")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_P2SP)
            .header("Accept", "*/*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post("0".toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        checkErrno(json, "获取高速下载链接失败")

        val urls = buildList {
            val array = json.optJSONArray("urls")
            for (i in 0 until (array?.length() ?: 0)) {
                val item = array?.optJSONObject(i) ?: continue
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                add(
                    BaiduCdnUrl(
                        url = url,
                        rank = item.optInt("rank", Int.MAX_VALUE),
                        encrypt = item.optInt("encrypt", 1)
                    )
                )
            }
        }
        val rankParam = json.optJSONObject("rank_param")
        val primaryKey = json.optJSONObject("encrypt_key")?.let(BaiduEncryptKey::fromJson)
        val keyList = buildList {
            val array = json.optJSONArray("encrypt_key_list")
            for (i in 0 until (array?.length() ?: 0)) {
                array?.optJSONObject(i)?.let { add(BaiduEncryptKey.fromJson(it)) }
            }
            if (primaryKey != null && none {
                    it.version == primaryKey.version && it.key == primaryKey.key
                }
            ) {
                add(primaryKey)
            }
        }
        return BaiduLocatedownloadInfo(
            localTime = time,
            timestamp = json.optLong("timestamp", time),
            urls = urls,
            encryptKey = primaryKey,
            encryptKeyList = keyList,
            sl = json.optLong("sl", 120L),
            fsl = json.optLong("fsl", -1L),
            maxContinuousFailure = rankParam?.optInt("max_continuous_failure", 5) ?: 5,
            bakRankSliceNum = rankParam?.optInt("bak_rank_slice_num", 30) ?: 30
        )
    }

    /**
     * 获取下载直链：filemetas（按 fs_id 取链，个人网盘文件无 path 时使用）。
     * 注意：该 dlink（d.pcs.baidu.com）在文件被删除后立即失效，仅限文件仍存在时下载。
     * @return dlink；失败抛异常
     */
    suspend fun fileMetasDlink(fsId: String, cookie: String): String = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
            ?: throw BaiduApiException("获取 bdstoken 失败，请重新登录")
        val fsids = URLEncoder.encode("""["$fsId"]""", "UTF-8")
        val url = "https://pan.baidu.com/api/filemetas?dlink=1&fsids=$fsids&bdstoken=$bdstoken" +
            "&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .get()
            .build()
        val json = executeJson(request)
        checkErrno(json, "获取下载链接失败")
        val info = json.optJSONArray("info")
        val dlink = info?.optJSONObject(0)?.optString("dlink")?.takeIf { it.isNotBlank() }
            ?: throw BaiduApiException("未返回下载链接")
        dlink
    }

    /** 删除个人网盘文件（转存后清理），按完整路径删除 */
    suspend fun deleteFile(path: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val body = "filelist=${URLEncoder.encode("""["$path"]""", "UTF-8")}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?async=2&onnest=fail&opera=delete" +
                "&bdstoken=$bdstoken&newVerify=1&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    // ---------- 云盘文件管理（百度网盘功能） ----------

    /**
     * 列出个人网盘目录（自动翻页，返回该目录下全部文件）。
     *
     * ★ 必须准备两个通道，而且**参数与 UA 不能混用**：
     *  - App 通道（首选）：`clienttype=1`，`channel` 要保留但值为空（网页会话也认），
     *    `bdstoken` 用 MD5(BDUSS) 而不是网页接口返回的 token；
     *  - 网页通道（兜底）：`channel=chunlei&clienttype=0&web=1` + 网页 UA + 站内 Referer。
     *
     * 之前这里把「网页参数 + App UA」拼在一起、又没带 channel：百度会按「这不是同一类客户端」
     * 拒绝（errno=2 参数错误 / -6 未登录），而旧实现把非 0 的 errno 当成空页吞掉，
     * 于是界面上只剩一个永远加载不出来的空目录。
     *
     * 返回 ShareFile（fid=fs_id，fidToken=绝对路径 path）
     */
    suspend fun listCloudFiles(dir: String, cookie: String): List<ShareFile> = withContext(Dispatchers.IO) {
        // 缩略图落在 thumbnail.baidupcs.com（签名地址，不需要 Cookie），但图床按网盘 UA + Referer 防盗链
        val thumbnailHeaders = mapOf(
            "User-Agent" to BaiduConstants.UA_NETDISK,
            "Referer" to "https://yun.baidu.com/"
        )
        val appResult = catching { fetchPersonalList(dir, cookie, thumbnailHeaders, app = true) }
        appResult.getOrNull()?.let { return@withContext it }
        val appError = appResult.exceptionOrNull()?.message
        val webResult = catching { fetchPersonalList(dir, cookie, thumbnailHeaders, app = false) }
        webResult.getOrNull()?.let { files ->
            // App 通道失败本身不算错（网页会话/老账号会这样），但要留痕：下次百度再改协议时能看出是兜底在生效
            DiagnosticLog.warn(DiagnosticLog.NETWORK, "baidu_list_web_fallback", "app=$appError")
            return@withContext files
        }
        // 两个通道都失败：把真实原因抛出去（UI 显示 errno），不要再假装成空目录
        val webError = webResult.exceptionOrNull()?.message
        DiagnosticLog.warn(
            DiagnosticLog.NETWORK,
            "baidu_list_failed",
            "app=$appError web=$webError"
        )
        throw BaiduApiException(webError ?: appError ?: "百度文件列表加载失败")
    }

    /**
     * 按指定通道逐页拉取目录。
     *
     * 分页语义两边不同：App 通道用 `start/limit` 偏移，网页通道用 `page/num` 页码——
     * 所以只能整条通道走完再决定要不要换另一条，不能从中间页切换（会漏文件）。
     */
    private fun fetchPersonalList(
        dir: String,
        cookie: String,
        thumbnailHeaders: Map<String, String>,
        app: Boolean
    ): List<ShareFile> {
        val files = mutableListOf<ShareFile>()
        val seen = HashSet<String>()
        for (page in 0 until LIST_MAX_PAGES) {
            val url = listPageUrl(dir, cookie, app, page)
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", if (app) BaiduConstants.UA_APP else BaiduConstants.UA_WEB)
                .apply { if (!app) header("Referer", "https://pan.baidu.com/disk/main") }
                .get()
                .build()
            val json = executeJson(request)
            if (json.optInt("errno") != 0) throw BaiduApiException(listErrorMessage(json))
            val array = json.optJSONArray("list")
                ?: throw BaiduApiException("百度文件列表响应不完整，请刷新重试")
            var fresh = 0
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val path = item.optString("path")
                val fid = item.optString("fs_id").ifBlank { path }
                if (fid.isBlank() || !seen.add(fid)) continue
                fresh++
                files.add(
                    ShareFile(
                        fid = item.optString("fs_id"),
                        fname = item.optString("server_filename"),
                        fsize = item.optLong("size"),
                        isdir = item.optInt("isdir") == 1,
                        pdirFid = dir,
                        fidToken = path,
                        modifyTime = item.optString("server_mtime"),
                        thumbnail = thumbnailOf(item, thumbnailHeaders)
                    )
                )
            }
            // 本页未满一页 → 已是最后一页；整页都是重复项 → 服务端忽略了偏移，继续翻只会死循环
            if (array.length() < LIST_PAGE_SIZE || fresh == 0) break
        }
        return files
    }

    /** 个人盘列表页 URL：App 通道 / 网页通道两套参数，混用会被判成参数错误（errno=2） */
    private fun listPageUrl(dir: String, cookie: String, app: Boolean, page: Int): String {
        val encodedDir = URLEncoder.encode(dir, "UTF-8")
        return if (app) {
            val uid = appDeviceId(cookie)
            "https://pan.baidu.com/api/list?clienttype=1&app_id=${BaiduConstants.APP_ID}" +
                "&version=${BaiduConstants.APP_VERSION}&channel=" +
                "&bdstoken=${appBdstoken(cookie)}&devuid=$uid&cuid=$uid" +
                "&apn_id=0_0&freeisp=0&queryfree=2" +
                "&start=${page * LIST_PAGE_SIZE}&limit=$LIST_PAGE_SIZE&preset=0" +
                "&order=time&desc=1&dir=$encodedDir"
        } else {
            "https://pan.baidu.com/api/list?channel=chunlei&clienttype=0" +
                "&app_id=${BaiduConstants.APP_ID}&web=1&page=${page + 1}&num=$LIST_PAGE_SIZE" +
                "&order=time&desc=1&dir=$encodedDir"
        }
    }

    /** 列表失败的错误文案：errno 一定带上，服务端有 err_msg 就一起显示 */
    private fun listErrorMessage(json: JSONObject): String {
        val errno = json.optInt("errno")
        // 字段为 JSON null 时 optString 会返回字符串 "null"，直接拼进提示会变成「：null」
        val message = listOf("err_msg", "errmsg", "show_msg")
            .firstNotNullOfOrNull { key ->
                json.optString(key).takeIf { it.isNotBlank() && it != "null" }
            }
            .orEmpty()
        return buildString {
            append("百度文件列表加载失败（errno=").append(errno)
            if (message.isNotBlank()) append("：").append(message)
            append('）')
            if (errno == -6) append("，").append(BaiduConstants.INCOMPLETE_LOGIN_HINT)
        }
    }

    /**
     * App 通道的 CSRF：百度 App 认的是 MD5(BDUSS) 的 32 位小写十六进制，
     * 不是 `gettemplatevariable` 返回的那个网页 token（两者在网页会话下并不通用）。
     */
    private fun appBdstoken(cookie: String): String =
        md5Hex(cookieValue(cookie, "BDUSS").orEmpty())

    /**
     * App 通道的设备号：接口只要求形状像设备号（32 位大写十六进制 + "|0"）。
     * 这里不采集任何硬件标识，用 BDUSS 派生一份稳定的「账号级设备号」：
     * 同一账号每次一致即可，换账号/换登录态会自然不同。
     */
    private fun appDeviceId(cookie: String): String =
        md5Hex("yunx|" + cookieValue(cookie, "BDUSS").orEmpty()).uppercase() + "|0"

    /** 从 Cookie 串取单个键（形如 `BDUSS=...; STOKEN=...`），缺失返回 null */
    private fun cookieValue(cookie: String, name: String): String? =
        Regex("(?:^|;\\s*)" + Regex.escape(name) + "=([^;]+)")
            .find(cookie)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * 等价于 `runCatching`，但把协程取消原样抛出去。
     *
     * `runCatching` 会连 `CancellationException` 一起吞掉：用户退出列表页/切目录时，
     * 这里的「通道回退」会继续发第二个请求并最终把取消伪装成业务失败。
     */
    private inline fun <T> catching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /**
     * 百度列表项的缩略图：thumbs.url3 > url2 > url1（按清晰度降序）。
     *
     * ★ 逐档校验而不是取第一个非空：字段为 JSON null 时 `optString` 会给出字符串 "null"，
     *   直接当地址用就变成「有一个永远加载不出来的缩略图」，也不会再往后找。
     */
    private fun thumbnailOf(item: JSONObject, headers: Map<String, String>): FileThumbnail? {
        val thumbs = item.optJSONObject("thumbs") ?: return null
        return FileThumbnail.of(thumbs.optString("url3"), headers)
            ?: FileThumbnail.of(thumbs.optString("url2"), headers)
            ?: FileThumbnail.of(thumbs.optString("url1"), headers)
    }

    /** 重命名（filemanager opera=rename，按完整路径） */
    suspend fun renameFile(path: String, newName: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val filelist = """[{"path":"$path","newname":"$newName"}]"""
        val body = "filelist=${URLEncoder.encode(filelist, "UTF-8")}"
        val request = Request.Builder()
            .url("https://yun.baidu.com/api/filemanager?async=0&onnest=fail&opera=rename" +
                "&bdstoken=$bdstoken&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://yun.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /** 移动单个/多个文件（filemanager opera=move，path→dest，newname 保持原名） */
    suspend fun moveFiles(
        paths: List<String>,
        dest: String,
        cookie: String
    ): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val items = paths.joinToString(",") { p ->
            val name = p.substringAfterLast('/')
            """{"path":"$p","dest":"$dest","newname":"$name"}"""
        }
        val body = "filelist=${URLEncoder.encode("[$items]", "UTF-8")}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?async=2&onnest=fail&opera=move" +
                "&bdstoken=$bdstoken&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://yun.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /** 批量删除（filemanager opera=delete，按完整路径） */
    suspend fun deleteFiles(paths: List<String>, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val body = "filelist=${URLEncoder.encode(paths.joinToString(",", "[", "]") { "\"$it\"" }, "UTF-8")}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?async=2&onnest=fail&opera=delete" +
                "&bdstoken=$bdstoken&newVerify=1&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Referer", "https://yun.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /** 百度创建分享结果
     *
     *  @param expiredType 服务端返回的有效期（0永久/1一天/7七天/30三十天）；响应未返回该字段时为 null，
     *    由调用方回退到用户所选值（别用 0 兜底——0 是「永久有效」，会把创建失败静默显示成永久）。
     */
    data class BaiduShareResult(
        val link: String,
        val pwd: String,
        val shareId: String,
        val expiredType: Int?
    )

    /** 创建分享（share/set，按 fs_id 列表 + 有效期 period + 4 位提取码）
     *
     *  @param period 百度 API 的有效期**字面值**：0=永久、1=一天、7=七天、30=三十天。
     *    ⚠️ 不是 UI 中性码（1/2/3/4），调用方必须先用
     *    [com.yunx.app.data.network.model.ShareExpire.baiduPeriod] 转换（Agent.md §3.20）。
     */
    suspend fun createShare(
        fsIds: List<String>,
        period: Int,
        pwd: String,
        cookie: String
    ): BaiduShareResult = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
            ?: throw BaiduApiException("获取 bdstoken 失败，请重新登录")
        val fidList = fsIds.joinToString(",", "[", "]")
        val body = "fid_list=${URLEncoder.encode(fidList, "UTF-8")}" +
            "&schannel=4&channel_list=%5B%5D&period=$period&pwd=${urlEncode(pwd)}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/share/set?channel=chunlei&web=1" +
                "&app_id=${BaiduConstants.APP_ID}&bdstoken=$bdstoken&clienttype=0")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Referer", "https://yun.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        checkErrno(json, "创建分享失败")
        val link = json.optString("shorturl").ifBlank { json.optString("link") }
            .takeIf { it.isNotBlank() } ?: throw BaiduApiException("未返回分享链接")
        BaiduShareResult(
            link = link,
            pwd = pwd,
            shareId = json.optString("shareid"),
            // 用 opt + Number 转换：字段缺失 / 显式 null / 非数字一律回 null，
            // 交给调用方回退到用户所选值（optInt 会把它们变成 0，而 0 是「永久有效」，会显示成假的有效期）
            expiredType = (json.opt("expiredType") as? Number)?.toInt()
        )
    }

    // ---------- 网盘空间详情 ----------

    /** 网盘空间详情（GET yun.baidu.com/api/quota：total / used） */
    suspend fun getQuota(cookie: String): QuotaInfo? = withContext(Dispatchers.IO) {
        val url = "https://yun.baidu.com/api/quota?clienttype=0&app_id=${BaiduConstants.APP_ID}" +
            "&web=1&channel=chunlei&version=${System.currentTimeMillis()}"
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_NETDISK)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://yun.baidu.com/disk/main")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.use { it.body?.string() ?: return@runCatching null }
            val json = JSONObject(body)
            if (json.optInt("errno") != 0) return@runCatching null
            QuotaInfo(
                used = json.optLong("used"),
                total = json.optLong("total")
            )
        }.getOrNull()
    }

    // ---------- 公共 ----------

    private fun executeJson(request: Request): JSONObject {
        val response = client.newCall(request).execute()
        val status = response.code
        val body = response.use { it.body?.string() ?: throw BaiduApiException("请求失败：响应为空") }
        // 带上 HTTP 状态码：百度鉴权失败时可能直接回 HTML 登录页，只报「解析失败」会查不出是哪一类
        return runCatching { JSONObject(body) }.getOrElse {
            throw BaiduApiException("响应解析失败（HTTP $status）")
        }
    }

    private fun checkErrno(json: JSONObject, fallback: String) {
        val errno = json.optInt("errno")
        if (errno != 0) {
            // 常见：errno=-12 提取码错误 / 403 分享已失效 / 31066 文件不存在
            val msg = json.optString("err_msg").ifBlank { json.optString("show_msg") }.ifBlank { fallback }
            val loginHint = if (errno == -6) "，${BaiduConstants.INCOMPLETE_LOGIN_HINT}" else ""
            throw BaiduApiException("$msg（errno=$errno）$loginHint")
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        /** 单页 100 条（接口上限）；两个通道的页码语义不同，但页大小可以共用一个值 */
        const val LIST_PAGE_SIZE = 100

        /** 翻页硬上限：防止服务端忽略偏移导致死循环 */
        const val LIST_MAX_PAGES = 100
    }
}
