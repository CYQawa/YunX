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

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsRequestPolicyTest {
    private val origin = "https://drive.uc.cn/media/master.m3u8".toHttpUrl()
    private val headers = mapOf(
        "Cookie" to "session=secret",
        "Authorization" to "Bearer secret",
        "Referer" to "https://drive.uc.cn/",
        "User-Agent" to "YunX"
    )

    @Test
    fun retainsCredentialsOnlyForSameOrigin() {
        val same = HlsRequestPolicy.headersFor(
            "https://drive.uc.cn/media/1.ts".toHttpUrl(), origin, headers
        )
        assertEquals(headers, same)

        val cross = HlsRequestPolicy.headersFor(
            "https://attacker.example/1.ts".toHttpUrl(), origin, headers
        )
        assertFalse(cross.containsKey("Cookie"))
        assertFalse(cross.containsKey("Authorization"))
        assertFalse(cross.containsKey("Referer"))
        assertEquals("YunX", cross["User-Agent"])
    }

    @Test
    fun resolvesOnlyHttpsChildren() {
        assertEquals(
            "https://drive.uc.cn/media/1.ts",
            HlsRequestPolicy.resolve(origin, "1.ts")?.toString()
        )
        assertNull(HlsRequestPolicy.resolve(origin, "http://attacker.example/1.ts"))
        assertNull(HlsRequestPolicy.initialUrl("file:///sdcard/a.m3u8"))
        assertTrue(HlsRequestPolicy.sameOrigin(origin, "https://drive.uc.cn/other".toHttpUrl()))
    }
}
