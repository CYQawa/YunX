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

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** locatedownload 返回的 AES 密钥描述。 */
data class BaiduEncryptKey(
    val algorithmBit: Int,
    val algorithmIv: String,
    val algorithmMode: String,
    val key: String,
    val padding: String,
    val sliceSize: Int,
    val version: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("bit", algorithmBit)
        .put("iv", algorithmIv)
        .put("mode", algorithmMode)
        .put("key", key)
        .put("pad", padding)
        .put("slice", sliceSize)
        .put("ver", version)

    companion object {
        fun fromJson(json: JSONObject): BaiduEncryptKey = BaiduEncryptKey(
            algorithmBit = json.optInt("algorithm_bit", 256),
            algorithmIv = json.optString("algorithm_iv"),
            algorithmMode = json.optString("algorithm_mode", "CTR"),
            key = json.optString("key"),
            padding = json.optString("padding", "NoPadding"),
            sliceSize = json.optInt("slice_size", 8192).coerceAtLeast(16),
            version = json.optLong("version")
        )
    }
}

/**
 * 百度 P2P 下载任务的可持久化快照。
 *
 * 内置下载器把合成 URL 写进 `download_task.url`，进程重启后仍能恢复：
 * fgid/uid/加密 key 都已保存在这里，不需要重新访问 locatedownload。
 * Cookie 仍由 DownloadTask 的 requestHeadersJson 单独保存，绝不重复写入本 spec。
 *
 * 编码格式刻意不用 org.json：项目的 JVM 单测里 android.jar 的 JSONObject 是空壳，
 * 一旦依赖它，快照往返测试就永远只能得到 null。这里用 URL 编码的逐行键值，
 * 字符串里的换行/竖线/等号都不会破坏格式。
 */
data class BaiduP2pSpec(
    val fgid: String,
    val fsId: String,
    val uid: String,
    val path: String,
    val fileSize: Long,
    val encryptKeys: List<BaiduEncryptKey>,
    val sl: Long = 120L,
    val fsl: Long = -1L,
    val localTime: Long = 0L,
    val serverTime: Long = 0L,
    val devuid: String = "",
    val deviceId: String = "",
    /** locatedownload 返回的明文 CDN 候选，按 rank 排序；P2P 失败时整任务回退。 */
    val cdnUrls: List<String> = emptyList()
) {
    fun toUrl(): String {
        val text = buildString {
            append("v=2\n")
            append("fgid=").append(encode(fgid)).append('\n')
            append("fsid=").append(encode(fsId)).append('\n')
            append("uid=").append(encode(uid)).append('\n')
            append("path=").append(encode(path)).append('\n')
            append("size=").append(fileSize).append('\n')
            append("sl=").append(sl).append('\n')
            append("fsl=").append(fsl).append('\n')
            append("local=").append(localTime).append('\n')
            append("server=").append(serverTime).append('\n')
            append("devuid=").append(encode(devuid)).append('\n')
            append("device=").append(encode(deviceId)).append('\n')
            encryptKeys.forEach { key ->
                append("key=").append(
                    listOf(
                        key.algorithmBit.toString(),
                        key.algorithmIv,
                        key.algorithmMode,
                        key.key,
                        key.padding,
                        key.sliceSize.toString(),
                        key.version.toString()
                    ).joinToString("|") { encode(it) }
                ).append('\n')
            }
            cdnUrls.forEach { append("cdn=").append(encode(it)).append('\n') }
        }
        return PREFIX + BaiduDownloadCrypto.encodeBase64Url(text.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val PREFIX = "baidu-p2p://"

        fun isP2p(url: String): Boolean = url.startsWith(PREFIX)

        fun decode(url: String): BaiduP2pSpec? {
            if (!isP2p(url)) return null
            val raw = BaiduDownloadCrypto.decodeBase64Url(url.removePrefix(PREFIX))
                ?: return null
            val text = runCatching { String(raw, Charsets.UTF_8) }.getOrNull() ?: return null
            val values = linkedMapOf<String, String>()
            val keys = mutableListOf<BaiduEncryptKey>()
            val cdns = mutableListOf<String>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val split = line.indexOf('=')
                if (split <= 0) continue
                val name = line.substring(0, split)
                val value = line.substring(split + 1)
                when (name) {
                    "key" -> parseKey(value)?.let(keys::add)
                    "cdn" -> decodeValue(value).takeIf { it.isNotBlank() }?.let(cdns::add)
                    else -> values[name] = decodeValue(value)
                }
            }
            val fgid = values["fgid"].orEmpty()
            val uid = values["uid"].orEmpty()
            val path = values["path"].orEmpty()
            val size = values["size"]?.toLongOrNull() ?: return null
            if (fgid.isBlank() || uid.isBlank() || path.isBlank() || size <= 0L) return null
            return BaiduP2pSpec(
                fgid = fgid,
                fsId = values["fsid"].orEmpty(),
                uid = uid,
                path = path,
                fileSize = size,
                encryptKeys = keys,
                sl = values["sl"]?.toLongOrNull() ?: 120L,
                fsl = values["fsl"]?.toLongOrNull() ?: -1L,
                localTime = values["local"]?.toLongOrNull() ?: 0L,
                serverTime = values["server"]?.toLongOrNull() ?: 0L,
                devuid = values["devuid"].orEmpty(),
                deviceId = values["device"].orEmpty(),
                cdnUrls = cdns
            )
        }

        private fun parseKey(value: String): BaiduEncryptKey? {
            val parts = value.split('|').map { decodeValue(it) }
            if (parts.size != 7) return null
            return BaiduEncryptKey(
                algorithmBit = parts[0].toIntOrNull() ?: 256,
                algorithmIv = parts[1],
                algorithmMode = parts[2],
                key = parts[3],
                padding = parts[4],
                sliceSize = parts[5].toIntOrNull() ?: 8192,
                version = parts[6].toLongOrNull() ?: 0L
            ).takeIf { it.key.isNotBlank() && it.algorithmIv.isNotBlank() }
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun decodeValue(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
