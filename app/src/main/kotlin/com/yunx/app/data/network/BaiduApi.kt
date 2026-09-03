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

/**
 * 百度网盘 API 封装（OkHttp + Cookie 认证）：
 * 登录态 BDUSS 经 Cookie 携带；分享解析链路 share/verify → xpan/share → share/transfer → filemetas。
 * 全部基于抓包字段，错误码用 errno（0 表示成功）判定。
 */
class BaiduApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    /** bdstoken 缓存（登录态内长期有效） */
    @Volatile
    private var cachedBdstoken: String? = null

    // ---------- 账号 ----------

    /** 获取昵称（gettemplatevariable 的 username 字段）；失败返回 null */
    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        val result = templateVariable(cookie, """["username"]""") ?: return@withContext null
        result.optString("username").takeIf { it.isNotBlank() }
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
            // 无 sekey 却失败 → 实为加密分享，提示用户索取提取码
            if (sekey.isBlank()) throw BaiduApiException("该分享需要提取码")
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

    /** 列出个人网盘目录（检查临时转存目录是否存在），返回子项 path 集合 */
    suspend fun listDir(dir: String, cookie: String): List<String> = withContext(Dispatchers.IO) {
        val url = "https://yun.baidu.com/api/list?clienttype=0&app_id=${BaiduConstants.APP_ID}" +
            "&web=1&order=time&desc=1&dir=" + URLEncoder.encode(dir, "UTF-8") + "&num=100&page=1"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .get()
            .build()
        runCatching {
            val json = executeJson(request)
            if (json.optInt("errno") != 0) return@runCatching emptyList()
            val array = json.optJSONArray("list") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(it.optString("path")) }
                }
            }
        }.getOrDefault(emptyList())
    }

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
     * 获取高速下载直链（官方 locatedownload 接口，对齐 MoePal 抓包）：
     * POST d.pcs.baidu.com/rest/2.0/pcs/file?method=locatedownload&path=<转存后完整路径>。
     * 响应 urls[] 按 rank 返回多个候选 CDN 直链（自带 sign/expires，无需计算）：
     *  - rank1 常为 d2-ant.baidu.com（encrypt=1 加密通道，内容需 AES-CTR 解密，且部分网络 TLS 握手失败）
     *  - rank2+ 为 appallNN.baidupcs.com（encrypt=0 明文通道，可直接 Range 下载）
     * 仅需 BDUSS 登录态 + 手机 UA；psign 为写死常量，rand/devuid 复用抓包常量即可。
     * @return 选中的 appall 明文直链；全部候选不可用时抛异常
     */
    suspend fun locateDownload(path: String, cookie: String): String = withContext(Dispatchers.IO) {
        val time = System.currentTimeMillis() / 1000
        // 抓包常量：psign 写死；rand/devuid/cuid/deviceid 有 BDUSS 登录态时可直接复用
        val url = "https://d.pcs.baidu.com/rest/2.0/pcs/file" +
            "?method=locatedownload" +
            "&app_id=${BaiduConstants.APP_ID}" +
            "&clienttype=17&ver=4.0" +
            "&ant=1&check_blue=1&es=1&esl=1&apn_id=1_-1" +
            "&freeisp=0&queryfree=0&use=1&dtype=1&eck=1&ehps=1" +
            "&err_ver=1.0&network_type=WIFI&channel=0" +
            "&path=${urlEncode(path)}" +
            "&time=$time" +
            "&rand=5ed606e9da222cde0474cdf70eda884b" +
            "&devuid=0F1E9FC2E084472DA5A61C4CF4C759AF" +
            "&cuid=0F1E9FC2E084472DA5A61C4CF4C759AF" +
            "&deviceid=348642637967375013" +
            "&psign=860a071f77c860e8cea06e4e54c518f3" +
            "&version=2.2.111.34&version_app=12.24.6&vip=0"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post("0".toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        checkErrno(json, "获取高速下载链接失败")
        // 直链候选选择：优先 encrypt=0（明文，无需解密）的 https 直链（appall01/02）；
        // rank1 的 d2-ant 为 encrypt=1 加密通道（需 AES-CTR 解密且部分网络 TLS 握手失败），直接排除；
        // 全部为加密通道时退回第一个 https 候选（仍有尝试价值），最后兜底第一个候选。
        val urls = json.optJSONArray("urls")
        val candidates = (0 until (urls?.length() ?: 0))
            .mapNotNull { urls?.optJSONObject(it) }
            .filter { it.optString("url").isNotBlank() }
        val directUrl = candidates
            .filter { it.optInt("encrypt", 1) == 0 }
            .sortedBy { !it.optString("url").startsWith("https") }
            .firstOrNull()?.optString("url")
            ?: candidates.firstOrNull { it.optString("url").startsWith("https") }?.optString("url")
            ?: candidates.firstOrNull()?.optString("url")
            ?: throw BaiduApiException("未返回下载链接")
        directUrl
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
     * 单页 num=100（官方 page_size 上限）；本页未满 100 视为末页，封顶 100 页防异常死循环。
     * 返回 ShareFile（fid=fs_id，fidToken=绝对路径 path）
     */
    suspend fun listCloudFiles(dir: String, cookie: String): List<ShareFile> = withContext(Dispatchers.IO) {
        val all = mutableListOf<ShareFile>()
        var page = 1
        while (true) {
            val url = "https://yun.baidu.com/api/list?clienttype=0&app_id=${BaiduConstants.APP_ID}" +
                "&web=1&order=time&desc=1&dir=" + URLEncoder.encode(dir, "UTF-8") + "&num=100&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_NETDISK)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://yun.baidu.com/disk/main")
                .get()
                .build()
            val pageFiles = runCatching {
                val json = executeJson(request)
                if (json.optInt("errno") != 0) return@runCatching emptyList()
                val array = json.optJSONArray("list") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        add(
                            ShareFile(
                                fid = item.optString("fs_id"),
                                fname = item.optString("server_filename"),
                                fsize = item.optLong("size"),
                                isdir = item.optInt("isdir") == 1,
                                pdirFid = dir,
                                fidToken = item.optString("path"),
                                modifyTime = item.optString("server_mtime")
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
            // 空页（含单页请求失败）视为已到末页，停止翻页
            if (pageFiles.isEmpty()) break
            all += pageFiles
            // 本页未满 100 → 已是最后一页；封顶 100 页防止异常死循环
            if (pageFiles.size < 100 || page >= 100) break
            page++
        }
        all
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

    /** 百度创建分享结果 */
    data class BaiduShareResult(
        val link: String,
        val pwd: String,
        val shareId: String
    )

    /** 创建分享（share/set，按 fs_id 列表 + 有效期 period + 4 位提取码） */
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
            shareId = json.optString("shareid")
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
        val body = response.use { it.body?.string() ?: throw BaiduApiException("请求失败：响应为空") }
        return runCatching { JSONObject(body) }.getOrElse {
            throw BaiduApiException("响应解析失败")
        }
    }

    private fun checkErrno(json: JSONObject, fallback: String) {
        val errno = json.optInt("errno")
        if (errno != 0) {
            // 常见：errno=-12 提取码错误 / 403 分享已失效 / 31066 文件不存在
            val msg = json.optString("err_msg").ifBlank { json.optString("show_msg") }.ifBlank { fallback }
            throw BaiduApiException("$msg（errno=$errno）")
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
