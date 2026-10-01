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

package com.yunx.app.ui.components

import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.model.DefaultMarkdownTypography

/**
 * 紧凑 Markdown 排版：对齐 GitHub 移动端观感（正文 14sp、标题逐级收紧、行距 1.35 倍）。
 *
 * README（ResolveScreen）与检查更新弹窗的更新说明（UpdateSheet）共用同一份排版，
 * 避免两处字号各自漂移；调用方用 `remember { compactMarkdownTypography() }` 包一层即可。
 */
fun compactMarkdownTypography() = run {
    fun body(size: Int, bold: Boolean = false) = TextStyle(
        fontSize = size.sp,
        lineHeight = (size * 1.35f).toInt().sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
    )
    DefaultMarkdownTypography(
        text = body(14),
        code = body(13),
        inlineCode = body(13),
        h1 = body(20, true),
        h2 = body(18, true),
        h3 = body(16, true),
        h4 = body(15, true),
        h5 = body(14, true),
        h6 = body(14, true),
        quote = body(13),
        paragraph = body(14),
        ordered = body(14),
        bullet = body(14),
        list = body(14),
        link = body(14),
        textLink = TextLinkStyles(),
        table = body(13)
    )
}
