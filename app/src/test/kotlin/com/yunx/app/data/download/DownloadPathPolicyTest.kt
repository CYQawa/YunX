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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadPathPolicyTest {

    @Test
    fun rejectsTraversalComponentsAndAbsolutePaths() {
        assertNull(DownloadPathPolicy.sanitize("../secret.txt", "fallback"))
        assertNull(DownloadPathPolicy.sanitize("folder/../secret.txt", "fallback"))
        assertNull(DownloadPathPolicy.sanitize("folder/./secret.txt", "fallback"))
        assertNull(DownloadPathPolicy.sanitize("/absolute/secret.txt", "fallback"))
    }

    @Test
    fun preservesLegitimateNestedDownload() {
        val path = DownloadPathPolicy.sanitize("folder/sub/a?.mp4", "fallback")
        assertEquals("folder/sub", path?.relativeDirectory)
        assertEquals("a_.mp4", path?.fileName)
    }

    @Test
    fun canonicalContainmentRejectsSibling() {
        val base = File("build/policy-test/downloads")
        assertTrue(DownloadPathPolicy.isContained(base, File(base, "folder/file.bin")))
        assertFalse(DownloadPathPolicy.isContained(base, File(base, "../outside.bin")))
    }
}
