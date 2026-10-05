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

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * 普通网络图片（公告封面 / 发布者头像 / 正文图集）：加载器复用 [RemoteImageLoader]
 * （OkHttp + 内存 LRU 128 张 + 最多 4 并发 + 总像素降采样），因此与 Markdown 正文里的图片共享缓存。
 *
 * 三种状态都有明确外观，永远不会抛异常、也不会留一个空洞：
 * - 加载中：只显示 [MaterialTheme.colorScheme.surfaceVariant] 底色（列表里不闪图标，避免噪音）；
 * - 成功：铺满容器（[contentScale] 默认 Crop，配合 [shape] 做圆角 / 圆形裁切）；
 * - 失败或地址为空：显示 [fallback] 图标（未指定则保留底色占位）。
 *
 * 尺寸有两种给法：
 * - 固定尺寸（头像、列表缩略图）：调用方传 `Modifier.size(...)`，此时 [contentScale] 用 Crop 最合适；
 * - 只给宽度（正文配图 / 封面，高度未知）：传 `Modifier.fillMaxWidth()` 且 [autoHeight] = true，
 *   内部按**图片自身比例**算高度（加载完成前用 [placeholderRatio] 占位）。
 *   ★ 必须这样兜底：不指定高度的 Image 在 `maxHeight = Infinity` 的列表项里会被量成 0 高或被
 *     按原始像素高排版（长图直接糊一屏），所以高度要么由调用方给、要么由位图比例推。
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: ImageVector? = null,
    fallbackTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    autoHeight: Boolean = false,
    placeholderRatio: Float = 16f / 9f
) {
    val link = url?.trim().orEmpty()
    var failed by remember(link) { mutableStateOf(false) }
    // 已缓存过的图直接当初始值：重组 / 回退到本页时不会先闪一下占位色
    val bitmap by produceState<Bitmap?>(
        initialValue = if (link.isEmpty()) null else RemoteImageLoader.cached(link),
        link
    ) {
        if (link.isEmpty()) {
            value = null
            return@produceState
        }
        if (value == null) {
            val loaded = RemoteImageLoader.load(link)
            failed = loaded == null
            value = loaded
        }
    }

    // autoHeight：按位图比例定高；位图还没到时用 placeholderRatio，避免容器高度从 0 跳变
    val bmp = bitmap
    val ratio = if (autoHeight && bmp != null && bmp.height > 0) {
        bmp.width.toFloat() / bmp.height.toFloat()
    } else {
        placeholderRatio
    }

    Box(
        modifier = modifier
            .then(if (autoHeight) Modifier.aspectRatio(ratio) else Modifier)
            .clip(shape)
            .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = shape),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else if (failed && fallback != null) {
            Icon(
                imageVector = fallback,
                contentDescription = contentDescription,
                tint = fallbackTint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
