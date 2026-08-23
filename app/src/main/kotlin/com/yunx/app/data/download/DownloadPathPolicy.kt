package com.yunx.app.data.download

import java.io.File

/** Pure path validation shared by Android storage backends and unit tests. */
internal object DownloadPathPolicy {

    data class SafePath(val fileName: String, val relativeDirectory: String)

    fun sanitize(relativePath: String, fallbackName: String): SafePath? {
        val normalized = relativePath.replace('\\', '/')
        if (normalized.startsWith('/')) return null

        val rawParts = normalized.split('/')
        if (rawParts.any { it == "." || it == ".." }) return null

        val parts = rawParts.filter { it.isNotBlank() }
        val rawName = parts.lastOrNull().orEmpty()
        val safeName = sanitizeName(rawName).ifBlank { fallbackName }
        val safeDirectory = parts.dropLast(1)
            .joinToString("/") { sanitizeName(it) }
        return SafePath(safeName, safeDirectory)
    }

    fun isContained(base: File, candidate: File): Boolean {
        val basePath = base.canonicalFile.path.trimEnd(File.separatorChar)
        val candidatePath = candidate.canonicalFile.path
        return candidatePath != basePath && candidatePath.startsWith(basePath + File.separator)
    }

    fun sanitizeName(name: String): String {
        var cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .trim()
        if (cleaned.length > 120) {
            val ext = cleaned.substringAfterLast('.', "").take(10)
            val base = cleaned.substringBeforeLast('.').take(100)
            cleaned = if (ext.isNotBlank() && ext != cleaned) "$base.$ext" else cleaned.take(120)
        }
        return cleaned
    }
}
