package com.yunx.app.data.network

import android.util.Base64
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ThreadLocalRandom
import java.util.zip.CRC32

/**
 * 123 云盘 API 封装（OkHttp，依据《123网盘API文档_面向Agent.md》）。
 *
 * 鉴权体系（文档 §3.2 / §6）：
 * - 登录凭证：由登录页从网页（yun.123pan.cn）localStorage 提取 authorToken（Bearer JWT）；
 * - 分享列表 `yun.123pan.cn/b/api/share/get`：匿名、无需签名；
 * - 其余 yun.123pan.cn / www.123865.com 鉴权请求：必须带 `auth-key` / `auth-value` 签名头
 *   （CRC32 派生，算法已抓包实证 + 实时验证，见 [makeSign]）。
 *
 * 下载流程（文档 §4.2）：分享文件无需转存——直接拿 ShareKey + FileID + S3KeyFlag + Etag + Size
 * 换 `DownloadURL`（download-v2 包装），对 params 做 Base64 解码得真实 CDN 直链，下载带 Referer。
 */
class Pan123Api(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    private val jsonMediaType = "application/json;charset=UTF-8".toMediaType()

    /** 设备标识（文档 §3.2：同一会话内不变、不参与签名；进程级固定即可） */
    private val loginuuid: String = Pan123Constants.newLoginUuid()

    // ---------- 签名算法（文档 §6，已抓包逐字还原 + 实时验证） ----------

    /** 标准 CRC-32（IEEE 802.3）→ 8 位小写十六进制（与 Python zlib.crc32 & 0xFFFFFFFF format 'x' 一致） */
    private fun crc32Hex(s: String): String {
        val crc = CRC32()
        crc.update(s.toByteArray(Charsets.UTF_8))
        return java.lang.Long.toHexString(crc.value and 0xFFFFFFFFL)
    }

    /**
     * 生成 123 云盘签名头（文档 §6.2）：
     * - auth-key (timeSign) = crc32_hex(替换表映射后的 UTC "YYYYMMDDHHmm"，基准 ts + 57600s = +16h)；
     * - auth-value = "<ts>-<random>-<crc32_hex(ts|random|path|web|3|auth_key)>"；
     * 签名内部固定 OS=web / VER=3（与请求头 platform/app-version 无关，文档 §6.3）。
     * @param path URL 路径：含 /b 前缀、不含 host、不含 query（如 /b/api/share/download/info）
     */
    fun makeSign(path: String, ts: Long = System.currentTimeMillis() / 1000): Pair<String, String> {
        // 1) auth-key (timeSign)：ts + 16h 以 UTC 格式化为 YYYYMMDDHHmm，逐数字替换
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = (ts + Pan123Constants.SIGN_OFFSET_SECONDS) * 1000L
        }
        val minute = String.format(
            "%04d%02d%02d%02d%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE)
        )
        val substituted = minute.map { Pan123Constants.SIGN_TABLE[it - '0'] }.joinToString("")
        val authKey = crc32Hex(substituted)

        // 2) auth-value：ts|random|path|web|3|auth_key 的 crc32
        val random = ThreadLocalRandom.current().nextInt(0, 10_000_000)
        val data = "$ts|$random|$path|${Pan123Constants.SIGN_OS}|${Pan123Constants.SIGN_VER}|$authKey"
        val authValue = "$ts-$random-${crc32Hex(data)}"
        return authKey to authValue
    }

    // ---------- 用户信息（文档 §5.11） ----------

    /** 校验登录态 + 取昵称：GET /b/api/user/info → data.Nickname；失败返回 null */
    suspend fun fetchNickname(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val json = getAuth(Pan123Constants.USER_INFO_URL, "/b/api/user/info", token)
            checkOk(json, "获取用户信息失败")
            json.optJSONObject("data")?.optString("Nickname")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 网盘空间详情：GET /b/api/user/info → SpaceUsed / SpacePermanent / SpaceTemp（文档 §5.11） */
    suspend fun getQuota(token: String): QuotaInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val json = getAuth(Pan123Constants.USER_INFO_URL, "/b/api/user/info", token)
            checkOk(json, "获取空间详情失败")
            val data = json.optJSONObject("data") ?: return@runCatching null
            QuotaInfo(
                used = data.optLong("SpaceUsed"),
                total = data.optLong("SpacePermanent") + data.optLong("SpaceTemp")
            )
        }.getOrNull()
    }

    // ---------- 分享文件列表（文档 §5.2，匿名、无签名） ----------

    /**
     * 读取分享文件/目录列表（匿名），支持提取码、翻页、进入子目录。
     * @return (文件列表, 下一页游标 or null=末页)。文档 §5.2：`Next=="-1"` 无下一页，空串 `""` 表示还有下一页。
     */
    suspend fun getShareFiles(
        shareKey: String,
        sharePwd: String,
        parentFileId: String,
        next: String,
        page: Int
    ): Pair<List<ShareFile>, String?> = withContext(Dispatchers.IO) {
        // 参数顺序与抓包一致（§5.2 文件夹分享/有提取码）；⚠️ 无提取码时不传 SharePwd（传空值会 400 "请输入Next"）
        val url = buildString {
            append(Pan123Constants.SHARE_GET_URL)
            append("?limit=100")
            append("&next=").append(next)
            append("&orderBy=file_name")
            append("&orderDirection=asc")
            append("&shareKey=").append(URLEncoder.encode(shareKey, "UTF-8"))
            append("&ParentFileId=").append(parentFileId)
            append("&Page=").append(page)
            if (sharePwd.isNotBlank()) {
                append("&SharePwd=").append(URLEncoder.encode(sharePwd, "UTF-8"))
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Pan123Constants.DART_UA)
            .get()
            .build()
        val json = executeJson(request)
        checkOk(json, "获取文件列表失败")
        val data = json.optJSONObject("data") ?: return@withContext Pair(emptyList(), null)
        if (data.optBoolean("Expired", false)) {
            throw IllegalStateException("分享已失效")
        }
        val files = parseInfoList(data)
        // 文档 §5.2：Next=="-1" 无下一页；空串 "" 表示还有下一页（需继续翻页）；数字为游标
        val nextCursor = data.optString("Next").takeIf { it != "-1" }
        Pair(files, nextCursor)
    }

    // ---------- 分享下载信息（文档 §5.3，需登录+签名） ----------

    /**
     * 分享文件取下载直链（POST /b/api/share/download/info）。
     * @param file 列表项（fidToken 编码了 "S3KeyFlag|Etag"）
     * @param token Bearer JWT
     * @return 解码后的真实 CDN 直链（下载需带 Referer: https://yun.123pan.cn/）
     */
    suspend fun getShareDownloadLink(
        shareKey: String,
        file: ShareFile,
        token: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val (s3KeyFlag, etag, _) = decodeToken(file.fidToken)
        val body = JSONObject()
            .put("ShareKey", shareKey)
            .put("FileID", file.fid)
            .put("S3KeyFlag", s3KeyFlag)
            .put("Size", file.fsize)
            .put("Etag", etag)
        // 签名 path 与请求头一致（含 /b）；分享下载信息走 android 平台头，签名内部仍固定 web/3（文档 §6.3）
        val json = postAuth(
            Pan123Constants.SHARE_DOWNLOAD_INFO_URL,
            "/b/api/share/download/info",
            body.toString(),
            token,
            platform = Pan123Constants.PLATFORM_ANDROID,
            appVersion = Pan123Constants.APP_VERSION_ANDROID
        )
        checkOk(json, "获取下载链接失败")
        val data = json.optJSONObject("data") ?: return@withContext null
        val downloadUrl = data.optString("DownloadURL")
        if (downloadUrl.isBlank()) return@withContext null
        // download-v2 包装 URL → Base64 解码 params 得真实 CDN 文件 URL（文档 §5.3.1）
        val decoded = decodeDownloadUrl(downloadUrl) ?: downloadUrl
        // 同样循环跟随可能存在的 redirect_url（auto_redirect=0）
        val realUrl = followRedirectUrl(decoded)
        DownloadLink(
            fid = file.fid,
            filename = file.fname,
            downloadUrl = realUrl,
            size = file.fsize
        )
    }

    // ---------- 个人盘（网盘页，需登录+签名） ----------

    /** 个人盘文件列表：GET /b/api/file/list/new（文档 §5.4）。返回 (文件列表, 下一页游标 or null) */
    suspend fun listCloudFiles(parentFileId: String, token: String): Pair<List<ShareFile>, String?> =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(Pan123Constants.FILE_LIST_URL)
                append("?driveId=0&limit=100&next=0&orderBy=update_time&orderDirection=desc")
                append("&parentFileId=").append(parentFileId)
                append("&trashed=false&SearchData=&Page=1&OnlyLookAbnormalFile=0")
                append("&event=homeListFile&operateType=1&inDirectSpace=false")
            }
            val json = getAuth(url, "/b/api/file/list/new", token)
            checkOk(json, "获取文件列表失败")
            val data = json.optJSONObject("data") ?: return@withContext Pair(emptyList(), null)
            val files = parseInfoList(data)
            val next = data.optString("Next").takeIf { it != "-1" }
            Pair(files, next)
        }

    /** 个人盘下载信息：POST /api/file/download_info（注意无 /b/，文档 §5.5）。返回真实直链 */
    suspend fun getDownloadLink(file: ShareFile, token: String): DownloadLink? = withContext(Dispatchers.IO) {
        val (s3keyFlag, etag, _) = decodeToken(file.fidToken)
        val body = JSONObject()
            .put("driveId", 0)
            .put("etag", etag)
            .put("fileId", file.fid.toLongOrNull() ?: 0L)
            .put("s3keyFlag", s3keyFlag)
            .put("type", 0)
            .put("fileName", file.fname)
            .put("size", file.fsize)
        val json = postAuth(
            Pan123Constants.FILE_DOWNLOAD_INFO_URL,
            "/api/file/download_info",
            body.toString(),
            token
        )
        checkOk(json, "获取下载链接失败")
        val data = json.optJSONObject("data") ?: return@withContext null
        val raw = data.optString("DownloadUrl")
        if (raw.isBlank()) return@withContext null
        // ★ 个人盘同样存在 download-v2?params=<base64> 包装（Web 平台头触发，Web 中转页不是可下载直链）：
        //   统一过 decodeDownloadUrl——能解码就给真实 CDN 直链；直链形态 decode 返回 null 回退 raw。
        //   绝不能用 startsWith("http") 短路：中转页 URL 同样以 http 开头，无法区分。
        val decoded = decodeDownloadUrl(raw) ?: raw
        // ★ 解码直链带 auto_redirect=0 时，CDN 返回 JSON（data.redirect_url）而非直接文件：
        //   取链阶段循环跟随 redirect_url，交给下载引擎的必须是最终可下载地址。
        val url = followRedirectUrl(decoded)
        DownloadLink(
            fid = file.fid,
            filename = file.fname,
            downloadUrl = url,
            size = file.fsize
        )
    }

    // ---------- 网盘管理操作（文档 §5.7-5.10） ----------

    /**
     * 保存他人分享到个人网盘（copy/save，文档 §4.3）。
     * ⚠️ mshare 子域**无需任何客户端签名**（源码实证 + 实测 code:0），仅带 Bearer + LoginUuid。
     * 转存是异步任务：返回 (taskID, ShareId) 用于轮询 copy/save/get。
     * @param toDirFid 转存目标目录 ID（个人盘 fileId；body 的 parentFileID/parentFileId 字段）
     */
    suspend fun copySave(
        shareKey: String,
        sharePwd: String,
        file: ShareFile,
        toDirFid: String,
        token: String
    ): Pair<Long, String>? = withContext(Dispatchers.IO) {
        val shareId = shareIdOf(file)
        if (shareId.isBlank()) throw IllegalStateException("无法识别分享 ID（缺少 S3KeyFlag）")
        val (s3KeyFlag, etag, storageNode) = decodeToken(file.fidToken)
        val fileId = file.fid.toLongOrNull() ?: 0L
        val parentId = toDirFid.toLongOrNull() ?: 0L
        val body = JSONObject()
            .put(
                "fileList",
                JSONArray().put(
                    JSONObject()
                        .put("fileID", fileId)
                        .put("fileId", fileId)
                        .put("size", file.fsize)
                        .put("etag", etag)
                        .put("type", if (file.isdir) 1 else 0)
                        .put("parentFileID", parentId)
                        .put("parentFileId", parentId)
                        .put("fileName", file.fname)
                        .put("driveID", 0)
                        .put("driveId", 0)
                        .put("s3keyFlag", s3KeyFlag)
                        .put("S3KeyFlag", s3KeyFlag)
                        .put("StorageNode", storageNode)
                )
            )
            .put("shareKey", shareKey)
            // 无提取码发空串 ""，不要发 null（文档 §4.3）
            .put("sharePwd", sharePwd.ifBlank { "" })
            .put("currentLevel", 1)
            .put("superAdmin", JSONObject.NULL)
        val request = Request.Builder()
            .url("https://$shareId.mshare.123pan.cn/b/api/restful/goapi/v1/file/copy/save")
            .header("Authorization", "Bearer $token")
            .header("LoginUuid", loginuuid)
            .header("platform", Pan123Constants.PLATFORM_WEB)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("User-Agent", Pan123Constants.DART_UA)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        val json = executeJson(request)
        checkOk(json, "转存失败")
        val taskId = json.optJSONObject("data")?.optLong("taskID") ?: return@withContext null
        taskId to shareId
    }

    /**
     * 轮询转存任务结果（GET copy/save/get?taskID=，同样无需签名）。
     * @return 转存成功后的新 fileId（无法解析时返回 taskId 字符串兜底）；超时返回 null
     */
    suspend fun pollCopySave(taskId: Long, shareId: String, token: String): String? = withContext(Dispatchers.IO) {
        repeat(15) {
            kotlinx.coroutines.delay(1000)
            val url =
                "https://$shareId.mshare.123pan.cn/b/api/restful/goapi/v1/file/copy/save/get?taskID=$taskId"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("LoginUuid", loginuuid)
                .header("platform", Pan123Constants.PLATFORM_WEB)
                .header("User-Agent", Pan123Constants.DART_UA)
                .get()
                .build()
            val json = executeJson(request)
            if (json.optInt("code", -1) != 0) {
                // 任务失败/异常：读取 message 抛错（若只是进行中则继续轮询）
                val msg = json.optString("message")
                if (msg.isNotBlank()) throw IllegalStateException("转存失败：$msg")
                return@repeat
            }
            val data = json.optJSONObject("data") ?: return@repeat
            // 完成标志（响应格式未在抓包完整呈现，容错多种形态）：
            val status = data.optInt("status", -1)
            val state = data.optString("state").lowercase()
            val done = data.optBoolean("finished", false) ||
                status == 2 || status == 3 ||
                state == "success" || state == "done" || state == "2" ||
                data.has("fileId") || data.has("FileId") || data.has("newFileId")
            if (done) {
                return@withContext data.optString("newFileId")
                    .ifBlank { data.optString("FileId") }
                    .ifBlank { data.optString("fileId") }
                    .ifBlank { taskId.toString() }
            }
        }
        null
    }

    /** 从分享列表项提取数值 ShareId（S3KeyFlag 形如 "1816216065-0"，前缀即 mshare 子域数字） */
    private fun shareIdOf(file: ShareFile): String {
        val s3 = file.fidToken.substringBefore('|')
        return s3.substringBefore('-')
    }

    /** 删除（移入回收站）：POST /b/api/file/trash */
    suspend fun deleteFiles(files: List<ShareFile>, token: String) = withContext(Dispatchers.IO) {
        val list = JSONArray()
        files.forEach { f ->
            val (s3, etag, _) = decodeToken(f.fidToken)
            list.put(
                JSONObject()
                    .put("FileId", f.fid.toLongOrNull() ?: 0L)
                    .put("FileName", f.fname)
                    .put("Type", if (f.isdir) 1 else 0)
                    .put("Size", f.fsize)
                    .put("S3KeyFlag", s3)
                    .put("Etag", etag)
            )
        }
        val body = JSONObject()
            .put("driveId", 0)
            .put("fileTrashInfoList", list)
            .put("operation", true)
            .put("event", "intoRecycle")
            .put("operatePlace", 1)
            .put("safeBox", false)
        val json = postAuth(Pan123Constants.FILE_TRASH_URL, "/b/api/file/trash", body.toString(), token)
        checkOk(json, "删除失败")
    }

    /** 重命名：POST /b/api/file/rename */
    suspend fun renameFile(fileId: String, newName: String, token: String) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("driveId", 0)
            .put("fileId", fileId.toLongOrNull() ?: 0L)
            .put("fileName", newName)
            .put("duplicate", 1)
            .put("event", "fileRename")
            .put("operatePlace", "right")
            .put("RequestSource", JSONObject.NULL)
        val json = postAuth(Pan123Constants.FILE_RENAME_URL, "/b/api/file/rename", body.toString(), token)
        checkOk(json, "重命名失败")
    }

    /** 移动：POST /b/api/file/mod_pid */
    suspend fun moveFiles(fileIds: List<String>, toParentFileId: String, token: String) = withContext(Dispatchers.IO) {
        val list = JSONArray()
        fileIds.forEach { list.put(JSONObject().put("FileId", it.toLongOrNull() ?: 0L)) }
        val body = JSONObject()
            .put("fileIdList", list)
            .put("parentFileId", toParentFileId.toLongOrNull() ?: 0L)
            .put("event", "fileMove")
            .put("operatePlace", 1)
            .put("RequestSource", JSONObject.NULL)
        val json = postAuth(Pan123Constants.FILE_MOD_PID_URL, "/b/api/file/mod_pid", body.toString(), token)
        checkOk(json, "移动失败")
    }

    /**
     * 创建分享：POST /b/api/share/create（文档 §5.10）。
     * @param fileIds 文件/目录 ID 列表（单文件抓包为标量 int，多文件用数组）
     * @param expiration 过期时间 ISO（永久用 Pan123Constants.EXPIRATION_FOREVER）
     * @param sharePwd 提取码（null/空 = 无提取码）
     */
    suspend fun createShare(
        fileIds: List<String>,
        shareName: String,
        expiration: String,
        sharePwd: String?,
        token: String
    ): ShareInfo = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("driveId", 0)
            .put("expiration", expiration)
            .apply {
                if (fileIds.size == 1) {
                    put("fileIdList", fileIds[0].toLongOrNull() ?: 0L)
                } else {
                    put("fileIdList", JSONArray().apply { fileIds.forEach { put(it.toLongOrNull() ?: 0L) } })
                }
            }
            .put("shareName", shareName)
            .put("event", "shareCreate")
            .put("fileNum", fileIds.size)
            .put("shareModality", 4)
            .put("trafficLimitSwitch", 1)
            .put("trafficLimit", 0)
            .put("trafficSwitch", 1)
            .put("fillPwdSwitch", 0)
            .apply { if (!sharePwd.isNullOrBlank()) put("sharePwd", sharePwd) }
        val json = postAuth(Pan123Constants.SHARE_CREATE_URL, "/b/api/share/create", body.toString(), token)
        checkOk(json, "创建分享失败")
        val data = json.optJSONObject("data")
            ?: throw IllegalStateException("创建分享失败：未返回数据")
        val shareKey = data.optString("ShareKey")
        if (shareKey.isBlank()) throw IllegalStateException("创建分享失败：未返回 ShareKey")
        val linkList = data.optJSONObject("shareLinkList")
        val shareUrl = linkList?.optJSONArray("list")?.optString(0)
            ?.takeIf { it.isNotBlank() }
            ?: linkList?.optString("standBy")
                ?.takeIf { it.isNotBlank() }
                ?: "https://www.123pan.com/s/$shareKey"
        ShareInfo(
            shareUrl = shareUrl,
            passcode = sharePwd.orEmpty(),
            pwdId = shareKey,
            title = shareName,
            expiredType = if (expiration == Pan123Constants.EXPIRATION_FOREVER) 1 else 4
        )
    }

    // ---------- 内部工具 ----------

    /** 解析响应 InfoList（分享与个人盘结构一致，文档 §5.2/§5.4） */
    private fun parseInfoList(data: JSONObject): List<ShareFile> {
        val arr = data.optJSONArray("InfoList") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val type = item.optInt("Type", 0)
                add(
                    ShareFile(
                        fid = item.optString("FileId"),
                        fname = item.optString("FileName"),
                        fsize = item.optLong("Size"),
                        isdir = type == 1,
                        pdirFid = item.optString("ParentFileId"),
                        // 123 下载/转存需要 S3KeyFlag + Etag + StorageNode，编码进 fidToken："S3KeyFlag|Etag|StorageNode"
                        fidToken = "${item.optString("S3KeyFlag")}|${item.optString("Etag")}|${item.optString("StorageNode")}",
                        modifyTime = item.optString("UpdateAt")
                    )
                )
            }
        }
    }

    /** 解码 fidToken（"S3KeyFlag|Etag|StorageNode"；旧格式两段时 StorageNode 为空） */
    private fun decodeToken(fidToken: String): Triple<String, String, String> {
        val parts = fidToken.split('|')
        return Triple(
            parts.getOrNull(0) ?: "",
            parts.getOrNull(1) ?: "",
            parts.getOrNull(2) ?: ""
        )
    }

    /** 解码 123 下载 URL（兼容两种形态，文档 §5.3.1）：
     *  - 形态 1：整段 base64（alist 风格）→ 直接解码
     *  - 形态 2：download-v2?params=<base64 URL-safe> → 解码 params
     */
    private fun decodeDownloadUrl(downloadUrl: String): String? {
        val trimmed = downloadUrl.trim()
        // 形态 1：整段 base64（不含协议头的串）
        if (!trimmed.contains("://")) {
            return runCatching {
                String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
                    .takeIf { it.startsWith("http", ignoreCase = true) }
            }.getOrNull()
        }
        // 形态 2：download-v2?params=<base64>
        val idx = trimmed.indexOf("params=")
        if (idx < 0) return null
        val params = trimmed.substring(idx + "params=".length).substringBefore("&")
        return runCatching {
            val normalized = params.replace('-', '+').replace('_', '/')
            String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * 跟随 123 CDN 的 redirect_url：带 `auto_redirect=0` 时，GET 直链返回
     * JSON `{"code":0,"data":{"redirect_url":"https://...pd1.cjjd19.com/..."}}` 而非直接文件，
     * 且 redirect_url 自身也可能带 auto_redirect=0（可能多跳）。这里循环跟随（最多 5 跳），
     * 每跳仅当响应体很小（≤8KB，JSON 跳转页）才读取解析；大响应视为真实文件流，返回当前 URL。
     */
    private fun followRedirectUrl(initialUrl: String): String {
        var url = initialUrl
        repeat(5) {
            val next = probeJsonRedirect(url) ?: return url
            url = next
        }
        return url
    }

    /** 探测单跳：响应为小 JSON 且含 data.redirect_url 时返回新地址，否则 null（当前 URL 即最终可下载地址） */
    private fun probeJsonRedirect(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Referer", Pan123Constants.DOWNLOAD_REFERER)
            .header("User-Agent", Pan123Constants.DART_UA)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val len = response.header("Content-Length")?.toLongOrNull() ?: -1L
            if (len >= 0 && len <= 8192) {
                val body = response.body?.string() ?: return@use null
                if (body.trimStart().startsWith("{")) {
                    runCatching {
                        JSONObject(body).optJSONObject("data")
                            ?.optString("redirect_url")
                            ?.takeIf { it.isNotBlank() }
                    }.getOrNull()
                } else null
            } else null
        }
    }.getOrNull()

    /** 成功判定：code == 0（登录接口除外，为 200） */
    private fun checkOk(json: JSONObject, fallback: String) {
        val code = json.optInt("code", -1)
        if (code == 0) return
        val msg = json.optString("message").ifBlank { fallback }
        throw IllegalStateException("$msg（code=$code）")
    }

    /** 鉴权 GET（带 auth-key/auth-value 签名头） */
    private fun getAuth(url: String, path: String, token: String): JSONObject {
        val (ak, av) = makeSign(path)
        val request = Request.Builder()
            .url(url)
            .header("platform", Pan123Constants.PLATFORM_WEB)
            .header("app-version", Pan123Constants.APP_VERSION_WEB)
            .header("authorization", "Bearer $token")
            .header("loginuuid", loginuuid)
            .header("auth-key", ak)
            .header("auth-value", av)
            .header("User-Agent", Pan123Constants.WEB_UA)
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()
        return executeJson(request)
    }

    /** 鉴权 POST（带 auth-key/auth-value 签名头；签名内部固定 web/3） */
    private fun postAuth(
        url: String,
        path: String,
        body: String,
        token: String,
        platform: String = Pan123Constants.PLATFORM_WEB,
        appVersion: String = Pan123Constants.APP_VERSION_WEB
    ): JSONObject {
        val (ak, av) = makeSign(path)
        val request = Request.Builder()
            .url(url)
            .header("platform", platform)
            .header("app-version", appVersion)
            .header("authorization", "Bearer $token")
            .header("loginuuid", loginuuid)
            .header("auth-key", ak)
            .header("auth-value", av)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("User-Agent", Pan123Constants.WEB_UA)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return executeJson(request)
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IllegalStateException("请求失败：响应为空（${response.code}）")
            if (!response.isSuccessful && body.isBlank()) {
                throw IllegalStateException("请求失败（HTTP ${response.code}）")
            }
            return JSONObject(body)
        }
    }
}
