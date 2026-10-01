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

package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「全进程在飞分片上限」的内存预算策略。
 *
 * OOM 根因（log/oom）：旧实现给每个任务一个容量恰等于 worker 数的信号量（等于不限流），
 * 线程数上限 512 × 同时下载数 可把在飞分片堆到数千，每路各持一份读缓冲 + 一条
 * OkHttp/Conscrypt 连接 ⇒ 256MB 堆被撑满。这里的边界必须守住：
 * 小堆保底 8 路（不能让低端机退化成单流），大堆夹到 512 路。
 *
 * 预算取最大堆的 1/8，而不是更早的 1/16：下载客户端已固定 HTTP/1.1，每路在飞只占一份 64KB
 * 读缓冲（HTTP/2 时代那条 16MB 的每流窗口不存在了，见 Agent.md §5.1.1）。
 * 256MB 堆按预算算得 512，正好等于上限 ⇒ 设置里的 512 档位 1:1 生效，占 32MB（堆的 1/8）。
 */
class InflightChunkBudgetTest {

    @Test
    fun clampsIntoSafeRange() {
        // 小堆仍保底 8 路
        assertTrue(DownloadManager.inflightChunksFor(16L * MB) >= 8)
        assertTrue(DownloadManager.inflightChunksFor(32L * MB) >= 8)
        assertTrue(DownloadManager.inflightChunksFor(64L * MB) >= 8)
        // 大堆也绝不放开到 512 路以上
        assertTrue(DownloadManager.inflightChunksFor(256L * MB) <= 512)
        assertTrue(DownloadManager.inflightChunksFor(512L * MB) <= 512)
        assertTrue(DownloadManager.inflightChunksFor(1024L * MB) <= 512)
        // 本机那种 256MB 堆（无 largeHeap）：选 512 就是 512 路，不再被夹到 256
        assertEquals(512, DownloadManager.inflightChunksFor(256L * MB))
    }

    @Test
    fun totalBufferStaysWithinHeapBudget() {
        for (heapMb in longArrayOf(32, 64, 128, 256, 512)) {
            val heap = heapMb * MB
            val chunks = DownloadManager.inflightChunksFor(heap)
            val buffers = chunks.toLong() * BUFFER_SIZE
            assertTrue(
                "heap=${heapMb}MB chunks=$chunks buffers=$buffers",
                buffers <= heap / 8
            )
        }
    }

    @Test
    fun perStreamBufferIs64Kb() {
        assertEquals(64L * 1024, BUFFER_SIZE.toLong())
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}
