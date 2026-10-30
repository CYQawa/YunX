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

package com.yunx.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件名归一化单测（issue #215）。
 *
 * 断言的重点不是"能修"，而是"**只修该修的**" —— 归一化跑在每次下载任务的入口，
 * 误伤一个正常文件名的代价比少修一条大得多，所以每条"不该动"的形态都要有反例兜住。
 */
class DownloadFileNamesTest {

    @Test
    fun `apk 的冲突序号挪回扩展名前面`() {
        assertEquals("x (1).apk", DownloadFileNames.normalize("x.apk (1)"))
        assertEquals("x (2).apk", DownloadFileNames.normalize("x.apk(2)"))
        // 全角括号与全角空格（部分网盘/输入法真的会给这种）
        assertEquals("x (1).apk", DownloadFileNames.normalize("x.apk（1）"))
        // 首尾空白先裁掉再判断
        assertEquals("报告 (1).apk", DownloadFileNames.normalize("  报告.apk (1)  "))
    }

    @Test
    fun `文本类扩展名后面的兜底 txt 去掉`() {
        assertEquals("README.md", DownloadFileNames.normalize("README.md.txt"))
        assertEquals("x.MD", DownloadFileNames.normalize("x.MD.txt"))
        assertEquals("app.log", DownloadFileNames.normalize("app.log.txt"))
        assertEquals("data.json", DownloadFileNames.normalize("data.json.txt"))
    }

    @Test
    fun `非文本类扩展名后面的 txt 保留`() {
        // `x.zip.txt` 可能是用户真的想把压缩包当文本存下来，不能替他改名
        assertEquals("x.zip.txt", DownloadFileNames.normalize("x.zip.txt"))
        assertEquals("x.exe.txt", DownloadFileNames.normalize("x.exe.txt"))
        assertEquals("x.tar.txt", DownloadFileNames.normalize("x.tar.txt"))
    }

    @Test
    fun `正常名字一律原样返回`() {
        assertEquals("x.apk", DownloadFileNames.normalize("x.apk"))
        assertEquals("x.tar.gz", DownloadFileNames.normalize("x.tar.gz"))
        assertEquals("没有扩展名", DownloadFileNames.normalize("没有扩展名"))
        assertEquals("photo (3).jpg", DownloadFileNames.normalize("photo (3).jpg"))
        assertEquals("下载 (1)", DownloadFileNames.normalize("下载 (1)"))
        assertEquals("x.txt", DownloadFileNames.normalize("x.txt"))
    }

    @Test
    fun `只剩畸形名字时不做二次变形`() {
        // 扩展名里已经带空格/括号，说明上游已经把名字写坏过一次，再挪只会越修越乱
        assertEquals("x.apk (1) (2)", DownloadFileNames.normalize("x.apk (1) (2)"))
        // 年份结尾的 `(2026)` 不是冲突序号：它出现在扩展名之前，本来就不该动
        assertEquals("会议记录 (2026).md", DownloadFileNames.normalize("会议记录 (2026).md"))
    }

    @Test
    fun `两种脏形态各自修好，但不互相乱套`() {
        // 只有冲突序号：扩展名不变，序号挪到前面
        assertEquals("x (1).md", DownloadFileNames.normalize("x.md (1)"))
        // 序号卡在中间（`x.md (1).txt`）不属于已知形态，原样保留 —— 硬猜只会把名字改得更不像
        assertEquals("x.md (1).txt", DownloadFileNames.normalize("x.md (1).txt"))
    }
}
