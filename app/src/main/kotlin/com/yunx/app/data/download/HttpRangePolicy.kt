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
