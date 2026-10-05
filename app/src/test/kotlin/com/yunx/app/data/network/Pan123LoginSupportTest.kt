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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 123 云盘账号密码登录的错误映射与令牌校验（纯逻辑，不联网）。
 *
 * 重点锁两件事：
 * 1. **绝不回显服务端原文**——登录接口的响应可能把账号甚至密码原样带回来，任何分支都不能把它抛给 UI；
 * 2. 频率/风控/账号冻结三类要给出不同的可执行指引（等着重试 vs 换网页登录 vs 去官方客户端查账号）。
 */
class Pan123LoginSupportTest {

    private val token = "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjE3MDAwMDAwMDB9.signature-part"

    @Test
    fun mapsHttpLevelFailures() {
        assertEquals(
            "123 登录接口发生变化，请稍后重试",
            Pan123LoginSupport.describeFailure(httpStatus = 302, code = 0, serverMessage = "")
        )
        assertEquals(
            Pan123LoginSupport.MESSAGE_TOO_FREQUENT,
            Pan123LoginSupport.describeFailure(httpStatus = 429, code = 0, serverMessage = "")
        )
        assertEquals(
            "123 登录服务暂时不可用，请稍后重试",
            Pan123LoginSupport.describeFailure(httpStatus = 503, code = 0, serverMessage = "")
        )
    }

    @Test
    fun mapsBusinessRateLimitMessages() {
        assertEquals(
            Pan123LoginSupport.MESSAGE_TOO_FREQUENT,
            Pan123LoginSupport.describeFailure(httpStatus = 200, code = 429, serverMessage = "")
        )
        listOf("请求频繁", "频率过高", "次数超限", "请稍后再试").forEach { message ->
            assertEquals(
                message,
                Pan123LoginSupport.MESSAGE_TOO_FREQUENT,
                Pan123LoginSupport.describeFailure(httpStatus = 200, code = -1, serverMessage = message)
            )
        }
    }

    @Test
    fun mapsSecurityCheckMessagesToWebLoginGuidance() {
        listOf("请完成安全验证", "需要滑块验证", "验证码错误", "captcha required", "Verify failed").forEach { message ->
            assertEquals(
                message,
                Pan123LoginSupport.MESSAGE_VERIFY_REQUIRED,
                Pan123LoginSupport.describeFailure(httpStatus = 200, code = 401, serverMessage = message)
            )
        }
    }

    @Test
    fun mapsFrozenAccountMessages() {
        listOf("账号已冻结", "封禁中", "已封停", "账号注销", "禁用").forEach { message ->
            val text = Pan123LoginSupport.describeFailure(httpStatus = 200, code = 401, serverMessage = message)
            assertTrue(message, text.contains("官方客户端"))
        }
    }

    @Test
    fun fallsBackWithoutEchoingServerMessage() {
        val message = "账号 fixture-phone 密码 Password123 不正确"
        val text = Pan123LoginSupport.describeFailure(httpStatus = 200, code = 401, serverMessage = message)
        assertEquals(Pan123LoginSupport.MESSAGE_FALLBACK, text)
        // 关键：服务端原文（含账号密码）一个字都不能出现在给用户的提示里
        assertFalse(text.contains("fixture-phone"))
        assertFalse(text.contains("Password123"))
    }

    @Test
    fun validatesTokenShape() {
        assertTrue(Pan123LoginSupport.isValidToken(token))
        assertTrue(Pan123LoginSupport.isValidToken("a".repeat(16384)))
        assertFalse(Pan123LoginSupport.isValidToken(""))
        assertFalse(Pan123LoginSupport.isValidToken("a".repeat(16385)))
        assertFalse(Pan123LoginSupport.isValidToken("line\nbreak"))
        assertFalse(Pan123LoginSupport.isValidToken("nul\u0000byte"))
    }
}
