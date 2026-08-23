package com.yunx.app.util

import java.net.URI

/** Redacts capability-bearing URL paths, queries, fragments and user info. */
object LogRedactor {
    private val absoluteUrl = Regex("""https?://[^\s\"'<>]+""", RegexOption.IGNORE_CASE)
    private val secretAssignment = Regex(
        """(?i)\b(cookie|authorization|access[_-]?token|refresh[_-]?token|captcha[_-]?token|bduss|stoken|__puus|__pus|rmkey|signature|sign)\b(\s*[=:]\s*)([^\s,;]+)"""
    )

    fun url(value: Any?): String {
        val raw = value?.toString()?.takeIf { it.isNotBlank() } ?: return "<none>"
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase() ?: return@runCatching "<relative-url>"
            val host = uri.host?.lowercase() ?: return@runCatching "<$scheme-url>"
            val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
            val port = if (uri.port >= 0 && !defaultPort) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrDefault("<invalid-url>")
    }

    fun line(value: String): String {
        val withoutUrls = absoluteUrl.replace(value) { match -> url(match.value) }
        return secretAssignment.replace(withoutUrls) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
        }
    }
}
