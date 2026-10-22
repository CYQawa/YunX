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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 「动态照片」（Motion Photo）封装：把静图 JPEG 与一段动图视频合成**一个文件**。
 *
 * 用途：微博 / 抖音的动图（Live Photo）在数据里都是「静图 + 一段视频」两半，而安卓相册认的
 * 「会动的照片」是 Google 的 Motion Photo **单文件**格式 —— 静图 JPEG 后面直接追加 MP4，
 * 并在 JPEG 的 XMP 里声明容器条目（`Semantic=Primary` 静图 + `Semantic=MotionPhoto` 视频）。
 *
 * ★ 本文件的字段与字节结构**照抄真机产物**（realme / ColorOS 相机拍的动态照片，2026-10-22 逆向）：
 *   文件 = `JPEG(内含 APP1 XMP)` + 若干 0 填充 + `MP4`；
 *   XMP 里只给视频那一条 `Item:Length`，读取方按「文件末尾 − Length」回推视频起点 ——
 *   所以静图长度与中间的填充都不参与定位，我们省略填充也不影响。
 *   ★ 真机**只写新式字段**（`GCamera:MotionPhoto*` + `Container:Directory`），没有 `MicroVideo`
 *     那一套老字段；这里同样只写新式，别画蛇添足。
 *   ★ 真机还有 `Semantic=GainMap` 一项（Ultra HDR 的增益图，存在 JPEG 内部）。我们没有增益图，
 *     不写这一项，也不写 `hdrgm:` 命名空间。
 *   ★ `OpCamera:VideoLength` 在真机产物里 = 「追加 MP4 中最后一个 box（ColorOS 自己的 `comm`）
 *     之前的长度」；我们自己生成的视频没有那个 box，所以它等于追加 MP4 的总长，与容器里的
 *     MotionPhoto `Item:Length` 取同一个值。
 *
 * ★★ **只有 XMP 是不够的**（2026-10-22 实测）：只写 XMP 时**小米相册认、OPPO/realme 相册不认**
 *   （相册里就是一张静图）。OPPO 相册不看 XMP，而是看 **Exif 里的标记串 `oplus_8388608`** ——
 *   所以最终结构是：`SOI → APP1 XMP → APP1 Exif(暗号) → JPEG 图像数据 → EOI → MP4`，
 *   见 [OPPO_EXIF_HEX] 的说明。
 *
 * ★ 全程**不重编码**，只做字节搬运 —— 静图与视频都按原样写出去；视频不是 mp4 时由调用方先
 *   `DashMuxer.remuxToMp4` 换封装（微博的动图是 `.mov`）。
 * ★ iOS 不认这个格式（苹果的 Live Photo 是「静图 + 配对 MOV」两个文件），在 iPhone 上它只是普通图片。
 */
object MotionPhotoWriter {

    private const val TAG = "YunX"

    /** JPEG 里 XMP 段的固定标识（APP1 载荷开头，含结尾的 NUL） */
    private val XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * OPPO 相册识别「动态照片」的**暗号**：一段 116 字节的 Exif，把标记字符串 `oplus_8388608`
     * 写在 Exif 的 `UserComment`（0x9286）标签里。
     *
     * ★ 为什么必须有它（2026-10-22 实测教训）：只写 XMP 时小米相册认、**OPPO/realme 相册不认**
     *   （文件在相册里就是一张静图）。OPPO 不看 XMP，只看 Exif 里这个字符串 ——
     *   「少即是多，不需要完整的相机信息」。
     * ★ 字节取自可在 OPPO 相册正常识别的合成方案（`flashlab/motion-live-photo` 的 oppo Exif 模板）：
     *   结构 = `FF E1 0072` + `Exif\0\0` + 大端 TIFF（IFD0 四条：宽、高、ExifIFD 指针、方向；
     *   Exif 子 IFD 两条：UserComment、0x9208）+ 标记串。尺寸字段由 [oppoExif] 按静图回填。
     * ★ 已知限制：静图**自带 Exif** 时会变成两段 Exif —— 微博的静图没有 Exif，暂不处理；
     *   将来接入静图带 Exif 的平台时，这里要改成替换而不是插入。
     */
    private const val OPPO_EXIF_HEX =
        "FFE100724578696600004D4D002A0000000800040100000400000001000005A001010004000000010000043C" +
            "87690004000000010000003E011200030000000100000000000000000002928600020000000E0000005C" +
            "920800040000000100000000000000006F706C75735F3833383836303800"

    /**
     * 组装 OPPO 的识别 Exif：[width]/[height] 回填进模板的宽高字段（模板里是相机的样例尺寸）。
     *
     * @param width 静图宽（像素）；<=0 表示没解出来，保留模板原值
     */
    private fun oppoExif(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(OPPO_EXIF_HEX.length / 2) { index ->
            OPPO_EXIF_HEX.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        if (width > 0 && height > 0) {
            // 模板里这两个字段就是大端 32 位整数：IFD0 第 1 条的值（28..31）与第 2 条的值（40..43）
            bytes[28] = (width ushr 24).toByte()
            bytes[29] = (width ushr 16).toByte()
            bytes[30] = (width ushr 8).toByte()
            bytes[31] = width.toByte()
            bytes[40] = (height ushr 24).toByte()
            bytes[41] = (height ushr 16).toByte()
            bytes[42] = (height ushr 8).toByte()
            bytes[43] = height.toByte()
        }
        return bytes
    }

    /** 只读文件头拿静图尺寸（`inJustDecodeBounds` 不解码像素）；失败返回 0 表示未知 */
    private fun jpegSize(file: File): Pair<Int, Int> = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth to options.outHeight
    }.getOrDefault(0 to 0)

    /**
     * 合成动态照片：[stillJpeg]（静图，非 JPEG 会先转成 JPEG）→ [outFile]，尾部追加 [motion]（mp4）。
     *
     * 流式搬运（不整文件读进内存）：静图可能有几 MB、视频可达几十 MB。
     *
     * @return 成功与否；动图为空、或静图既不JPEG也解码不出来时返回 false，由调用方给出可见的错误
     */
    fun write(stillJpeg: File, motion: File, outFile: File): Boolean {
        if (motion.length() <= 0L) {
            Log.w(TAG, "MotionPhotoWriter: 动图资源为空")
            return false
        }
        // 静图必须是 JPEG：不是就先转一份（抖音的动图静图是 webp）
        val still = toJpegStill(stillJpeg, outFile) ?: return false
        val temporaryStill = still != stillJpeg
        try {
            // 只读前几个字节判断 JPEG 与 APP0，避免把整张图读进内存
            val head = ByteArray(8)
            val headRead = runCatching { FileInputStream(still).use { it.read(head) } }.getOrDefault(-1)
            if (headRead < 4 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) {
                Log.w(TAG, "MotionPhotoWriter: 静态图不是 JPEG（只读到 $headRead 字节）")
                return false
            }
            val insertAt = jfifEndOffset(head)
            val (width, height) = jpegSize(still)
            val xmp = xmpPacket(motion.length())
            val exif = oppoExif(width, height)
            return runCatching {
                FileOutputStream(outFile).use { out ->
                    FileInputStream(still).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        // ① SOI（以及可能存在的 APP0/JFIF）原样写出
                        var remaining = insertAt
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            remaining -= n
                        }
                        // ② 插入 XMP 段（APP1）—— 必须在静图剩余数据之前
                        out.write(app1Segment(xmp))
                        // ③ 紧接着插入 OPPO 的识别 Exif（APP1）：顺序照抄可用的合成方案（XMP 在前、Exif 在后）
                        out.write(exif)
                        // ④ 静图剩余部分
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                    // ⑤ 追加动图视频：读取方按「末尾 − XMP 里声明的长度」定位它
                    FileInputStream(motion).use { it.copyTo(out) }
                }
                val ok = outFile.length() > 0L
                Log.d(
                    TAG,
                    "MotionPhotoWriter: 合成完成 still=${still.length()} motion=${motion.length()} " +
                        "out=${outFile.length()} ok=$ok"
                )
                ok
            }.getOrElse {
                Log.e(TAG, "MotionPhotoWriter: 合成失败 ${it.message}")
                false
            }
        } finally {
            // 转换出来的临时静图用完就删（原始静图归调用方管）
            if (temporaryStill) runCatching { still.delete() }
        }
    }

    /**
     * 拿到可用的静图 JPEG：本来就是 JPEG 就原样返回；不是（**抖音的动图静图是 webp**）就解码后重编码。
     *
     * ★ Motion Photo 的静图只接受 JPEG/HEIC/AVIF，webp 不行（真机产物也都是 JPEG）。
     * ★ 只有静图不是 JPEG 时才多这一次编解码；动图视频始终原样搬运、不重编码。
     *
     * @return 可用的 JPEG 文件；返回的不是 [still] 本体时说明是临时产物，由 [write] 负责删除
     */
    private fun toJpegStill(still: File, outFile: File): File? {
        val head = ByteArray(4)
        val read = runCatching { FileInputStream(still).use { it.read(head) } }.getOrDefault(-1)
        if (read >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) return still
        val bitmap = runCatching { BitmapFactory.decodeFile(still.absolutePath) }.getOrNull()
        if (bitmap == null) {
            Log.w(TAG, "MotionPhotoWriter: 静图既不是 JPEG 也解码不出来，没法转成 JPEG")
            return null
        }
        val converted = File(outFile.parentFile, "still_converted.jpg")
        val ok = runCatching {
            FileOutputStream(converted).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }.getOrDefault(false)
        bitmap.recycle()
        if (!ok || converted.length() <= 0L) {
            Log.w(TAG, "MotionPhotoWriter: 静图转 JPEG 失败")
            runCatching { converted.delete() }
            return null
        }
        Log.d(
            TAG,
            "MotionPhotoWriter: 静图不是 JPEG（${still.length()} 字节），已转成 JPEG（${converted.length()} 字节）"
        )
        return converted
    }

    /**
     * 插入点：有 APP0(JFIF) 就插在它**之后**（JFIF 规范要求 APP0 紧跟 SOI），否则紧跟 SOI。
     *
     * ★ 这里踩过一次坑（2026-10-22）：APP0 段总长 = 标记 2 字节 + 长度字段的值（该值**含长度字段自身**），
     *   而长度字段从文件偏移 4 开始，所以**下一段起点 = 4 + 长度值**（不是 `2 + 长度值`）。
     *   少算 2 字节就会把 XMP/Exif 插进 JFIF 段内部：JFIF 的声明长度把这两个 APP1 一起"吞掉"，
     *   相册读不到 Exif 里的 OPPO 暗号 —— 表现就是「产物结构看着像对、相册却当静图」。
     *
     * @param head 文件头部若干字节（至少 6 字节才能读到 APP0 的长度字段）
     */
    private fun jfifEndOffset(head: ByteArray): Int {
        if (head.size < 6) return 2
        if (head[2] != 0xFF.toByte() || head[3] != 0xE0.toByte()) return 2
        val segmentLength = ((head[4].toInt() and 0xFF) shl 8) or (head[5].toInt() and 0xFF)
        return 4 + segmentLength
    }

    /** JPEG 段：`FF E1` + 2 字节大端长度（**含长度字段自身**）+ 标识 + XMP */
    private fun app1Segment(xmp: ByteArray): ByteArray {
        val payloadSize = XMP_IDENTIFIER.size + xmp.size
        val length = payloadSize + 2
        val segment = ByteArray(4 + payloadSize)
        segment[0] = 0xFF.toByte()
        segment[1] = 0xE1.toByte()
        segment[2] = ((length shr 8) and 0xFF).toByte()
        segment[3] = (length and 0xFF).toByte()
        System.arraycopy(XMP_IDENTIFIER, 0, segment, 4, XMP_IDENTIFIER.size)
        System.arraycopy(xmp, 0, segment, 4 + XMP_IDENTIFIER.size, xmp.size)
        return segment
    }

    /**
     * XMP 包正文：字段集与真机产物一致（去掉我们没有的 GainMap 项）。
     *
     * @param videoLength 追加 MP4 的字节数（真机这里等于「`comm` box 之前的长度」，我们没有该 box）
     */
    private fun xmpPacket(videoLength: Long): ByteArray {
        val xml = buildString {
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
            append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            append("  <rdf:Description rdf:about=\"\"\n")
            append("    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n")
            append("    xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"\n")
            append("    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n")
            append("    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n")
            append("   GCamera:MotionPhoto=\"1\"\n")
            append("   GCamera:MotionPhotoVersion=\"1\"\n")
            append("   GCamera:MotionPhotoPresentationTimestampUs=\"0\"\n")
            append("   OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"0\"\n")
            append("   OpCamera:MotionPhotoOwner=\"oplus\"\n")
            append("   OpCamera:OLivePhotoVersion=\"2\"\n")
            append("   OpCamera:VideoLength=\"$videoLength\">\n")
            append("   <Container:Directory>\n")
            append("    <rdf:Seq>\n")
            append("     <rdf:li rdf:parseType=\"Resource\">\n")
            append("      <Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" ")
            append("Item:Length=\"0\" Item:Padding=\"0\"/>\n")
            append("     </rdf:li>\n")
            append("     <rdf:li rdf:parseType=\"Resource\">\n")
            append("      <Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" ")
            append("Item:Length=\"$videoLength\"/>\n")
            append("     </rdf:li>\n")
            append("    </rdf:Seq>\n")
            append("   </Container:Directory>\n")
            append("  </rdf:Description>\n")
            append(" </rdf:RDF>\n")
            append("</x:xmpmeta>")
        }
        return xml.toByteArray(Charsets.UTF_8)
    }
}
