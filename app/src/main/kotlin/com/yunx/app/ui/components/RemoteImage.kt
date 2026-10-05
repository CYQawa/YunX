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
import androidx.compose.foundation.layout.BoxWithConstraints
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
 * 尺寸两种给法：
 * - **固定尺寸**（头像、列表缩略图、弹窗封面）：调用方传 `Modifier.size(...)` / `height(...)`，
 *   必须让宽高**都有界**（`fillMaxSize` 遇到无界高度会退化成图片固有尺寸）；
 * - **只给宽度、高度随图片比例**：传 `Modifier.fillMaxWidth()` 且 [autoHeight] = true。
 *
 * ★ [autoHeight] 的高度是**自己算的**，不依赖 `Modifier.aspectRatio`：
 *   实测「`fillMaxWidth` + `heightIn(max)` + `aspectRatio`」这组写法在弹窗里会让图片远超上限、
 *   把标题与正文压成一团（公告弹窗封面的实测症状）。这里用 [BoxWithConstraints] 拿可用宽度，
 *   按位图比例算高度、再用调用方给的 `maxHeight` 夹一次，最后 `Modifier.size(w, h)` 落一个明确尺寸，
 *   图片永远画在框内；加载完成前用 [placeholderRatio] 占位，避免高度从 0 跳变。
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
    val bmp = bitmap

    if (autoHeight) {
        BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
            val ratio = if (bmp != null && bmp.height > 0) {
                bmp.width.toFloat() / bmp.height.toFloat()
            } else {
                placeholderRatio
            }
            // ★ 有界性判断用 constraints.hasBoundedWidth/Height，而不是拿 Dp 去比 `Dp.Infinity`：
            //   无界时 BoxWithConstraints 给的 maxWidth 是一个巨大的有限 Dp（≈Int.MAX_VALUE/density），
            //   比不出 Infinity 来。宽度无界就兜一个默认宽度，别把高度算成天文数字。
            val width = if (constraints.hasBoundedWidth) maxWidth else DefaultAutoWidth
            val natural = if (ratio > 0f) width / ratio else width
            // 调用方可用 heightIn(max = …) 给上限；高度无界时不夹
            val height = if (constraints.hasBoundedHeight) minOf(natural, maxHeight) else natural
            ImageFrame(
                bitmap = bmp,
                failed = failed,
                contentDescription = contentDescription,
                shape = shape,
                contentScale = contentScale,
                fallback = fallback,
                fallbackTint = fallbackTint,
                modifier = Modifier.size(width, height)
            )
        }
        return
    }

    ImageFrame(
        bitmap = bmp,
        failed = failed,
        contentDescription = contentDescription,
        shape = shape,
        contentScale = contentScale,
        fallback = fallback,
        fallbackTint = fallbackTint,
        modifier = modifier
    )
}

/** 图片本体（底色占位 + 载入后的图 / 失败图标）：两种尺寸路径共用，保证外观完全一致 */
@Composable
private fun ImageFrame(
    bitmap: Bitmap?,
    failed: Boolean,
    contentDescription: String?,
    shape: Shape,
    contentScale: ContentScale,
    fallback: ImageVector?,
    fallbackTint: Color,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = shape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
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

/** [RemoteImage] autoHeight 模式下宽度无界时的兜底宽度（正常调用方都会给 fillMaxWidth） */
private val DefaultAutoWidth = 240.dp
