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
