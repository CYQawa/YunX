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

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private const val TAG = "YunX-DL"

/**
 * 保存目的地：**直接向最终位置流式写入**，不再经过私有缓存副本。
 *
 * 大文件下载由此把峰值存储占用从「分片 + 合并副本 + 公共副本」的 3 份降到 ≈ 1 份：
 * 上层可边写边删分片（见 [ChunkDownloader.mergeChunksToStream]），
 * 6GB 级文件在只预留约 1 倍空间的设备上也能保存成功。
 *
 * 生命周期：open() → 写入 → 校验 → commit()；中途失败/取消必须 abort() 删除半成品
 * （MediaStore 的 IS_PENDING 半成品在「下载」里不可见，却真实占空间）。
 */
class DownloadDestination internal constructor(
    /** 成功后返回上层、用于展示/删除的标识（MediaStore uri / SAF 文档 uri / 文件绝对路径） */
    val path: String,
    private val openStream: () -> OutputStream?,
    private val onCommit: () -> Unit,
    private val onAbort: () -> Unit
) {
    /** 打开目标输出流；失败返回 null，由调用方按失败处理（此时应 abort） */
    fun open(): OutputStream? = runCatching { openStream() }.getOrNull()

    /** 写入完成且大小校验通过后调用（MediaStore 置 IS_PENDING=0；其余实现为 no-op） */
    fun commit() {
        runCatching { onCommit() }.onFailure { Log.e(TAG, "提交下载目标失败: ${it.message}") }
    }

    /** 失败/取消：删除半成品，避免残留不可见的占位数据 */
    fun abort() {
        runCatching { onAbort() }.onFailure { Log.e(TAG, "清理下载目标失败: ${it.message}") }
    }
}

/**
 * 完成文件保存到公共 Download 目录：
 * - Android 10+（Q）：MediaStore.Downloads，无需存储权限；
 * - Android 9-：Environment.getExternalStoragePublicDirectory + WRITE_EXTERNAL_STORAGE；
 * - 自定义目录：SAF 文档树。
 * 幽灵文件（文件已删但 MediaStore 残留）导致同名 insert 失败时，自动加时间戳防重保存。
 */
object DownloadSaver {

    /** 大文件拷贝缓冲：1MB（默认 copyTo 8KB 对 GB 级文件是灾难，IO 次数过多导致保存极慢） */
    private const val COPY_BUFFER_SIZE = 1 * 1024 * 1024

    /** 遗留半成品清理的年龄门槛（秒）：远大于任何一次正常保存的耗时，避免误删正在写入的记录 */
    private const val PENDING_PURGE_AGE_SEC = 3600L

    /**
     * 打开「直接向最终位置写入」的保存目的地（大文件下载主路径）。
     *
     * 与 [save] 的区别：不接收源文件、不自己做拷贝，由调用方边合并分片边写入、边删分片。
     * 三条保存路径只在「选名/建目标」阶段换候选名；**写入阶段的失败一律由调用方 abort() 处理**，
     * 绝不在写失败后换名重写整文件（旧实现在磁盘写满时会把整文件重复写多遍，残留多份不可见半成品）。
     *
     * @param fileName 可为**相对路径**（如 "文件夹A/子/文件.mp4"，用于下载整个文件夹保持目录结构）；
     *                 纯文件名时保存到根目录。
     * @param targetDirUri 自定义保存目录（SAF tree Uri，content://...）；null 时用系统默认 Download
     * @return 保存目的地；路径非法/无可用目标时返回 null
     */
    fun openDestination(
        context: Context,
        fileName: String,
        targetDirUri: String? = null
    ): DownloadDestination? {
        val safePath = DownloadPathPolicy.sanitize(
            fileName,
            fallbackName = "download_${System.currentTimeMillis()}"
        ) ?: run {
            Log.e(TAG, "拒绝不安全的下载相对路径")
            return null
        }
        val safeName = safePath.fileName
        val safeDir = safePath.relativeDirectory
        // 自定义 SAF 目录：优先走系统文档树（适配 Android 10/11+ 分区存储与 Android 9-，无需额外存储权限）
        if (!targetDirUri.isNullOrBlank()) {
            return safDestination(context, safeName, safeDir, targetDirUri)
        }
        // 默认目录：Android 10+ 优先 MediaStore；失败则回退传统文件路径（Android 9- 可用）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreDestination(context, safeName, safeDir)?.let { return it }
            Log.e(TAG, "MediaStore 目标创建失败，回退传统路径：$safeDir/$safeName")
        }
        return legacyDestination(safeName, safeDir)
    }

    /**
     * 保存文件到下载目录（小文件/一次性拷贝场景，如 HLS 转码流）。
     * 内部即 [openDestination] + 拷贝 + commit，失败自动 abort 半成品。
     * @return 保存成功后的标识（MediaStore uri 字符串 / SAF 文档 uri / 文件绝对路径）；失败返回 null
     */
    fun save(context: Context, fileName: String, source: File, targetDirUri: String? = null): String? {
        val dest = openDestination(context, fileName, targetDirUri) ?: return null
        return try {
            val out = dest.open() ?: throw IllegalStateException("无法打开下载目标")
            out.use { o -> source.inputStream().use { it.copyTo(o, COPY_BUFFER_SIZE) } }
            dest.commit()
            dest.path
        } catch (e: Exception) {
            Log.e(TAG, "保存异常（$fileName）: ${e.message}")
            dest.abort()
            null
        }
    }

    /**
     * SAF 文档树目标（自定义下载目录）：
     * - tree uri 由用户经系统「选择文件夹」弹窗授权（takePersistableUriPermission 持久化）；
     * - 相对路径子目录逐级查找/创建（MIME_TYPE_DIR）；
     * - 仅在创建文档失败/重名时换时间戳候选名。
     */
    private fun safDestination(
        context: Context,
        fileName: String,
        subDir: String,
        treeUriString: String
    ): DownloadDestination? = runCatching {
        val resolver = context.contentResolver
        val treeUri = Uri.parse(treeUriString)
        // tree URI 不能直接作为 createDocument 的父目录（Android 10 抛 Invalid URI）：
        // 先取根文档 id，构建根 document URI 作为初始目录
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        var dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        // 定位（或创建）目标目录：相对路径逐级解析
        if (subDir.isNotBlank()) {
            for (part in subDir.split('/').filter { it.isNotBlank() }) {
                dirUri = getOrCreateSafDir(resolver, treeUri, dirUri, part) ?: return@runCatching null
            }
        }
        for (candidate in nameCandidates(fileName)) {
            val docUri = runCatching {
                DocumentsContract.createDocument(resolver, dirUri, mimeOf(candidate), candidate)
            }.onFailure { Log.e(TAG, "SAF 创建文档失败（$candidate）: ${it.message}") }.getOrNull() ?: continue
            return@runCatching DownloadDestination(
                path = docUri.toString(),
                openStream = { resolver.openOutputStream(docUri) },
                onCommit = {},
                onAbort = { resolver.delete(docUri, null, null) }
            )
        }
        null
    }.onFailure { Log.e(TAG, "SAF 目标创建异常（$fileName）: ${it.message}") }.getOrNull()

    /**
     * 在父文档树下查找/创建指定名称的子目录，返回其文档 uri。
     * @param treeUri 原始 tree Uri（供 buildChildDocumentsUriUsingTree / buildDocumentUriUsingTree 使用）
     * @param parentDocUri 当前父目录的 document Uri（供 createDocument 使用）
     */
    private fun getOrCreateSafDir(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
        name: String
    ): Uri? {
        // 先查已存在的子目录：children 查询基于 tree Uri + 父目录文档 id
        val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val display = cursor.getString(1)
                val mime = cursor.getString(2)
                if (display == name && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
        }
        // 不存在则创建（父目录必须是 document Uri）
        return DocumentsContract.createDocument(
            resolver, parentDocUri, DocumentsContract.Document.MIME_TYPE_DIR, name
        )
    }

    /** 从 SAF tree uri 提取可读目录名（如 primary:Download/MyFolder → "Download/MyFolder"） */
    fun safDirDisplay(uriString: String): String {
        return runCatching {
            val treeId = DocumentsContract.getTreeDocumentId(Uri.parse(uriString))
            treeId.substringAfterLast(':').replace("%2F", "/").replace("%2f", "/")
                .ifBlank { "自定义目录" }
        }.getOrDefault("自定义目录")
    }

    /**
     * MediaStore.Downloads 目标（Android 10+ 默认下载目录）：
     * 1. 从原名开始尝试，同路径同名时换时间戳候选名，绝不预删用户文件；
     * 2. 以 IS_PENDING=1 插入（写入期间对外不可见），写入完成后 commit 置 0；
     * 3. 写入失败由调用方 abort() 删除该记录 —— 否则会残留「看不见却占空间」的半成品。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreDestination(
        context: Context,
        fileName: String,
        subDir: String
    ): DownloadDestination? {
        val resolver = context.contentResolver
        val relativePath = if (subDir.isBlank()) {
            Environment.DIRECTORY_DOWNLOADS
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/$subDir"
        }
        for (candidate in nameCandidates(fileName)) {
            try {
                if (mediaStoreNameExists(resolver, candidate, relativePath)) continue
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, candidate)
                    put(MediaStore.Downloads.MIME_TYPE, mimeOf(candidate))
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                return DownloadDestination(
                    path = uri.toString(),
                    openStream = { resolver.openOutputStream(uri) },
                    onCommit = {
                        resolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                            null, null
                        )
                    },
                    onAbort = { resolver.delete(uri, null, null) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore 目标创建异常（$candidate）: ${e.message}")
            }
        }
        return null
    }

    /** 传统文件路径目标（Android 9-）：createNewFile 原子占位，占用失败则换候选名 */
    private fun legacyDestination(fileName: String, subDir: String): DownloadDestination? = runCatching {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        val destDir = (if (subDir.isBlank()) dir else File(dir, subDir)).canonicalFile
        if (destDir != dir && !DownloadPathPolicy.isContained(dir, destDir)) {
            throw SecurityException("下载目录越界")
        }
        if (!destDir.exists()) destDir.mkdirs()
        for (candidate in nameCandidates(fileName)) {
            val dest = File(destDir, candidate).canonicalFile
            if (!DownloadPathPolicy.isContained(dir, dest) || dest.exists()) continue
            if (!dest.createNewFile()) continue
            return@runCatching DownloadDestination(
                path = dest.absolutePath,
                openStream = { FileOutputStream(dest) },
                onCommit = {},
                onAbort = { dest.delete() }
            )
        }
        null
    }.onFailure { Log.e(TAG, "传统路径目标创建异常（$fileName）: ${it.message}") }.getOrNull()

    /** 候选文件名：原名 → 时间戳防重名（base.apk → base_20260812165000.apk → base_..._2.apk） */
    private fun nameCandidates(fileName: String): List<String> = buildList {
        add(fileName)
        repeat(3) { i -> add(timestampedName(fileName, i)) }
    }

    /** 在文件名扩展名前加时间戳防重：base.apk → base_20260812165000.apk */
    private fun timestampedName(fileName: String, attempt: Int): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val ts = System.currentTimeMillis()
        return if (attempt == 0) "${base}_$ts$ext" else "${base}_${ts}_${attempt + 1}$ext"
    }

    /** 同路径同名对象存在时换一个候选名，绝不删除既有内容。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreNameExists(
        resolver: ContentResolver,
        fileName: String,
        relativePath: String
    ): Boolean = runCatching {
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
            val projection = arrayOf(MediaStore.Downloads._ID)
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(fileName, relativePath),
                null
            )?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        }.onFailure { Log.e(TAG, "查询 MediaStore 同名记录失败: ${it.message}") }
            .getOrDefault(true)

    /**
     * 清理本应用遗留的「未完成 MediaStore 记录」（IS_PENDING=1）。
     *
     * 历史版本在保存失败（如 ENOSPC）时不删半成品，还会计时器式换名重写整文件，
     * 于是留下一批在文件管理器和「下载」里都**不可见**、却真实占空间的记录，
     * 把设备剩余空间吃到 0，导致之后任何下载都必然 ENOSPC。
     * 只删本应用自己的记录（分区存储保证跨应用不可见/不可删），且只删 1 小时前的，
     * 不会碰到本次运行中正在写入的目标。
     *
     * @return 实际删除的记录数
     */
    fun purgeOwnPendingFiles(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val resolver = context.contentResolver
        val cutoffSec = System.currentTimeMillis() / 1000 - PENDING_PURGE_AGE_SEC
        return runCatching {
            val targets = mutableListOf<Uri>()
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.IS_PENDING}=1 AND ${MediaStore.Downloads.DATE_ADDED}<?",
                arrayOf(cutoffSec.toString()),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    targets.add(
                        ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(0)
                        )
                    )
                }
            }
            var deleted = 0
            targets.forEach { uri ->
                deleted += runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
            }
            if (deleted > 0) Log.d(TAG, "清理未完成的下载残留记录 $deleted 条")
            deleted
        }.onFailure { Log.e(TAG, "清理未完成下载记录失败: ${it.message}") }.getOrDefault(0)
    }

    /**
     * 删除已保存的本地文件（配合任务删除）。
     * @param savePath 保存时返回的 MediaStore uri 字符串 / SAF 文档 uri / 文件绝对路径
     * @return 是否删除成功（false 表示未找到或删除失败）
     */
    fun delete(context: Context, savePath: String): Boolean {
        if (savePath.isBlank()) return false
        return runCatching {
            if (savePath.startsWith("content://")) {
                val uri = Uri.parse(savePath)
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    deleteSafDocument(context, uri)
                } else {
                    context.contentResolver.delete(uri, null, null) > 0
                }
            } else {
                File(savePath).delete()
            }
        }.onFailure {
            Log.e(TAG, "删除本地文件失败: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * SAF 文档删除：多级 fallback，兼容国产 ROM 对 tree 授权子文档删除的权限/实现差异。
     * ① resolver.delete（标准） → ② deleteDocument（重试） → ③ 还原 tree Uri 逐级查找删除。
     */
    private fun deleteSafDocument(context: Context, docUri: Uri): Boolean {
        // ① 标准删除
        if (runCatching { context.contentResolver.delete(docUri, null, null) > 0 }.getOrDefault(false)) {
            return true
        }
        // ② DocumentsContract.deleteDocument 重试
        if (runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri) }.getOrDefault(false)) {
            return true
        }
        // ③ 还原 tree Uri（持久授权域），沿相对路径逐级 findFile 后删除
        return runCatching {
            val segments = docUri.pathSegments
            val treeIdx = segments.indexOf("tree")
            if (treeIdx < 0 || segments.size < treeIdx + 2) return@runCatching false
            // docUri: /tree/{treeId}/document/{fileDocId} → treeUri: /tree/{treeId}
            val treeUri = docUri.buildUpon().path("/" + segments.subList(0, treeIdx + 2).joinToString("/")).build()
            val treeDocId = Uri.decode(segments[treeIdx + 1])
            val fileDocId = DocumentsContract.getDocumentId(docUri)
            if (!fileDocId.startsWith(treeDocId)) return@runCatching false
            val relParts = fileDocId.removePrefix(treeDocId).trimStart('/').split('/').filter { it.isNotBlank() }
            if (relParts.isEmpty()) return@runCatching false
            val resolver = context.contentResolver
            var parentDocId = treeDocId
            for ((i, part) in relParts.withIndex()) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                var foundId: String? = null
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(1) == part) {
                            foundId = c.getString(0)
                            break
                        }
                    }
                } ?: return@runCatching false
                val id = foundId ?: return@runCatching false
                if (i == relParts.lastIndex) {
                    // 最后一层即目标文件：基于 tree 构造文档 uri 删除
                    val target = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    return@runCatching runCatching { resolver.delete(target, null, null) > 0 }
                        .getOrElse { DocumentsContract.deleteDocument(resolver, target) }
                }
                parentDocId = id
            }
            false
        }.getOrDefault(false)
    }

    private fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "zip", "rar", "7z" -> "application/zip"
            "mp4", "mkv", "mov", "avi", "webm" -> "video/mp4"
            "mp3", "wav", "flac", "aac" -> "audio/mpeg"
            "jpg", "jpeg", "png", "gif", "webp" -> "image/jpeg"
            "txt", "md", "log" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
