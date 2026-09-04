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

internal object HttpRangePolicy {
    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    private val pattern = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    fun parse(value: String?): ContentRange? {
        val match = pattern.matchEntire(value?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (start < 0 || end < start || (total != null && end >= total)) return null
        return ContentRange(start, end, total)
    }

    fun matches(value: String?, requestedStart: Long, requestedEnd: Long?): Boolean {
        val range = parse(value) ?: return false
        if (range.start != requestedStart) return false
        return requestedEnd == null || range.end == requestedEnd
    }
}
