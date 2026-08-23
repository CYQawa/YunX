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
