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

import android.util.Log
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import java.io.File
import java.io.FileOutputStream

/**
 * mp4 分轨合轨（isoparser）：把「只有画面」的视频轨与「只有声音」的音轨合并成一个 mp4。
 *
 * 用途：哔哩哔哩的高清是 **DASH 音视频分离**的两路流（视频 `.m4s` + 音频 `.m4s`），
 * 各自下载完必须合轨才是一个能播放的文件。参考实现靠 ffmpeg，Android 上我们用纯 Java 的
 * isoparser 直接搬 box，**不重新编码**（无损、秒级完成、无需 native 库）。
 *
 * ★ 为什么不做降级（合轨失败就留下无声视频）：静默给用户一个没声音的文件，比明确失败更难排查 ——
 *   失败时任务会以可见的错误结束，用户还能改用「未登录的单文件低清」那条路。
 * ★ 前提：两路的编码/时长应当匹配。B 站同一 `qn` 下发的轨道是配套的，跨清晰度混用才可能不同步。
 */
object DashMuxer {

    private const val TAG = "YunX"

    /**
     * 合轨 [videoFile] + [audioFile] → [outFile]。
     *
     * @return 成功与否；失败原因记日志（调用方据此给出用户可见的错误）
     */
    fun mux(videoFile: File, audioFile: File, outFile: File): Boolean = try {
        val videoTracks = MovieCreator.build(videoFile.absolutePath).tracks.filter { it.handler == "vide" }
        val audioTracks = MovieCreator.build(audioFile.absolutePath).tracks.filter { it.handler == "soun" }
        if (videoTracks.isEmpty() || audioTracks.isEmpty()) {
            Log.w(
                TAG,
                "DashMuxer: 轨道缺失 video=${videoTracks.size} audio=${audioTracks.size}" +
                    "（文件可能不是预期的 DASH 分轨）"
            )
            false
        } else {
            val movie = Movie()
            videoTracks.forEach { movie.addTrack(it) }
            audioTracks.forEach { movie.addTrack(it) }
            // 直接写 box 到目标文件：不经过任何编码器
            FileOutputStream(outFile).use { out ->
                DefaultMp4Builder().build(movie).writeContainer(out.channel)
            }
            val ok = outFile.length() > 0
            Log.d(
                TAG,
                "DashMuxer: 合轨完成 video=${videoFile.length()} audio=${audioFile.length()} " +
                    "out=${outFile.length()} ok=$ok"
            )
            ok
        }
    } catch (e: Throwable) {
        Log.e(TAG, "DashMuxer: 合轨失败 ${e.message}", e)
        false
    }
}
