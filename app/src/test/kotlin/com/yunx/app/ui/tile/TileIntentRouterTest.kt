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

package com.yunx.app.ui.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileIntentRouterTest {

    @Test
    fun emptyOrBlankRoutesToEmptyAdd() {
        assertEquals(TileTarget.EmptyAdd, TileIntentRouter.target(null))
        assertEquals(TileTarget.EmptyAdd, TileIntentRouter.target(""))
        assertEquals(TileTarget.EmptyAdd, TileIntentRouter.target("   \n\t  "))
    }

    @Test
    fun magnetLinkRoutesToAddDownload() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=ubuntu"
        val target = TileIntentRouter.target(magnet)
        assertTrue(target is TileTarget.AddDownload)
        assertEquals(magnet, (target as TileTarget.AddDownload).url)
    }

    @Test
    fun directHttpLinkRoutesToAddDownload() {
        val url = "https://example.com/downloads/setup.exe"
        val target = TileIntentRouter.target(url)
        assertTrue(target is TileTarget.AddDownload)
        assertEquals(url, (target as TileTarget.AddDownload).url)
    }

    @Test
    fun textWithDirectLinkExtractsUrlToAddDownload() {
        val text = "这是下载链接：https://example.com/package.zip，请在WiFi下下载！"
        val target = TileIntentRouter.target(text)
        assertTrue(target is TileTarget.AddDownload)
        assertEquals("https://example.com/package.zip", (target as TileTarget.AddDownload).url)
    }

    @Test
    fun gitHubDirectFileRoutesToAddDownload() {
        val releaseUrl = "https://github.com/CYQawa/YunX/releases/download/v1.3.1/app-release.apk"
        val rawUrl = "https://raw.githubusercontent.com/CYQawa/YunX/main/README.md"

        val t1 = TileIntentRouter.target(releaseUrl)
        assertTrue(t1 is TileTarget.AddDownload)
        assertEquals(releaseUrl, (t1 as TileTarget.AddDownload).url)

        val t2 = TileIntentRouter.target(rawUrl)
        assertTrue(t2 is TileTarget.AddDownload)
        assertEquals(rawUrl, (t2 as TileTarget.AddDownload).url)
    }

    @Test
    fun gitHubRepoRoutesToResolve() {
        val repo = "https://github.com/CYQawa/YunX"
        val target = TileIntentRouter.target(repo)
        assertTrue(target is TileTarget.Resolve)
        assertEquals(repo, (target as TileTarget.Resolve).text)
    }

    @Test
    fun panShareLinkRoutesToResolve() {
        val text = "链接: https://pan.quark.cn/s/123456 提取码: a1b2 复制后打开云析"
        val target = TileIntentRouter.target(text)
        assertTrue(target is TileTarget.Resolve)
        assertEquals(text, (target as TileTarget.Resolve).text)
    }

    @Test
    fun videoLinkRoutesToResolve() {
        val text = "7.12 复制打开抖音，看看【测试的作品】 https://v.douyin.com/eXXXXX/"
        val target = TileIntentRouter.target(text)
        assertTrue(target is TileTarget.Resolve)
        assertEquals(text, (target as TileTarget.Resolve).text)
    }

    @Test
    fun c139KoulingRoutesToResolve() {
        val text = "和彩云口令 /*测试口令123:/ 复制后打开"
        val target = TileIntentRouter.target(text)
        assertTrue(target is TileTarget.Resolve)
        assertEquals(text, (target as TileTarget.Resolve).text)
    }

    @Test
    fun xunleiKoulingRoutesToResolve() {
        val text = "「张三丰资源合集」"
        val target = TileIntentRouter.target(text)
        assertTrue(target is TileTarget.Resolve)
        assertEquals(text, (target as TileTarget.Resolve).text)
    }

    @Test
    fun plainTextWithoutLinksRoutesToEmptyAdd() {
        val text = "今天天气真好，出去散步吧"
        val target = TileIntentRouter.target(text)
        assertEquals(TileTarget.EmptyAdd, target)
    }
}
