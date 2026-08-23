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
