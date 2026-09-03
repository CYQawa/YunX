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

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Origin and header policy for every playlist, redirect, map and segment request. */
internal object HlsRequestPolicy {
    private val sensitiveHeaders = setOf(
        "authorization", "cookie", "origin", "proxy-authorization", "referer"
    )

    fun initialUrl(url: String): HttpUrl? =
        url.toHttpUrlOrNull()?.takeIf { it.isHttps }

    fun resolve(base: HttpUrl, candidate: String): HttpUrl? =
        base.resolve(candidate)?.takeIf { it.isHttps }

    fun headersFor(
        target: HttpUrl,
        credentialOrigin: HttpUrl,
        headers: Map<String, String>
    ): Map<String, String> {
        if (sameOrigin(target, credentialOrigin)) return headers
        return headers.filterKeys { it.lowercase() !in sensitiveHeaders }
    }

    fun sameOrigin(left: HttpUrl, right: HttpUrl): Boolean =
        left.scheme == right.scheme && left.host == right.host && left.port == right.port
}
