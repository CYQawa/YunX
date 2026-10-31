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

package com.yunx.app.data.upload

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.CancellationException

/**
 * 当前上传任务的展示状态（网盘页的上传进度弹窗直接读它；null 表示没有任务）。
 *
 * @param index 本次第几个文件（1 起）
 * @param count 本次一共几个文件
 */
data class UploadProgress(
    val index: Int,
    val count: Int,
    val name: String,
    val sent: Long,
    val size: Long,
    val phase: UploadPhase,
    val cancelling: Boolean = false
) {
    val fraction: Float
        get() = if (size <= 0) 0f else (sent.toFloat() / size).coerceIn(0f, 1f)
}

/** 一批上传的收尾结果：ViewModel 用它拼提示文案并决定要不要刷新列表 */
data class UploadOutcome(
    val uploaded: Int,
    val failed: Int,
    val cancelled: Boolean,
    val error: String?
)

/**
 * 「选一批文件 → 依次上传」的执行器：每个网盘页 ViewModel 各持有一个。
 *
 * 只做与平台无关的部分：解析 SAF 元数据、按顺序串行上传（各家上传都吃带宽，并发没有意义）、
 * 节流上报进度、取消、把异常收敛成 [UploadOutcome]。真正的协议在各自 Api 的 `upload(...)` 里。
 *
 * ★ 进度状态在 IO 线程直接写 Compose 快照状态：`mutableStateOf` 的写入本身是线程安全的，
 *   节流到 ~120ms 一次，主线程不会被打爆。
 */
class UploadRunner(
    private val scope: CoroutineScope,
    private val clientProvider: () -> OkHttpClient,
    private val onFinished: (UploadOutcome) -> Unit
) {

    var progress by mutableStateOf<UploadProgress?>(null)
        private set

    private var active: UploadIo? = null

    @Volatile
    private var cancelRequested = false

    private var lastEmit = 0L

    val isRunning: Boolean get() = progress != null

    /** 取消：主线程点「取消」→ 打断在飞请求，读循环下一轮就退出 */
    fun cancel() {
        if (progress == null) return
        cancelRequested = true
        progress = progress?.copy(cancelling = true)
        active?.cancel()
    }

    /**
     * 开始上传一批文件。
     *
     * @param upload 单个文件的上传动作（平台协议）；抛异常即视为该文件失败，后面的文件不再继续
     */
    fun start(
        resolver: ContentResolver,
        uris: List<Uri>,
        upload: suspend (UploadIo, UploadSource) -> Unit
    ) {
        if (isRunning || uris.isEmpty()) return
        cancelRequested = false
        scope.launch {
            var uploaded = 0
            var failed = 0
            var error: String? = null
            var cancelled = false
            try {
                val sources = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri -> UploadSource.resolve(resolver, uri) }
                }
                if (sources.isEmpty()) {
                    failed = 1
                    error = "无法读取所选文件的大小，请换一个文件后再试"
                    return@launch
                }
                loop@ for ((index, source) in sources.withIndex()) {
                    if (cancelRequested) {
                        cancelled = true
                        break@loop
                    }
                    val io = UploadIo(resolver, source, clientProvider) { phase, sent ->
                        emit(index + 1, sources.size, source, phase, sent)
                    }
                    active = io
                    try {
                        withContext(Dispatchers.IO) { upload(io, source) }
                        uploaded++
                    } catch (e: CancellationException) {
                        cancelled = true
                        break@loop
                    } catch (e: Exception) {
                        if (cancelRequested) {
                            cancelled = true
                        } else {
                            failed = 1
                            error = "「${source.name}」上传失败：${e.message ?: "网络异常，请重试"}"
                        }
                        break@loop
                    } finally {
                        active = null
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                cancelled = true
                error = e.message ?: "上传已中断，请重试"
            } finally {
                active = null
                progress = null
                onFinished(UploadOutcome(uploaded, failed, cancelled, error))
            }
        }
    }

    /** 节流上报：阶段变化/传完立刻刷新，其余 120ms 一次 */
    private fun emit(
        index: Int,
        count: Int,
        source: UploadSource,
        phase: UploadPhase,
        sent: Long
    ) {
        val now = System.currentTimeMillis()
        val current = progress
        val phaseChanged = current == null || current.phase != phase
        if (!phaseChanged && now - lastEmit < PROGRESS_INTERVAL_MS) return
        lastEmit = now
        progress = UploadProgress(
            index = index,
            count = count,
            name = source.name,
            sent = sent.coerceIn(0L, source.size),
            size = source.size,
            phase = phase,
            cancelling = cancelRequested
        )
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 120L
    }
}
