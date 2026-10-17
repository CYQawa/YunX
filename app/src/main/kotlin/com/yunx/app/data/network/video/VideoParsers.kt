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

package com.yunx.app.data.network.video

/**
 * 平台 → 解析器注册表。
 *
 * ★ 唯一需要「新增平台时改一下」的地方（识别规则在 [VideoLinkParser]，账号页的平台列表由
 *   [VideoPlatform] 枚举驱动）。[all] 的顺序就是账号页里「视频解析」区的展示顺序：
 *   免登录的平台在前、需要登录的在后，用户一眼能看到「哪些要登录」。
 */
object VideoParsers {

    /**
     * 全部已实现的解析器。顺序只影响账号页里的展示先后（见 [supportedPlatforms]）：
     * 先免登录平台（抖音 / 快手 / 微博 / AcFun），再需要登录的（哔哩哔哩 / 小红书 / Pixiv）。
     */
    val all: List<VideoParser> = listOf(
        DouyinParser,
        KuaishouParser,
        WeiboParser,
        AcFunParser,
        BilibiliParser,
        XhsParser,
        PixivParser
    )

    private val byPlatform: Map<VideoPlatform, VideoParser> = all.associateBy { it.platform }

    fun of(platform: VideoPlatform): VideoParser? = byPlatform[platform]

    /**
     * 账号页展示用：**免登录的排在前面，需要登录的三个聚在最后**。
     *
     * ★ 用 `sortedBy` 而不是靠 [all] 的手写顺序：以后新增平台不必再想着「该往哪插」，
     *   分组规则由平台自身的 `needsLogin` 决定，不会因为有人插错位置又散开。
     *   （`sortedBy` 稳定排序，同组内保持 [all] 的先后。）
     */
    val supportedPlatforms: List<VideoPlatform> get() = all.map { it.platform }.sortedBy { it.needsLogin }
}
