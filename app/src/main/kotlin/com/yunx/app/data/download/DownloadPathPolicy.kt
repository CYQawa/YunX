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
