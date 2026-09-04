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
