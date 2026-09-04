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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局 Snackbar 通道：任何位置（Composable / 工具函数）调用 show() 即可显示。
 * 页面层使用 GlobalSnackbarHost() / rememberGlobalSnackbarHostState() 渲染监听。
 *
 * 修复要点：多条消息并发时不会互相覆盖——每条 show() 都生成自增序号的独立事件，
 * consume(seq) 只清空"刚显示的那条"，期间新来的消息保留排队，前一条消失后继续显示。
 * 多个宿主（MainScreen / 各 Sheet / 各 LoginScreen）同时活跃时共享广播，各自渲染。
 */
object SnackbarController {
    internal data class Event(val seq: Long, val message: String)

    private val _events = MutableStateFlow<Event?>(null)
    private var seq = 0L

    internal val events: StateFlow<Event?> = _events

    fun show(message: String) {
        // 每次 show 都是新 Event（seq 递增），StateFlow 值必然变化 → 所有收集者都会收到
        _events.value = Event(++seq, message)
    }

    /**
     * 消费（清空）事件：仅当当前事件就是刚刚显示的那条时置空，
     * 避免清空动作覆盖期间新 show 进来的消息（这是旧实现丢失第二条的根因）。
     */
    fun consume(shownSeq: Long) {
        val cur = _events.value
        if (cur != null && cur.seq == shownSeq) _events.value = null
    }
}

/** 渲染 SnackbarHost 并监听全局事件（放在页面最外层 Box 内即可，位于内容之上、不拦截点击） */
@Composable
fun GlobalSnackbarHost(modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(hostState) {
        SnackbarController.events.collect { event ->
            if (event != null) {
                hostState.showSnackbar(event.message)
                SnackbarController.consume(event.seq)
            }
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        SnackbarHost(hostState = hostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** 供 Scaffold(snackbarHost = { SnackbarHost(state) }) 或页面 Box 使用的宿主状态（自动监听全局事件） */
@Composable
fun rememberGlobalSnackbarHostState(): SnackbarHostState {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(hostState) {
        SnackbarController.events.collect { event ->
            if (event != null) {
                hostState.showSnackbar(event.message)
                SnackbarController.consume(event.seq)
            }
        }
    }
    return hostState
}