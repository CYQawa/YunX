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
