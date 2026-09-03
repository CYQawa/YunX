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

package com.yunx.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {
    @Test
    fun removesPathQueryFragmentAndUserInfo() {
        val redacted = LogRedactor.url("https://user:pass@cdn.example:8443/private/a?sign=secret#token")
        assertEquals("https://cdn.example:8443", redacted)
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("private"))
        assertFalse(redacted.contains("user"))
    }

    @Test
    fun handlesInvalidAndRelativeValues() {
        assertEquals("<relative-url>", LogRedactor.url("segment.ts?token=secret"))
        assertEquals("<none>", LogRedactor.url(null))
    }

    @Test
    fun redactsUrlsAndKnownSecretAssignmentsFromExportedLines() {
        val line = LogRedactor.line(
            "download https://cdn.example/private.bin?sign=url-secret Cookie=session-secret access_token=jwt-secret"
        )
        assertFalse(line.contains("url-secret"))
        assertFalse(line.contains("session-secret"))
        assertFalse(line.contains("jwt-secret"))
        assertEquals("download https://cdn.example Cookie=<redacted> access_token=<redacted>", line)
    }
}
