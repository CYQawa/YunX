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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * 上传签名/编码单测。
 *
 * 这些函数算错时服务端只会回一句 403 或 `SignatureDoesNotMatch`，在真机上很难定位；
 * 这里用固定向量把「待签串怎么拼」「字节怎么编码」钉死（向量由 openssl/python hmac 独立算出）。
 */
class UploadSigningTest {

    @Test
    fun `十六进制补零且小写`() {
        assertEquals("", UploadSigning.hex(ByteArray(0)))
        assertEquals(
            "00ff10",
            UploadSigning.hex(byteArrayOf(0x00, 0xFF.toByte(), 0x10))
        )
    }

    @Test
    fun `标准 base64 的三种补齐`() {
        assertEquals("", UploadSigning.base64(ByteArray(0)))
        assertEquals("Zg==", UploadSigning.base64("f".toByteArray()))
        assertEquals("Zm8=", UploadSigning.base64("fo".toByteArray()))
        assertEquals("Zm9v", UploadSigning.base64("foo".toByteArray()))
        assertEquals("Zm9vYmFy", UploadSigning.base64("foobar".toByteArray()))
    }

    @Test
    fun `URL-safe base64 换字母表`() {
        assertEquals(
            "-_8=",
            UploadSigning.base64(byteArrayOf(0xFB.toByte(), 0xFF.toByte()), urlSafe = true)
        )
        assertEquals(
            "++8=",
            UploadSigning.base64(byteArrayOf(0xFB.toByte(), 0xEF.toByte()))
        )
    }

    @Test
    fun `OSS V1 签名串与抓包格式一致`() {
        assertEquals(
            "OSS AKID:ds4+7X/EpW+x5USaSpTVsFn6uxo=",
            UploadSigning.ossV1Authorization(
                accessKey = "AKID",
                secretKey = "secret",
                method = "PUT",
                contentType = "application/octet-stream",
                date = "Wed, 21 Oct 2015 07:28:00 GMT",
                canonicalResource = "/bucket/object?partNumber=1&uploadId=abc"
            )
        )
    }

    @Test
    fun `OSS V1 把 security-token 计入待签串`() {
        assertEquals(
            "OSS AKID:mZEK0TqBfU5qh1a4s5UT4BQ94is=",
            UploadSigning.ossV1Authorization(
                accessKey = "AKID",
                secretKey = "secret",
                method = "PUT",
                contentType = "application/octet-stream",
                date = "Wed, 21 Oct 2015 07:28:00 GMT",
                canonicalResource = "/bucket/object?partNumber=1&uploadId=abc",
                securityToken = "STS.token"
            )
        )
    }

    @Test
    fun `迅雷 gcid 先拼原始摘要再整体哈希`() {
        val blocks = listOf(sha1("abc"), sha1("def"))
        assertEquals("18A55436CE198B1412DF9FA91896524CE4173053", UploadSigning.gcid(blocks))
    }

    @Test
    fun `合并 XML 按顺序编号并转义 ETag`() {
        assertEquals(
            "<CompleteMultipartUpload>" +
                "<Part><PartNumber>1</PartNumber><ETag>&quot;a&amp;b&quot;</ETag></Part>" +
                "<Part><PartNumber>2</PartNumber><ETag>&quot;c&quot;</ETag></Part>" +
                "</CompleteMultipartUpload>",
            UploadSigning.completeXml(listOf("\"a&b\"", "\"c\""))
        )
    }

    @Test
    fun `识别带命名空间的 S3 错误响应`() {
        assertTrue(UploadSigning.xmlError("<Error><Code>NoSuchUpload</Code></Error>"))
        assertTrue(UploadSigning.xmlError("<s3:Error><Code>x</Code></s3:Error>"))
        assertFalse(UploadSigning.xmlError("<CompleteMultipartUpload/>"))
        assertFalse(UploadSigning.xmlError("{\"error\":1}"))
    }

    @Test
    fun `取 XML 标签值并还原实体`() {
        assertEquals(
            "abc-123",
            UploadSigning.xmlValue("<r><UploadId>abc-123</UploadId></r>", "UploadId")
        )
        assertEquals("<x>", UploadSigning.xmlValue("<r><E>&lt;x&gt;</E></r>", "E"))
        assertEquals("", UploadSigning.xmlValue("<r/>", "UploadId"))
    }

    private fun sha1(value: String): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
}
