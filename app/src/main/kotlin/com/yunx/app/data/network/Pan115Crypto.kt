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
import java.math.BigInteger
import java.security.SecureRandom

/**
 * 115「电脑端」下载接口（`proapi.115.com/app/chrome/downurl`）的请求/响应加解密。
 *
 * 为什么需要它：网页端接口 `webapi.115.com/files/download` 对大文件直接拒发直链
 * （`50028 文件大小超出限制，请使用115电脑端下载`），分享直链同样被拒（`50029 当前版本过低…`）。
 * 115 电脑端走的是 proapi + RSA/XOR 包装的私有协议，实测 482MB/1.05GB 文件在网页端被拒、
 * 而下面这套协议是 115 官方电脑端自己用的（参考实现：
 * `云析分析资料/网盘参考/115drive-webdav-main/115/api.go`、`115/crypto.go` 与
 * `云析分析资料/网盘参考/115-minus-main/src/platform/115/download-codec.ts`，两份实现互为印证）。
 *
 * 协议结构（以 [encode]/[decode] 为准）：
 * - 请求：`data = base64( RSA( 我们的16字节key || 密文 ) )`，RSA 为 1024 位、指数 65537、128 字节分块；
 * - 响应：`base64( RSA密文 )` → 去掉前 16 字节（服务端 salt）后按 12/4 两段 XOR 解出明文 JSON。
 * 这套「RSA」在客户端侧只是 `modPow(e)`，因为服务端是拿私钥做的「加密」（本质是签名），
 * 所以公钥就能解回来 —— 两处都用同一个公钥模数，见 [MODULUS]。
 */
internal object Pan115Crypto {

    /** XOR 密钥表（144 字节，与参考实现的 `xorKeySeed` / `KEY_TABLE` 逐字节一致，改动前先核对） */
    private val KEY_TABLE = intArrayOf(
        0xf0, 0xe5, 0x69, 0xae, 0xbf, 0xdc, 0xbf, 0x8a,
        0x1a, 0x45, 0xe8, 0xbe, 0x7d, 0xa6, 0x73, 0xb8,
        0xde, 0x8f, 0xe7, 0xc4, 0x45, 0xda, 0x86, 0xc4,
        0x9b, 0x64, 0x8b, 0x14, 0x6a, 0xb4, 0xf1, 0xaa,
        0x38, 0x01, 0x35, 0x9e, 0x26, 0x69, 0x2c, 0x86,
        0x00, 0x6b, 0x4f, 0xa5, 0x36, 0x34, 0x62, 0xa6,
        0x2a, 0x96, 0x68, 0x18, 0xf2, 0x4a, 0xfd, 0xbd,
        0x6b, 0x97, 0x8f, 0x4d, 0x8f, 0x89, 0x13, 0xb7,
        0x6c, 0x8e, 0x93, 0xed, 0x0e, 0x0d, 0x48, 0x3e,
        0xd7, 0x2f, 0x88, 0xd8, 0xfe, 0xfe, 0x7e, 0x86,
        0x50, 0x95, 0x4f, 0xd1, 0xeb, 0x83, 0x26, 0x34,
        0xdb, 0x66, 0x7b, 0x9c, 0x7e, 0x9d, 0x7a, 0x81,
        0x32, 0xea, 0xb6, 0x33, 0xde, 0x3a, 0xa9, 0x59,
        0x34, 0x66, 0x3b, 0xaa, 0xba, 0x81, 0x60, 0x48,
        0xb9, 0xd5, 0x81, 0x9c, 0xf8, 0x6c, 0x84, 0x77,
        0xff, 0x54, 0x78, 0x26, 0x5f, 0xbe, 0xe8, 0x1e,
        0x36, 0x9f, 0x34, 0x80, 0x5c, 0x45, 0x2c, 0x9b,
        0x76, 0xd5, 0x1b, 0x8f, 0xcc, 0xc3, 0xb8, 0xf5
    )

    /** 12 字节长密钥（参考实现的 xorClientKey / LONG_KEY） */
    private val LONG_KEY = intArrayOf(
        0x78, 0x06, 0xad, 0x4c, 0x33, 0x86, 0x5d, 0x18, 0x4c, 0x01, 0x3f, 0x46
    )

    /** 1024 位 RSA 公钥模数（115 电脑端内置公钥，两份参考实现一致） */
    private val MODULUS = BigInteger(
        "8686980c0f5a24c4b9d43020cd2c22703ff3f450756529058b1cf88f09b86021" +
            "36477198a6e2683149659bd122c33592fdb5ad47944ad1ea4d36c6b172aad633" +
            "8c3bb6ac6227502d010993ac967d1aef00f0c8e038de2e4d3bc2ec368af2e9f1" +
            "0a6f1eda4f7262f136420c07c331b871bf139f74f3010e3c4fe57df3afb71683",
        16
    )

    /** 公钥指数 */
    private val EXPONENT = BigInteger.valueOf(0x10001L)

    /** RSA 分块：加密 117 字节（128 - 11），解密 128 字节 */
    private const val RSA_ENCODE_BLOCK = 117
    private const val RSA_BLOCK = 128

    private val random = SecureRandom()

    /** 生成一次请求用的 16 字节 key（响应用同一个 key 解） */
    fun newKey(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    /** 明文 payload → 请求体 `data` 参数值（base64） */
    fun encode(payload: String, key: ByteArray): String {
        require(key.size == 16) { "115 下载协议要求 16 字节 key" }
        val body = payload.toByteArray(Charsets.UTF_8).copyOf() // 由 symmetricEncode 就地改写
        symmetricEncode(body, key)
        val plain = ByteArray(16 + body.size)
        System.arraycopy(key, 0, plain, 0, 16)
        System.arraycopy(body, 0, plain, 16, body.size)
        return Base64.encodeToString(rsaEncrypt(plain), Base64.NO_WRAP)
    }

    /** 响应 `data` 字段（base64）→ 明文 JSON */
    fun decode(data: String, key: ByteArray): String {
        require(key.size == 16) { "115 下载协议要求 16 字节 key" }
        val decrypted = rsaDecrypt(Base64.decode(data, Base64.DEFAULT))
        check(decrypted.size > 16) { "115 下载响应长度异常" }
        val salt = decrypted.copyOfRange(0, 16)
        val body = decrypted.copyOfRange(16, decrypted.size)
        symmetricDecode(body, key, salt)
        return String(body, Charsets.UTF_8)
    }

    /** XOR → 反转 → 用长密钥再 XOR（对上参考实现的 symmetricEncode） */
    private fun symmetricEncode(data: ByteArray, key: ByteArray) {
        xorTransform(data, deriveKey(key, 4))
        data.reverse()
        xorTransform(data, ByteArray(LONG_KEY.size) { LONG_KEY[it].toByte() })
    }

    /** 用服务端 salt 反 XOR → 反转 → 用我们的 key 再 XOR（对上 symmetricDecode） */
    private fun symmetricDecode(data: ByteArray, key: ByteArray, salt: ByteArray) {
        xorTransform(data, deriveKey(salt, 12))
        data.reverse()
        xorTransform(data, deriveKey(key, 4))
    }

    /** 参考实现的 deriveKey：key[i] = ((seed[i] + KEY_TABLE[size*i]) & 0xff) ^ KEY_TABLE[size*(size-1-i)] */
    private fun deriveKey(seed: ByteArray, size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) {
            val left = KEY_TABLE[size * i]
            val right = KEY_TABLE[size * (size - 1 - i)]
            out[i] = (((seed[i % seed.size].toInt() and 0xff) + left) and 0xff xor right).toByte()
        }
        return out
    }

    /** 参考实现的 xorTransform：先处理 len%4 的前缀，其余整体平移 */
    private fun xorTransform(data: ByteArray, key: ByteArray) {
        val offset = data.size % 4
        for (i in 0 until offset) {
            data[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        for (i in offset until data.size) {
            data[i] = (data[i].toInt() xor key[(i - offset) % key.size].toInt()).toByte()
        }
    }

    /** RSA 分块「加密」：PKCS#1 v1.5 填充后按公钥指数做 modPow（参考实现的 rsaEncrypt） */
    private fun rsaEncrypt(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < input.size) {
            val size = minOf(RSA_ENCODE_BLOCK, input.size - offset)
            val block = ByteArray(RSA_BLOCK) { 0xff.toByte() }
            block[0] = 0
            block[1] = 2
            val separator = RSA_BLOCK - size - 1
            block[separator] = 0
            System.arraycopy(input, offset, block, separator + 1, size)
            out.write(toFixedLength(BigInteger(1, block).modPow(EXPONENT, MODULUS)))
            offset += size
        }
        return out.toByteArray()
    }

    /** RSA 分块「解密」：服务端用私钥加密，这里用公钥指数还原（参考实现的 rsaDecrypt） */
    private fun rsaDecrypt(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < input.size) {
            val size = minOf(RSA_BLOCK, input.size - offset)
            val block = input.copyOfRange(offset, offset + size)
            val plain = toFixedLength(BigInteger(1, block).modPow(EXPONENT, MODULUS))
            // PKCS#1 v1.5：0x00 0x02 <填充> 0x00 <数据>，从第 2 字节起找第一个 0x00
            var start = -1
            for (i in 2 until plain.size) {
                if (plain[i] == 0.toByte()) {
                    start = i + 1
                    break
                }
            }
            check(start in 1 until plain.size) { "115 下载响应缺少 RSA 分隔符" }
            out.write(plain, start, plain.size - start)
            offset += size
        }
        return out.toByteArray()
    }

    /** BigInteger → 固定 [RSA_BLOCK] 字节大端表示（左补 0） */
    private fun toFixedLength(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        if (raw.size == RSA_BLOCK) return raw
        val out = ByteArray(RSA_BLOCK)
        if (raw.size > RSA_BLOCK) {
            System.arraycopy(raw, raw.size - RSA_BLOCK, out, 0, RSA_BLOCK)
        } else {
            System.arraycopy(raw, 0, out, RSA_BLOCK - raw.size, raw.size)
        }
        return out
    }
}
