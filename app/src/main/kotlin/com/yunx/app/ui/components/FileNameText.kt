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

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.yunx.app.ui.theme.ThemeController

/** 多行模式下文件名最多显示的行数（再多会把行撑得很高，反而更难扫读） */
private const val FILE_NAME_MAX_LINES = 3

/**
 * 文件名文本：跟随「主题与外观 → 文件名显示」设置。
 * - 跑马灯模式（默认）：单行 + 循环滚动，与改动前的观感一致；
 * - 多行模式：最多 [maxLines] 行折行显示，仍放不下才用省略号截断。
 *
 * 展示文件名的地方统一用它（解析页 / 六个网盘页 / 转存另存弹窗 / 下载列表 / 主页快捷方式），
 * 这样设置一处生效全局；调用处不要再自己写 maxLines / overflow。
 *
 * @param maxLines 多行模式下的最大行数（跑马灯模式恒为 1 行）
 * @param textAlign 文本对齐；跑马灯模式也生效（如主页快捷方式网格要求居中）
 */
@Composable
fun FileNameText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = FILE_NAME_MAX_LINES,
    textAlign: TextAlign? = null
) {
    if (ThemeController.fileNameMultiLine) {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign
        )
    } else {
        // basicMarquee 只在单行（maxLines = 1）且内容溢出时滚动
        Text(
            text = text,
            modifier = modifier.basicMarquee(iterations = Int.MAX_VALUE),
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = 1,
            textAlign = textAlign
        )
    }
}
