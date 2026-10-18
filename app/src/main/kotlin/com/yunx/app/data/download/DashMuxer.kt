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

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * mp4 分轨合轨：把「只有画面」的视频轨与「只有声音」的音轨合并成一个标准 mp4。
 *
 * 用途：哔哩哔哩的高清是 **DASH 音视频分离**的两路流（视频 `.m4s` + 音频 `.m4s`），
 * 各自下载完必须合轨才是一个能播放的文件。参考实现靠 ffmpeg，这里用 Android 原生
 * [MediaExtractor] + [MediaMuxer] **逐样本重封装**：只搬 H.264/H.265 + AAC 原始码流，
 * 不经过任何编码器（无损、秒级完成、无需 native 库）。
 *
 * ★ 为什么不用 mp4parser/isoparser（2026-10-18）：isoparser 对 B 站 DASH 的 fragmented MP4
 *   （.m4s）重建出的 moov 结构不规范 —— VLC 容错强能放，但 Android 系统播放器直接拒绝，
 *   PC 端 Windows 媒体播放器也拿不到时间轴（进度条不可拖动）。原生 MediaMuxer 写出的
 *   moov 是标准结构，所有播放器都能放、进度条正常。
 * ★ 为什么不做降级（合轨失败就留下无声视频）：静默给用户一个没声音的文件，比明确失败更难排查 ——
 *   失败时任务会以可见的错误结束，用户还能改用「未登录的单文件低清」那条路。
 * ★ 前提：两路的编码/时长应当匹配。B 站同一 `qn` 下发的轨道是配套的，跨清晰度混用才可能不同步。
 */
object DashMuxer {

    private const val TAG = "YunX"

    /** 单个输入轨道的抽取句柄：MediaExtractor + 轨道格式（MediaMuxer.addTrack 需要） */
    private class Track(
        val extractor: MediaExtractor,
        val format: MediaFormat
    ) {
        fun release() = runCatching { extractor.release() }
    }

    /**
     * 合轨 [videoFile] + [audioFile] → [outFile]（标准 MP4）。
     *
     * 同步合轨是大文件 IO 密集操作（逐样本重封装可能几十秒），调用方应在 IO 线程调用。
     *
     * @param onProgress 进度回调（0~100，按已写字节 / 两轨总字节）：UI 据此显示「合并中 n%」，
     *                   避免合轨期间进度条停在 100% 像卡死（与分片合并同一口径）
     * @return 成功与否；失败原因记日志（调用方据此给出用户可见的错误）
     */
    fun mux(videoFile: File, audioFile: File, outFile: File, onProgress: (Int) -> Unit = {}): Boolean {
        val video = readTrack(videoFile, "video")
        val audio = readTrack(audioFile, "audio")
        if (video == null || audio == null) {
            Log.w(TAG, "DashMuxer: 轨道缺失（文件可能不是预期的 DASH 分轨）")
            return false
        }
        val muxer = try {
            MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Throwable) {
            Log.e(TAG, "DashMuxer: 创建 MediaMuxer 失败 ${e.message}")
            video.release()
            audio.release()
            return false
        }
        try {
            val videoTrack = muxer.addTrack(video.format)
            val audioTrack = muxer.addTrack(audio.format)
            // 竖屏视频把旋转角写进 moov（MediaMuxer 只认 start 前的 setOrientationHint）
            muxer.setOrientationHint(video.format.getInteger(MediaFormat.KEY_ROTATION, 0))
            muxer.start()
            // 进度分母 = 两轨字节之和（读盘量与耗时正相关）；样本级累计，视频轨写完报一次、音轨写完再补到 100%
            val total = videoFile.length() + audioFile.length()
            val tracker = ProgressTracker(total, onProgress)
            // 先写完整视频轨再写音轨：本地文件播放无需流式交错，MediaMuxer 会正确生成 moov
            copySamples(video, muxer, videoTrack, tracker)
            copySamples(audio, muxer, audioTrack, tracker)
            muxer.stop()
            val ok = outFile.length() > 0
            Log.d(
                TAG,
                "DashMuxer: 合轨完成 video=${videoFile.length()} audio=${audioFile.length()} " +
                    "out=${outFile.length()} ok=$ok"
            )
            return ok
        } catch (e: Throwable) {
            Log.e(TAG, "DashMuxer: 合轨失败 ${e.message}", e)
            return false
        } finally {
            runCatching { muxer.release() }
            video.release()
            audio.release()
            // 失败产物不留半成品（MediaMuxer.stop 未走到时文件可能只有 ftyp 头）
            if (!outFile.exists() || outFile.length() == 0L) {
                runCatching { outFile.delete() }
            }
        }
    }

    /** 从 [file] 抽第一条 mime 前缀匹配 [want]（"video"/"audio"）的轨道；失败返回 null 并释放抽取器 */
    private fun readTrack(file: File, want: String): Track? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val matches = when (want) {
                    "video" -> mime.startsWith("video/")
                    "audio" -> mime.startsWith("audio/")
                    else -> false
                }
                if (matches) {
                    extractor.selectTrack(i)
                    return Track(extractor, format)
                }
            }
            Log.w(TAG, "DashMuxer: $file 里没有 $want 轨")
        } catch (e: Throwable) {
            Log.e(TAG, "DashMuxer: 读取 $want 轨失败 ${e.message}")
        }
        extractor.release()
        return null
    }

    /**
     * 把 [track] 的全部样本逐帧搬进 [muxer]（写入 [trackIndex]）。
     *
     * 缓冲按轨道声明的大小起步；样本超过缓冲（罕见的大关键帧）时扩容并回到同一时间点重读。
     *
     * @param tracker 进度累计器：每写完一个样本按累计字节数回调百分比
     */
    private fun copySamples(track: Track, muxer: MediaMuxer, trackIndex: Int, tracker: ProgressTracker) {
        val extractor = track.extractor
        val info = MediaCodec.BufferInfo()
        var capacity = runCatching {
            track.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        }.getOrDefault(2 * 1024 * 1024)
        var buffer = ByteBuffer.allocate(capacity)
        while (true) {
            val timeUs = extractor.sampleTime
            if (timeUs < 0) break
            buffer.clear()
            val size = try {
                extractor.readSampleData(buffer, 0)
            } catch (e: IllegalArgumentException) {
                // 样本超过缓冲：翻倍扩容后回到同一时间点重读
                capacity *= 2
                buffer = ByteBuffer.allocate(capacity)
                extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                continue
            }
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = timeUs
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else 0
            muxer.writeSampleData(trackIndex, buffer, info)
            tracker.onWritten(size.toLong())
            extractor.advance()
        }
    }
}

/** 合轨进度累计器：分母 [total]（两轨字节之和），样本写完后按已写字节回调 0~100 百分比 */
private class ProgressTracker(
    private val total: Long,
    private val onProgress: (Int) -> Unit
) {
    private var done = 0L
    fun onWritten(bytes: Long) {
        done += bytes
        if (total > 0) {
            onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
        }
    }
}
