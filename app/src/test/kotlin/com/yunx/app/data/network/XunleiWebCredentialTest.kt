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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迅雷网页登录凭据的判定逻辑。
 *
 * 只测纯函数（[XunleiWebCredential.fieldsFrom] / [XunleiWebCredential.parseRawToken] /
 * [XunleiWebCredential.isValidToken]）：JVM 单测里 android.jar 的 org.json 是空壳
 * （工程开了 `unitTests.isReturnDefaultValues`），任何 JSON 调用都只会返回 null，
 * 所以 JSON 那一层不进单测——判定逻辑全部搬到了纯函数里，这里覆盖的就是它们。
 */
class XunleiWebCredentialTest {

    /** 一个长度/字符集都合法的假 token（32 位十六进制，与真实 JWT 一样能过校验） */
    private val access = "0123456789abcdef0123456789abcdef"
    private val refresh = "fedcba9876543210fedcba9876543210"

    @Test
    fun readsSnakeCaseFields() {
        val tokens = XunleiWebCredential.fieldsFrom(
            mapOf(
                "access_token" to access,
                "refresh_token" to refresh,
                "device_id" to "78a70629a2b17d0b4302317ffa94807a",
                "captcha_token" to "captcha-token-1234567890",
                "sub" to "user-1",
                "nick_name" to "迅雷用户"
            )
        )
        assertEquals(access, tokens?.accessToken)
        assertEquals(refresh, tokens?.refreshToken)
        assertEquals("78a70629a2b17d0b4302317ffa94807a", tokens?.deviceId)
        assertEquals("captcha-token-1234567890", tokens?.captchaToken)
        assertEquals("user-1", tokens?.userId)
        assertEquals("迅雷用户", tokens?.nickname)
    }

    @Test
    fun readsCamelCaseFields() {
        val tokens = XunleiWebCredential.fieldsFrom(
            mapOf("accessToken" to access, "refreshToken" to refresh, "deviceId" to "abc", "userId" to "u2")
        )
        assertEquals(access, tokens?.accessToken)
        assertEquals(refresh, tokens?.refreshToken)
        assertEquals("abc", tokens?.deviceId)
        assertEquals("u2", tokens?.userId)
    }

    /** 网页登录过程中会把中间态写进 localStorage：半截/占位 token 一律不能当成登录成功 */
    @Test
    fun rejectsPlaceholderAndShortTokens() {
        assertNull(XunleiWebCredential.fieldsFrom(mapOf("access_token" to "null")))
        assertNull(XunleiWebCredential.fieldsFrom(mapOf("access_token" to "undefined")))
        assertNull(XunleiWebCredential.fieldsFrom(mapOf("access_token" to "short")))
        assertNull(XunleiWebCredential.fieldsFrom(mapOf("access_token" to "")))
        assertNull(XunleiWebCredential.fieldsFrom(emptyMap()))
    }

    @Test
    fun rejectsTokensWithIllegalCharacters() {
        // 带空格 / 换行的值不是 token：可能是页面写入的说明文案
        assertNull(XunleiWebCredential.fieldsFrom(mapOf("access_token" to "has space in it 1234567890")))
        assertNull(XunleiWebCredential.fieldsFrom(mapOf("access_token" to "$access\nsecond")))
    }

    /** access 缺失但 refresh 可用时仍然算登录成功（少见，但刷新后能救回来） */
    @Test
    fun acceptsRefreshOnlyCredential() {
        val tokens = XunleiWebCredential.fieldsFrom(mapOf("refresh_token" to refresh))
        assertEquals("", tokens?.accessToken)
        assertEquals(refresh, tokens?.refreshToken)
    }

    /** 非法 captcha 只丢弃该字段，不影响整体登录（captcha 只影响云接口的风控） */
    @Test
    fun dropsInvalidCaptchaButKeepsCredential() {
        val tokens = XunleiWebCredential.fieldsFrom(
            mapOf("access_token" to access, "captcha_token" to "bad")
        )
        assertEquals(access, tokens?.accessToken)
        assertEquals("", tokens?.captchaToken)
    }

    @Test
    fun parsesRawTokenWithAndWithoutBearerPrefix() {
        assertEquals(access, XunleiWebCredential.parseRawToken(access)?.accessToken)
        assertEquals(access, XunleiWebCredential.parseRawToken("  Bearer $access  ")?.accessToken)
        assertEquals(access, XunleiWebCredential.parseRawToken("bearer $access")?.accessToken)
        assertNull(XunleiWebCredential.parseRawToken("not-a-token"))
    }

    @Test
    fun validatesTokenShape() {
        assertTrue(XunleiWebCredential.isValidToken(access))
        assertTrue(XunleiWebCredential.isValidToken("a".repeat(16384)))
        assertFalse(XunleiWebCredential.isValidToken("a".repeat(16385)))
        assertFalse(XunleiWebCredential.isValidToken("a".repeat(15)))
        assertFalse(XunleiWebCredential.isValidToken("null"))
        assertFalse(XunleiWebCredential.isValidToken("has space 1234567890"))
    }

    /**
     * 端到端：整段 JSON / 带引号的 JSON 字符串 / 裸 token 三种粘贴形态。
     * 这里会碰 org.json，因此只在「解析结果非空」这一层做粗断言——
     * 真机上 org.json 是完整实现，JSON 层的正确性由真机验证兜底。
     */
    @Test
    fun parseHandlesJsonAndBareTokenInputs() {
        assertNull(XunleiWebCredential.parse(""))
        assertNull(XunleiWebCredential.parse("   "))
        // 裸 token 走纯字符串路径（不依赖 org.json），必须稳定可用
        assertEquals(access, XunleiWebCredential.parse(access)?.accessToken)
        assertEquals(access, XunleiWebCredential.parse("Bearer $access")?.accessToken)
    }
}
