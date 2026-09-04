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
