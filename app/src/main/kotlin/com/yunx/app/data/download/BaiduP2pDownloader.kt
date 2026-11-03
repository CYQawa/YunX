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

package com.yunx.app.data.download

import android.util.Log
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.BaiduDownloadCrypto
import com.yunx.app.data.network.BaiduEncryptKey
import com.yunx.app.data.network.BaiduP2pProto
import com.yunx.app.data.network.BaiduP2pSpec
import com.yunx.app.data.network.HttpClients
import com.yunx.app.util.LogRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext
import kotlin.math.min

private const val TAG = "YunX-BD-P2P"

/**
 * 百度 Seed + POP + TPDATA 下载器。
 *
 * 边界（不可放宽）：
 * - 只做客户端：不监听端口、不响应 `tpdata` 上传请求、不上报资源；
 * - Seed 前必须先通过 `/cms/config` 声明 `open_p2p_upload=0`，关闭失败不启用 P2P；
 * - `query_peer.resource_capability` 固定为 0，明确声明本机不做种；
 * - 只访问 locatedownload 已授权的 fgid，不实现分享转存/网盘权限之外的任何绕过。
 */
class BaiduP2pDownloader(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    private val client get() = clientProvider()

    /**
     * 建立一次 P2P 会话：Seed query_peer -> query_pop -> 可用 POP 节点。
     *
     * 控制面是抓包确认的 HTTP 80/8827；Android 明文策略只为本项目的
     * `nj.t.bcsp2p.baidu.com` 单独开域，绝不打开全局 cleartext。
     */
    suspend fun prepare(
        spec: BaiduP2pSpec,
        cookie: String
    ): BaiduP2pSession = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) {
            throw IOException("P2P 缺少登录 Cookie，无法关闭上传能力")
        }
        val timestamp = System.currentTimeMillis() / 1000
        val sequence = ((System.nanoTime() ushr 1) and 0x7FFFFFFFL)
        val logId = BaiduDownloadCrypto.makeLogId(timestamp, sequence)
        val peerId = BaiduDownloadCrypto.md5(
            (spec.devuid.ifBlank { spec.uid } + "|yunx-p2p-peer").toByteArray(Charsets.UTF_8)
        ).copyOf(16)
        configureNoUpload(spec, cookie, peerId)
        val payload = BaiduP2pProto.queryPeer(
            peerId = peerId,
            fgid = spec.fgid,
            fileSize = spec.fileSize,
            uid = spec.uid,
            sequence = sequence,
            logId = logId
        )
        val packet = BaiduDownloadCrypto.buildSeedPacket(
            payload = payload,
            cmd = BaiduDownloadCrypto.SEED_CMD_REQUEST,
            timestamp = timestamp,
            sequence = sequence,
            logId = logId
        )
        val seedUrl = BaiduConstants.P2P_SEED_URL +
            "?uk=" + URLEncoder.encode(spec.uid, "UTF-8")
        val request = Request.Builder()
            .url(seedUrl)
            .header("User-Agent", BaiduConstants.UA_P2SP)
            .header("Content-Type", "application/octet-stream")
            .post(packet.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Seed 查询失败（HTTP ${response.code}）")
            }
            response.body?.bytes() ?: throw IOException("Seed 响应为空")
        }
        val plain = BaiduDownloadCrypto.unpackSeedPacket(responseBody)
            ?: throw IOException("Seed 响应包校验/解密失败")
        val response = BaiduP2pProto.parseQueryPeerResponse(plain)
            ?: throw IOException("Seed query_peer 响应解析失败")
        if (response.result != 0) {
            throw IOException("Seed query_peer 失败（result=${response.result}）")
        }
        if (response.fileSize > 0 && response.fileSize != spec.fileSize) {
            throw IOException(
                "Seed 返回的文件大小与 locatedownload 不一致" +
                    "（${response.fileSize}/${spec.fileSize}）"
            )
        }
        // 同一 endpoint 可能在 Seed 里出现多次，各自携带不同的 2 MiB 区间；
        // 这里不能按 IP:port 去重，否则会丢掉后续区间。
        val peers = response.peers
        if (peers.isEmpty()) {
            throw IOException("Seed 未返回带 range_list 的 P2P 节点，回退 CDN")
        }
        Log.d(
            TAG,
            "P2P 会话就绪 peers=${response.peerCount} routes=${peers.size} " +
                "ranges=${peers.sumOf { it.ranges.size }} size=${spec.fileSize} " +
                "path=${LogRedactor.url(spec.path)}"
        )
        BaiduP2pSession(peers = peers, cookie = cookie)
    }

    /**
     * 先向百度声明 `open_p2p_upload=0`，再进入 Seed。
     *
     * 本机本来就不监听/不做种，但这个服务端开关决定百度是否把本机登记成
     * 可上传资源。若关闭失败就不继续 P2P（上层回退 CDN），保证“只下载不上传”
     * 不是仅靠客户端自觉。
     */
    private fun configureNoUpload(
        spec: BaiduP2pSpec,
        cookie: String,
        peerId: ByteArray
    ) {
        val uid = URLEncoder.encode(spec.uid, "UTF-8")
        val url = "https://pan.baidu.com/cms/config?method=query" +
            "&clienttype=${BaiduConstants.P2P_CLIENT_TYPE}" +
            "&version=${BaiduConstants.P2P_SDK_VERSION}" +
            "&uk=$uid&open_p2p_upload=0"
        val peerIdHex = BaiduDownloadCrypto.hex(peerId).uppercase()
        val form = "version=${BaiduConstants.P2P_SDK_VERSION}" +
            "&peerid=$peerIdHex" +
            "&uk=$uid" +
            "&fgid=${URLEncoder.encode(spec.fgid, "UTF-8")}" +
            "&cfg=p2sp_time_sharing"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_P2SP)
            .header("Accept", "*/*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("关闭 P2P 上传能力失败（HTTP ${response.code}）")
            }
            val json = runCatching {
                JSONObject(response.body?.string() ?: "{}")
            }.getOrElse {
                throw IOException("关闭 P2P 上传能力失败（响应非 JSON）")
            }
            if (json.optInt("error_code", -1) != 0) {
                throw IOException(
                    "关闭 P2P 上传能力失败（error_code=${json.optInt("error_code", -1)}）"
                )
            }
        }
    }

    /**
     * 连续下载 [start]..[endInclusive]（含端点）到 [partFile]。
     *
     * 每 1 MiB 才落一次盘，因此断点只可能落在完整分片边界；若进程在 write 中被杀，
     * 下次会把残缺尾块截掉，绝不会把半片密文当成有效进度。
     */
    suspend fun downloadRange(
        spec: BaiduP2pSpec,
        session: BaiduP2pSession,
        start: Long,
        endInclusive: Long,
        partFile: File,
        onBytes: suspend (Long) -> Unit
    ) {
        if (endInclusive < start) return
        require(start % BaiduConstants.P2P_ENCRYPT_SLICE_SIZE == 0L) {
            "P2P 分片起点必须按 ${BaiduConstants.P2P_ENCRYPT_SLICE_SIZE} 字节对齐"
        }
        val expected = endInclusive - start + 1
        var existing = partFile.length().coerceAtMost(expected)
        if (existing == expected) return
        // 上次可能死在 append 中途：只保留完整 1 MiB 块。
        val aligned = existing - existing % BaiduConstants.P2P_RANGE_SIZE
        if (aligned != existing) {
            java.io.RandomAccessFile(partFile, "rw").use { it.setLength(aligned) }
            existing = aligned
        }
        if (!partFile.exists()) partFile.createNewFile()
        FileOutputStream(partFile, true).use { output ->
            var position = start + existing
            while (position <= endInclusive) {
                if (!coroutineContext.isActive) throw CancellationException("P2P 下载被取消")
                val length = min(
                    BaiduConstants.P2P_RANGE_SIZE.toLong(),
                    endInclusive - position + 1
                )
                val plain = requestRange(spec, session, position, length)
                if (plain.size.toLong() != length) {
                    throw IOException("POP 返回字节数不符：期望 $length，实际 ${plain.size}")
                }
                output.write(plain)
                output.flush()
                onBytes(length)
                position += length
            }
        }
    }

    private suspend fun requestRange(
        spec: BaiduP2pSpec,
        session: BaiduP2pSession,
        offset: Long,
        length: Long
    ): ByteArray {
        // Seed 的 range_list 就是各节点的负责区间。节点只接受区间内请求，
        // 区间外会返回 404 invalid fgid；这不是 fgid 本身错误。
        val candidates = session.peers
            .filter { it.covers(offset, length) }
            .distinctBy { "${it.ip}:${it.publicPort}" }
            .take(5)
        if (candidates.isEmpty()) {
            Log.d(
                TAG,
                "P2P 无节点覆盖 offset=$offset length=$length，改用 CDN 补齐"
            )
            return requestCdnRange(spec, session.cookie, offset, length)
        }
        var lastError: Throwable? = null
        for (peer in candidates) {
            try {
                return requestPeer(spec, peer, offset, length)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!coroutineContext.isActive) {
                    throw CancellationException("P2P 下载被取消")
                }
                lastError = e
                Log.w(
                    TAG,
                    "POP 节点失败 node=${peer.ip}:${peer.publicPort} offset=$offset " +
                        "err=${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
        Log.w(
            TAG,
            "P2P 覆盖节点均失败 offset=$offset length=$length，改用 CDN 补齐：" +
                "${lastError?.message ?: "未知错误"}"
        )
        return try {
            requestCdnRange(spec, session.cookie, offset, length)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IOException(
                "P2P 节点与 CDN 补齐均失败：${e.message ?: "未知错误"}",
                e
            )
        }
    }

    private suspend fun requestPeer(
        spec: BaiduP2pSpec,
        peer: BaiduP2pProto.PeerNode,
        offset: Long,
        length: Long
    ): ByteArray {
        if (peer.ip.any { it.isISOControl() }) {
            throw IOException("POP 节点地址含控制字符，已拒绝")
        }
        val body = BaiduP2pProto.tpdataRequest(
            fgid = spec.fgid,
            uid = spec.uid,
            offset = offset,
            length = length,
            devuid = spec.devuid,
            deviceId = spec.deviceId,
            sl = spec.sl,
            fsl = spec.fsl,
            locateDownloadLocalTime = spec.localTime,
            locateDownloadServerTime = spec.serverTime
        )
        val hostText = if (peer.ip.contains(':')) "[${peer.ip}]" else peer.ip
        val hostHeader = if (peer.publicPort == 80) hostText else "$hostText:${peer.publicPort}"
        val header = buildString {
            append("POST /pop/tpdata HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Accept: */*\r\n")
            append("Accept-Language: zh-CN\r\n")
            append("Connection: close\r\n")
            append("Range: bytes=").append(offset).append('-').append(offset + length - 1).append("\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("User-Agent: ").append(BaiduConstants.UA_P2SP).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n\r\n")
        }
        val response = rawPost(
            peer.ip,
            peer.publicPort,
            header.toByteArray(Charsets.US_ASCII),
            body
        )
        val headers = response.headers
        val range = HttpRangePolicy.parse(headers["content-range"])
            ?: throw IOException("POP 响应缺少合法 Content-Range")
        val requestedEnd = offset + length - 1
        if (range.start != offset || range.end != requestedEnd) {
            throw IOException(
                "POP Content-Range 不匹配（期望 $offset-$requestedEnd，实际 " +
                    "${range.start}-${range.end}）"
            )
        }
        if (range.total != null && range.total != spec.fileSize) {
            throw IOException("POP 文件大小不一致（${range.total}/${spec.fileSize}）")
        }
        // Keyversion 缺失表示该 POP 直接回明文；有值就必须命中 locatedownload 下发的 key。
        // 解密必须紧贴响应头处理，不能把 keyversion 存进共享字段（并发分片会串号）。
        val version = headers["keyversion"]?.toLongOrNull() ?: return response.body
        val key = spec.encryptKeys.firstOrNull { it.version == version }
            ?: throw IOException("POP Keyversion=$version 在 locatedownload 密钥列表中不存在")
        return decryptWithKey(key, response.body, offset)
    }

    /**
     * P2SP 节点只缓存文件的一部分；未被任何节点覆盖的区间由 locatedownload
     * 下发的明文 CDN 直链补齐。这样不会把 `invalid fgid` 当成整任务失败。
     */
    private fun requestCdnRange(
        spec: BaiduP2pSpec,
        cookie: String,
        offset: Long,
        length: Long
    ): ByteArray {
        val end = offset + length - 1
        var lastError: Throwable? = null
        for (url in spec.cdnUrls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$offset-$end")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", BaiduConstants.UA_P2SP)
                    .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("CDN 补齐失败（HTTP ${response.code}）")
                    }
                    val range = HttpRangePolicy.parse(response.header("Content-Range"))
                        ?: throw IOException("CDN 补齐响应缺少合法 Content-Range")
                    if (range.start != offset || range.end != end) {
                        throw IOException(
                            "CDN Content-Range 不匹配（期望 $offset-$end，实际 " +
                                "${range.start}-${range.end}）"
                        )
                    }
                    if (range.total != null && range.total != spec.fileSize) {
                        throw IOException("CDN 文件大小不一致（${range.total}/${spec.fileSize}）")
                    }
                    val body = response.body?.bytes() ?: throw IOException("CDN 补齐响应为空")
                    if (body.size.toLong() != length) {
                        throw IOException("CDN 补齐字节数不符：期望 $length，实际 ${body.size}")
                    }
                    return body
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw IOException(
            "没有可用 CDN 补齐源：${lastError?.message ?: "locatedownload 未返回明文 CDN"}",
            lastError
        )
    }

    private fun decryptWithKey(
        key: BaiduEncryptKey,
        encrypted: ByteArray,
        offset: Long
    ): ByteArray {
        if (!key.algorithmMode.equals("CTR", ignoreCase = true) ||
            !key.padding.equals("NoPadding", ignoreCase = true)
        ) {
            throw IOException("不支持的 P2P 加密参数：${key.algorithmMode}/${key.padding}")
        }
        if (key.algorithmBit != 256 || key.key.length != 64 || key.algorithmIv.length != 64) {
            throw IOException(
                "locatedownload 密钥格式不支持：bit=${key.algorithmBit} " +
                    "key=${key.key.length} iv=${key.algorithmIv.length}"
            )
        }
        return BaiduDownloadCrypto.decryptP2pRange(
            encrypted = encrypted,
            keyHex = key.key,
            ivHex = key.algorithmIv,
            absoluteStart = offset,
            sliceSize = key.sliceSize.coerceAtLeast(16)
        )
    }

    private suspend fun rawPost(
        host: String,
        port: Int,
        header: ByteArray,
        body: ByteArray
    ): RawResponse {
        val addresses = InetAddress.getAllByName(host)
        var lastError: Throwable? = null
        for (address in addresses) {
            val socket = Socket()
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion {
                runCatching { socket.close() }
            }
            try {
                socket.connect(InetSocketAddress(address, port), 10_000)
                socket.soTimeout = 20_000
                socket.tcpNoDelay = true
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write(header)
                output.write(body)
                output.flush()
                return readResponse(BufferedInputStream(socket.getInputStream()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!coroutineContext.isActive) {
                    throw CancellationException("P2P 下载被取消")
                }
                lastError = e
            } finally {
                cancelHandle?.dispose()
                runCatching { socket.close() }
            }
        }
        throw IOException("连接 POP 节点失败：$host:$port", lastError)
    }

    private fun readResponse(input: InputStream): RawResponse {
        val headerBytes = readHeader(input)
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) throw IOException("POP 响应为空")
        val statusParts = lines.first().split(' ')
        val status = statusParts.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("POP 状态行非法")
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] =
                line.substring(colon + 1).trim()
        }
        if (status !in 200..299) {
            val detail = runCatching {
                readBody(input, headers).toString(Charsets.UTF_8).trim()
            }.getOrNull().orEmpty().take(120)
            throw IOException(
                "POP HTTP $status" + detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            )
        }
        return RawResponse(headers, readBody(input, headers))
    }

    private fun readBody(
        input: InputStream,
        headers: Map<String, String>
    ): ByteArray {
        return when {
            headers["transfer-encoding"].orEmpty().contains("chunked", ignoreCase = true) ->
                readChunked(input)
            headers["content-length"]?.toLongOrNull() != null -> {
                val length = headers["content-length"]!!.toLong()
                if (length < 0 || length > Int.MAX_VALUE) throw IOException("POP Content-Length 非法")
                readExactly(input, length.toInt())
            }
            else -> readLimited(input, 4 * 1024 * 1024)
        }
    }

    private fun readHeader(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(1024)
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (out.size() < 64 * 1024) {
            val value = input.read()
            if (value < 0) throw IOException("POP 响应头截断")
            out.write(value)
            matched = if (value.toByte() == terminator[matched]) matched + 1 else if (value.toByte() == terminator[0]) 1 else 0
            if (matched == terminator.size) {
                return out.toByteArray().copyOfRange(0, out.size() - terminator.size)
            }
        }
        throw IOException("POP 响应头过大")
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readAsciiLine(input)
            val size = line.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("POP chunk size 非法")
            if (size == 0) {
                // 读取 trailer，直到空行
                while (readAsciiLine(input).isNotEmpty()) Unit
                break
            }
            if (size < 0 || out.size() + size > 4 * 1024 * 1024) {
                throw IOException("POP chunk 过大")
            }
            out.write(readExactly(input, size))
            if (input.read() != '\r'.code || input.read() != '\n'.code) {
                throw IOException("POP chunk 结尾非法")
            }
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (out.size() < 4096) {
            val value = input.read()
            if (value < 0) throw IOException("POP 行截断")
            if (value == '\n'.code) {
                val bytes = out.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, Charsets.US_ASCII)
            }
            out.write(value)
        }
        throw IOException("POP 行过长")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read <= 0) throw IOException("POP 响应体截断")
            offset += read
        }
        return out
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (out.size() + read > limit) throw IOException("POP 响应体过大")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private data class RawResponse(val headers: Map<String, String>, val body: ByteArray)
}

/** 一次 P2P 会话的 Seed 节点及其负责区间；由 [BaiduP2pDownloader.prepare] 创建。 */
class BaiduP2pSession internal constructor(
    internal val peers: List<BaiduP2pProto.PeerNode>,
    internal val cookie: String
)
