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

package com.yunx.app.ui.preview

import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.video.VideoHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本预览的取内容结果。
 *
 * @param content 已解码的文本
 * @param truncated 是否被截断（响应是 206，或服务端忽略 Range 返回 200 但我们只读了上限）——
 *   界面据此提示「已截断，完整内容请下载后查看」
 */
data class PreviewTextContent(
    val content: String,
    val truncated: Boolean
)

/**
 * 文本预览取内容：**只读前 512KB**，绝不整文件下载。
 *
 * 为什么单独一个取内容的实现：
 * - 文本直链（网盘 raw/直链）同样校验 `Referer`/`Cookie`/`UA`，必须带请求头，所以不能交给任何"通用下载"；
 * - 电子书式的大文件整下再渲染既慢又费流量，预览只要开头一段就够看出「是不是我要找的那份文本」。
 *
 * 编码判定顺序（见 [decodeText]）：UTF-8 严格解码 → GBK → ISO-8859-1 兜底。
 * 中文 txt 大多由 Windows 记事本另存为 GBK，只试 UTF-8 会得到一整页乱码或空白。
 */
object PreviewTextLoader {

    /** 读取上限：512KB（Range 与内存缓冲共用同一个数，避免"声明 512KB 实际读更多"） */
    const val MAX_BYTES = 512 * 1024

    /** 只取前 512KB：206 是服务端支持 Range 的正常响应，200 表示服务端忽略了 Range（那时靠读满上限截断） */
    private const val RANGE_HEADER_VALUE = "bytes=0-${MAX_BYTES - 1}"

    /** GBK 在 Android（ICU）上一定有，但仍按可空处理：拿不到就只剩 ISO-8859-1 兜底，不能因此崩 */
    private val gbkCharset: Charset? = runCatching { Charset.forName("GBK") }.getOrNull()

    /**
     * 取文本内容。
     *
     * @throws IOException 非 2xx、响应无内容等失败原因（带中文说明，给界面直接展示）
     */
    suspend fun load(url: String, headers: Map<String, String>): PreviewTextContent =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply {
                    // 先铺项目统一桌面 UA，再用调用方请求头覆盖（平台直链的 UA/Cookie/Referer 缺一个就 403）
                    header("User-Agent", VideoHttp.UA_CHROME)
                    headers.forEach { (name, value) -> header(name, value) }
                    // ★ Range 必须最后设置：请求头里若混进 Range，绝不能被它顶掉，否则可能整文件下载
                    header("Range", RANGE_HEADER_VALUE)
                }
                .get()
                .build()

            HttpClients.apiClient().newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 206) {
                    throw IOException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("响应没有内容")

                // 服务端可能无视 Range 返回 200：按字节数硬读上限，绝不把整文件拉进内存
                val buffer = ByteArray(MAX_BYTES)
                var read = 0
                body.byteStream().use { input ->
                    while (read < MAX_BYTES) {
                        val count = input.read(buffer, read, MAX_BYTES - read)
                        if (count <= 0) break
                        read += count
                    }
                }
                val bytes = if (read == buffer.size) buffer else buffer.copyOf(read)

                // 已知长度的响应：长度超过上限一定是被截断的；长度未知（分块传输）则以"读满了上限"为准
                val declaredLength = body.contentLength()
                val truncated = response.code == 206 ||
                    declaredLength > MAX_BYTES ||
                    (declaredLength < 0 && read >= MAX_BYTES)

                PreviewTextContent(content = decodeText(bytes), truncated = truncated)
            }
        }

    /** UTF-8 严格解码 → GBK → ISO-8859-1 兜底（后者的任何字节都能映射成字符，永远不会失败，不会给空白页） */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        gbkCharset?.let { charset -> strictDecode(bytes, charset)?.let { return it } }
        return String(bytes, Charsets.ISO_8859_1)
    }

    /**
     * 严格解码（遇到非法字节返回 null 交给下一个编码，而不是塞替换字符）。
     *
     * ★ 末尾最多退 3 个字节再试：按上限截断时很可能把一个多字节字符劈成两半，
     *   此时整段内容其实只差最后一个残字，直接判定"编码不对"会白白退到 ISO-8859-1 变一整页乱码。
     */
    private fun strictDecode(bytes: ByteArray, charset: Charset, maxTrim: Int = 3): String? {
        var end = bytes.size
        while (end > 0) {
            val text = try {
                charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, end))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
            if (text != null) return text.removePrefix("\uFEFF") // 去掉 UTF-8 BOM，否则正文首字前会多一个怪符号
            if (bytes.size - end >= maxTrim) return null
            end -= 1
        }
        return null
    }
}
