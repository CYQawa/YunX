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

package com.yunx.app.data.upload

import android.content.ContentResolver
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CancellationException

/** 上传阶段：预处理（算哈希/建任务）→ 传字节 → 等网盘确认 */
enum class UploadPhase { PREPARING, UPLOADING, FINISHING }

/** 一次上传 HTTP 响应的最小快照（上传协议只看状态码 / 响应头 / 文本体） */
data class UploadResponse(val code: Int, val headers: Headers, val body: String) {

    val isSuccessful: Boolean get() = code in 200..299

    fun header(name: String): String = headers[name].orEmpty()

    fun json(): JSONObject = JSONObject(body)

    fun errorText(fallback: String): String =
        runCatching { JSONObject(body).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallback
}

/**
 * 一次文件上传的 I/O 引擎：把「本地 SAF 文件 + OkHttp」包成各家协议都能直接用的小工具。
 *
 * 职责刻意收得很窄：
 * - 按区间读本地文件（[UploadSource.open]）、流式计算哈希（各家秒传/校验都要）；
 * - 发**定长请求体**（裸二进制分片，或带附加字段的 multipart 表单），边发边回报进度；
 * - 提供 OSS V1 / S3 V4 两种通用分片上传（光鸭、123、迅雷走它们）；
 * - 支持取消：主线程点「取消」后立刻 cancel 掉在飞的 Call，读循环里也会检查标志位。
 *
 * ★ 所有方法都是**阻塞**的，调用方负责放到 `Dispatchers.IO` 里；进度回调也在 IO 线程触发，
 *   ViewModel 侧做了节流后再写 Compose 状态。
 */
class UploadIo(
    private val resolver: ContentResolver,
    val file: UploadSource,
    private val clientProvider: () -> OkHttpClient,
    private val onProgress: (UploadPhase, Long) -> Unit
) {

    @Volatile
    private var cancelled = false

    private val inFlight = Collections.synchronizedList(mutableListOf<Call>())

    private val client get() = clientProvider()

    val isCancelled: Boolean get() = cancelled

    /** 取消上传：置标志位 + 打断正在进行的请求（OkHttp 的 Call.cancel 会让 execute 抛 IOException） */
    fun cancel() {
        cancelled = true
        synchronized(inFlight) {
            inFlight.forEach { call -> runCatching { call.cancel() } }
            inFlight.clear()
        }
    }

    private fun alive() {
        if (cancelled) throw CancellationException("上传已取消")
        if (Thread.currentThread().isInterrupted) throw CancellationException("上传已取消")
    }

    fun progress(phase: UploadPhase, sent: Long = 0L) {
        onProgress(phase, sent.coerceIn(0L, file.size))
    }

    // ---------- 本地读 + 哈希 ----------

    /**
     * 流式计算 `[start, end)` 区间的摘要，返回小写十六进制。
     *
     * 进度按「已读字节」回报成 [UploadPhase.PREPARING]：大文件算 MD5/SHA1 本身就要几十秒，
     * 不回报进度用户会以为卡死。
     */
    fun digest(algorithm: String, start: Long = 0L, end: Long = file.size): String =
        hex(digestBytes(algorithm, start, end))

    /** 同 [digest]，但返回原始字节（迅雷的 gcid 要先拼各分块的 SHA1 原始字节再算一次） */
    fun digestBytes(algorithm: String, start: Long = 0L, end: Long = file.size): ByteArray {
        val digest = MessageDigest.getInstance(algorithm)
        var read = 0L
        file.open(resolver, start, end).use { input ->
            val buffer = ByteArray(IO_BUFFER)
            while (true) {
                alive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                read += count
                progress(UploadPhase.PREPARING, start + read)
            }
        }
        if (read != end - start) throw IOException("读取本地文件不完整，请重新选择")
        return digest.digest()
    }

    /**
     * 发一次请求。
     *
     * @param body 非空时直接发这段文本（XML 合并等），此时不读本地文件
     * @param fields 非空时按 `multipart/form-data` 发送，文件作为 `file` 字段
     * @param start/end 本地文件要发的区间（默认整个文件）
     * @param retries 网络异常重试次数；写请求默认不重试（重发可能产生重复文件）
     */
    fun send(
        url: String,
        method: String = "PUT",
        headers: Map<String, String> = emptyMap(),
        contentType: String? = null,
        start: Long = 0L,
        end: Long = file.size,
        fields: List<Pair<String, String>> = emptyList(),
        fieldName: String = "file",
        fileName: String = file.name,
        body: String? = null,
        retries: Int = 0
    ): UploadResponse {
        val boundary = if (fields.isEmpty() || body != null) null else "----YunXUpload${System.nanoTime()}"
        val mediaType = contentType?.toMediaType()
        val requestBody: RequestBody = when {
            body != null -> body.toRequestBody(mediaType ?: "application/xml".toMediaType())
            else -> FileRequestBody(
                start = start,
                end = end,
                contentType = mediaType ?: file.mimeType.toMediaType(),
                fields = fields,
                boundary = boundary,
                fieldName = fieldName,
                fileName = fileName
            )
        }
        var attempt = 0
        while (true) {
            alive()
            val builder = Request.Builder().url(url).method(method, requestBody)
            headers.forEach { (key, value) -> builder.header(key, value) }
            val call = client.newCall(builder.build())
            inFlight.add(call)
            try {
                call.execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    return UploadResponse(response.code, response.headers, text)
                }
            } catch (e: IOException) {
                if (cancelled) throw CancellationException("上传已取消")
                if (attempt >= retries) throw e
                attempt++
                runCatching { Thread.sleep(500L * attempt) }
            } finally {
                inFlight.remove(call)
            }
        }
    }

    /** 定长请求体：裸二进制分片或 multipart 表单，边写边回报进度 */
    private inner class FileRequestBody(
        private val start: Long,
        private val end: Long,
        private val contentType: MediaType,
        private val fields: List<Pair<String, String>>,
        private val boundary: String?,
        private val fieldName: String,
        private val fileName: String
    ) : RequestBody() {

        /** multipart 的头部/尾部字节提前算好：contentLength 与实际写出的必须逐字节一致 */
        private val openBytes: ByteArray
        private val closeBytes: ByteArray

        init {
            if (boundary == null) {
                openBytes = ByteArray(0)
                closeBytes = ByteArray(0)
            } else {
                val head = StringBuilder()
                for ((key, value) in fields) {
                    head.append("--").append(boundary).append("\r\n")
                    head.append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                    head.append(value).append("\r\n")
                }
                head.append("--").append(boundary).append("\r\n")
                head.append("Content-Disposition: form-data; name=\"").append(fieldName)
                head.append("\"; filename=\"").append(fileName.replace("\"", "")).append("\"\r\n")
                head.append("Content-Type: ").append(contentType).append("\r\n\r\n")
                openBytes = head.toString().toByteArray(Charsets.UTF_8)
                closeBytes = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            }
        }

        override fun contentType(): MediaType? =
            if (boundary == null) contentType else "multipart/form-data; boundary=$boundary".toMediaType()

        override fun contentLength(): Long = openBytes.size + (end - start) + closeBytes.size

        override fun writeTo(sink: BufferedSink) {
            if (openBytes.isNotEmpty()) sink.write(openBytes)
            var written = 0L
            file.open(resolver, start, end).use { input: InputStream ->
                val buffer = ByteArray(IO_BUFFER)
                while (true) {
                    alive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    written += count
                    progress(UploadPhase.UPLOADING, start + written)
                }
            }
            if (written != end - start) throw IOException("读取本地文件不完整，请重新选择")
            if (closeBytes.isNotEmpty()) sink.write(closeBytes)
            progress(UploadPhase.UPLOADING, end)
        }
    }

    // ---------- 阿里云 OSS（V1 签名，光鸭用） ----------

    /**
     * OSS 临时凭据直传：单分片走一次 PUT，大文件走 `?uploads` 初始化 → 分段 PUT → 合并。
     *
     * 签名是 OSS V1（HMAC-SHA1），待签串为
     * `方法\nContent-MD5(空)\nContent-Type\nDate\n[security-token\n]CanonicalizedResource`。
     */
    fun ossMultipart(
        endpoint: String,
        bucket: String,
        objectKey: String,
        accessKey: String,
        secretKey: String,
        securityToken: String = "",
        contentType: String? = null
    ) {
        require(endpoint.isNotBlank() && bucket.isNotBlank() && objectKey.isNotBlank() &&
            accessKey.isNotBlank() && secretKey.isNotBlank()) {
            "网盘未返回完整上传凭据"
        }
        var base = endpoint.trim()
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
        val schemeEnd = base.indexOf("://") + 3
        val originRest = base.substring(schemeEnd).trimEnd('/')
        val host = originRest.substringBefore('/')
        val finalHost = if (host.startsWith("$bucket.")) host else "$bucket.$host"
        val baseUrl = base.substring(0, schemeEnd) + finalHost
        val objectPath = objectKey.split('/').joinToString("/") { escapePathSegment(it) }
        val url = "$baseUrl/$objectPath"

        fun signature(method: String, type: String, date: String, canonical: String): String =
            UploadSigning.ossV1Authorization(
                accessKey = accessKey,
                secretKey = secretKey,
                method = method,
                contentType = type,
                date = date,
                canonicalResource = "/$bucket/$objectKey$canonical",
                securityToken = securityToken
            )

        fun request(
            method: String,
            parameters: Map<String, String> = emptyMap(),
            body: String? = null,
            start: Long? = null,
            end: Long? = null
        ): UploadResponse {
            val date = httpDate()
            val type = contentType ?: if (start == null) "application/xml" else file.mimeType
            val canonicalQuery = parameters.keys.sorted()
                .joinToString("&") { key ->
                    val value = parameters.getValue(key)
                    if (value.isEmpty()) escapeQuery(key) else "${escapeQuery(key)}=${escapeQuery(value)}"
                }
            val canonical = if (canonicalQuery.isEmpty()) "" else "?$canonicalQuery"
            val headers = mutableMapOf(
                "Authorization" to signature(method, type, date, canonical),
                "Date" to date,
                "Content-Type" to type
            )
            if (securityToken.isNotBlank()) headers["x-oss-security-token"] = securityToken
            val target = if (canonicalQuery.isEmpty()) url else "$url?$canonicalQuery"
            return send(
                url = target,
                method = method,
                headers = headers,
                contentType = type,
                start = start ?: 0L,
                end = end ?: file.size,
                // 控制类请求（初始化/合并/中止）没有文件分片，必须发空文本体：
                // 走文件体的话会把整个文件当请求体再传一遍
                body = if (start == null) body.orEmpty() else null,
                retries = if (start == null) 0 else 1
            )
        }

        if (file.size == 0L) {
            val response = request("PUT", start = 0L, end = 0L)
            require(response.isSuccessful && !xmlError(response.body)) {
                "网盘上传服务返回错误（HTTP ${response.code}）"
            }
            return
        }
        val initiated = request("POST", parameters = mapOf("uploads" to ""))
        require(initiated.isSuccessful && !xmlError(initiated.body)) {
            "网盘无法创建分段上传（HTTP ${initiated.code}）"
        }
        val uploadId = xmlValue(initiated.body, "UploadId")
        require(uploadId.isNotBlank()) { "网盘未创建分段上传任务" }
        var completed = false
        try {
            val partSize = maxOf(MIN_PART_SIZE, (file.size + 8999) / 9000)
            val etags = mutableListOf<String>()
            var offset = 0L
            while (offset < file.size) {
                val stop = minOf(file.size, offset + partSize)
                val response = request(
                    "PUT",
                    parameters = mapOf(
                        "partNumber" to (etags.size + 1).toString(),
                        "uploadId" to uploadId
                    ),
                    start = offset,
                    end = stop
                )
                require(response.isSuccessful) { "文件上传失败（HTTP ${response.code}）" }
                val etag = response.header("ETag")
                require(etag.isNotBlank()) { "网盘未确认上传分段" }
                etags.add(etag)
                offset = stop
            }
            progress(UploadPhase.FINISHING, file.size)
            val merged = request(
                "POST",
                parameters = mapOf("uploadId" to uploadId),
                body = completeXml(etags)
            )
            require(merged.isSuccessful && !xmlError(merged.body)) { "网盘未完成分段合并" }
            completed = true
        } finally {
            if (!completed && !cancelled) {
                runCatching { request("DELETE", parameters = mapOf("uploadId" to uploadId)).isSuccessful }
            }
        }
    }

    // ---------- AWS S3（V4 签名，123 的 S3 直传 / 迅雷用） ----------

    /**
     * S3 兼容存储直传：单分片一次 PUT，大文件 `?uploads` → 分段 PUT → 合并。
     *
     * @param region 服务商要求的 region（123 为 `123pan`，迅雷为 `xunlei`；它只参与签名，不做校验）
     * @param pathStyle true 时 URL 形如 `{origin}/{bucket}/{object}`（123 用），否则把 bucket 拼进主机名
     */
    fun s3Multipart(
        endpoint: String,
        bucket: String,
        objectKey: String,
        accessKey: String,
        secretKey: String,
        region: String,
        securityToken: String = "",
        pathStyle: Boolean = false
    ) {
        require(endpoint.isNotBlank() && bucket.isNotBlank() && objectKey.isNotBlank() &&
            accessKey.isNotBlank() && secretKey.isNotBlank()) {
            "网盘未返回完整上传凭据"
        }
        var base = endpoint.trim()
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
        val schemeEnd = base.indexOf("://") + 3
        val originRest = base.substring(schemeEnd).trimEnd('/')
        val host = originRest.substringBefore('/')
        val finalHost = if (pathStyle || host.startsWith("$bucket.")) host else "$bucket.$host"
        val origin = base.substring(0, schemeEnd) + finalHost
        val path = if (pathStyle) {
            "/$bucket/" + objectKey.split('/').joinToString("/") { escapePathSegment(it) }
        } else {
            "/" + objectKey.split('/').joinToString("/") { escapePathSegment(it) }
        }

        fun request(
            method: String,
            parameters: Map<String, String> = emptyMap(),
            body: String? = null,
            start: Long? = null,
            end: Long? = null
        ): UploadResponse {
            val stamp = isoStamp()
            val date = stamp.substring(0, 8)
            val contentHash = if (start == null) {
                sha256Hex(body.orEmpty().toByteArray(Charsets.UTF_8))
            } else {
                digest("SHA-256", start, end ?: file.size)
            }
            val signedHeaders = sortedMapOf(
                "host" to finalHost,
                "x-amz-content-sha256" to contentHash,
                "x-amz-date" to stamp
            )
            if (securityToken.isNotBlank()) signedHeaders["x-amz-security-token"] = securityToken
            val headerNames = signedHeaders.keys.joinToString(";")
            val canonicalQuery = parameters.keys.sorted()
                .joinToString("&") { key -> "${escapeQuery(key)}=${escapeQuery(parameters.getValue(key))}" }
            val canonicalHeaders = signedHeaders.entries
                .joinToString("") { (key, value) -> "$key:$value\n" }
            val canonical = "$method\n$path\n$canonicalQuery\n$canonicalHeaders\n" +
                "$headerNames\n$contentHash"
            val scope = "$date/$region/s3/aws4_request"
            val toSign = "AWS4-HMAC-SHA256\n$stamp\n$scope\n${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
            var key = hmac("HmacSHA256", "AWS4$secretKey".toByteArray(Charsets.UTF_8), date.toByteArray(Charsets.UTF_8))
            key = hmac("HmacSHA256", key, region.toByteArray(Charsets.UTF_8))
            key = hmac("HmacSHA256", key, "s3".toByteArray(Charsets.UTF_8))
            key = hmac("HmacSHA256", key, "aws4_request".toByteArray(Charsets.UTF_8))
            val signature = hex(hmac("HmacSHA256", key, toSign.toByteArray(Charsets.UTF_8)))
            val headers = mutableMapOf(
                "Host" to signedHeaders.getValue("host"),
                "x-amz-content-sha256" to contentHash,
                "x-amz-date" to stamp,
                "Authorization" to
                    "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, " +
                    "SignedHeaders=$headerNames, Signature=$signature"
            )
            if (securityToken.isNotBlank()) headers["x-amz-security-token"] = securityToken
            val query = if (canonicalQuery.isEmpty()) "" else "?$canonicalQuery"
            return send(
                url = origin + path + query,
                method = method,
                headers = headers,
                contentType = if (start == null) "application/xml" else file.mimeType,
                start = start ?: 0L,
                end = end ?: file.size,
                // 同上：初始化/合并/中止不能带上文件内容
                body = if (start == null) body.orEmpty() else null,
                retries = if (start == null) 0 else 1
            )
        }

        val partSize = maxOf(MIN_PART_SIZE, (file.size + 8999) / 9000)
        if (file.size <= partSize) {
            val response = request("PUT", start = 0L, end = file.size)
            require(response.isSuccessful && !xmlError(response.body)) {
                "网盘上传服务返回错误（HTTP ${response.code}）"
            }
            return
        }
        val initiated = request("POST", parameters = mapOf("uploads" to ""))
        require(initiated.isSuccessful && !xmlError(initiated.body)) {
            "网盘无法创建分段上传（HTTP ${initiated.code}）"
        }
        val uploadId = xmlValue(initiated.body, "UploadId")
        require(uploadId.isNotBlank()) { "网盘未创建分段上传任务" }
        var completed = false
        try {
            val etags = mutableListOf<String>()
            var offset = 0L
            while (offset < file.size) {
                val stop = minOf(file.size, offset + partSize)
                val response = request(
                    "PUT",
                    parameters = mapOf(
                        "partNumber" to (etags.size + 1).toString(),
                        "uploadId" to uploadId
                    ),
                    start = offset,
                    end = stop
                )
                require(response.isSuccessful) { "文件上传失败（HTTP ${response.code}）" }
                val etag = response.header("ETag")
                require(etag.isNotBlank()) { "网盘未确认上传分段" }
                etags.add(etag)
                offset = stop
            }
            progress(UploadPhase.FINISHING, file.size)
            val merged = request(
                "POST",
                parameters = mapOf("uploadId" to uploadId),
                body = completeXml(etags)
            )
            require(merged.isSuccessful && !xmlError(merged.body)) { "网盘未完成分段合并" }
            completed = true
        } finally {
            if (!completed && !cancelled) {
                runCatching { request("DELETE", parameters = mapOf("uploadId" to uploadId)).isSuccessful }
            }
        }
    }

    companion object {
        /** 读文件/发字节的缓冲区：64 KiB 是 SAF 流的常见块大小，再大对进度粒度没啥帮助 */
        private const val IO_BUFFER = 64 * 1024

        /** OSS/S3 分片下限（两者都要求 5 MiB 以上，这里按 8 MiB 留余量），同时按 9000 段上限自适应 */
        private const val MIN_PART_SIZE = 8L * 1024 * 1024

        fun hex(bytes: ByteArray): String = UploadSigning.hex(bytes)

        fun base64(bytes: ByteArray): String = UploadSigning.base64(bytes)

        /** URL-safe Base64（七牛对象键用，保留 `=` 填充） */
        fun base64Url(bytes: ByteArray): String = UploadSigning.base64(bytes, urlSafe = true)

        fun sha256Hex(bytes: ByteArray): String = UploadSigning.sha256Hex(bytes)

        fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray =
            UploadSigning.hmac(algorithm, key, data)

        /** HTTP 日期（OSS V1 用的 `Date` 头，必须是 GMT 的 RFC 1123 格式） */
        fun httpDate(): String {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            return format.format(Date())
        }

        /** S3 V4 的 `x-amz-date`：`yyyyMMdd'T'HHmmss'Z'`（UTC） */
        fun isoStamp(): String {
            val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            return format.format(Date())
        }

        /** 路径分段/查询值编码：只放开 RFC 3986 的 unreserved 字符（`/` 由调用方先分段） */
        fun escapePathSegment(value: String): String = escape(value)

        fun escapeQuery(value: String): String = escape(value)

        private fun escape(raw: String): String {
            val builder = StringBuilder()
            for (byte in raw.toByteArray(Charsets.UTF_8)) {
                val value = byte.toInt() and 0xFF
                val safe = value in 'A'.code..'Z'.code ||
                    value in 'a'.code..'z'.code ||
                    value in '0'.code..'9'.code ||
                    value == '-'.code || value == '.'.code || value == '_'.code || value == '~'.code
                if (safe) {
                    builder.append(value.toChar())
                } else {
                    builder.append('%').append(digits[value ushr 4]).append(digits[value and 0x0F])
                }
            }
            return builder.toString()
        }

        private const val digits = "0123456789ABCDEF"

        fun xmlEscape(value: String): String = UploadSigning.xmlEscape(value)

        fun xmlError(body: String): Boolean = UploadSigning.xmlError(body)

        fun xmlValue(body: String, tag: String): String = UploadSigning.xmlValue(body, tag)

        fun completeXml(etags: List<String>): String = UploadSigning.completeXml(etags)
    }
}
