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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer

/**
 * mikepenz MarkdownText 的自定义 ImageTransformer：README（以及公告正文）里的内嵌图片
 * 走 [RemoteImageLoader]（项目自研 OkHttp 加载器，**不引入 Coil**）。
 *
 * 本对象现在只是「渲染器适配层」——缓存 / 并发限制 / 降采样 / 镜像 / svg 跳过都在
 * [RemoteImageLoader] 里，与普通网络图片（公告封面、头像）共用同一份实现，
 * 这样同一张图在 Markdown 正文与普通 Image 之间不会各存一份 Bitmap。
 *
 * 相对链接已在 ResolveScreen.preprocessReadme 补全为 raw.githubusercontent.com 绝对 URL。
 */
object GitHubMarkdownImageTransformer : ImageTransformer {

    /** 镜像前缀（由调用方在 ResolveScreen 读取设置后设置；null 表示直连）。转发给共享加载器 */
    var mirrorPrefix: String?
        get() = RemoteImageLoader.mirrorPrefix
        set(value) {
            RemoteImageLoader.mirrorPrefix = value
        }

    @Composable
    override fun transform(link: String): ImageData? {
        // ★★ 与 RemoteImage 同一个坑（已踩过）：状态必须用 `remember(link)` 建。
        //   不能用 `produceState(initialValue, link)` —— 它内部的 remember 不带 key，
        //   Markdown 渲染器复用同一个槽位渲染另一张图（换公告 / 换 README）时，
        //   状态里还留着上一张图，配合"已有值就跳过加载"就会一直显示旧图。
        val state = remember(link) { mutableStateOf<Bitmap?>(RemoteImageLoader.cached(link)) }
        LaunchedEffect(link) {
            if (state.value == null) state.value = RemoteImageLoader.load(link)
        }
        // 加载完成前返回 null（库显示占位）
        val bitmap = state.value ?: return null
        return ImageData(painter = BitmapPainter(bitmap.asImageBitmap()))
    }
}
