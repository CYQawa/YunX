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

import com.yunx.app.data.upload.UploadIo
import com.yunx.app.data.upload.UploadPhase
import com.yunx.app.data.upload.UploadSource
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 夸克 / UC 共用的上传协议（两家的接口路径、签名串与 OSS 授权流程完全一致，只有域名和 `pr` 不同）。
 *
 * 流程：
 * 1. `file/upload/pre` 建任务 → `file/update/hash` 登记 MD5/SHA1（`finish=true` 即秒传）；
 * 2. 逐片算 OSS 待签串 → `file/upload/auth` 换 `auth_key` → 带 `Authorization` PUT 到 OSS；
 * 3. 用 XML 合并分片（带 `x-oss-callback`，让 OSS 回调通知网盘落库）→ `file/upload/finish`。
 *
 * ★ 每个请求的 `x-oss-date` 与 auth_meta 里的日期必须**同一次取值**，否则签名对不上；
 * ★ OSS 阶段不带 Cookie（Authorization 已经含全部权限）。
 */
internal class QuarkUpload(
    private val apiBase: String,
    private val product: String,
    private val origin: String,
    private val userAgent: String,
    private val post: (url: String, body: JSONObject) -> JSONObject
) {

    private fun url(path: String): String = "$apiBase/1/clouddrive/$path?pr=$product&fr=pc"

    fun upload(io: UploadIo, file: UploadSource, parentFid: String): String? {
        require(file.size > 0) { "网盘不支持上传空文件" }
        io.progress(UploadPhase.PREPARING)
        val now = System.currentTimeMillis()
        val pre = post(
            url("file/upload/pre"),
            JSONObject()
                .put("ccp_hash_update", true)
                .put("dir_name", "")
                .put("file_name", file.name)
                .put("format_type", file.mimeType)
                .put("pdir_fid", parentFid)
                .put("size", file.size)
                .put("l_created_at", now)
                .put("l_updated_at", now)
                .put("same_path_reuse", false)
        )
        val taskId = pre.optString("task_id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("网盘未返回上传任务")
        val hashResult = post(
            url("file/update/hash"),
            JSONObject()
                .put("md5", io.digest("MD5"))
                .put("sha1", io.digest("SHA-1"))
                .put("task_id", taskId)
        )
        if (hashResult.optBoolean("finish")) {
            // 秒传：服务端已有同内容文件，不用传字节
            io.progress(UploadPhase.FINISHING, file.size)
            return null
        }
        val bucket = pre.optString("bucket")
        val objectKey = pre.optString("obj_key")
        val uploadId = pre.optString("upload_id")
        val endpoint = runCatching { java.net.URI(pre.optString("upload_url")) }.getOrNull()
        val host = endpoint?.host.orEmpty()
        if (host.isBlank()) throw IllegalStateException("网盘未返回上传服务器")
        if (bucket.isBlank() || objectKey.isBlank() || uploadId.isBlank()) {
            throw IllegalStateException("网盘未返回完整上传凭据")
        }
        val bucketHost = if (host.startsWith("$bucket.")) host else "$bucket.$host"
        // 对象键按 / 分段编码：OSS 要求每段单独转义，斜杠本身保留
        val objectPath = objectKey.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        val address = "https://$bucketHost/$objectPath"
        val declared = pre.optJSONObject("metadata")?.optLong("part_size", DEFAULT_PART_SIZE)
            ?: DEFAULT_PART_SIZE
        val partSize = if (declared > 0) declared else DEFAULT_PART_SIZE

        fun authorize(meta: String): String {
            val body = JSONObject()
                .put("auth_info", pre.opt("auth_info"))
                .put("auth_meta", meta)
                .put("task_id", taskId)
            val key = post(url("file/upload/auth"), body).optString("auth_key")
            if (key.isBlank()) throw IllegalStateException("网盘未签发上传授权")
            return key
        }

        val count = maxOf(1L, (file.size + partSize - 1) / partSize)
        val etags = mutableListOf<String>()
        for (index in 0 until count) {
            val start = index * partSize
            val stop = minOf(file.size, start + partSize)
            val date = UploadIo.httpDate()
            val meta = "PUT\n\n${file.mimeType}\n$date\n" +
                "x-oss-date:$date\nx-oss-user-agent:$OSS_AGENT\n" +
                "/$bucket/$objectKey?partNumber=${index + 1}&uploadId=$uploadId"
            val key = authorize(meta)
            val response = io.send(
                url = "$address?partNumber=${index + 1}&uploadId=$uploadId",
                method = "PUT",
                start = start,
                end = stop,
                headers = mapOf(
                    "Authorization" to key,
                    "Referer" to "$origin/",
                    "User-Agent" to userAgent,
                    "x-oss-date" to date,
                    "x-oss-user-agent" to OSS_AGENT
                ),
                retries = 1
            )
            if (!response.isSuccessful) {
                throw IllegalStateException("文件上传失败（HTTP ${response.code}）")
            }
            val etag = response.header("ETag")
            if (etag.isBlank()) throw IllegalStateException("网盘未确认上传分段")
            etags.add(etag)
        }

        io.progress(UploadPhase.FINISHING, file.size)
        val xml = UploadIo.completeXml(etags)
        val date = UploadIo.httpDate()
        val contentMd5 = UploadIo.base64(
            MessageDigest.getInstance("MD5").digest(xml.toByteArray(Charsets.UTF_8))
        )
        val callback = UploadIo.base64(
            (pre.opt("callback") ?: throw IllegalStateException("网盘未返回上传回调"))
                .toString().toByteArray(Charsets.UTF_8)
        )
        val completeMeta = "POST\n$contentMd5\napplication/xml\n$date\n" +
            "x-oss-callback:$callback\nx-oss-date:$date\nx-oss-user-agent:$OSS_AGENT\n" +
            "/$bucket/$objectKey?uploadId=$uploadId"
        val merged = io.send(
            url = "$address?uploadId=$uploadId",
            method = "POST",
            headers = mapOf(
                "Authorization" to authorize(completeMeta),
                "Content-MD5" to contentMd5,
                "Referer" to "$origin/",
                "User-Agent" to userAgent,
                "x-oss-callback" to callback,
                "x-oss-date" to date,
                "x-oss-user-agent" to OSS_AGENT
            ),
            body = xml,
            contentType = "application/xml",
            retries = 0
        )
        if (!merged.isSuccessful || UploadIo.xmlError(merged.body)) {
            throw IllegalStateException("网盘未完成上传分段合并")
        }
        post(
            url("file/upload/finish"),
            JSONObject().put("obj_key", objectKey).put("task_id", taskId)
        )
        return null
    }

    private companion object {
        /** OSS 分片默认 8 MiB；服务端会在 metadata.part_size 里给更精确的值 */
        const val DEFAULT_PART_SIZE = 8L * 1024 * 1024

        /** 签名串里的 x-oss-user-agent 必须与请求头逐字一致（按网页端上传 SDK 的值） */
        const val OSS_AGENT = "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit"
    }
}
