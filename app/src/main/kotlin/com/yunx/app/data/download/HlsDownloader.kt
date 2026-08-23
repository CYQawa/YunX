package com.yunx.app.data.download

import android.util.Log
import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** Bounded HLS downloader that never forwards credentials across origins. */
object HlsDownloader {
    private const val TAG = "YunX-HLS"
    private const val MAX_REDIRECTS = 5
    private const val MAX_PLAYLIST_BYTES = 1024 * 1024L
    private const val MAX_SEGMENTS = 20_000
    private const val MAX_SEGMENT_BYTES = 512L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 100L * 1024 * 1024 * 1024
    private const val COPY_BUFFER_SIZE = 64 * 1024

    // Redirects are handled here so a cross-origin hop cannot inherit Cookie/Authorization.
    private val client get() = HttpClients.apiClient().newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        destFile: File,
        onBytes: suspend (Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val credentialOrigin = HlsRequestPolicy.initialUrl(url) ?: run {
            Log.w(TAG, "拒绝非 HTTPS 或无效的 HLS 地址")
            return@withContext false
        }

        runCatching {
            val master = fetchText(credentialOrigin, credentialOrigin, headers) ?: return@runCatching false
            val mediaUrl = resolveMediaPlaylist(master.finalUrl, master.text) ?: return@runCatching false
            val media = if (mediaUrl == master.finalUrl) master
            else fetchText(mediaUrl, credentialOrigin, headers) ?: return@runCatching false

            if (media.text.contains("#EXT-X-KEY") || media.text.contains("#EXT-X-BYTERANGE")) {
                Log.w(TAG, "HLS 含不支持的加密或 BYTERANGE")
                return@runCatching false
            }

            val initUri = parseMapUri(media.text)?.let { HlsRequestPolicy.resolve(media.finalUrl, it) }
            val rawSegments = parseSegments(media.text)
            if (rawSegments.isEmpty()) return@runCatching false
            val segments = rawSegments.map { raw ->
                HlsRequestPolicy.resolve(media.finalUrl, raw)
                    ?: throw IllegalArgumentException("HLS 分片地址不是受支持的 HTTPS URL")
            }

            destFile.parentFile?.mkdirs()
            FileOutputStream(destFile, false).use { out ->
                var total = 0L
                if (initUri != null) {
                    val wrote = fetchTo(initUri, credentialOrigin, headers, out, MAX_SEGMENT_BYTES) { bytes ->
                        total = checkedTotal(total, bytes)
                        onBytes(bytes)
                    } ?: return@runCatching false
                    if (wrote <= 0) return@runCatching false
                }
                segments.forEachIndexed { index, segment ->
                    val wrote = fetchTo(segment, credentialOrigin, headers, out, MAX_SEGMENT_BYTES) { bytes ->
                        total = checkedTotal(total, bytes)
                        onBytes(bytes)
                    } ?: return@runCatching false
                    if (wrote <= 0) return@runCatching false
                    if (index % 10 == 0) Log.d(TAG, "HLS 分片 ${index + 1}/${segments.size}")
                }
                Log.d(TAG, "HLS 下载完成 segments=${segments.size} size=$total")
            }
            true
        }.onFailure {
            Log.e(TAG, "HLS 下载失败: ${it.message}")
        }.getOrDefault(false).also { success ->
            if (!success) destFile.delete()
        }
    }

    private data class FetchedText(val finalUrl: HttpUrl, val text: String)

    private suspend fun fetchText(
        startUrl: HttpUrl,
        credentialOrigin: HttpUrl,
        headers: Map<String, String>
    ): FetchedText? {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val result = executeCancellable(requestFor(current, credentialOrigin, headers)) { response ->
                redirectTarget(response, current)?.let { return@executeCancellable it to null }
                if (!response.isSuccessful) return@executeCancellable null
                val body = response.body ?: return@executeCancellable null
                null to readBoundedText(body.byteStream(), body.contentLength())
            } ?: return null
            val redirect = result.first
            if (redirect == null) return FetchedText(current, result.second ?: return null)
            if (redirectCount >= MAX_REDIRECTS) return null
            current = redirect
        }
        return null
    }

    private suspend fun fetchTo(
        startUrl: HttpUrl,
        credentialOrigin: HttpUrl,
        headers: Map<String, String>,
        output: OutputStream,
        maxBytes: Long,
        onBytes: suspend (Long) -> Unit
    ): Long? {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val result = executeCancellable(requestFor(current, credentialOrigin, headers)) { response ->
                redirectTarget(response, current)?.let { return@executeCancellable it to null }
                if (!response.isSuccessful) return@executeCancellable null
                val body = response.body ?: return@executeCancellable null
                if (body.contentLength() > maxBytes) throw IllegalStateException("HLS 分片超过大小限制")
                var written = 0L
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                body.byteStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        written += count
                        if (written > maxBytes) throw IllegalStateException("HLS 分片超过大小限制")
                        output.write(buffer, 0, count)
                        onBytes(count.toLong())
                    }
                }
                null to written
            } ?: return null
            val redirect = result.first
            if (redirect == null) return result.second
            if (redirectCount >= MAX_REDIRECTS) return null
            current = redirect
        }
        return null
    }

    private fun requestFor(
        target: HttpUrl,
        credentialOrigin: HttpUrl,
        headers: Map<String, String>
    ): Request = Request.Builder()
        .url(target)
        .apply {
            HlsRequestPolicy.headersFor(target, credentialOrigin, headers)
                .forEach { (name, value) -> header(name, value) }
        }
        .get()
        .build()

    private fun redirectTarget(response: Response, current: HttpUrl): HttpUrl? {
        if (response.code !in 300..399) return null
        val location = response.header("Location") ?: return null
        return HlsRequestPolicy.resolve(current, location)
            ?: throw IllegalArgumentException("HLS 重定向到非 HTTPS 地址")
    }

    private suspend fun <T> executeCancellable(request: Request, block: suspend (Response) -> T): T {
        val call = client.newCall(request)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        val response = call.execute()
        return try {
            block(response)
        } finally {
            response.close()
            cancelHandle?.dispose()
        }
    }

    private fun readBoundedText(input: java.io.InputStream, declaredLength: Long): String {
        if (declaredLength > MAX_PLAYLIST_BYTES) throw IllegalStateException("HLS 播放列表超过大小限制")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        input.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size().toLong() + count > MAX_PLAYLIST_BYTES) {
                    throw IllegalStateException("HLS 播放列表超过大小限制")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun checkedTotal(current: Long, added: Long): Long {
        val total = Math.addExact(current, added)
        if (total > MAX_TOTAL_BYTES) throw IllegalStateException("HLS 总下载量超过限制")
        return total
    }

    private fun resolveMediaPlaylist(playlistUrl: HttpUrl, text: String): HttpUrl? {
        val lines = text.lineSequence().toList()
        for (index in lines.indices) {
            if (lines[index].startsWith("#EXT-X-STREAM-INF")) {
                val next = lines.getOrNull(index + 1)?.trim() ?: continue
                if (next.isNotBlank() && !next.startsWith("#")) {
                    return HlsRequestPolicy.resolve(playlistUrl, next)
                }
            }
        }
        return playlistUrl
    }

    private fun parseSegments(text: String): List<String> = buildList {
        for (line in text.lineSequence()) {
            val value = line.trim()
            if (value.isNotBlank() && !value.startsWith("#")) {
                if (size >= MAX_SEGMENTS) throw IllegalStateException("HLS 分片数量超过限制")
                add(value)
            }
        }
    }

    private fun parseMapUri(text: String): String? {
        val line = text.lineSequence().firstOrNull { it.startsWith("#EXT-X-MAP") } ?: return null
        return Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
    }
}
