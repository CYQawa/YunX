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
 * 「只要音频」：把视频文件里的**音轨原样抽出来**，写成 `.m4a`（MP4 容器 + 原来的 AAC 码流）。
 *
 * 为什么要有它：单文件平台（抖音/快手/小红书/微博/AcFun）只给一条**整段视频**直链，
 * 平台侧没有独立的音频地址 —— 想要一个能单独播放的音频文件，只能在本地把音轨抽出来。
 * 抽的时候**只搬样本、不经过编码器**（与 [DashMuxer] 同一套 [MediaExtractor] + [MediaMuxer]），
 * 所以无损、耗时基本只取决于磁盘 IO（几十分钟的视频也就几秒，进度按音轨时间轴回报）。
 *
 * ★ 产物一律 `.m4a`：Android 的 [MediaMuxer] 只会写 MP4 容器，塞不进 mp3/ogg；
 *   统一成 m4a 才能让「下载音频」在七个平台上格式一致（AAC 原样搬运，音质与视频里完全一致）。
 * ★ 前提是输入容器里真的有音轨：静音视频、纯画面录屏会返回 false，由上层明确提示，
 *   而不是给用户一个 0 字节的「音频」。
 */
object AudioExtractor {

    private const val TAG = "YunX"

    /** 单条输入音轨的抽取句柄（与 DashMuxer 的 Track 同构：MediaMuxer.addTrack 需要轨道格式） */
    private class Track(
        val extractor: MediaExtractor,
        val format: MediaFormat
    ) {
        fun release() = runCatching { extractor.release() }
    }

    /**
     * 从 [input] 抽出第一条音轨写进 [output]。
     *
     * @param onProgress 进度回调（0~100，按音轨时间轴 / 总时长）：抽音轨虽然快，但长视频也要几秒，
     *                   不报进度的话进度条会停在「下载 100%」像卡死（与合轨同一口径）
     * @return 成功与否。false = 没有音轨 / 音轨格式塞不进 MP4 / 写出为空，失败半成品会被删掉
     */
    fun extract(input: File, output: File, onProgress: (Int) -> Unit = {}): Boolean {
        val track = readAudioTrack(input) ?: return false
        val muxer = try {
            MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Throwable) {
            Log.e(TAG, "AudioExtractor: 创建 MediaMuxer 失败 ${e.message}")
            track.release()
            return false
        }
        try {
            val trackIndex = muxer.addTrack(track.format)
            muxer.start()
            copySamples(track, muxer, trackIndex, onProgress)
            muxer.stop()
            val ok = output.length() > 0
            Log.d(
                TAG,
                "AudioExtractor: 抽取完成 in=${input.length()} out=${output.length()} " +
                    "mime=${track.format.getString(MediaFormat.KEY_MIME)} ok=$ok"
            )
            return ok
        } catch (e: Throwable) {
            // 音轨是 DTS/AC3 之类塞不进 MP4 的编码时，这里会抛异常 —— 落到「没有可用音轨」的同一条提示
            Log.e(TAG, "AudioExtractor: 抽取失败 ${e.message}", e)
            return false
        } finally {
            runCatching { muxer.release() }
            track.release()
            // 失败产物不留半成品（MediaMuxer.stop 未走到时文件可能只有 ftyp 头，播放器会当成坏文件）
            if (!output.exists() || output.length() == 0L) runCatching { output.delete() }
        }
    }

    /** 抽出 [file] 的第一条音频轨（MIME 以 `audio/` 开头，已 selectTrack）；没有音轨返回 null 并释放抽取器 */
    private fun readAudioTrack(file: File): Track? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    return Track(extractor, format)
                }
            }
            Log.w(TAG, "AudioExtractor: ${file.name} 里没有音轨（静音视频/纯画面）")
        } catch (e: Throwable) {
            Log.e(TAG, "AudioExtractor: 读取音轨失败 ${e.message}")
        }
        extractor.release()
        return null
    }

    /**
     * 把 [track] 的全部样本逐帧搬进 [muxer]（写入 [trackIndex]），并按音轨时长回报进度。
     *
     * 缓冲起步用轨道声明的 `KEY_MAX_INPUT_SIZE`；样本超过缓冲时扩容并回到同一时间点重读
     * （与 DashMuxer.copySamples 完全同一套逻辑，只是进度按时间轴算而不是按字节）。
     */
    private fun copySamples(
        track: Track,
        muxer: MediaMuxer,
        trackIndex: Int,
        onProgress: (Int) -> Unit
    ) {
        val extractor = track.extractor
        val info = MediaCodec.BufferInfo()
        val durationUs = runCatching {
            track.format.getLong(MediaFormat.KEY_DURATION)
        }.getOrDefault(0L)
        var capacity = runCatching {
            track.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        }.getOrDefault(2 * 1024 * 1024)
        var buffer = ByteBuffer.allocate(capacity)
        var lastPercent = -1
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
            if (durationUs > 0) {
                val percent = ((timeUs * 100) / durationUs).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
            extractor.advance()
        }
    }
}
