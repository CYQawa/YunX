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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * - 加载中：只显示 [placeholderColor] 底色（默认 surfaceVariant，列表里不闪图标，避免噪音）；
 * - 成功：铺满容器（[contentScale] 默认 Crop，配合 [shape] 做圆角 / 圆形裁切）；
 * - 失败或地址为空：显示 [fallback] 图标（未指定则保留底色占位）。
 *
 * ★ [placeholderColor] 给「全屏看图」这类深色底用：默认的浅色占位在纯黑背景上会是一块灰板，
 *   传 `Color.Transparent` 就只剩图片本身（`contentScale = Fit` 时留白处直接透出黑底）。
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
    placeholderRatio: Float = 16f / 9f,
    placeholderColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val link = url?.trim().orEmpty()
    // ★★ 状态必须用 `remember(link)` 建，**不能用 `produceState(initialValue, link)`**：
    //   produceState 内部是 `remember { mutableStateOf(initialValue) }` —— 它的 remember **不带 key**，
    //   换 URL 后状态里还留着**上一张图**，再配合"已有值就跳过加载"的判断，新图永远不会加载 ⇒
    //   一直显示上一条公告的封面/头像（实测 bug：打开公告 A 再打开公告 B，B 的图是 A 的；
    //   文字是直接传参所以正常，只有图片残留，很容易误判成数据串了）。
    //   `remember(link)` 在 URL 变化时**同步**重建状态：旧图立刻消失、新图先用缓存（没有则占位色），
    //   连一帧旧图都不会闪。
    val bitmapState = remember(link) {
        mutableStateOf<Bitmap?>(if (link.isEmpty()) null else RemoteImageLoader.cached(link))
    }
    var failed by remember(link) { mutableStateOf(false) }
    LaunchedEffect(link) {
        // 空地址、或命中缓存：没什么要加载的
        if (link.isEmpty() || bitmapState.value != null) return@LaunchedEffect
        val loaded = RemoteImageLoader.load(link)
        failed = loaded == null
        bitmapState.value = loaded
    }
    // ★ 这里必须取成局部 val，**不能写成 `by` 委托**（`val bmp by bitmapState`）：
    //   委托属性的 getter 每次读都可能返回不同的值，编译器不允许对它做非空智能转换，
    //   CI 报过 `Smart cast to 'android.graphics.Bitmap' is impossible, because 'bmp' is a delegated property`。
    //   局部 val 只读一次，下面 `bmp != null && bmp.height > 0` 才能智能转换。
    val bmp = bitmapState.value

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
                placeholderColor = placeholderColor,
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
        placeholderColor = placeholderColor,
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
    placeholderColor: Color,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(color = placeholderColor, shape = shape),
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
