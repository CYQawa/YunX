package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRangePolicyTest {
    @Test
    fun parsesAndMatchesExactRange() {
        val range = HttpRangePolicy.parse("bytes 100-199/1000")
        assertEquals(100L, range?.start)
        assertEquals(199L, range?.end)
        assertEquals(1000L, range?.total)
        assertTrue(HttpRangePolicy.matches("bytes 100-199/1000", 100, 199))
        assertFalse(HttpRangePolicy.matches("bytes 0-99/1000", 100, 199))
    }

    @Test
    fun rejectsMalformedOrImpossibleRanges() {
        assertNull(HttpRangePolicy.parse("bytes 10-9/100"))
        assertNull(HttpRangePolicy.parse("bytes 0-100/100"))
        assertFalse(HttpRangePolicy.matches(null, 0, 0))
    }
}
