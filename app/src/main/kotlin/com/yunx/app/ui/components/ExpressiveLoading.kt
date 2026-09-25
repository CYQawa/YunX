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

import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Material 3 Expressive 的加载 / 进度指示器封装。
 *
 * 为什么包一层：`LoadingIndicator` 与波浪进度条目前只在 material3 1.5.0-alpha 中
 * （见 `gradle/libs.versions.toml` 里 material3Expressive 的说明），API 可能随 alpha 变动；
 * 调用点统一走这里，将来升级 alpha 或退回稳定版只需要改本文件，不用动几十个调用点。
 *
 * 选型约定（避免同一页面里两种加载指示器风格混杂）：
 * - 独立加载态、或尺寸 ≥ 24dp 的行内加载 → [YunXLoading]（MaterialShapes 形变动画）
 * - 尺寸 < 24dp 的行内小 spinner（通常与 18dp 图标并排）→ 继续用 `CircularProgressIndicator`：
 *   形变动画在该尺寸下读不出来，细描边转圈的观感反而更协调
 * - 确定进度（下载 / 容量占比等）→ [YunXWavyProgress]；不确定进度 → [YunXWavyLoading]
 */

/** Expressive 加载指示器（MaterialShapes 形变）。尺寸由 [modifier] 决定，默认 48dp。 */
@Composable
fun YunXLoading(modifier: Modifier = Modifier) {
    LoadingIndicator(modifier = modifier)
}

/**
 * 波浪进度条（确定进度 0f..1f）：波峰会随进度推进。
 * 颜色必须显式传入——各调用点原本就各自指定了主色/错误色与轨道色。
 */
@Composable
fun YunXWavyProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color,
    trackColor: Color
) {
    LinearWavyProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor
    )
}

/** 波浪进度条（不确定进度）：用于"正在加载但无进度"的场景，如 WebView 页面加载。 */
@Composable
fun YunXWavyLoading(modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(modifier = modifier)
}
