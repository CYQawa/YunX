package com.yunx.app.ui.login

import java.net.URI

/** Origin policy for the Xunlei verification page and its native callback. */
internal object XunleiVerificationPolicy {
    fun isTrustedPage(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme.equals("https", ignoreCase = true) &&
            (host == "xunlei.com" || host.endsWith(".xunlei.com"))
    }.getOrDefault(false)

    fun isTrustedCallback(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme.equals("xlaccsdk01", ignoreCase = true) &&
            uri.host.equals("xunlei.com", ignoreCase = true) &&
            uri.path == "/callback"
    }.getOrDefault(false)
}
