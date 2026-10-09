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

package com.yunx.app.ui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.collect
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SnackbarEventQueueTest {
    @Test fun appendingDoesNotRestartVisibleMessageAndHostChangesTransferOwnership() = runBlocking {
        val queue = SnackbarEventQueue()
        val page = Any()
        val sheet = Any()
        val seen = mutableListOf<String?>()
        queue.register(page)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.eventsFor(page).collect { seen += it?.message }
        }
        try {
            queue.show("第一条")
            yield()
            queue.show("第二条")
            yield()
            assertEquals(listOf(null, "第一条"), seen)
            queue.consume(page, queue.state.value.events.first().seq)
            yield()
            queue.register(sheet)
            yield()
            queue.unregister(sheet)
            yield()
            assertEquals(listOf(null, "第一条", "第二条", null, "第二条"), seen)
        } finally { collector.cancel() }
    }

    @Test fun burstPreservesEveryMessageInOrderIncludingDuplicates() {
        val queue = SnackbarEventQueue()
        val host = Any()
        queue.register(host)
        listOf("第一条", "第二条", "第二条", "第四条").forEach(queue::show)
        assertEquals(listOf("第一条", "第二条", "第二条", "第四条"), queue.state.value.events.map { it.message })
        val sequences = queue.state.value.events.map { it.seq }
        assertEquals(4, sequences.distinct().size)
        sequences.forEach { queue.consume(host, it) }
        assertTrue(queue.state.value.events.isEmpty())
    }

    @Test fun coveredHostCannotConsumeAndClosingSheetPreservesPendingMessage() {
        val queue = SnackbarEventQueue()
        val page = Any()
        val sheet = Any()
        queue.register(page)
        queue.show("请求失败")
        val sequence = queue.state.value.events.first().seq
        queue.register(sheet)
        queue.consume(page, sequence)
        assertEquals(1, queue.state.value.events.size)
        queue.unregister(sheet)
        assertSame(page, queue.state.value.hosts.last())
        queue.consume(page, sequence)
        assertTrue(queue.state.value.events.isEmpty())
    }

    @Test fun staleCompletionCannotRemoveNextMessage() {
        val queue = SnackbarEventQueue()
        val host = Any()
        queue.register(host)
        queue.show("第一条")
        queue.show("第二条")
        val first = queue.state.value.events.first().seq
        queue.consume(host, first)
        queue.consume(host, first)
        assertEquals("第二条", queue.state.value.events.single().message)
    }

    @Test fun concurrentSendersLoseNoMessagesOrSequenceNumbers() {
        val queue = SnackbarEventQueue()
        val executor = Executors.newFixedThreadPool(4)
        try {
            repeat(1000) { index -> executor.submit { queue.show("消息 $index") } }
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            val events = queue.state.value.events
            assertEquals(1000, events.size)
            assertEquals(1000, events.map { it.message }.distinct().size)
            assertEquals((1L..1000L).toList(), events.map { it.seq })
        } finally { executor.shutdownNow() }
    }
}
