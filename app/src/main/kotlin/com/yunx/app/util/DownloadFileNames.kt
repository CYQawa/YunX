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

/**
 * 下载文件名的归一化（纯函数，可单测；issue #215）。
 *
 * 文件名不是我们起的：它来自服务端 `Content-Disposition`、引擎落盘名，或某条链路的 MIME 兜底。
 * 这里只修**已经被搞坏**的两种形态，别的一概原样返回 —— 归一化只负责把扩展名救回来，
 * 不负责重命名、不做任何"看起来更好"的加工：
 *
 * ① 同名冲突后缀被顶到扩展名后面：`x.apk (1)`。
 *    正规的防重名是 `x (1).apk`。前者系统/安装器按**末尾后缀**认文件类型，
 *    看到 `.apk (1)` 就不认，用户看到的现象就是"下载的 apk 打不开"。→ 把 `(n)` 挪回扩展名前。
 * ② MIME 兜底扩展名叠在真扩展名后面：`x.md.txt`。
 *    从 GitHub 下 `.md` 会变成 `.md.txt`，因为某条链路"认不出类型"时补了 `.txt`。
 *    → 只对**确定是文本类**的内层扩展名动手（白名单），避免误伤 `x.zip.txt` 这种真的想保留 `.txt` 的。
 *
 * ★ 故意不动 [com.yunx.app.data.download.DownloadSaver.nameCandidates] 里的 `_时间戳` 命名：
 *   那是断点续传/防重名的既有口径，改了会让老任务的 savePath 对不上，等于把已完成的任务变成幽灵。
 */
object DownloadFileNames {

    /**
     * 末尾的冲突序号：`x.apk (1)` / `x.apk（1）`（全角括号、全角空格都见过）。
     * 只认 1~3 位数字：`Movie (2026)` 这种年份结尾的名字不该被当成冲突序号。
     */
    private val CONFLICT_SUFFIX_REGEX = Regex("""^(.+?)[\s\u3000]*[（(](\d{1,3})[)）]$""")

    /** 两层扩展名且最外层是 `.txt`：`x.md.txt` → stem=`x`、inner=`md` */
    private val TXT_OVER_TEXT_REGEX =
        Regex("""^(.+)\.([A-Za-z][A-Za-z0-9]{0,7})\.txt$""", RegexOption.IGNORE_CASE)

    /**
     * 内层扩展名属于这一组时，才认定外层 `.txt` 是兜底补出来的。
     * 宁可少修（没列进来的就保留 `.txt`），也不能误伤 `x.zip.txt` / `x.exe.txt` 这类
     * 「本来就是文本文件，只是名字里带个其它后缀」的情况。
     */
    private val TEXT_EXTENSIONS = setOf(
        "md", "markdown", "txt", "text", "json", "jsonl", "log", "ini", "cfg", "conf", "properties",
        "csv", "tsv", "yml", "yaml", "xml", "html", "htm", "css", "scss", "less",
        "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue",
        "kt", "kts", "java", "py", "rb", "php", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs",
        "sh", "bash", "zsh", "bat", "ps1", "sql", "toml", "gradle", "pro", "gitignore", "lock"
    )

    /**
     * 归一化下载文件名。
     *
     * @param raw 上游给的文件名（库里/任务里要用的那个）
     * @return 修好的名字；没有可修的形态时返回原值（只做首尾空白裁剪）
     */
    fun normalize(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) return raw
        // 先剥多余的 .txt 再看冲突序号：`x.md (1).txt` 这种双症状也能走到第二步
        val stripped = stripRedundantTxt(name)
        return restoreConflictSuffix(stripped).ifEmpty { name }
    }

    /** 去掉叠在文本类扩展名后面的 `.txt`；不是这种形态就原样返回 */
    private fun stripRedundantTxt(name: String): String {
        val match = TXT_OVER_TEXT_REGEX.matchEntire(name) ?: return name
        val stem = match.groupValues[1]
        val inner = match.groupValues[2]
        if (inner.lowercase() !in TEXT_EXTENSIONS) return name
        // 保留内层扩展名的原始大小写：`x.MD.txt` → `x.MD`
        return "$stem.$inner"
    }

    /** 把末尾的 `(n)` 挪到最后一个扩展名之前；挪不了（没扩展名、扩展名里带空格等）就原样返回 */
    private fun restoreConflictSuffix(name: String): String {
        val match = CONFLICT_SUFFIX_REGEX.matchEntire(name) ?: return name
        val base = match.groupValues[1].trimEnd()
        val index = match.groupValues[2]
        val dot = base.lastIndexOf('.')
        // 没有扩展名 / 以点结尾：无处可挪，保持原样（`备份 (1)` 这种名字本来就是对的）
        if (dot <= 0 || dot == base.length - 1) return name
        val stem = base.substring(0, dot)
        val ext = base.substring(dot + 1)
        // 扩展名里还有空格/括号，说明前面已经是畸形名（`x.apk (1) (2)`），别越修越乱
        if (stem.isBlank() || ext.any { it.isWhitespace() || it == '(' || it == '（' }) return name
        return "$stem ($index).$ext"
    }
}
