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

package com.yunx.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yunx.app.R
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.effectsFast
import com.yunx.app.ui.theme.spatialDefault
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 首次启动引导页（两页式）：
 * - 第 1 页：简洁欢迎 —— 图标 / 名称 / 版本 / 一句话 / 开源仓库入口；
 * - 第 2 页：用户协议 + 免责声明 + 开源协议（长文，页内可滚动）；
 * - 底部导航：圆点指示器 + 「上一步 / 下一步 / 开始使用」，页面切换用 pager 自身滑动 + 按钮区淡入淡出；
 * - 背景：Animated Blob（流体渐变）—— 自绘实现，不引第三方库。
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    // 只在换页时变化的粗粒度读取：按钮文案/指示器需要它
    val page = pagerState.currentPage

    Box(modifier = modifier.fillMaxSize()) {
        BlobBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { index ->
                // 页面内部的过渡：随滑动比例做轻微位移 + 淡出。
                // ★ currentPageOffsetFraction 只在 graphicsLayer 块里读 —— 逐帧只失效图层，不重组页面内容。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val offset = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                            translationX = offset * size.width * 0.22f
                            alpha = (1f - abs(offset)).coerceIn(0f, 1f)
                        }
                ) {
                    when (index) {
                        0 -> WelcomePage(context)
                        else -> TermsPage()
                    }
                }
            }

            OnboardingBottomBar(
                page = page,
                pageCount = pagerState.pageCount,
                onPrev = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                onNext = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                onFinish = onFinish
            )
        }
    }
}

// ==================== 背景：流体渐变 / Blob ====================

/**
 * Animated Blob 背景：底色 + 3 个缓慢漂移的径向渐变光斑（颜色→透明，天然柔边）。
 *
 * ★ 为什么用"径向渐变"而不是 Modifier.blur：硬件模糊要 Android 12+，低版本上直接不生效；
 *   径向渐变的 alpha 衰减本身就有柔边效果，全版本一致，也不用额外绘制层。
 * ★ 动效刻意用超长周期 + 线性缓动（环境动效是"呼吸"，不是"操作反馈"，弹簧/过冲在这里只会显得躁）。
 */
@Composable
private fun BlobBackground(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // 颜色 / 透明度 / 初始相位
    val blobs = listOf(
        Triple(scheme.primary, 0.34f, 0.00f),
        Triple(scheme.tertiary, 0.28f, 0.34f),
        Triple(scheme.secondary, 0.24f, 0.67f)
    )
    val transition = rememberInfiniteTransition(label = "blob")
    val drift = blobs.mapIndexed { i, _ ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 17000 + i * 5300, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "blob$i"
        )
    }
    val base = scheme.surface

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = base)
        blobs.forEachIndexed { i, (color, alpha, phase) ->
            val t = (drift[i].value + phase) % 1f
            val angle = 2f * PI.toFloat() * t
            val center = Offset(
                x = size.width * (0.5f + 0.40f * cos(angle)),
                y = size.height * (0.5f + 0.32f * sin(angle * 0.85f + phase * 6f))
            )
            val radius = size.minDimension * (0.62f + 0.10f * sin(angle * 1.3f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
}

// ==================== 第 1 页：简洁欢迎 ====================

@Composable
private fun WelcomePage(context: Context) {
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    } ?: "1.0"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 应用图标：外层是同色渐变底 + 图标，形成"浮在 blob 上"的观感
        Box(
            modifier = Modifier
                .size(112.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    ),
                    shape = RoundedCornerShape(30.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.icon),
                contentDescription = "云析图标",
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(30.dp)),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.height(26.dp))
        Text(
            text = "云析",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "YunX · v$versionName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "网盘分享链接解析与高速下载",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactChip("6 大网盘")
            FactChip("分片下载")
            FactChip("完全免费")
        }

        Spacer(modifier = Modifier.height(30.dp))
        GitHubCard(context)
    }
}

/** 小标签（胶囊）：底色比背景亮一档，浮在 blob 上仍然清晰 */
@Composable
private fun FactChip(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

/** 开源仓库入口卡片 */
@Composable
private fun GitHubCard(context: Context) {
    Card(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/CYQawa/YunX"))
            context.startActivity(intent)
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Code,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "开源仓库",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "github.com/CYQawa/YunX",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ==================== 第 2 页：协议与声明 ====================

@Composable
private fun TermsPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp)
    ) {
        Text(
            text = "使用前请阅读",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "云析是开源工具，以下是使用条款与免责说明",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        TermsCard(
            icon = Icons.Outlined.Description,
            title = "用户协议",
            paragraphs = listOf(
                "本应用是开源的网盘分享链接解析与下载工具，仅供个人学习、技术交流使用，请勿用于任何商业用途。",
                "请遵守你所使用网盘的服务条款及当地法律法规；不得利用本应用下载、传播侵权或违法内容。",
                "网盘账号凭证（Cookie / Token）仅保存在本机，不会上传到任何服务器。",
                "应用按「现状」提供，不保证解析成功率与下载速度，也不对你的使用结果作出任何承诺。"
            )
        )
        Spacer(modifier = Modifier.height(12.dp))

        TermsCard(
            icon = Icons.Outlined.Shield,
            title = "免责声明",
            paragraphs = listOf(
                "下载内容的版权归原作者所有，请于下载后 24 小时内删除。",
                "使用本应用产生的任何后果由使用者自行承担，开发者不承担由此引起的任何责任。"
            )
        )
        Spacer(modifier = Modifier.height(12.dp))

        TermsCard(
            icon = Icons.Outlined.Code,
            title = "开源协议",
            paragraphs = listOf(
                "本项目基于 GNU AGPL-3.0 协议开源，源码公开于 github.com/CYQawa/YunX。"
            )
        )

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "点击下方「开始使用」即表示你已阅读并同意以上条款",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 协议卡片：图标 + 标题 + 若干段落 */
@Composable
private fun TermsCard(
    icon: ImageVector,
    title: String,
    paragraphs: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            paragraphs.forEachIndexed { i, p ->
                if (i > 0) Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = p,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

// ==================== 底部导航 ====================

/**
 * 底部导航：圆点指示器 + 「上一步 / 下一步 / 开始使用」。
 * 指示器的宽度与颜色、按钮文案的切换都带过渡（规格取项目统一的 M3E 动效）。
 */
@Composable
private fun OnboardingBottomBar(
    page: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.Center) {
            repeat(pageCount) { i ->
                val selected = i == page
                val dotWidth by animateDpAsState(
                    targetValue = if (selected) 22.dp else 8.dp,
                    animationSpec = spatialDefault(),
                    label = "dotWidth"
                )
                val dotColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    animationSpec = effectsDefault(),
                    label = "dotColor"
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = dotWidth, height = 8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 第 2 页才出现「上一步」：淡入淡出，不挤动主按钮（主按钮占满剩余宽度）
            AnimatedVisibility(
                visible = page > 0,
                enter = fadeIn(effectsDefault()),
                exit = fadeOut(effectsFast())
            ) {
                TextButton(onClick = onPrev) {
                    Text("上一步", style = MaterialTheme.typography.titleSmall)
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = { fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast()) },
                    label = "onboardingAction"
                ) { p ->
                    val last = p == pageCount - 1
                    Button(
                        onClick = { if (last) onFinish() else onNext() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(
                            text = if (last) "开始使用" else "下一步",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}
