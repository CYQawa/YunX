package com.yunx.app.ui.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XunleiVerificationPolicyTest {
    @Test
    fun acceptsOnlyXunleiHttpsOrigins() {
        assertTrue(XunleiVerificationPolicy.isTrustedPage("https://verify.xunlei.com/a?token=1"))
        assertFalse(XunleiVerificationPolicy.isTrustedPage("http://verify.xunlei.com/a"))
        assertFalse(XunleiVerificationPolicy.isTrustedPage("https://xunlei.com.attacker.example/a"))
        assertFalse(XunleiVerificationPolicy.isTrustedPage("javascript:alert(1)"))
    }

    @Test
    fun acceptsOnlyExactNativeCallback() {
        assertTrue(XunleiVerificationPolicy.isTrustedCallback("xlaccsdk01://xunlei.com/callback?state=harbor"))
        assertFalse(XunleiVerificationPolicy.isTrustedCallback("xlaccsdk01://attacker.example/callback"))
        assertFalse(XunleiVerificationPolicy.isTrustedCallback("xlaccsdk01://xunlei.com/other"))
    }
}
