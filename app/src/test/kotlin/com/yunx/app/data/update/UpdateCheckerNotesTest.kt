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

package com.yunx.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release 说明正文的装饰过滤（纯字符串，不联网、不碰 Android API）。
 *
 * 用例里的 [V128_BODY] 是 **v1.2.8 的真实 Release 正文**：既要保证那两样装饰被去掉，
 * 也要保证「其他内容一条都没少」——所以下面逐行断言了所有非装饰行仍然在。
 */
class UpdateCheckerNotesTest {

    /** v1.2.8 的真实 Release 正文（尾部网盘链接照抄，前后各有一个换行） */
    private val v128Body = """
<img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&height=300&section=header&text=YunX&desc=%E4%BA%91%E6%9E%90%20%C2%B7%20%E7%BD%91%E7%9B%98%E5%88%86%E4%BA%AB%E9%93%BE%E6%8E%A5%E8%A7%A3%E6%9E%90%E4%B8%8E%E9%AB%98%E9%80%9F%E4%B8%8B%E8%BD%BD&fontSize=64&fontColor=ffffff&descSize=16&animation=fadeIn&fontAlignY=40&descAlignY=55" />

[![QQ交流群](https://img.shields.io/badge/QQ%E7%BE%A4-635207650-12B7F5?style=flat-square&logo=qq&logoColor=white)](http://qm.qq.com/cgi-bin/qm/qr?...&group_code=635207650)

# YunX（云析） v1.2.8

## 更新内容

### 新增
- 新增 115 网盘支持：登录 / 云盘管理 / 分享 / 转存 / 下载
- 新增 GitHub 解析平台：仓库 / 账号 / 文件直链解析，自动渲染 README，支持配置 Token
- 适配 OPPO 流体云：下载进度实时通知，下载完成 / 失败终态通知

### 修复
- 根治大文件下载内存溢出（OOM），弱网慢连接自动断开换线续传
- 修复安卓 15 / 16 桌面图标偏小、留白过多（改用自适应图标）

### 优化
- Release 包开启 R8 混淆与资源压缩，安装包体积更小
- 最低系统版本提升至 Android 7.0（API 24）

## 通知
* 由于作者还是初中生，开学住校后仅休息日可查看消息，开学后更新速度会减缓，但不会停止维护，望理解

## 新贡献者
* @xiaoxun007 made their first contribution in #107
* @tidain made their first contribution in #115 #128


**Full Changelog**: https://github.com/CYQawa/YunX/compare/v1.2.7...v1.2.8

[网盘下载](https://pan.quark.cn/s/ab15b5f0a155)
""".trimIndent()

    /** 去掉头图与 QQ 群徽章这两行，另加首尾空行与连续空行压缩 */
    @Test
    fun stripsBannerAndQqBadgeOnly() {
        val cleaned = UpdateChecker.cleanReleaseNotes(v128Body)

        assertFalse(cleaned.contains("capsule-render"))
        assertFalse(cleaned.contains("<img"))
        assertFalse(cleaned.contains("img.shields.io"))
        assertFalse(cleaned.contains("qm.qq.com"))
        // 说明正文从版本标题开始，头顶不留空行（说明区只有 220dp，空行白占高度）
        assertTrue(cleaned.startsWith("# YunX（云析） v1.2.8"))
    }

    /** 「其他不要给我去了」：原文里除那两行以外的每一行都必须原样还在 */
    @Test
    fun keepsEveryOtherLine() {
        val cleaned = UpdateChecker.cleanReleaseNotes(v128Body)
        val kept = v128Body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !it.contains("capsule-render") && !it.contains("img.shields.io") }

        // 12 个内容行 + 3 个标题行…数量对不上时下面会直接指出是哪一行丢了
        assertTrue("过滤后不该只剩这么点内容：${cleaned.length} 字", kept.size > 15)
        kept.forEach { line ->
            assertTrue("原文这一行被误删了：$line", cleaned.contains(line))
        }
        // 逐条点名几个最容易被误伤的
        assertTrue(cleaned.contains("### 新增"))
        assertTrue(cleaned.contains("- 新增 115 网盘支持：登录 / 云盘管理 / 分享 / 转存 / 下载"))
        assertTrue(cleaned.contains("## 通知"))
        assertTrue(cleaned.contains("* @tidain made their first contribution in #115 #128"))
        assertTrue(cleaned.contains("**Full Changelog**: https://github.com/CYQawa/YunX/compare/v1.2.7...v1.2.8"))
        assertTrue(cleaned.contains("[网盘下载](https://pan.quark.cn/s/ab15b5f0a155)"))
    }

    /** 清洗后仍要能提出网盘直链（真正消费这条数据的路径：清洗 → netdiskDownloadUrl） */
    @Test
    fun netdiskLinkStillFoundAfterCleaning() {
        val cleaned = UpdateChecker.cleanReleaseNotes(v128Body)
        assertEquals("https://pan.quark.cn/s/ab15b5f0a155", UpdateChecker.netdiskDownloadUrl(cleaned))
    }

    /** 不认识的装饰一律不动：别的 shields.io 徽章、别的图床 `<img>` 都保留 */
    @Test
    fun keepsUnrelatedBadgesAndImages() {
        val body = """
<img src="https://example.com/logo.png" /> 文字
[![Build](https://img.shields.io/badge/build-passing-green)](https://ci.example.com/123)
[![QQ交流群](https://img.shields.io/badge/QQ%E7%BE%A4-123?logo=qq)](http://qm.qq.com/x)
![QQ交流群](https://img.shields.io/badge/QQ%E7%BE%A4-123?logo=qq)
""".trimIndent()
        val cleaned = UpdateChecker.cleanReleaseNotes(body)

        assertTrue(cleaned.contains("""<img src="https://example.com/logo.png" /> 文字"""))
        assertTrue(cleaned.contains("[![Build](https://img.shields.io/badge/build-passing-green)](https://ci.example.com/123)"))
        assertFalse(cleaned.contains("qm.qq.com"))
        assertFalse(cleaned.contains("logo=qq"))
    }

    /** 空 / 纯装饰正文：不能抛异常，空正文原样返回（UpdateSheet 靠 ifBlank 显示「暂无更新说明」） */
    @Test
    fun handlesEmptyAndDecorationOnlyBodies() {
        assertEquals("", UpdateChecker.cleanReleaseNotes(""))
        assertEquals("   ", UpdateChecker.cleanReleaseNotes("   "))
        val onlyDecoration = """
<img src="https://capsule-render.vercel.app/api?type=waving" />
""".trimIndent()
        assertEquals("", UpdateChecker.cleanReleaseNotes(onlyDecoration))
    }
}
