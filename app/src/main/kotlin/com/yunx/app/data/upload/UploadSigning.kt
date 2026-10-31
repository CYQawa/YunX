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

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 上传用到的纯计算：十六进制 / Base64 / HMAC / OSS 签名 / 迅雷 gcid。
 *
 * 单独抽出来是因为这些是最容易算错、又最适合单测的部分（不碰 Android、不碰网络）。
 * Base64 自己实现是为了两件事：`java.util.Base64` 要 API 26（本项目 minSdk 24），
 * 而 `android.util.Base64` 在 JVM 单测里直接抛 "not mocked"。
 */
internal object UploadSigning {

    private const val STANDARD =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** 小写十六进制（各家哈希/ETag 对比用的都是小写） */
    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[value ushr 4]
            out[i * 2 + 1] = digits[value and 0x0F]
        }
        return String(out)
    }

    /**
     * 标准 / URL-safe Base64。
     *
     * @param urlSafe `+/` 换成 `-_`（七牛对象键要求）
     * @param padding 是否补 `=`（七牛 key 的编码保留填充，OSS 回调也是）
     */
    fun base64(bytes: ByteArray, urlSafe: Boolean = false, padding: Boolean = true): String {
        val alphabet = if (urlSafe) URL_SAFE else STANDARD
        val builder = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val has1 = index + 1 < bytes.size
            val has2 = index + 2 < bytes.size
            val b1 = if (has1) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (has2) bytes[index + 2].toInt() and 0xFF else 0
            builder.append(alphabet[b0 ushr 2])
            builder.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (has1) {
                builder.append(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            } else if (padding) {
                builder.append('=')
            }
            if (has2) {
                builder.append(alphabet[b2 and 0x3F])
            } else if (padding) {
                builder.append('=')
            }
            index += 3
        }
        return builder.toString()
    }

    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).let(::hex)

    /**
     * 阿里云 OSS V1 `Authorization` 头（光鸭 / 夸克那类临时凭据直传都用这套规则）。
     *
     * 待签串：`方法\nContent-MD5(空)\nContent-Type\nDate\n[security-token\n]CanonicalizedResource`
     */
    fun ossV1Authorization(
        accessKey: String,
        secretKey: String,
        method: String,
        contentType: String,
        date: String,
        canonicalResource: String,
        securityToken: String = ""
    ): String {
        val tokenPart =
            if (securityToken.isBlank()) "" else "x-oss-security-token:$securityToken\n"
        val toSign = "$method\n\n$contentType\n$date\n$tokenPart$canonicalResource"
        val signature = base64(
            hmac("HmacSHA1", secretKey.toByteArray(Charsets.UTF_8), toSign.toByteArray(Charsets.UTF_8))
        )
        return "OSS $accessKey:$signature"
    }

    // ---------- OSS / S3 的 XML 报文 ----------

    fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** OSS/S3 的错误响应都是 XML `<Error>`（可能带命名空间前缀），不能只看状态码 */
    fun xmlError(body: String): Boolean =
        Regex("<(?:[\\w-]+:)?Error(?:\\s|>)").containsMatchIn(body)

    fun xmlValue(body: String, tag: String): String {
        val match = Regex("<$tag>([\\s\\S]*?)</$tag>").find(body)?.groupValues?.get(1) ?: ""
        return match
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    fun completeXml(etags: List<String>): String = buildString {
        append("<CompleteMultipartUpload>")
        etags.forEachIndexed { index, etag ->
            append("<Part><PartNumber>").append(index + 1).append("</PartNumber><ETag>")
            append(xmlEscape(etag)).append("</ETag></Part>")
        }
        append("</CompleteMultipartUpload>")
    }

    /**
     * 迅雷 gcid：先把每个分块的 SHA1 **原始字节**按顺序拼起来，再整体算一次 SHA1，转大写十六进制。
     */
    fun gcid(blockDigests: List<ByteArray>): String {
        val concat = ByteArrayOutputStream()
        blockDigests.forEach { concat.write(it) }
        val digest = MessageDigest.getInstance("SHA-1").digest(concat.toByteArray())
        return hex(digest).uppercase()
    }
}
