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

package com.yunx.app.ui.items

import androidx.annotation.DrawableRes
import com.yunx.app.R
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.video.VideoPlatform

/**
 * 平台 Logo 表：网盘与视频平台 → drawable 资源。
 *
 * ★ 为什么不给 `VideoPlatform` 加个 `logoRes` 字段：logo 是**纯展示资产**，
 *   数据层（`data/network/video`、数据库实体）不该依赖 `R`；映射放 UI 层，数据模型保持干净。
 * ★ 各平台官方图标放在 `res/drawable/logo_*.png`（200×200 PNG）。**资源名只能是全小写 ASCII**，
 *   中文文件名根本用不了，所以原始文件名到资源名是这样对应过来的：
 *
 *   | 原始文件 | 资源名 | 原始文件 | 资源名 |
 *   |---|---|---|---|
 *   | `抖音.png` | `logo_douyin` | `百度网盘.png` | `logo_baidu` |
 *   | `小红书.png` | `logo_xhs` | `夸克网盘.png` | `logo_quark` |
 *   | `快手.png` | `logo_kuaishou` | `UC网盘.png` | `logo_uc` |
 *   | `微博.png` | `logo_weibo` | `迅雷.png` | `logo_xunlei` |
 *   | `acfun.png` | `logo_acfun` | `中国移动云盘.png` | `logo_c139` |
 *   | `哔哩哔哩.png` | `logo_bilibili` | `123云盘.png` | `logo_123pan` |
 *   | `Pixiv.png` | `logo_pixiv` | `115网盘` | `logo_pan115` |
 *   | `github.png` | `logo_github` | `光鸭云盘.png` | `logo_guangya` |
 *   | `蓝奏云盘.png` | `logo_lanzou` | `蓝奏云优享版.png` | `logo_ilanzou` |
 *
 * ★ 换图只要覆盖同名 PNG 即可，不用改代码；新增平台在这里补一行。
 * ★ 渲染约定：**按 Logo 自身形状原样画**（不裁圆、不加底色）——这些图标有的是带底色的
 *   圆角方块、有的是透明背景的图形，统一裁形会把前者裁掉边、给后者糊一圈假底色。
 *   未登录状态用整体降透明度表示（原先靠圆形底色区分登录态）。
 */
@DrawableRes
fun SharePlatform.logoRes(): Int = when (this) {
    SharePlatform.QUARK -> R.drawable.logo_quark
    SharePlatform.UC -> R.drawable.logo_uc
    SharePlatform.XUNLEI -> R.drawable.logo_xunlei
    SharePlatform.BAIDU -> R.drawable.logo_baidu
    SharePlatform.C139 -> R.drawable.logo_c139
    SharePlatform.PAN123 -> R.drawable.logo_123pan
    SharePlatform.PAN115 -> R.drawable.logo_pan115
    SharePlatform.GUANGYA -> R.drawable.logo_guangya
    SharePlatform.ILANZOU -> R.drawable.logo_ilanzou
    SharePlatform.LANZOU -> R.drawable.logo_lanzou
    SharePlatform.GITHUB -> R.drawable.logo_github
}

@DrawableRes
fun VideoPlatform.logoRes(): Int = when (this) {
    VideoPlatform.DOUYIN -> R.drawable.logo_douyin
    VideoPlatform.XHS -> R.drawable.logo_xhs
    VideoPlatform.KUAISHOU -> R.drawable.logo_kuaishou
    VideoPlatform.WEIBO -> R.drawable.logo_weibo
    VideoPlatform.ACFUN -> R.drawable.logo_acfun
    VideoPlatform.BILIBILI -> R.drawable.logo_bilibili
    VideoPlatform.PIXIV -> R.drawable.logo_pixiv
}

/**
 * 按平台标识查网盘 logo（标识就是 [SharePlatform.name] 的小写形式，如 `quark` / `pan115`）。
 *
 * 网盘账号卡片（`DriveScreen.DriveAccount`）里存的是字符串标识而不是枚举，这里替它转一次；
 * 查不到返回 null，调用方退回文字首字 —— 新加平台时就算忘了配图也只是显示得朴素，不会空一块。
 */
@DrawableRes
fun drivePlatformLogoRes(platformId: String): Int? =
    SharePlatform.entries.firstOrNull { it.name.equals(platformId, ignoreCase = true) }?.logoRes()
