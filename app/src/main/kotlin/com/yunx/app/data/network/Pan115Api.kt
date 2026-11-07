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

import com.yunx.app.R
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.model.ShareExpire
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import com.yunx.app.data.upload.UploadIo
import com.yunx.app.data.upload.UploadPhase
import com.yunx.app.data.upload.UploadSource
import com.yunx.app.util.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/** 115 个人盘列表分页结果 */
data class Pan115FilePage(
    val files: List<ShareFile>,
    val total: Int
)

/** 115 分享列目录结果：文件列表 + 总数 + 分享者 UID（取直链必需）+ 标题 + 分享状态 */
data class Pan115SharePage(
    val files: List<ShareFile>,
    val total: Int,
    val userId: String,
    val title: String,
    val shareState: Int
)

/**
 * 115 网盘接口（个人盘 + 分享）。
 *
 * 抓包文档：`云析分析资料/网盘API文档/115/115_api.md`、`115_share_api.md`。
 * 全部接口共用同一套请求头：桌面 UA + `X-Requested-With` + 对应页面 Referer + Cookie。
 */
class Pan115Api(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    /** 首次分享前的协议确认只需一次（进程级缓存，失败不阻塞后续分享） */
    @Volatile
    private var protocolAllowed = false

    /** 客户端版本号只拉一次（进程级缓存；`Pan115Constants.CLIENT_UA` 由它刷新） */
    @Volatile
    private var clientVersionFetched = false

    // ---------- 客户端版本（取直链必需） ----------

    /**
     * 刷新客户端版本号（进程内只拉一次）。
     *
     * 115 取链接口会校验客户端版本，UA 里版本过低直接 `50029 当前版本过低`（alist 默认的
     * `27.0.5.7` 在 2026-10-03 已被判过低）。这里按 alist `drivers/115/appver.go` 的做法，
     * 从 [Pan115Constants.APP_VERSION_URL] 取当前 Windows 版版本号刷新 [Pan115Constants.CLIENT_UA]；
     * 拉取失败就保留兜底值，不影响后续请求。
     */
    suspend fun refreshClientVersion() = withContext(Dispatchers.IO) {
        if (clientVersionFetched) return@withContext
        clientVersionFetched = true
        runCatching {
            val json = getJson(
                Pan115Constants.APP_VERSION_URL,
                cookie = "",
                referer = Pan115Constants.REFERER
            )
            val win = json.optJSONObject("data")?.optJSONObject("win") ?: return@runCatching
            Pan115Constants.applyClientVersion(win.optString("version_code"))
        }
    }

    // ---------- 用户信息（文档 §1.1） ----------

    /**
     * 校验登录态并取昵称：`GET /user/info?ct=json`。
     * 已登录时 `data` 是脱敏手机号；失效返回 null（服务端 errno=990001）。
     */
    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson(Pan115Constants.USER_INFO_URL, cookie, Pan115Constants.REFERER)
            checkState(json, AppStrings.get(R.string.e1_get_user_info_failed))
            json.optString("data").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 网盘空间：`GET /files/index_info?count_space_nums=1`（文档 §2.2） */
    suspend fun getQuota(cookie: String): QuotaInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson(Pan115Constants.INDEX_INFO_URL, cookie, Pan115Constants.REFERER)
            checkState(json, AppStrings.get(R.string.e1_get_space_info_failed))
            val space = json.optJSONObject("data")?.optJSONObject("space_info") ?: return@runCatching null
            val total = space.optJSONObject("all_total")?.optLong("size") ?: 0L
            val used = space.optJSONObject("all_use")?.optLong("size") ?: 0L
            QuotaInfo(used = used, total = total, usedInTrash = 0L)
        }.getOrNull()
    }

    // ---------- 个人盘（文档 §2、§3） ----------

    /** 列目录：`GET /files`（offset 为索引分页；文件夹用 cid、文件用 fid） */
    suspend fun listFiles(
        cid: String,
        cookie: String,
        offset: Int = 0,
        limit: Int = Pan115Constants.PAGE_LIMIT
    ): Pan115FilePage = withContext(Dispatchers.IO) {
        val url = buildString {
            append(Pan115Constants.FILES_URL)
            append("?aid=").append(Pan115Constants.AID_PERSONAL)
            append("&cid=").append(encode(cid.ifBlank { Pan115Constants.ROOT_CID }))
            append("&o=user_ptime&asc=0")
            append("&offset=").append(offset)
            append("&show_dir=1")
            append("&limit=").append(limit)
            append("&snap=0&natsort=1&record_open_time=1&count_folders=1")
            append("&format=json")
        }
        val json = getJson(url, cookie, Pan115Constants.REFERER)
        checkState(json, AppStrings.get(R.string.e1_get_file_list_failed))
        Pan115FilePage(
            files = parsePersonalFiles(json),
            total = json.optInt("count", 0)
        )
    }

    /**
     * 个人盘直链。
     *
     * 先走「电脑端」加密接口 [getAppDownloadLink]：网页端接口 `GET /files/download?pickcode=`
     * 对大文件直接拒发（实测 482MB 就返回 `50028 文件大小超出限制，请使用115电脑端下载`，
     * 抓包见 `/storage/emulated/0/抓包/bug/115/2/`，换客户端 UA 也无效）。
     * 电脑端接口失败时再退回网页端接口，小文件仍可用（它的响应头会下发 CDN Cookie）。
     */
    suspend fun getDownloadLink(file: ShareFile, cookie: String): DownloadLink? =
        withContext(Dispatchers.IO) {
            val pickCode = file.fidToken
            if (pickCode.isBlank()) throw IllegalStateException(AppStrings.get(R.string.e1_115_missing_pickcode))
            refreshClientVersion()
            runCatching { getAppDownloadLink(pickCode, cookie) }
                .getOrNull()
                ?.let { appLink ->
                    return@withContext appLink.copy(
                        fid = file.fid,
                        filename = appLink.filename.ifBlank { file.fname },
                        size = if (appLink.size > 0L) appLink.size else file.fsize
                    )
                }
            getWebDownloadLink(pickCode, file, cookie)
        }

    /**
     * 网页端直链：`GET /files/download?pickcode=<pc>`；响应头下发的 CDN Cookie 放进 guestCookie。
     * 大文件会被 115 以 `50028` 拒绝，仅作电脑端接口的兜底。
     */
    private suspend fun getWebDownloadLink(
        pickCode: String,
        file: ShareFile,
        cookie: String
    ): DownloadLink? =
        withContext(Dispatchers.IO) {
            refreshClientVersion()
            val url = "${Pan115Constants.FILE_DOWNLOAD_URL}?pickcode=${encode(pickCode)}"
            val (json, cdnCookie) = executeJson(
                baseBuilder(
                    url,
                    cookie,
                    Pan115Constants.DOWNLOAD_REFERER,
                    Pan115Constants.CLIENT_UA
                ).get().build()
            )
            checkState(json, AppStrings.get(R.string.e1_get_download_link_failed))
            val data = json.optJSONObject("data") ?: return@withContext null
            val fileUrl = data.optString("file_url").takeIf { it.isNotBlank() }
                ?: return@withContext null
            DownloadLink(
                fid = file.fid,
                filename = data.optString("file_name").ifBlank { file.fname },
                downloadUrl = fileUrl,
                size = data.optString("file_size").toLongOrNull() ?: file.fsize,
                cleanupDirFid = null,
                isHls = false,
                guestCookie = cdnCookie.orEmpty()
            )
        }

    /**
     * 「电脑端」直链：`POST https://proapi.115.com/app/chrome/downurl?t=<unix秒>`。
     *
     * 请求体加密：`data=<Pan115Crypto.encode(json{"pickcode":…}, key)>`；响应 `data` 是同一个 key
     * 加密回来的 JSON（file_id → {file_name, file_size, url.url}）。
     * 这是 115 官方电脑端下载大文件的唯一通道，网页端接口对大文件一律 50028。
     * 参考实现：`115drive-webdav-main/115/api.go`（APIGetDownloadURL）、
     * `115-minus-main/src/platform/115/download-api.ts`。
     */
    suspend fun getAppDownloadLink(pickCode: String, cookie: String): DownloadLink? =
        withContext(Dispatchers.IO) {
            if (pickCode.isBlank()) return@withContext null
            val key = Pan115Crypto.newKey()
            val payload = JSONObject().put("pickcode", pickCode).toString()
            val url = "${Pan115Constants.APP_DOWNLOAD_URL}?t=${System.currentTimeMillis() / 1000}"
            val (json, cdnCookie) = postFormWithCookie(
                url,
                cookie,
                Pan115Constants.DOWNLOAD_REFERER,
                form("data" to Pan115Crypto.encode(payload, key)),
                Pan115Constants.CLIENT_UA
            )
            checkState(json, AppStrings.get(R.string.e1_get_download_link_failed))
            val encrypted = json.optString("data")
            if (encrypted.isBlank()) return@withContext null
            val decoded = JSONObject(Pan115Crypto.decode(encrypted, key))
            val info = decoded.keys().asSequence()
                .mapNotNull { decoded.optJSONObject(it) }
                .firstOrNull { it.optJSONObject("url") != null }
                ?: return@withContext null
            val fileUrl = info.optJSONObject("url")?.optString("url").orEmpty()
            if (fileUrl.isBlank()) return@withContext null
            DownloadLink(
                fid = info.optString("pick_code").ifBlank { pickCode },
                filename = info.optString("file_name"),
                downloadUrl = fileUrl,
                size = info.optString("file_size").toLongOrNull() ?: info.optLong("file_size", -1L),
                cleanupDirFid = null,
                isHls = false,
                // 电脑端接口也下发 CDN Cookie（实测缺它直链 403 no cookie value）
                guestCookie = cdnCookie.orEmpty()
            )
        }

    /** 新建文件夹：`POST /files/add` → data.cid */
    suspend fun createDir(pid: String, name: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val json = postForm(
                Pan115Constants.FILE_ADD_URL,
                cookie,
                Pan115Constants.REFERER,
                form("pid" to pid.ifBlank { Pan115Constants.ROOT_CID }, "cname" to name)
            )
            checkState(json, AppStrings.get(R.string.e1_create_folder_failed))
            // 有的接口把结果放 data 内层，两层都读
            json.optString("cid").ifBlank { json.optJSONObject("data")?.optString("cid").orEmpty() }
                .takeIf { it.isNotBlank() }
        }

    // ---------- 上传 ----------

    /**
     * 上传一个文件到 [pid] 目录，返回网盘给出的文件 id（读不到返回 null）。
     *
     * 115 网页端只有「OSS 单次表单直传」这一条路：先向 `sampleinitupload.php` 要一份上传策略
     * （host/object/policy/accessid/signature/callback），再把文件作为 `file` 字段连同策略一起
     * POST 到 OSS，OSS 回调里带回 file_id。
     *
     * ★ 没有分片、没有秒传、没有断点续传：整个文件一次 multipart 发完，超过 5 GB 只能换官方客户端。
     * ★ OSS 阶段不带 Cookie（策略里已经签好权限），带上反而可能被对象存储拒。
     */
    suspend fun upload(
        io: UploadIo,
        file: UploadSource,
        pid: String,
        cookie: String
    ): String? = withContext(Dispatchers.IO) {
        if (file.size > Pan115Constants.UPLOAD_MAX_SIZE) {
            throw IllegalStateException(AppStrings.get(R.string.e1_115_upload_too_large))
        }
        io.progress(UploadPhase.PREPARING)
        val init = postForm(
            Pan115Constants.UPLOAD_INIT_URL,
            cookie,
            Pan115Constants.REFERER,
            form(
                "filename" to file.name,
                "filesize" to file.size.toString(),
                "target" to "U_1_${pid.ifBlank { Pan115Constants.ROOT_CID }}",
                "path" to ""
            )
        )
        // 成功是裸策略对象；带 state 的是标准错误信封（登录过期/空间不足等）
        if (init.has("state")) checkState(init, AppStrings.get(R.string.e1_get_upload_url_failed))
        val host = init.optString("host")
        val objectKey = init.optString("object")
        val policy = init.optString("policy")
        val accessId = init.optString("accessid")
        val signature = init.optString("signature")
        val callback = init.optString("callback")
        val uri = runCatching { java.net.URI(host) }.getOrNull()
        val hostName = uri?.host.orEmpty()
        if (!hostName.endsWith(".aliyuncs.com")) {
            throw IllegalStateException(AppStrings.get(R.string.e1_115_invalid_upload_server))
        }
        if (objectKey.isBlank() || policy.isBlank() || accessId.isBlank() ||
            signature.isBlank() || callback.isBlank()
        ) {
            throw IllegalStateException(AppStrings.get(R.string.e1_115_incomplete_upload_credential))
        }
        val response = io.send(
            url = "https://$hostName${uri?.rawPath.orEmpty()}",
            method = "POST",
            headers = mapOf("User-Agent" to Pan115Constants.WEB_UA),
            fields = listOf(
                "name" to file.name,
                "key" to objectKey,
                "policy" to policy,
                "OSSAccessKeyId" to accessId,
                "success_action_status" to "200",
                "callback" to callback,
                "signature" to signature
            ),
            retries = 0
        )
        if (!response.isSuccessful) {
            throw IllegalStateException(AppStrings.get(R.string.e1_upload_failed_http, response.code))
        }
        io.progress(UploadPhase.FINISHING, file.size)
        val json = runCatching { response.json() }.getOrNull() ?: return@withContext null
        if (json.has("state") && !json.optBoolean("state")) {
            throw IllegalStateException(json.optString("error").ifBlank { AppStrings.get(R.string.e1_115_upload_failed) })
        }
        json.optJSONObject("data")?.optString("file_id")?.takeIf { it.isNotBlank() }
            ?: json.optString("file_id").takeIf { it.isNotBlank() }
    }

    /** 重命名：`POST /files/batch_rename`（文档 §3.3） */
    suspend fun rename(id: String, newName: String, cookie: String) = withContext(Dispatchers.IO) {
        val json = postForm(
            Pan115Constants.FILE_RENAME_URL,
            cookie,
            Pan115Constants.REFERER,
            form(
                "fid" to id,
                "file_name" to newName,
                "files_new_name[$id]" to newName
            )
        )
        checkState(json, AppStrings.get(R.string.e1_rename_failed))
        Unit
    }

    /** 移动：`POST /files/move`（异步，响应含 move_proid 时轮询进度，文档 §3.2） */
    suspend fun move(fids: List<String>, toPid: String, cookie: String) = withContext(Dispatchers.IO) {
        val ids = fids.filter { it.isNotBlank() }
        if (ids.isEmpty()) throw IllegalStateException(AppStrings.get(R.string.e1_115_select_files_to_move))
        val pairs = buildList {
            add("pid" to toPid.ifBlank { Pan115Constants.ROOT_CID })
            ids.forEachIndexed { index, id -> add("fid[$index]" to id) }
        }
        val json = postForm(
            Pan115Constants.FILE_MOVE_URL,
            cookie,
            Pan115Constants.REFERER,
            form(*pairs.toTypedArray())
        )
        checkState(json, AppStrings.get(R.string.e1_move_failed))
        // 115 的字段层级不稳定（`share/send` 就把信息放在 data 内层，见 createShare 注释），两层都读
        val moveId = json.optString("move_proid")
            .ifBlank { json.optJSONObject("data")?.optString("move_proid").orEmpty() }
            .takeIf { it.isNotBlank() } ?: return@withContext
        repeat(Pan115Constants.MOVE_POLL_MAX) {
            delay(Pan115Constants.MOVE_POLL_INTERVAL_MS)
            val progress = runCatching {
                getJson(
                    "${Pan115Constants.MOVE_PROGRESS_URL}?move_proid=${encode(moveId)}",
                    cookie,
                    Pan115Constants.REFERER
                )
            }.getOrNull() ?: return@withContext
            if (!progress.optBoolean("state", false)) return@withContext
            val data = progress.optJSONObject("data") ?: return@withContext
            val percent = data.optDouble("percent", 0.0)
            val count = data.optInt("count", 0)
            if (percent >= 100.0 || count <= 0) return@withContext
        }
    }

    /** 删除（进回收站）：`POST /rb/delete`；errno=800007 时按文档再确认一次 */
    suspend fun delete(fids: List<String>, pid: String, cookie: String) = withContext(Dispatchers.IO) {
        val ids = fids.filter { it.isNotBlank() }
        if (ids.isEmpty()) throw IllegalStateException(AppStrings.get(R.string.e1_115_select_files_to_delete))
        val pairs = buildList {
            add("pid" to pid.ifBlank { Pan115Constants.ROOT_CID })
            ids.forEachIndexed { index, id -> add("fid[$index]" to id) }
            add("ignore_warn" to "1")
        }
        val body = form(*pairs.toTypedArray())
        var json = postForm(Pan115Constants.FILE_DELETE_URL, cookie, Pan115Constants.REFERER, body)
        if (errnoOf(json) == Pan115Constants.ERRNO_NEED_CONFIRM) {
            json = postForm(Pan115Constants.FILE_DELETE_URL, cookie, Pan115Constants.REFERER, body)
        }
        checkState(json, AppStrings.get(R.string.e1_delete_failed))
        Unit
    }

    // ---------- 分享解析（文档 §3） ----------

    /**
     * 分享列目录：`GET /share/snap`（`cid=0` 为分享根）。
     * 同时带回分享者 `user_id`（取直链必需）与分享标题。
     */
    suspend fun getSharePage(
        shareCode: String,
        receiveCode: String,
        cid: String,
        cookie: String,
        offset: Int = 0,
        limit: Int = Pan115Constants.SHARE_PAGE_LIMIT
    ): Pan115SharePage = withContext(Dispatchers.IO) {
        val url = buildString {
            append(Pan115Constants.SHARE_SNAP_URL)
            append("?share_code=").append(encode(shareCode))
            append("&receive_code=").append(encode(receiveCode))
            append("&offset=").append(offset)
            append("&limit=").append(limit)
            append("&asc=0&cid=").append(encode(cid.ifBlank { Pan115Constants.ROOT_CID }))
            append("&format=json")
        }
        val json = getJson(url, cookie, Pan115Constants.shareReferer(shareCode, receiveCode))
        checkState(json, AppStrings.get(R.string.e1_get_share_list_failed))
        val data = json.optJSONObject("data") ?: return@withContext Pan115SharePage(
            files = emptyList(),
            total = 0,
            userId = "",
            title = "",
            shareState = 0
        )
        val shareInfo = data.optJSONObject("shareinfo")
        Pan115SharePage(
            files = parseShareFiles(data),
            total = data.optInt("count", 0),
            userId = data.optJSONObject("userinfo")?.optString("user_id").orEmpty(),
            title = shareInfo?.optString("share_title").orEmpty(),
            shareState = data.optInt("share_state", shareInfo?.optInt("share_state") ?: 0)
        )
    }

    /**
     * 分享直链：`GET /share/downurl`（`user_id` 必须是分享者 UID）。
     * 响应头同样会下发 900 秒 CDN Cookie。
     * **必须用客户端 UA**：浏览器 UA 会被判「版本过低」直接拒发直链（50029），见 [Pan115Constants.CLIENT_UA]。
     */
    suspend fun getShareDownloadLink(
        shareCode: String,
        receiveCode: String,
        userId: String,
        file: ShareFile,
        cookie: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        if (userId.isBlank()) throw IllegalStateException(AppStrings.get(R.string.e1_115_missing_sharer_id))
        refreshClientVersion()
        val url = buildString {
            append(Pan115Constants.SHARE_DOWNLOAD_URL)
            append("?dl=1")
            append("&user_id=").append(encode(userId))
            append("&share_code=").append(encode(shareCode))
            append("&file_id=").append(encode(file.fid))
            if (receiveCode.isNotBlank()) {
                append("&receive_code=").append(encode(receiveCode))
            }
        }
        val (json, cdnCookie) = executeJson(
            baseBuilder(
                url,
                cookie,
                Pan115Constants.shareReferer(shareCode, receiveCode),
                Pan115Constants.CLIENT_UA
            ).get().build()
        )
        checkState(json, AppStrings.get(R.string.e1_get_download_link_failed))
        val data = json.optJSONObject("data") ?: return@withContext null
        // 网页 JS 取的是 file_url_302 || url.url，这里三种形态都兜住
        val fileUrl = data.optString("file_url_302")
            .ifBlank { data.optString("file_url") }
            .ifBlank { data.optJSONObject("url")?.optString("url").orEmpty() }
        if (fileUrl.isBlank()) return@withContext null
        DownloadLink(
            fid = file.fid,
            filename = data.optString("fn").ifBlank { file.fname },
            downloadUrl = fileUrl,
            size = data.optString("fs").toLongOrNull() ?: file.fsize,
            cleanupDirFid = null,
            isHls = false,
            guestCookie = cdnCookie.orEmpty()
        )
    }

    /** 转存到自己的网盘：`POST /share/receive`（不返回新文件 id，见文档 §6） */
    suspend fun receiveShare(
        shareCode: String,
        receiveCode: String,
        fileId: String,
        toCid: String,
        cookie: String
    ) = withContext(Dispatchers.IO) {
        val json = postForm(
            Pan115Constants.SHARE_RECEIVE_URL,
            cookie,
            Pan115Constants.REFERER,
            form(
                "share_code" to shareCode,
                "receive_code" to receiveCode,
                "file_id" to fileId,
                "cid" to toCid.ifBlank { Pan115Constants.ROOT_CID }
            )
        )
        checkState(json, AppStrings.get(R.string.e1_copy_save_failed))
        Unit
    }

    // ---------- 创建分享（文档 §2） ----------

    /**
     * 创建分享并设置有效期。
     *
     * 文档 §2.4：创建请求里没有有效期字段，必须再调 `share/updateshare`，
     * 否则选「长期」也会落成默认的 15 天。
     *
     * @param duration `-1`（长期）/ `1` / `3` / `5` / `7` / `15`（天）
     */
    suspend fun createShare(fileIds: List<String>, duration: String, cookie: String): ShareInfo =
        withContext(Dispatchers.IO) {
            val userId = Pan115Constants.extractUserId(cookie)
                ?: throw IllegalStateException(AppStrings.get(R.string.e1_115_missing_user_id))
            val ids = fileIds.filter { it.isNotBlank() }
            if (ids.isEmpty()) throw IllegalStateException(AppStrings.get(R.string.e1_115_select_files_to_share))
            allowProtocol(cookie)
            val idText = ids.joinToString(",")
            // 预检（ignore_warn=0）拿统计与提示，失败不阻塞真正创建
            runCatching {
                postForm(
                    Pan115Constants.SHARE_SEND_URL,
                    cookie,
                    Pan115Constants.REFERER,
                    form("user_id" to userId, "file_ids" to idText, "ignore_warn" to "0")
                )
            }
            val json = postForm(
                Pan115Constants.SHARE_SEND_URL,
                cookie,
                Pan115Constants.REFERER,
                form("user_id" to userId, "file_ids" to idText, "ignore_warn" to "1")
            )
            checkState(json, AppStrings.get(R.string.e1_create_share_failed))
            // 实测（`/storage/emulated/0/抓包/bug/115/2/`）：`share/send` 的分享信息在 data 内层，
            // 顶层只有 state/error/errno。两层都读，服务端挪字段也不会再炸。
            val data = json.optJSONObject("data") ?: JSONObject()
            fun field(name: String): String = data.optString(name).ifBlank { json.optString(name) }
            val shareCode = field("share_code")
            if (shareCode.isBlank()) throw IllegalStateException(AppStrings.get(R.string.e1_115_create_share_no_code))
            // 有效期只能建完再改（创建接口没有该字段）。改失败**不能丢掉已建好的分享**：
            // 回填服务端给的默认档位，并把失败原因放进 ShareInfo.warning 让界面提示（见 Agent.md §3.25 硬规则 4）。
            val update = runCatching {
                postForm(
                    Pan115Constants.SHARE_UPDATE_URL,
                    cookie,
                    Pan115Constants.REFERER,
                    form("share_code" to shareCode, "share_duration" to duration)
                )
            }
            val updateJson = update.getOrNull()
            val applied = updateJson != null && updateJson.optBoolean("state", false)
            val expiredType = if (applied) {
                ShareExpire.pan115CodeOf(duration)
            } else {
                ShareExpire.pan115CodeOfText(field("share_ex_duration"))
            }
            val warning = if (applied) {
                null
            } else {
                val reason = updateJson?.let { json2 ->
                    json2.optString("error").ifBlank { json2.optString("msg") }
                }?.takeIf { it.isNotBlank() }
                    ?: update.exceptionOrNull()?.message
                    ?: AppStrings.get(R.string.e1_115_unknown_reason)
                AppStrings.get(R.string.e1_115_share_expiry_warning, reason)
            }
            ShareInfo(
                shareUrl = field("share_url")
                    .ifBlank { "${Pan115Constants.SHARE_HOST}/s/$shareCode" },
                passcode = field("receive_code").ifBlank { field("sys_receive_code") },
                pwdId = shareCode,
                title = field("share_title"),
                expiredType = expiredType,
                warning = warning
            )
        }

    // ---------- 内部实现 ----------

    /** 首次分享前确认「公开分享」协议（失败忽略，真正创建时服务端会再判） */
    private suspend fun allowProtocol(cookie: String) {
        if (protocolAllowed) return
        runCatching {
            postForm(
                Pan115Constants.ALLOW_PROTOCOL_URL,
                cookie,
                Pan115Constants.REFERER,
                form("action" to "pubshare")
            )
        }
        protocolAllowed = true
    }

    /** 个人盘列表解析：文件夹无 `fid`（自身 ID 在 `cid`），文件的 `cid` 是父目录（文档 §2.1） */
    private fun parsePersonalFiles(json: JSONObject): List<ShareFile> {
        val array = json.optJSONArray("data") ?: return emptyList()
        val result = ArrayList<ShareFile>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val fid = item.optString("fid")
            val isDir = fid.isBlank()
            val id = if (isDir) item.optString("cid") else fid
            if (id.isBlank()) continue
            result.add(
                ShareFile(
                    fid = id,
                    fname = item.optString("n"),
                    fsize = if (isDir) 0L else item.optLong("s"),
                    isdir = isDir,
                    pdirFid = if (isDir) item.optString("pid") else item.optString("cid"),
                    fidToken = item.optString("pc"),
                    modifyTime = item.optString("t")
                )
            )
        }
        return result
    }

    /** 分享列表解析：`fc == 0` 为目录，目录 ID 取 `cid`、文件 ID 取 `fid` */
    private fun parseShareFiles(data: JSONObject): List<ShareFile> {
        val array = data.optJSONArray("list") ?: return emptyList()
        val result = ArrayList<ShareFile>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val fid = item.optString("fid")
            val isDir = item.optInt("fc", if (fid.isBlank()) 0 else 1) == 0
            val id = if (isDir) item.optString("cid") else fid
            if (id.isBlank()) continue
            result.add(
                ShareFile(
                    fid = id,
                    fname = item.optString("n"),
                    fsize = if (isDir) 0L else item.optLong("s"),
                    isdir = isDir,
                    pdirFid = if (isDir) item.optString("pid") else item.optString("cid"),
                    fidToken = "",
                    modifyTime = item.optString("t")
                )
            )
        }
        return result
    }

    private fun baseBuilder(
        url: String,
        cookie: String,
        referer: String,
        userAgent: String = Pan115Constants.WEB_UA
    ): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", Pan115Constants.X_REQUESTED_WITH)
            .header("Referer", referer)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return builder
    }

    private fun getJson(url: String, cookie: String, referer: String): JSONObject =
        executeJson(baseBuilder(url, cookie, referer).get().build()).first

    private fun postForm(
        url: String,
        cookie: String,
        referer: String,
        body: String,
        userAgent: String = Pan115Constants.WEB_UA
    ): JSONObject =
        executeJson(
            baseBuilder(url, cookie, referer, userAgent)
                // 与网页版一致（抓到的是 `application/x-www-form-urlencoded; charset=utf-8`）
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .post(body.toRequestBody(formMediaType))
                .build()
        ).first

    /**
     * 同 [postForm]，但把响应头里下发的「下载专用 Cookie」一起返回。
     *
     * 电脑端取链接口 [Pan115Constants.APP_DOWNLOAD_URL] 也会下发 path 绑定的 CDN Cookie
     * （实测缺它直链 CDN 返回 403 `no cookie value`，带上就是 206），所以这条路径必须保留 Cookie。
     */
    private fun postFormWithCookie(
        url: String,
        cookie: String,
        referer: String,
        body: String,
        userAgent: String = Pan115Constants.WEB_UA
    ): Pair<JSONObject, String?> =
        executeJson(
            baseBuilder(url, cookie, referer, userAgent)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .post(body.toRequestBody(formMediaType))
                .build()
        )

    /** 执行请求：返回响应 JSON 与响应头里下发的「下载专用 Cookie」（文档 §4.2） */
    private fun executeJson(request: Request): Pair<JSONObject, String?> {
        client.newCall(request).execute().use { response ->
            val downloadCookie = downloadCookie(response.headers("Set-Cookie"))
            val body = response.body?.string()
                ?: throw IllegalStateException(AppStrings.get(R.string.e1_response_empty_http, response.code))
            if (!response.isSuccessful && body.isBlank()) {
                throw IllegalStateException(AppStrings.get(R.string.e1_request_failed_http, response.code))
            }
            return JSONObject(body) to downloadCookie
        }
    }

    /** 从 Set-Cookie 里取第一个 `name=value`（文档 §4.2：32 位 hex 名值、path 绑定 object） */
    private fun downloadCookie(headers: List<String>): String? {
        var fallback: String? = null
        for (raw in headers) {
            val pair = raw.substringBefore(';').trim()
            val name = pair.substringBefore('=', "")
            if (name.isBlank() || !pair.contains('=')) continue
            if (name.equals("expires", true) || name.equals("path", true) || name.equals("domain", true)) {
                continue
            }
            if (fallback == null) fallback = pair
            // 直链 CDN 只认 path 绑定的那条（`path=/<对象路径>/`），其余（如 WAF 的 acw_tc）不是它
            val path = PATH_ATTR_REGEX.find(raw)?.groupValues?.get(1)?.trim()
            if (!path.isNullOrBlank() && path != "/") return pair
        }
        return fallback
    }

    private companion object {
        /** 匹配 Set-Cookie 里的 path 属性 */
        val PATH_ATTR_REGEX = Regex("""[;\s]path=([^;]+)""", RegexOption.IGNORE_CASE)
    }

    private fun errnoOf(json: JSONObject): Int = json.optInt("errno", json.optInt("errNo", 0))

    /** 成功判定：`state == true`；失败按抓包错误码给出可读提示（文档 §7） */
    private fun checkState(json: JSONObject, fallback: String) {
        if (json.optBoolean("state", false)) return
        val errno = errnoOf(json)
        val raw = json.optString("error").ifBlank { json.optString("msg") }
        val message = when (errno) {
            Pan115Constants.ERRNO_LOGIN_TIMEOUT -> AppStrings.get(R.string.e1_115_login_expired)
            Pan115Constants.ERRNO_NEED_CODE -> AppStrings.get(R.string.e1_115_need_access_code)
            Pan115Constants.ERRNO_WRONG_CODE -> AppStrings.get(R.string.e1_115_wrong_access_code)
            Pan115Constants.ERRNO_SHARE_GONE -> AppStrings.get(R.string.e1_115_share_gone)
            Pan115Constants.ERRNO_EMPTY_FOLDER -> AppStrings.get(R.string.e1_115_empty_folder_share)
            Pan115Constants.ERRNO_BUSY -> AppStrings.get(R.string.e1_115_busy)
            // 115 按 UA 判定「网页端/客户端」：浏览器 UA 拿不到大文件直链（抓包见 bug/115/），
            // 正常情况已改用客户端 UA，这里只兜底给出可执行提示
            Pan115Constants.ERRNO_WEB_SIZE_LIMIT ->
                AppStrings.get(R.string.e1_115_web_size_limit)
            Pan115Constants.ERRNO_CLIENT_TOO_OLD ->
                AppStrings.get(R.string.e1_115_client_too_old)
            else -> raw.ifBlank { fallback }
        }
        val suffix = if (errno != 0 && !message.contains(errno.toString())) {
            AppStrings.get(R.string.e1_errno_suffix, errno)
        } else ""
        throw IllegalStateException(message + suffix)
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
