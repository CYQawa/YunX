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

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yunx.app.R
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.theme.ThemeController
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.effectsFast
import com.yunx.app.ui.theme.readableSeedForeground
import com.yunx.app.ui.theme.spatialDefault
import com.yunx.app.ui.theme.spatialFast

/** 预置主题色（Material 风格种子色）：名称走字符串资源，随应用内语言切换 */
private val presetColors = listOf(
    R.string.color_blue to 0xFF415F91L,
    R.string.color_indigo to 0xFF3F51B5L,
    R.string.color_purple to 0xFF6750A4L,
    R.string.color_rose to 0xFFC2185BL,
    R.string.color_red to 0xFFB3261EL,
    R.string.color_orange to 0xFFF4631CL,
    R.string.color_gold to 0xFFF9A825L,
    R.string.color_green to 0xFF38761DL,
    R.string.color_cyan to 0xFF00897BL,
    R.string.color_sky to 0xFF0288D1L,
)

/**
 * 主题与外观设置页（参考 WebIDE ThemeSettingsItem 风格）：
 * - 外观模式：FilterChip 胶囊单选（跟随系统 / 浅色 / 深色）
 * - 文件名显示：FilterChip 胶囊单选（跑马灯滚动 / 多行显示），全局生效见 FileNameText
 * - 主题色：可折叠卡片，动态色彩开关（Android12+）+ LazyRow 色圆选择 + 自定义调色盘
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    BackHandler { onBack() }
    var showColorPicker by remember { mutableStateOf(false) }
    // 主题色卡片默认展开
    var expanded by rememberSaveable { mutableStateOf(true) }

    // Animatable 支持中断，快速连续点击时平滑过渡到新目标。
    // 高度走 spatial，透明度走不回弹的 effects，避免将空间弹簧用于透明度。
    val density = LocalDensity.current
    var contentHeightPx by remember { mutableIntStateOf(0) }
    val expandProgress = remember { Animatable(if (expanded) 1f else 0f) }
    val expandAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = effectsDefault(),
        label = "expandAlpha"
    )
    LaunchedEffect(expanded) {
        expandProgress.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = spatialDefault()
        )
    }

    // Android12- 动态色不可用，视为默认蓝色
    val effectiveColorMode = if (ThemeController.colorMode == 0 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        1
    } else {
        ThemeController.colorMode
    }

    // ---------- 桌面图标动态切换 ----------
    val settingsRepo = remember { SettingsRepository(context) }
    var appIconVariant by remember { mutableStateOf(settingsRepo.appIconVariant) }
    var iconExpanded by rememberSaveable { mutableStateOf(true) }
    val switchAppIcon: (Int) -> Unit = { variant ->
        val pm = context.packageManager
        val main = ComponentName(context, "com.yunx.app.MainActivity")
        val alias = ComponentName(context, "com.yunx.app.MainActivityIcon2")
        if (variant == 1) {
            pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(main, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        } else {
            pm.setComponentEnabledSetting(main, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        settingsRepo.appIconVariant = variant
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // ---------- 外观模式 ----------
            ThemeSectionLabel(stringResource(R.string.section_appearance_mode))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.appearance_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf(
                            R.string.appearance_follow_system,
                            R.string.appearance_light,
                            R.string.appearance_dark
                        )
                        modes.forEachIndexed { index, labelRes ->
                            SmoothFilterChip(
                                selected = ThemeController.darkMode == index,
                                label = stringResource(labelRes),
                                onClick = { ThemeController.setDarkMode(context, index) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---------- 文件名显示 ----------
            ThemeSectionLabel(stringResource(R.string.section_file_name))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.file_name_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 默认「跑马灯滚动」= 改动前的观感；「多行显示」把长文件名折成最多 3 行
                        val nameModes = listOf(
                            R.string.file_name_marquee,
                            R.string.file_name_multiline
                        )
                        nameModes.forEachIndexed { index, labelRes ->
                            SmoothFilterChip(
                                selected = ThemeController.fileNameMultiLine == (index == 1),
                                label = stringResource(labelRes),
                                onClick = { ThemeController.setFileNameMultiLine(context, index == 1) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---------- 主题色（可折叠卡片） ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column {
                    // Header：点击展开/收起
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.theme_color_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            AnimatedVisibility(
                                visible = !expanded,
                                enter = fadeIn(effectsDefault()) + expandVertically(spatialDefault(), expandFrom = Alignment.Top),
                                exit = fadeOut(effectsFast()) + shrinkVertically(spatialFast(), shrinkTowards = Alignment.Top)
                            ) {
                                Text(
                                    text = stringResource(
                                        when {
                                            effectiveColorMode == 0 -> R.string.theme_color_summary_dynamic
                                            effectiveColorMode == 2 -> R.string.theme_color_summary_custom
                                            else -> R.string.theme_color_summary_default
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        val rotation by animateFloatAsState(
                            targetValue = if (expanded) 180f else 0f,
                            label = "arrow",
                            animationSpec = spatialDefault()
                        )
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.rotate(rotation)
                        )
                    }

                            // 高度和透明度分别使用空间与效果动效，折叠过程裁剪内容。
                            val animatedHeightDp = with(density) { (contentHeightPx * expandProgress.value.coerceAtLeast(0f)).toDp() }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (contentHeightPx > 0) Modifier.height(animatedHeightDp) else Modifier)
                                    .clipToBounds()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight(unbounded = true)
                                        .onSizeChanged { contentHeightPx = it.height }
                                        .graphicsLayer { alpha = expandAlpha }
                                ) {
                        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(16.dp))

                            // 动态色彩开关（仅 Android 12+）
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.theme_color_dynamic_switch), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            stringResource(R.string.theme_color_dynamic_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = ThemeController.colorMode == 0,
                                        onCheckedChange = { on ->
                                            ThemeController.setColorMode(context, if (on) 0 else 1)
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // 主题色选择（动态开启时隐藏）
                            AnimatedVisibility(visible = effectiveColorMode != 0) {
                                Column {
                                    Text(
                                        text = stringResource(R.string.theme_color_palette_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    LazyRow(
                                        modifier = Modifier.selectableGroup(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(top = 10.dp, bottom = 8.dp)
                                    ) {
                                        itemsIndexed(presetColors) { _, (nameRes, color) ->
                                            // 默认蓝色模式只高亮蓝色；自定义模式高亮匹配种子色的那个
                                            val isSelected = (effectiveColorMode == 1 && color == 0xFF415F91L) ||
                                                (effectiveColorMode == 2 && ThemeController.seedColor == color)
                                            ColorSelectionItem(
                                                color = color,
                                                name = stringResource(nameRes),
                                                isSelected = isSelected,
                                                onClick = { ThemeController.setSeedColor(context, color) }
                                            )
                                        }
                                        item {
                                            val isCustomSelected = effectiveColorMode == 2 &&
                                                ThemeController.seedColor !in presetColors.map { it.second }
                                            CustomColorButton(
                                                isSelected = isCustomSelected,
                                                customColor = ThemeController.seedColor,
                                                onClick = {
                                                    ThemeController.setColorMode(context, 2)
                                                    showColorPicker = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

            Spacer(modifier = Modifier.height(16.dp))

            // ---------- 桌面图标（可折叠卡片） ----------
            ThemeSectionLabel(stringResource(R.string.section_desktop_icon))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column {
                    // Header：点击展开/收起
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { iconExpanded = !iconExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.StarOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.section_desktop_icon),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            // 与主题色卡片一致的副标题动画：展开时隐藏、收起时显示
                            AnimatedVisibility(
                                visible = !iconExpanded,
                                enter = fadeIn(effectsDefault()) + expandVertically(spatialDefault(), expandFrom = Alignment.Top),
                                exit = fadeOut(effectsFast()) + shrinkVertically(spatialFast(), shrinkTowards = Alignment.Top)
                            ) {
                                Text(
                                    text = stringResource(
                                        if (appIconVariant == 1) R.string.icon_new else R.string.icon_classic
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        val iconRotation by animateFloatAsState(
                            targetValue = if (iconExpanded) 180f else 0f,
                            label = "iconArrow",
                            animationSpec = spatialDefault()
                        )
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.rotate(iconRotation)
                        )
                    }
                    AnimatedVisibility(
                        visible = iconExpanded,
                        enter = fadeIn(effectsDefault()) + expandVertically(spatialDefault(), expandFrom = Alignment.Top),
                        exit = fadeOut(effectsFast()) + shrinkVertically(spatialFast(), shrinkTowards = Alignment.Top)
                    ) {
                        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                AppIconOption(
                                    iconRes = R.drawable.icon,
                                    name = stringResource(R.string.icon_classic),
                                    isSelected = appIconVariant == 0,
                                    onClick = {
                                        appIconVariant = 0
                                        switchAppIcon(0)
                                    }
                                )
                                AppIconOption(
                                    iconRes = R.drawable.icon2,
                                    name = stringResource(R.string.icon_new),
                                    isSelected = appIconVariant == 1,
                                    onClick = {
                                        appIconVariant = 1
                                        switchAppIcon(1)
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.icon_apply_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 自定义调色盘
    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = ThemeController.seedColor,
            onDismiss = { showColorPicker = false },
            onColorSelected = { color ->
                ThemeController.setSeedColor(context, color)
                showColorPicker = false
            }
        )
    }
}

/** FilterChip 胶囊单选（参考 WebIDE SmoothFilterChip：选中 secondaryContainer，未选中描边） */
@Composable
private fun SmoothFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        label = { Text(label, style = MaterialTheme.typography.labelLarge) }
    )
}

/** 预置色圆点（色圆 + 名称，选中显示对勾） */
@Composable
internal fun ColorSelectionItem(
    color: Long,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = name },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(color.toInt()))
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = readableSeedForeground(Color(color.toInt())),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 自定义颜色按钮（色圆 + 名称，圆内显示 Palette 图标） */
@Composable
private fun CustomColorButton(
    isSelected: Boolean,
    customColor: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val customLabel = stringResource(R.string.color_custom)
    Column(
        modifier = modifier
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = customLabel },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) Color(customColor.toInt())
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                )
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Palette,
                contentDescription = null,
                tint = if (isSelected) readableSeedForeground(Color(customColor.toInt())) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = customLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ================= 调色盘 Dialog（参考 WebIDE ColorPickerDialog 精简版） =================

@Composable
internal fun ColorPickerDialog(
    initialColor: Long,
    onDismiss: () -> Unit,
    onColorSelected: (Long) -> Unit
) {
    val initialHsv = colorToHsv(Color(initialColor.toInt()))
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    val currentColor = Color.hsv(hue, saturation, value)
    var hexInput by remember(currentColor) {
        mutableStateOf(colorToHex(currentColor).removePrefix("#"))
    }
    // 滑杆的无障碍描述与标题共用同一份翻译
    val hexLabel = stringResource(R.string.color_hex_desc)
    val hueLabel = stringResource(R.string.color_hue)
    val saturationLabel = stringResource(R.string.color_saturation)
    val valueLabel = stringResource(R.string.color_value)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                // 顶部：HEX 输入 + 预览
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "#",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BasicTextField(
                            value = hexInput,
                            singleLine = true,
                            onValueChange = { input ->
                                val filtered = input.filter { it in '0'..'9' || it.uppercaseChar() in 'A'..'F' }.take(6).uppercase()
                                hexInput = filtered
                                if (filtered.length == 6) {
                                    runCatching {
                                        val color = hexToColor(filtered)
                                        val hsv = colorToHsv(color)
                                        hue = hsv[0]
                                        saturation = hsv[1]
                                        value = hsv[2]
                                    }
                                }
                            },
                            textStyle = MaterialTheme.typography.headlineMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f).semantics { contentDescription = hexLabel }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(currentColor)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 颜色选择核心区：左侧 SatVal 方块 + 右侧色相条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    SatValPanel(
                        hue = hue,
                        saturation = saturation,
                        value = value,
                        onValChange = { s, v ->
                            saturation = s
                            value = v
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    VerticalHueSlider(
                        hue = hue,
                        onHueChange = { hue = it },
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 标准 Slider 提供值域、调节动作、焦点和方向键支持。
                Text(stringResource(R.string.color_hue), style = MaterialTheme.typography.labelLarge)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f,
                    modifier = Modifier.semantics { contentDescription = hueLabel })
                Text(stringResource(R.string.color_saturation), style = MaterialTheme.typography.labelLarge)
                Slider(value = saturation, onValueChange = { saturation = it },
                    modifier = Modifier.semantics { contentDescription = saturationLabel })
                Text(stringResource(R.string.color_value), style = MaterialTheme.typography.labelLarge)
                Slider(value = value, onValueChange = { value = it },
                    modifier = Modifier.semantics { contentDescription = valueLabel })

                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onColorSelected(currentColor.toArgb().toLong() and 0xFFFFFFFFL) }) {
                        Text(stringResource(R.string.action_apply))
                    }
                }
            }
        }
    }
}

/** 饱和度/明度大方块（可拖拽/点击） */
@Composable
private fun SatValPanel(
    hue: Float,
    saturation: Float,
    value: Float,
    onValChange: (Float, Float) -> Unit,
    modifier: Modifier
) {
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onValChange(
                            (offset.x / size.width).coerceIn(0f, 1f),
                            1f - (offset.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onValChange(
                            (change.position.x / size.width).coerceIn(0f, 1f),
                            1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                }
        ) {
            drawRect(color = Color.hsv(hue, 1f, 1f))
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

            // 指示器（方形描边）
            val x = saturation * size.width
            val y = (1f - value) * size.height
            val cursorSize = 14f
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(x - cursorSize / 2, y - cursorSize / 2),
                size = Size(cursorSize, cursorSize),
                style = Stroke(3f)
            )
            drawRect(
                color = Color.White,
                topLeft = Offset(x - cursorSize / 2, y - cursorSize / 2),
                size = Size(cursorSize, cursorSize),
                style = Stroke(1.5f)
            )
        }
    }
}

/** 竖向色相条（可拖拽/点击） */
@Composable
private fun VerticalHueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier
) {
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onHueChange((offset.y / size.height * 360f).coerceIn(0f, 360f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onHueChange((change.position.y / size.height * 360f).coerceIn(0f, 360f))
                    }
                }
        ) {
            val colors = (0..360 step 10).map { Color.hsv(it.toFloat(), 1f, 1f) }
            drawRect(brush = Brush.verticalGradient(colors = colors))

            // 指示器（横条）
            val y = (hue / 360f) * size.height
            val barHeight = 6f
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(0f, y - barHeight / 2),
                size = Size(size.width, barHeight),
                style = Stroke(2f)
            )
            drawRect(
                color = Color.White,
                topLeft = Offset(0f, y - barHeight / 2),
                size = Size(size.width, barHeight),
                style = Stroke(1f)
            )
        }
    }
}

// ================= 辅助 =================

/** 桌面图标选项：图标预览 + 名称，选中高亮边框 + 角标对勾 */
@Composable
private fun AppIconOption(
    iconRes: Int,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(18.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = name,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            if (isSelected) {
                // 右上角选中角标（对勾 + 主题色圆底）
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun ThemeSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

private fun colorToHsv(color: Color): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv
}

private fun colorToHex(color: Color): String {
    val red = (color.red * 255).toInt()
    val green = (color.green * 255).toInt()
    val blue = (color.blue * 255).toInt()
    return "#%02X%02X%02X".format(red, green, blue)
}

private fun hexToColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    return Color(
        red = clean.substring(0, 2).toInt(16) / 255f,
        green = clean.substring(2, 4).toInt(16) / 255f,
        blue = clean.substring(4, 6).toInt(16) / 255f
    )
}
