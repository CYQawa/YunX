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
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * 139 网盘 API 封装（OkHttp）。
 * 登录态：cookie（含账号信息，authorization 可选）。
 * 分享解析（§15，7.13+）：share-kd-njs.yun.139.com
 *   - 列目录 getOutLinkInfoV6（pCaID:"root"/父coID，passwd 提取码）
 *   - 下载 dlFromOutLinkV3（coIDLst.item:[coID] → data.redrUrl OBS 直链，900s）
 * 分享接口请求/响应均经 AES-CBC 加密（§14）：base64(IV(16B) ‖ AES_CBC(KEY=PVGDwmcvfs1uV3d1, IV, 明文))；
 * mcloud-sign 按「明文 body」计算（§4），加密只是传输包装；mcloud-skey 可省略。
 */
class C139Api(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    private val jsonMediaType = "application/json;charset=UTF-8".toMediaType()

    private val shareAesKey: SecretKeySpec =
        SecretKeySpec(C139Constants.SHARE_AES_KEY.toByteArray(Charsets.UTF_8), "AES")

    // ---------- mcloud-sign 签名（§4） ----------

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** §4.1：encodeURIComponent（+→%20，并还原 ! ' ( ) *） */
    private fun encodeURIComponent(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%2A", "*")

    /**
     * §4.2 calSign：
     * body' 单字符 ASCII 升序 → base64 → md5(base64) + md5(ts:rand) → md5 → upper。
     * 注意：签名必须基于与实际发送一致的「明文 JSON」字符串（字段顺序、无空格）。
     */
    fun calSign(bodyJson: String, ts: String, rand: String): String {
        val encoded = encodeURIComponent(bodyJson)
        val sorted = encoded.toCharArray().sorted().joinToString("")
        val b64 = Base64.encodeToString(sorted.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val res = md5(b64) + md5("$ts:$rand")
        return md5(res).uppercase()
    }

    /** 生成 mcloud-sign 头值：<ts>,<rand>,<sign>；ts 格式 YYYY-MM-DD HH:MM:SS，rand 16 位字母数字 */
    fun signHeader(bodyJson: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val rand = buildString {
            val pool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            repeat(16) { append(pool.random()) }
        }
        return "$ts,$rand,${calSign(bodyJson, ts, rand)}"
    }

    /**
     * 从 authorization（"Basic base64(pc:账号:authToken)"）解码账号。
     * §3.2 最终态：Authorization = base64("pc:<account>:<authToken>")。
     */
    fun accountFromAuthorization(authorization: String): String? = runCatching {
        val b64 = authorization.removePrefix("Basic").trim()
        val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        decoded.split(":").getOrNull(1)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---------- §14 分享接口 AES-CBC 加解密（固定密钥 + IV 前置） ----------

    /** 明文 JSON → 加密 base64（IV(16B) 前置） */
    private fun encryptBody(plaintext: String): String {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, shareAesKey, IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /** 加密 base64 → 明文 JSON；解密后若为 gzip（首 2 字节 0x1f 0x8b）先解压（alist YunCrypto 同款） */
    private fun decryptBody(b64: String): String {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 16)
        val ct = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, shareAesKey, IvParameterSpec(iv))
        var d = cipher.doFinal(ct)  // PKCS5Padding 自动去填充
        // alist YunCrypto 同款：解密后若为 gzip 则解压（首 2 字节 0x1f 0x8b）
        if (d.size > 2 && d[0] == 0x1f.toByte() && d[1] == 0x8b.toByte()) {
            d = GZIPInputStream(ByteArrayInputStream(d)).use { it.readBytes() }
        }
        return String(d, Charsets.UTF_8)
    }

    // ---------- 分享解析（§15，7.13+ 加密） ----------

    /**
     * 分享标题：getOutLinkGeneral（匿名，§2.3）→ data.getOutLinkGeneralResp.outLinkGeneral[].lkName；
     * 失败返回 null。
     */
    suspend fun getOutLinkTitle(linkId: String): String? = withContext(Dispatchers.IO) {
        val req = JSONObject()
            .put("linkID", linkId)
            .put("isPasswd", 1)
            .put("account", "")
        val plain = JSONObject().put("getOutLinkGeneralReq", req).toString()
        val respJson = sharePostAnonymous(C139Constants.SHARE_GENERAL_URL, plain)
        val resultCode = respJson.optString("resultCode")
        if (resultCode.isNotBlank() && resultCode != "0") return@withContext null
        if (!respJson.optBoolean("success", true)) return@withContext null
        val data = respJson.optJSONObject("data")
            ?.optJSONObject("getOutLinkGeneralResp") ?: return@withContext null
        val array = data.optJSONArray("outLinkGeneral") ?: return@withContext null
        if (array.length() == 0) return@withContext null
        array.optJSONObject(0)?.optString("lkName")?.takeIf { it.isNotBlank() }
    }

    /**
     * 分享明文提取码：getOutLinkGeneral（匿名）→ outLinkGeneral[].passwd。
     * 139 会在该接口明文回吐提取码（官方 Web 同样自动填），用于自动填入、避免下载因缺密码报 9188。
     */
    suspend fun getOutLinkPassword(linkId: String): String? = withContext(Dispatchers.IO) {
        val req = JSONObject()
            .put("linkID", linkId)
            .put("isPasswd", 1)
            .put("account", "")
        val plain = JSONObject().put("getOutLinkGeneralReq", req).toString()
        val respJson = sharePostAnonymous(C139Constants.SHARE_GENERAL_URL, plain)
        val resultCode = respJson.optString("resultCode")
        if (resultCode.isNotBlank() && resultCode != "0") return@withContext null
        if (!respJson.optBoolean("success", true)) return@withContext null
        val data = respJson.optJSONObject("data")
            ?.optJSONObject("getOutLinkGeneralResp") ?: return@withContext null
        val array = data.optJSONArray("outLinkGeneral") ?: return@withContext null
        if (array.length() == 0) return@withContext null
        array.optJSONObject(0)?.optString("passwd")?.takeIf { it.isNotBlank() }
    }

    /**
     * 分享列目录：getOutLinkInfoV6 —— 官方为「匿名」调用（§9530修复文档 §2/§3）：
     * 不带 authorization、不带 mcloud-sign、不带 mcloud-* 头；body account 固定空串；
     * 带完整字段（caSrt/coSrt/srtDr/bNum/eNum），否则 9530；passwd 填错返回 9188。
     * ⚠️ 139 把【子文件夹】放在 caLst、【文件】放在 coLst（coType==2 也可能是文件夹）。
     *    原实现只读了 coLst，导致「顶层是文件夹 / 顶层只挂子文件夹」的分享显示为空。
     *    现同时解析 caLst + coLst 并合并返回（文件夹在前）。
     * @param pcaId 根目录传 "root"（不能为空，§16.2），子目录传父级 caID（或 coType==2 的 coID）
     * @param passwd 提取码（无则空串）
     * @return 文件夹（caLst）+ 文件/嵌套文件夹（coLst）合并列表；空目录返回 emptyList
     */
    suspend fun getShareFiles(
        linkId: String,
        pcaId: String,
        passwd: String,
        begin: Int = 1,
        end: Int = 200
    ): List<ShareFile> = withContext(Dispatchers.IO) {
        val req = JSONObject()
            .put("account", "")            // 列表端点 account 必须为空串，且本调用不带鉴权头（§3）
            .put("linkID", linkId)
            .put("passwd", passwd)
            .put("caSrt", 1)               // 排序：目录按创建时间
            .put("coSrt", 1)               // 排序：文件按创建时间
            .put("srtDr", 0)               // 排序方向：降序
            .put("bNum", begin)            // 分页起始
            .put("pCaID", pcaId)
            .put("eNum", end)              // 分页结束
        val plain = JSONObject().put("getOutLinkInfoReq", req).toString()
        val respJson = sharePostAnonymous(C139Constants.SHARE_LIST_URL, plain)
        val resultCode = respJson.optString("resultCode")
        if (resultCode.isNotBlank() && resultCode != "0") {
            throw IllegalStateException(respJson.optString("desc").ifBlank { "获取文件列表失败（$resultCode）" })
        }
        if (!respJson.optBoolean("success", true)) {
            throw IllegalStateException(respJson.optString("desc").ifBlank { "获取文件列表失败" })
        }
        val data = respJson.optJSONObject("data") ?: return@withContext emptyList()
        val result = mutableListOf<ShareFile>()

        // 1) 子文件夹列表 caLst（之前被完全忽略 → 根因：含子文件夹的分享显示为空）
        data.optJSONArray("caLst")?.let { ca ->
            for (i in 0 until ca.length()) {
                val item = ca.optJSONObject(i) ?: continue
                result.add(
                    ShareFile(
                        fid = item.optString("caID"),
                        fname = item.optString("caName"),
                        fsize = 0,
                        isdir = true,
                        pdirFid = pcaId,
                        fidToken = "",
                        modifyTime = item.optString("udTime").ifBlank { item.optString("ctTime") }
                    )
                )
            }
        }

        // 2) 文件列表 coLst（含 coType==2 的文件夹）
        data.optJSONArray("coLst")?.let { co ->
            for (i in 0 until co.length()) {
                val item = co.optJSONObject(i) ?: continue
                result.add(
                    ShareFile(
                        fid = item.optString("coID"),
                        fname = item.optString("coName"),
                        fsize = item.optLong("coSize"),
                        isdir = item.optBoolean("isdir", item.optInt("coType", 1) == 2),
                        pdirFid = pcaId,
                        fidToken = "",
                        modifyTime = item.optString("udTime").ifBlank { item.optString("ctTime") }
                    )
                )
            }
        }
        result   // 空目录返回 emptyList，UI 显示「此目录为空」（保持原语义）
    }

    /**
     * 分享下载：dlFromOutLinkV3 → data.redrUrl（OBS S3 签名直链，900s 有效）。
     * @param coId Step1 列目录得到的 coID
     */
    suspend fun getShareDownloadLink(
        coId: String,
        linkId: String,
        account: String,
        authorization: String?
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val reqV3 = JSONObject()
            .put("account", account)
            .put("linkID", linkId)
            .put("coIDLst", JSONObject().put("item", JSONArray().put(coId)))
            .put(
                "commonAccountInfo",
                JSONObject().put("account", account).put("accountType", 1)
            )
        val plain = JSONObject().put("dlFromOutLinkReqV3", reqV3).toString()
        val respJson = sharePostEncrypted(C139Constants.SHARE_LINK_URL, plain, authorization)
        val resultCode = respJson.optString("resultCode")
        if (resultCode.isNotBlank() && resultCode != "0") {
            throw IllegalStateException(respJson.optString("desc").ifBlank { "获取下载链接失败（$resultCode）" })
        }
        if (!respJson.optBoolean("success", true)) {
            throw IllegalStateException(respJson.optString("desc").ifBlank { "获取下载链接失败" })
        }
        val data = respJson.optJSONObject("data") ?: return@withContext null
        val url = data.optString("redrUrl")
        if (url.isBlank()) return@withContext null
        DownloadLink(
            fid = coId,
            filename = data.optString("fileName").ifEmpty { data.optString("coName").ifEmpty { coId } },
            downloadUrl = url,
            size = data.optLong("coSize", data.optLong("size"))
        )
    }

    // ---------- 个人网盘管理（§1 明文 JSON + Authorization + mcloud-sign，无 Cookie） ----------

    /** 异步任务状态（移动/删除轮询） */
    data class C139TaskStatus(
        val status: String,
        val progress: Int,
        val results: List<Pair<String, String>>
    )

    /** 转存结果（分享导入） */
    data class C139TransferResult(
        val done: Boolean,
        val mapping: Map<String, String>
    )

    /** 单页列目录（含翻页游标）；返回 (文件列表, 下一页游标 or null=末页) */
    private suspend fun fetchCloudPage(
        parentFileId: String,
        cookie: String,
        pageCursor: String?
    ): Pair<List<ShareFile>, String?>? = withContext(Dispatchers.IO) {
        val authorization = C139Constants.extractAuthorization(cookie)
            ?: throw IllegalStateException("登录态缺少 authorization，请重新登录")
        val req = JSONObject()
            .put("pageInfo", JSONObject().put("pageSize", 100).put("pageCursor", pageCursor ?: JSONObject.NULL))
            .put("orderBy", "updated_at")
            .put("orderDirection", "DESC")
            .put("parentFileId", parentFileId)
            .put("imageThumbnailStyleList", JSONArray().put("Small").put("Large"))
        val resp = cloudPost(C139Constants.FILE_LIST_URL, req.toString(), authorization)
        checkCloud(resp, "获取文件列表失败")
        val data = resp.optJSONObject("data") ?: return@withContext null
        val files = buildList {
            data.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(
                        ShareFile(
                            fid = item.optString("fileId"),
                            fname = item.optString("name"),
                            fsize = item.optLong("size"),
                            isdir = item.optString("type") == "folder",
                            pdirFid = parentFileId,
                            fidToken = item.optString("fileId"),
                            modifyTime = item.optString("updatedAt")
                        )
                    )
                }
            }
        }
        val next = data.optString("nextPageCursor").takeIf { it.isNotBlank() }
        Pair(files, next)
    }

    /** 列目录（自动翻页，返回该目录下全部文件）；pageCursor 为起始游标（一般传 null=从头） */
    suspend fun listCloudFiles(
        parentFileId: String,
        cookie: String,
        pageCursor: String? = null
    ): List<ShareFile> {
        val all = mutableListOf<ShareFile>()
        val seen = HashSet<String>()
        var cursor = pageCursor
        repeat(200) {            // 封顶 200 页，防异常死循环
            val (files, next) = fetchCloudPage(parentFileId, cookie, cursor) ?: return all
            // 139 按 updated_at 排序翻页，大量文件时间相同时游标会重复返回同一批/边界项，
            // 按 fid 去重，避免同一 fid 重复导致 LazyColumn key 冲突崩溃
            for (f in files) {
                if (f.fid.isNotBlank() && seen.add(f.fid)) all.add(f)
            }
            cursor = next ?: return all   // nextPageCursor 为空 → 末页，结束
            // 游标未推进（服务端异常重复返回同一游标）→ 终止，防死循环重复累加
            if (cursor == pageCursor) return all
        }
        return all
    }

    /** 仅列文件夹（移动到…目标选择） */
    suspend fun listFolders(parentFileId: String, cookie: String): List<ShareFile> = withContext(Dispatchers.IO) {
        val authorization = C139Constants.extractAuthorization(cookie)
            ?: throw IllegalStateException("登录态缺少 authorization，请重新登录")
        val req = JSONObject()
            .put("pageInfo", JSONObject().put("pageSize", 100).put("pageCursor", JSONObject.NULL))
            .put("orderBy", "updated_at")
            .put("orderDirection", "DESC")
            .put("parentFileId", parentFileId)
            .put("imageThumbnailStyleList", JSONArray().put("Small").put("Large"))
            .put("type", "folder")
        val resp = cloudPost(C139Constants.FILE_LIST_URL, req.toString(), authorization)
        checkCloud(resp, "获取文件夹列表失败")
        val data = resp.optJSONObject("data") ?: return@withContext emptyList()
        buildList {
            data.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(
                        ShareFile(
                            fid = item.optString("fileId"),
                            fname = item.optString("name"),
                            fsize = 0,
                            isdir = true,
                            pdirFid = parentFileId,
                            fidToken = item.optString("fileId"),
                            modifyTime = item.optString("updatedAt")
                        )
                    )
                }
            }
        }
    }

    /** 重命名 */
    suspend fun renameFile(fileId: String, newName: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val authorization = C139Constants.extractAuthorization(cookie)
            ?: throw IllegalStateException("登录态缺少 authorization，请重新登录")
        val req = JSONObject()
            .put("fileId", fileId)
            .put("name", newName)
            .put("description", "")
        val resp = cloudPost(C139Constants.FILE_UPDATE_URL, req.toString(), authorization)
        checkCloud(resp, "重命名失败")
        true
    }

    /** 移动（异步），返回 taskId */
    suspend fun moveFiles(fileIds: List<String>, toParentFileId: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val authorization = C139Constants.extractAuthorization(cookie)
            ?: throw IllegalStateException("登录态缺少 authorization，请重新登录")
        val req = JSONObject()
            .put("fileIds", JSONArray().apply { fileIds.forEach { put(it) } })
            .put("toParentFileId", toParentFileId)
        val resp = cloudPost(C139Constants.BATCH_MOVE_URL, req.toString(), authorization)
        checkCloud(resp, "移动失败")
        resp.optJSONObject("data")?.optString("taskId")?.takeIf { it.isNotBlank() }
    }

    /** 删除（异步，移入回收站），返回 taskId */
    suspend fun deleteFiles(fileIds: List<String>, cookie: String): String? = withContext(Dispatchers.IO) {
        val authorization = C139Constants.extractAuthorization(cookie)
            ?: throw IllegalStateException("登录态缺少 authorization，请重新登录")
        val req = JSONObject().put("fileIds", JSONArray().apply { fileIds.forEach { put(it) } })
        val resp = cloudPost(C139Constants.BATCH_TRASH_URL, req.toString(), authorization)
        checkCloud(resp, "删除失败")
        resp.optJSONObject("data")?.optString("taskId")?.takeIf { it.isNotBlank() }
    }

    /** 异步任务轮询（移动/删除），返回状态 */
    suspend fun getTask(taskId: String, cookie: String): C139TaskStatus = withContext(Dispatchers.IO) {
        val authorization = C139Constants.extractAuthorization(cookie)
            ?: throw IllegalStateException("登录态缺少 authorization，请重新登录")
        val req = JSONObject().put("taskId", taskId)
        val resp = cloudPost(C139Constants.TASK_GET_URL, req.toString(), authorization)
        checkCloud(resp, "查询任务失败")
        val data = resp.optJSONObject("data") ?: return@withContext C139TaskStatus("", 0, emptyList())
        val taskInfo = data.optJSONObject("taskInfo")
        val status = taskInfo?.optString("status") ?: ""
        val progress = taskInfo?.optInt("progress") ?: 0
        val results = buildList {
            data.optJSONArray("batchFileResults")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(item.optString("fileId") to item.optString("errCode"))
                }
            }
        }
        C139TaskStatus(status, progress, results)
    }

    /** 下载直链（OBS 预签名，900s 有效） */
    suspend fun getDownloadUrl(fileId: String, cookie: String): DownloadLink? = withContext(Dispatchers.IO) {
        val authorization = C139Constants.extractAuthorization(cookie)
            ?: throw IllegalStateException("登录态缺少 authorization，请重新登录")
        val req = JSONObject().put("fileId", fileId)
        val resp = cloudPost(C139Constants.DOWNLOAD_URL, req.toString(), authorization)
        checkCloud(resp, "获取下载链接失败")
        val data = resp.optJSONObject("data") ?: return@withContext null
        val url = data.optString("url")
        if (url.isBlank()) return@withContext null
        DownloadLink(
            fid = fileId,
            // getDownloadUrl 响应不含 name（§4.6 仅 url/expiration/size）；文件名由调用方用列表 name 填充
            filename = data.optString("name"),
            downloadUrl = url,
            size = data.optLong("size")
        )
    }

    /** 创建分享（getOutLink，需 Cookie + mcloud-skey；提取码系统自动生成）
     *  @param period 有效期：null=永久 1/7/30=天数
     */
    suspend fun createShare(
        coIDLst: List<String>,
        caIDLst: List<String>,
        period: Int?,
        dedicatedName: String,
        cookie: String
    ): ShareInfo = withContext(Dispatchers.IO) {
        val authorization = C139Constants.extractAuthorization(cookie)
            ?: throw IllegalStateException("登录态缺少 authorization，请重新登录")
        val account = accountFromAuthorization(authorization)
            ?: throw IllegalStateException("无法从登录态解析账号，请重新登录")
        val getOutLinkReq = JSONObject()
            .put("subLinkType", 0)
            .put("encrypt", 1)
            .put("coIDLst", JSONArray().apply { coIDLst.forEach { put(it) } })
            .put("caIDLst", JSONArray().apply { caIDLst.forEach { put(it) } })
            .put("pubType", 1)
            .put("dedicatedName", dedicatedName)
            .put("periodUnit", 1)
            .apply { if (period != null) put("period", period) }
            .put("viewerLst", JSONArray())
            .put("extInfo", JSONObject().put("isWatermark", 0).put("shareChannel", "3001"))
            .put("commonAccountInfo", JSONObject().put("account", account).put("accountType", 1))
        val plain = JSONObject().put("getOutLinkReq", getOutLinkReq).toString()
        val resp = cloudPost(C139Constants.OUTLINK_CREATE_URL, plain, authorization, cookie, needSkey = true)
        // 分享接口成功码为 "0"
        if (!resp.optBoolean("success", true) || resp.optString("code") != "0") {
            throw IllegalStateException(resp.optString("message").ifBlank { "创建分享失败" })
        }
        val set = resp.optJSONObject("data")
            ?.optJSONObject("getOutLinkRes")
            ?.optJSONArray("getOutLinkResSet")
            ?.optJSONObject(0)
            ?: throw IllegalStateException("创建分享失败：未返回链接")
        val linkUrl = set.optString("linkUrl")
        if (linkUrl.isBlank()) throw IllegalStateException("创建分享失败：未返回链接")
        ShareInfo(
            shareUrl = linkUrl,
            passcode = set.optString("passwd"),
            pwdId = set.optString("linkID"),
            title = dedicatedName,
            expiredType = when (period) {
                1 -> 2
                7 -> 3
                30 -> 4
                else -> 1
            }
        )
    }

    // ---------- 网盘空间详情 ----------

    /** 网盘空间详情（POST user-njs.yun.139.com/user/disk/quota/detail：diskSize/freeDiskSize，单位 MB；需 Cookie+mcloud-skey） */
    suspend fun getQuota(cookie: String): QuotaInfo? = withContext(Dispatchers.IO) {
        val authorization = C139Constants.extractAuthorization(cookie)
            ?: return@withContext null
        val account = accountFromAuthorization(authorization) ?: return@withContext null
        runCatching {
            val req = JSONObject()
                .put("userDomainId", "")
                .put("commonAccountInfo", JSONObject().put("account", account).put("accountType", 1))
            val resp = cloudPost(
                "https://user-njs.yun.139.com/user/disk/quota/detail",
                req.toString(), authorization, cookie, needSkey = true
            )
            checkCloud(resp, "获取空间详情失败")
            val data = resp.optJSONObject("data") ?: return@runCatching null
            val total = data.optLong("diskSize") * 1024L * 1024L
            val used = data.optLong("freeDiskSize").let { free ->
                // quotaList[0] = 个人云（我的文件）已用（MB）
                data.optJSONArray("quotaList")?.optJSONObject(0)?.optLong("usedSize")?.times(1024L * 1024L)
                    ?: (total - free * 1024L * 1024L)
            }
            QuotaInfo(used = used, total = total)
        }.getOrNull()
    }

    // ---------- 转存（分享导入，share host AES 加密） ----------

    /** 创建转存任务，返回 taskID（含 sk* 前缀） */
    suspend fun createTransferTask(
        coIDLst: List<String>,
        catalogIDLst: List<String>,
        toFolderId: String,
        linkID: String,
        account: String,
        authorization: String?
    ): String? = withContext(Dispatchers.IO) {
        val taskInfo = JSONObject()
            .put("contentInfoList", JSONArray().apply { coIDLst.forEach { put("/$it") } })
            .put("catalogInfoList", JSONArray().apply { catalogIDLst.forEach { put(it) } })
            .put("newCatalogID", toFolderId)
            .put("linkID", linkID)
            .put("newCatalogName", "手机图片")
            .put("needPassword", true)
        val req = JSONObject()
            .put("createOuterLinkBatchOprTaskReq", JSONObject()
                .put("msisdn", account)
                .put("ownerAccount", "")
                .put("taskType", 1)
                .put("taskInfo", taskInfo)
                .put("linkID", linkID)
                .put("needPassword", true))
            .put("commonAccountInfo", JSONObject().put("account", account).put("accountType", 1))
        val resp = sharePostEncrypted(C139Constants.TRANSFER_CREATE_URL, req.toString(), authorization)
        val code = resp.optString("resultCode").ifBlank { resp.optString("code") }
        if (code.isNotBlank() && code != "0") {
            throw IllegalStateException(resp.optString("desc").ifBlank { "创建转存任务失败（$code）" })
        }
        resp.optJSONObject("data")?.optString("taskID")?.takeIf { it.isNotBlank() }
    }

    /** 查询转存结果 */
    suspend fun queryTransferTask(taskID: String, account: String, authorization: String?): C139TransferResult =
        withContext(Dispatchers.IO) {
            val req = JSONObject().put(
                "queryBatchOprTaskDetailReq",
                JSONObject()
                    .put("taskID", taskID)
                    .put("msisdn", account)
                    .put("commonAccountInfo", JSONObject().put("account", account).put("accountType", 1))
            )
            val resp = sharePostEncrypted(C139Constants.TRANSFER_QUERY_URL, req.toString(), authorization)
            val code = resp.optString("resultCode").ifBlank { resp.optString("code") }
            if (code.isNotBlank() && code != "0") {
                throw IllegalStateException(resp.optString("desc").ifBlank { "查询转存结果失败（$code）" })
            }
            val data = resp.optJSONObject("data") ?: return@withContext C139TransferResult(false, emptyMap())
            val task = data.optJSONObject("batchOprTask")
            val done = (task?.optInt("progress") ?: 0) >= 100 && (task?.optInt("taskStatus") ?: 0) == 2
            val mapping = buildMap {
                data.optJSONObject("contentList")?.optJSONArray("idRspInfo")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optString("reason") == "0000") {
                            put(item.optString("srcId"), item.optString("rstId"))
                        }
                    }
                }
            }
            C139TransferResult(done, mapping)
        }

    /**
     * 个人网盘管理专用 POST（§1：明文 JSON，Authorization + mcloud-sign 按明文算；
     * 不需要 hcy-cool-flag，不需要加密；可选 Cookie + mcloud-skey（创建分享 getOutLink 必需））。
     * ⚠️ 修复（《139网盘管理认证失败修复》）：APISIX 网关鉴权层强制要求全套 x-yun-* / mcloud-* 渠道头，
     *    仅有 Authorization+mcloud-sign 会返回 HTTP 404 + code:"04000005" 认证失败。
     */
    private fun cloudPost(
        url: String,
        plainBody: String,
        authorization: String,
        cookie: String? = null,
        needSkey: Boolean = false
    ): JSONObject {
        val builder = Request.Builder()
            .url(url)
            // —— 鉴权 ——
            .header("Authorization", authorization)
            .header("mcloud-sign", signHeader(plainBody))
            // —— 渠道/上下文头（缺失 → 04000005 认证失败；值来自成功抓包写死）——
            .header("x-yun-channel-source", C139Constants.YUN_CHANNEL_SOURCE)
            .header("x-yun-app-channel", C139Constants.YUN_CHANNEL_SOURCE)
            .header("x-huawei-channelSrc", C139Constants.YUN_CHANNEL_SOURCE)
            .header("mcloud-version", C139Constants.MCLOUD_VERSION)
            .header("mcloud-client", C139Constants.MCLOUD_CLIENT)
            .header("mcloud-channel", C139Constants.MCLOUD_CHANNEL)
            .header("mcloud-route", "001")
            .header("x-yun-module-type", C139Constants.YUN_MODULE_TYPE)
            .header("x-yun-api-version", "v1")
            .header("x-yun-svc-type", "1")
            .header("x-SvcType", "1")
            .header("caller", "web")
            .header("x-inner-ntwk", "2")
            .header("CMS-DEVICE", "default")
            .header("x-m4c-src", C139Constants.M4C_SRC)
            .header("x-m4c-caller", C139Constants.M4C_CALLER)
            .header("X-Deviceinfo", C139Constants.X_DEVICEINFO)
            .header("x-yun-client-info", C139Constants.X_CLIENT_INFO)
            .header("INNER-HCY-ROUTER-HTTPS", "1")
            .header("Sec-Fetch-Site", "same-site")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("X-Requested-With", "mark.via")
            // —— 基础头 ——
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("User-Agent", C139Constants.PC_UA)
            .header("Origin", "https://yun.139.com")
            .header("Referer", "https://yun.139.com/")
            .header("Accept", "application/json, text/plain, */*")
        if (cookie != null) {
            builder.header("Cookie", cookie)
            if (needSkey) {
                val skey = cookie.split(";").map { it.trim() }
                    .firstOrNull { it.startsWith("skey=") }?.substringAfter('=')
                if (!skey.isNullOrBlank()) builder.header("mcloud-skey", skey)
            }
        }
        val request = builder.post(plainBody.toRequestBody(jsonMediaType)).build()
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string() ?: throw IllegalStateException("请求失败：响应为空") }
        return JSONObject(body)   // 管理接口明文响应
    }

    /** 管理接口成功判定：success==true 且 code ∈ {"0000","0"} */
    private fun checkCloud(json: JSONObject, fallback: String) {
        val code = json.optString("code")
        if (json.optBoolean("success", true) && (code == "0000" || code == "0")) return
        val msg = json.optString("message").ifBlank { fallback }
        throw IllegalStateException("$msg（code=$code）")
    }

    // ---------- 请求构造与响应解析 ----------

    /**
     * 匿名 POST（列表端点 getOutLinkInfoV6 专用，§9530修复文档 §3/§5 + hcy-cool-flag 修复）：
     * 不带 Authorization、不带 mcloud-sign、不带 mcloud-* 头；body 仍加密；
     * 必须带 hcy-cool-flag: 1（139 网关选择解密方案的开关，缺它业务层拿不到明文 → 9530）；
     * 响应解密（兼容明文透传）。
     */
    private fun sharePostAnonymous(url: String, plainBody: String): JSONObject {
        val encrypted = encryptBody(plainBody)
        val request = Request.Builder()
            .url(url)
            .header("hcy-cool-flag", "1")
            .header("x-deviceinfo", C139Constants.SHARE_X_DEVICEINFO)
            .header("x-huawei-channelsrc", C139Constants.SHARE_X_HUAWEI_CHANNELSRC)
            .header("x-mm-source", C139Constants.SHARE_X_MM_SOURCE)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("User-Agent", C139Constants.SHARE_MOBILE_UA)
            .header("Origin", "https://yun.139.com")
            .header("Referer", "https://yun.139.com/")
            .header("Accept", "application/json, text/plain, */*")
            .post(encrypted.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string() ?: throw IllegalStateException("请求失败：响应为空") }
        // 响应体应为加密 base64（§14）；网关透传明文时兜底
        return runCatching { JSONObject(decryptBody(body)) }.getOrElse { JSONObject(body) }
    }

    /**
     * 分享专用 POST（§14/§15 + hcy-cool-flag 修复）：body 加密发送，mcloud-sign 按明文算，
     * 必须带 hcy-cool-flag: 1（网关解密开关，缺它业务层拿不到明文 → 9530），响应解密（兼容明文透传）。
     */
    private fun sharePostEncrypted(url: String, plainBody: String, authorization: String?): JSONObject {
        val encrypted = encryptBody(plainBody)
        val request = Request.Builder()
            .url(url)
            .apply { if (!authorization.isNullOrBlank()) header("Authorization", authorization) }
            .header("hcy-cool-flag", "1")
            .header("x-deviceinfo", C139Constants.SHARE_X_DEVICEINFO)
            .header("x-huawei-channelsrc", C139Constants.SHARE_X_HUAWEI_CHANNELSRC)
            .header("x-mm-source", C139Constants.SHARE_X_MM_SOURCE)
            .header("mcloud-sign", signHeader(plainBody))
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("User-Agent", C139Constants.SHARE_MOBILE_UA)
            .header("Origin", "https://yun.139.com")
            .header("Referer", "https://yun.139.com/")
            .header("Accept", "application/json, text/plain, */*")
            .post(encrypted.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string() ?: throw IllegalStateException("请求失败：响应为空") }
        // 响应体应为加密 base64（§14）；网关透传明文时兜底
        return runCatching { JSONObject(decryptBody(body)) }.getOrElse { JSONObject(body) }
    }

}
