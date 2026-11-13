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

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * 百度下载协议中用到的纯计算。
 *
 * 单独抽出来是为了能在 JVM 单测里验证：uinfo 解码、rand/psign、Seed 包
 * 以及 P2P 分片 AES-CTR 都不依赖 Android API。
 */
object BaiduDownloadCrypto {

    /** 本版本 kernel.dll 的固定 psign 后缀。 */
    const val PSIGN_SK = "P7Fs7XWt0G1G0ZqmSVAXfYqt4K87xWnz"

    /** Seed 请求命令；响应命令是 [SEED_CMD_RESPONSE]。 */
    const val SEED_CMD_REQUEST = 0x00100008
    const val SEED_CMD_RESPONSE = 0x00100009

    private const val BASE64_STANDARD =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val BASE64_URL_SAFE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun md5(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(bytes)

    fun sha1(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(bytes)

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun md5Hex(text: String): String = hex(md5(text.toByteArray(Charsets.UTF_8)))

    fun sha1Hex(text: String): String = hex(sha1(text.toByteArray(Charsets.UTF_8)))

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

    fun decodeHex(text: String): ByteArray? {
        val clean = text.trim()
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val high = clean[i * 2].digitToIntOrNull(16) ?: return null
            val low = clean[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    /** 标准 Base64 解码（uinfo）；同时兼容 URL-safe 与无填充输入。 */
    fun decodeBase64(text: String): ByteArray? {
        val clean = text
            .filterNot { it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
        if (clean.isEmpty()) return null
        val out = ByteArrayOutputStream((clean.length * 3) / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val value = BASE64_STANDARD.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** 无填充的 URL-safe Base64（P2P spec 使用）。 */
    fun encodeBase64Url(bytes: ByteArray): String {
        val builder = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val has1 = index + 1 < bytes.size
            val has2 = index + 2 < bytes.size
            val b1 = if (has1) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (has2) bytes[index + 2].toInt() and 0xFF else 0
            builder.append(BASE64_URL_SAFE[b0 ushr 2])
            builder.append(BASE64_URL_SAFE[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (has1) builder.append(BASE64_URL_SAFE[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            if (has2) builder.append(BASE64_URL_SAFE[b2 and 0x3F])
            index += 3
        }
        return builder.toString()
    }

    fun decodeBase64Url(text: String): ByteArray? = decodeBase64(text)

    fun rc4(data: ByteArray, key: ByteArray): ByteArray {
        if (key.isEmpty()) return ByteArray(0)
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
        }
        val out = ByteArray(data.size)
        var i = 0
        j = 0
        for (index in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + state[i]) and 0xFF
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
            out[index] = (data[index].toInt() xor state[(state[i] + state[j]) and 0xFF]).toByte()
        }
        return out
    }

    /** uinfo -> Base64 -> RC4(uid) -> sk 原始字节。 */
    fun decodeUinfo(uinfo: String, uid: String): ByteArray? {
        val cipher = decodeBase64(uinfo) ?: return null
        return rc4(cipher, uid.toByteArray(Charsets.US_ASCII))
    }

    fun makeRand(
        bduss: String,
        uid: String,
        sk: ByteArray,
        time: Long,
        devuid: String
    ): String {
        val bdussSha = sha1Hex(bduss).toByteArray(Charsets.US_ASCII)
        val material = ByteArrayOutputStream()
        material.write(bdussSha)
        material.write(uid.toByteArray(Charsets.US_ASCII))
        material.write(sk)
        material.write(time.toString().toByteArray(Charsets.US_ASCII))
        material.write(devuid.toByteArray(Charsets.UTF_8))
        return hex(sha1(material.toByteArray()))
    }

    /** 生成 Seed 包头与 protobuf common_header 共用的 64 位 logid。 */
    fun makeLogId(timestamp: Long, sequence: Long): Long {
        val suffix = sequence and 0xFFFFF
        return (timestamp and 0x7FFFFFFFFFFL) * 1_000L + suffix
    }

    /**
     * locatedownload psign：按 key 字典序只拼 value，再两级 MD5。
     * 传入的 value 必须是最终 URL 中已经百分号编码的形态。
     */
    fun makePsign(params: Map<String, String>, psignSk: String = PSIGN_SK): String {
        val data = params.entries
            .asSequence()
            .filter { it.key != "psign" }
            .sortedBy { it.key }
            .joinToString("") { it.value }
        val inner = md5Hex(data)
        return md5Hex(inner + psignSk)
    }

    /**
     * Seed 包：32 字节头 + AES-128-ECB(PKCS7) 的 protobuf。
     *
     * 头字段与 kernel 的 `sub_181098A80` 一致：
     * CRC32、时间戳、命令、模式 1、保留位、logid、密文长度。
     */
    fun buildSeedPacket(
        payload: ByteArray,
        cmd: Int,
        timestamp: Long = System.currentTimeMillis() / 1000,
        sequence: Long = (System.nanoTime() ushr 1) and 0x7FFFFFFF,
        logId: Long = makeLogId(timestamp, sequence)
    ): ByteArray {
        val header = ByteArray(32)
        putIntBE(header, 4, timestamp.toInt())
        putIntBE(header, 8, cmd)
        header[12] = 1 // AES-128-ECB
        // 抓包固定为 0A 1E 03；保留原始控制位，避免新 SDK 用它做能力协商。
        header[13] = 0x0A
        header[14] = 0x1E
        header[15] = 0x03
        header[16] = 0x00
        header[17] = 0x14
        header[18] = 0xF2.toByte()
        header[19] = 0x00
        putLongLE(header, 20, logId)

        val key = md5(header.copyOfRange(4, 12))
        val cipherText = aesEcbEncrypt(payload, key)
        putIntBE(header, 28, cipherText.size)
        val body = ByteArray(32 + cipherText.size)
        System.arraycopy(header, 0, body, 0, 32)
        System.arraycopy(cipherText, 0, body, 32, cipherText.size)
        putIntBE(body, 0, crc32(body, 4, body.size - 4).toInt())
        return body
    }

    /** Seed 包校验 + 解密；返回 protobuf payload。 */
    fun unpackSeedPacket(body: ByteArray): ByteArray? {
        if (body.size < 32) return null
        if (readIntBE(body, 0) != crc32(body, 4, body.size - 4).toInt()) return null
        val payloadLen = readIntBE(body, 28)
        if (payloadLen < 0 || 32 + payloadLen > body.size) return null
        val payload = body.copyOfRange(32, 32 + payloadLen)
        return when (body[12].toInt() and 0xFF) {
            0 -> payload
            1 -> aesEcbDecrypt(payload, md5(body.copyOfRange(4, 12)))
            2 -> {
                val key = md5(body.copyOfRange(4, 12))
                ByteArray(payload.size) { index ->
                    (payload[index].toInt() xor key[index % key.size].toInt()).toByte()
                }
            }
            else -> null
        }
    }

    /**
     * P2P 数据面 AES-256-CTR：每个 8192 字节 slice 独立派生 key/iv。
     *
     * kernel 的 `onecloud_aes_ctr_decrypt` 用 SHA-256(hexKey + decimal(sliceIndex))
     * 作为密钥、SHA-256(hexIv + decimal(sliceIndex)) 的前 16 字节作为计数器。
     *
     * ★ sliceIndex 是**分片序号**（absoluteOffset / sliceSize），不是字节偏移。
     *   官方 `p2p_kernel::preprocess` 的格式串是 `%s%d`，用错会把第 2 片起全部解坏。
     */
    fun decryptP2pSlice(
        encrypted: ByteArray,
        keyHex: String,
        ivHex: String,
        sliceIndex: Long
    ): ByteArray {
        val key = sha256((keyHex + sliceIndex.toString()).toByteArray(Charsets.US_ASCII))
        val iv = sha256((ivHex + sliceIndex.toString()).toByteArray(Charsets.US_ASCII))
            .copyOf(16)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(encrypted)
    }

    /**
     * 对从 [absoluteStart] 开始的密文按 8192 字节 slice 解密。
     *
     * ★ 起点必须落在 slice 边界：每一片的 AES-CTR 计数器都从该片起点重新开始，
     *   从片中间直接起算会把密钥流错位，解密结果静默损坏。
     */
    fun decryptP2pRange(
        encrypted: ByteArray,
        keyHex: String,
        ivHex: String,
        absoluteStart: Long,
        sliceSize: Int = 8192
    ): ByteArray {
        if (encrypted.isEmpty()) return encrypted
        require(sliceSize > 0) { "sliceSize 必须大于 0" }
        require(absoluteStart % sliceSize == 0L) {
            "P2P 密文起点必须按 $sliceSize 字节对齐（实际 $absoluteStart）"
        }
        val out = ByteArray(encrypted.size)
        var source = 0
        var absolute = absoluteStart
        while (source < encrypted.size) {
            val sliceStart = absolute - (absolute % sliceSize)
            val inSlice = (absolute - sliceStart).toInt()
            val take = min(sliceSize - inSlice, encrypted.size - source)
            val block = encrypted.copyOfRange(source, source + take)
            val plain = decryptP2pSlice(block, keyHex, ivHex, sliceStart / sliceSize)
            System.arraycopy(plain, 0, out, source, take)
            source += take
            absolute += take
        }
        return out
    }

    private fun aesEcbEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plain)
    }

    private fun aesEcbDecrypt(cipherText: ByteArray, key: ByteArray): ByteArray? {
        if (cipherText.isEmpty() || cipherText.size % 16 != 0) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            val plain = cipher.doFinal(cipherText)
            val pad = plain.last().toInt() and 0xFF
            if (pad !in 1..16 || pad > plain.size) return null
            for (i in plain.size - pad until plain.size) {
                if ((plain[i].toInt() and 0xFF) != pad) return null
            }
            plain.copyOfRange(0, plain.size - pad)
        }.getOrNull()
    }

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }

    private fun putIntBE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readIntBE(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    private fun putLongLE(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            target[offset + i] = (value ushr (i * 8)).toByte()
        }
    }
}
