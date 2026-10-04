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

package com.yunx.app.data.gopeed

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.gopeed.libgopeed.InvokeResultListener
import com.gopeed.libgopeed.Libgopeed
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.LogRedactor
import com.yunx.app.util.PermissionState
import com.yunx.app.util.StorageDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * Gopeed 内置下载引擎（进程内嵌 gomobile 编译出来的 libgojni.so）。
 *
 * 与项目其它下载代码的区别：这里不是 Kotlin 实现的下载器，而是 Gopeed 自己那套 Go 核心
 * （HTTP/HLS/BT/ed2k 协议 + 多连接分片 + 断点续传），通过 gomobile 生成的 Java 桥接类直接调用；
 * 所有 REST 路由都在本进程内分发（配置里的 apiEnable=false ⇒ 不监听任何 TCP 端口）。
 *
 * 引擎文件来源：用户在设置页导入 libgopeed-<abi>.aar，本类负责把包内 jni/<abi>/libgojni.so
 * 解到 filesDir/gopeed/lib/ 后 System.load。之所以不把它打进 APK，是因为这个 .so 有 56 MB
 * （静态链接了整个 Go 运行时），而 Java 桥接类只有 12 KB（已作为 app/libs/gopeed-classes.jar
 * 编译进 APK，无需运行时生成 dex；该 jar 由 tools/patch-gopeed-classes.py 从官方 AAR 生成，
 * 补丁内容见 loadLibrary 的注释）。
 *
 * 调用约定：本类所有方法都会阻塞（invoke 内部等引擎回调），必须在 IO 线程调用，不要在 UI 线程直接调。
 * 排查：全链路打点，logcat 过滤 GopeedEngine（失败路径一律 Log.e 并附带排查提示）。
 */
object GopeedEngine {

    /** 引擎状态：未导入 / 已导入未启动 / 运行中 */
    enum class State { NOT_INSTALLED, INSTALLED, RUNNING }

    private const val DIR_NAME = "gopeed"
    private const val SO_NAME = "libgojni.so"

    /** 系统「外部存储」provider 的 authority：只有它给的 SAF tree Uri 才能反解出真实路径 */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** logcat 过滤用：adb logcat -s GopeedEngine（导入/加载/启动/调用 全链路打点） */
    private const val TAG = "GopeedEngine"

    /** AAR 内可能存在的 ABI 目录名（按 Build.SUPPORTED_ABIS 顺序挑第一个命中的） */
    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** 进程内调用请求号（引擎按此号回调结果，只用于配对，单调递增即可） */
    private val requestId = AtomicLong(1L)

    private val _state = MutableStateFlow(State.NOT_INSTALLED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 最近一次失败原因（原样保留引擎/系统给的错误文本，便于真机排查） */
    @Volatile
    var lastError: String? = null
        private set

    /** 引擎 API 端口；apiEnable=false 时无监听器，为 0 */
    @Volatile
    var port: Int = 0
        private set

    /** 本进程是否已经 System.load 过引擎库（dlopen 无法卸载，重复导入需重启应用才生效） */
    @Volatile
    private var loaded = false

    /** 引擎库文件（导入后才有） */
    fun soFile(context: Context): File = File(File(context.filesDir, "$DIR_NAME/lib"), SO_NAME)

    fun isInstalled(context: Context): Boolean = soFile(context).isFile

    /**
     * 把内存里的状态与真实文件对齐一次。
     *
     * `_state` 只在导入 / 启动 / 停止 / 卸载时被写过，进程重启后它一律是 `NOT_INSTALLED`；
     * 而**选内置下载器时启动流程根本不会去碰引擎**（见 YunXApp），于是内核明明还在、界面却显示
     * 「未导入内核」。`state` 的读者都要先调一次这个（引擎页进页面时、应用启动时）。
     * 运行中的状态不动；只做一次 `stat`，很轻。
     */
    fun syncInstalledState(context: Context) {
        if (_state.value == State.RUNNING) return
        val next = if (isInstalled(context)) State.INSTALLED else State.NOT_INSTALLED
        if (_state.value == next) return
        Log.d(TAG, "状态同步：${_state.value} → $next")
        _state.value = next
    }

    /**
     * 引擎的下载目录（必须是真实文件系统路径——Gopeed 写不了 SAF 的 `content://` 目录）。
     *
     * 优先级：**用户自定义目录**（`SettingsRepository.engineDownloadDir`，设置页里 SAF 反解或手输，
     * 见 Agent.md §3.33）→ 公共 `Download/`（`StorageDirs.defaultDownloadDir()`，与内置下载器同一默认口径：
     * Android 11+ 需要「所有文件访问」，Android 9- 需要 `WRITE_EXTERNAL_STORAGE`，Android 10 由 manifest 的
     * `requestLegacyExternalStorage` 回到旧模式 + 同一运行时权限）→ 应用外部私有目录（免权限，
     * 但 Android 11+ 用户自己也看不到）。
     *
     * 可写性用「试写探针」判断，不按系统版本推断权限：各 ROM 对 legacy / 分区存储的处理并不一致。
     * 自定义目录不可写时（SD 卡拔了、权限被撤销）**记日志并退回默认目录**：每个任务都失败比换个目录更糟。
     */
    fun resolveDownloadDir(context: Context): File {
        val custom = SettingsRepository(context).engineDownloadDir.trim()
        if (custom.isNotEmpty()) {
            val dir = File(custom)
            if (canWrite(dir)) return dir
            Log.e(
                TAG,
                "自定义下载目录不可写，退回默认目录：custom=$custom " +
                    "allFilesAccess=${PermissionState.allFilesAccessGranted()} " +
                    "→ default=${StorageDirs.defaultDownloadPath()}"
            )
        }
        val publicDir = StorageDirs.defaultDownloadDir()
        if (canWrite(publicDir)) return publicDir
        val privateDir = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)
        Log.e(
            TAG,
            "公共下载目录不可写，退回私有目录：public=${publicDir.absolutePath} " +
                "allFilesAccess=${PermissionState.allFilesAccessGranted()} " +
                "allFilesRequired=${PermissionState.allFilesAccessRequired()} → private=${privateDir.absolutePath}"
        )
        return privateDir
    }

    /**
     * 把用户给的目录路径准备成「引擎真的能写」的目录：建目录 + 试写探针。
     *
     * 失败抛 `IllegalArgumentException`，**消息直接给用户看**（引擎页与设置页都原样提示）。
     * 只接受绝对路径：引擎写不了 `content://`，也写不了相对路径。
     */
    fun prepareDownloadDir(path: String): File {
        val trimmed = path.trim()
        require(trimmed.startsWith("/")) { "请输入绝对路径（以 / 开头）" }
        val dir = File(trimmed)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalArgumentException("建不出这个目录：${dir.absolutePath}（检查路径是否正确、上级目录在不在）")
        }
        if (!canWrite(dir)) {
            val needAllFiles =
                PermissionState.allFilesAccessRequired() && !PermissionState.allFilesAccessGranted()
            val hint = if (needAllFiles) "（先去授予「所有文件访问」）" else ""
            throw IllegalArgumentException("这个目录不可写：${dir.absolutePath}$hint")
        }
        Log.d(TAG, "自定义下载目录可用：${dir.absolutePath} ${describeDir(dir)}")
        return dir
    }

    /**
     * 从 SAF 选的 tree Uri 反解真实文件系统路径（拿不到返回 null）。
     *
     * 只有系统「文件」应用里代表**本机存储**的位置才能反解：它们的 documentId 形如
     * `primary:Download/YunX`（主存储 → `/storage/emulated/0/Download/YunX`）或
     * `1A2B-3C4D:xxx`（SD 卡 / OTG，卷名就是那个 UUID → `/storage/1A2B-3C4D/xxx`）。
     * 第三方 provider（网盘、Google Drive 之类）的 documentId 不对应任何真实路径，一律返回 null ——
     * 调用方要退到手输路径，别把拿到的字符串当路径用。
     */
    @Suppress("DEPRECATION")
    fun realPathFromTreeUri(uri: Uri): File? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val index = docId.indexOf(':')
        if (index <= 0) return null
        val volume = docId.substring(0, index)
        val relative = docId.substring(index + 1).trim('/')
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume")
        }
        return if (relative.isEmpty()) root else File(root, relative)
    }

    /** 目录可写性说明（进日志用：排查"为什么退回了私有目录"时一眼能看出缺哪个权限） */
    private fun describeDir(dir: File): String =
        "exists=${dir.isDirectory} canWrite=${runCatching { dir.canWrite() }.getOrDefault(false)}"

    /** 这个目录现在是否真的能写：建目录 + 写一个探针文件再删掉 */
    private fun canWrite(dir: File): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val probe = File(dir, ".yunx_write_probe")
        return runCatching {
            probe.writeText("ok")
            probe.delete()
        }.isSuccess
    }

    /**
     * 导入 AAR：从 SAF Uri 流式解出本机 ABI 的 libgojni.so。
     * 只写出解压后的 .so，不落地保存 24 MB 的原始 AAR。
     *
     * @return 解出的字节数
     */
    fun installFromAar(context: Context, uri: Uri): Long {
        if (_state.value == State.RUNNING) throw IllegalStateException("请先停止引擎，再导入新的引擎文件")
        val abi = preferredAbi()
        val so = soFile(context)
        val dir = so.parentFile ?: throw IllegalStateException("引擎目录不可用")
        Log.d(TAG, "导入开始：uri=${LogRedactor.url(uri)} abi=$abi supportedAbis=${Build.SUPPORTED_ABIS.joinToString()} 目标=${so.absolutePath}")
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.e(TAG, "导入失败：无法创建目录 ${dir.absolutePath}（可写=${dir.canWrite()}）")
            throw IllegalStateException("无法创建目录：${dir.absolutePath}")
        }

        val tmp = File(dir, "$SO_NAME.tmp")
        var written = 0L
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) {
            Log.e(TAG, "导入失败：contentResolver 打不开所选文件 uri=$uri")
            throw IllegalStateException("无法读取所选文件")
        }
        input.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry = zip.nextEntry
                var found = false
                val entries = ArrayList<String>()
                while (entry != null) {
                    if (entries.size < 24) entries.add(entry.name)
                    // 精确匹配 jni/<abi>/libgojni.so（AAR 里只有这一个 .so）
                    if (!entry.isDirectory && entry.name == "jni/$abi/$SO_NAME") {
                        FileOutputStream(tmp).use { out -> written = zip.copyTo(out) }
                        found = true
                        break
                    }
                    entry = zip.nextEntry
                }
                if (!found) {
                    Log.e(TAG, "导入失败：AAR 里没有 jni/$abi/$SO_NAME，实际条目=$entries")
                    throw IllegalStateException("这个 AAR 里没有 jni/$abi/$SO_NAME（本机首选 ABI：$abi）")
                }
            }
        }
        if (written <= 0L) {
            Log.e(TAG, "导入失败：解出的引擎文件是空的（tmp=${tmp.absolutePath}）")
            throw IllegalStateException("解出的引擎文件是空的")
        }

        if (so.exists() && !so.delete()) {
            Log.e(TAG, "导入失败：无法删除旧引擎文件 ${describe(so)}")
            throw IllegalStateException("无法覆盖旧的引擎文件：${so.absolutePath}")
        }
        if (!tmp.renameTo(so)) {
            Log.e(TAG, "导入失败：改名失败 tmp=${tmp.absolutePath} → ${so.absolutePath}")
            throw IllegalStateException("无法写入引擎文件：${so.absolutePath}")
        }
        // 尽量以只读文件加载：Android 10+ 对「由可写 fd 映射出的可执行代码」有额外限制
        so.setReadable(true, true)
        so.setWritable(false, false)

        if (loaded) {
            // dlopen 进来的库没法卸载，只能提示重启
            lastError = "引擎已在本进程中加载过，新文件需重启应用后才会生效"
        } else {
            lastError = null
        }
        _state.value = State.INSTALLED
        Log.d(TAG, "导入完成：写出 $written 字节，${describe(so)}")
        return written
    }

    /** 删除导入的引擎文件（本进程已加载的库仍然有效，直至进程结束） */
    fun uninstall(context: Context) {
        if (_state.value == State.RUNNING) throw IllegalStateException("请先停止引擎")
        val so = soFile(context)
        if (so.exists() && !so.delete()) throw IllegalStateException("删除失败：${so.absolutePath}")
        if (!loaded) _state.value = State.NOT_INSTALLED
    }

    /**
     * 启动引擎（幂等：已在运行直接返回端口）。
     *
     * @param downloadDir 下载保存目录。必须是真实文件系统路径——Gopeed 是原生进程内核心，
     *        写不了 SAF 的 content:// 目录，所以这里固定用应用外部私有目录（Android/data 下，免权限）。
     */
    fun start(context: Context, downloadDir: File): Int {
        if (_state.value == State.RUNNING) return port
        val so = soFile(context)
        Log.d(TAG, "启动引擎：${describe(so)} loaded=$loaded 下载目录=${downloadDir.absolutePath}")
        if (!so.isFile) {
            Log.e(TAG, "启动失败：还没有导入引擎文件（缺少 ${so.absolutePath}）")
            throw IllegalStateException("还没有导入 Gopeed 引擎（缺少 ${so.absolutePath}）")
        }
        lastError = null
        try {
            if (!loaded) {
                loadLibrary(so)
                loaded = true
            }
            val cfg = buildConfig(context, downloadDir).toString()
            Log.d(TAG, "调用 Libgopeed.start，启动配置=$cfg")
            port = Libgopeed.start(cfg).toInt()
            _state.value = State.RUNNING
            Log.d(TAG, "引擎已启动：port=$port")
            return port
        } catch (e: Throwable) {
            lastError = e.message ?: e.toString()
            Log.e(TAG, "启动引擎失败：${e.javaClass.name}: ${e.message}\n$LOAD_HINT", e)
            throw e
        }
    }

    /** 停止引擎（Go 侧内部上限 3 秒，超时未完成的任务会以错误回调收尾） */
    fun stop() {
        if (_state.value != State.RUNNING) return
        Log.d(TAG, "停止引擎")
        try {
            Libgopeed.stop()
        } catch (e: Throwable) {
            lastError = e.message ?: e.toString()
            Log.e(TAG, "停止引擎失败：${e.javaClass.name}: ${e.message}", e)
        }
        _state.value = State.INSTALLED
        port = 0
    }

    /**
     * 进程内 REST 调用（等价于 Gopeed 的 HTTP API，但不走 TCP、不需要 apiToken）。
     *
     * @return 响应信封 JSON（{code,msg,data}）；code != 0 时抛异常并带上服务端的 msg。
     */
    fun invoke(method: String, path: String, query: String? = null, body: String? = null): JSONObject {
        if (_state.value != State.RUNNING) throw IllegalStateException("引擎未启动")
        val id = requestId.incrementAndGet()
        val latch = CountDownLatch(1)
        var ok = false
        var payload: String? = null
        // 回调来自 Go 侧线程，这里只做配对唤醒，不碰任何 UI 状态
        val listener = object : InvokeResultListener {
            override fun onResult(id: Long, success: Boolean, data: String?) {
                ok = success
                payload = data
                latch.countDown()
            }
        }
        // 日志走项目统一的脱敏（§2 的 util/LogRedactor）：URL 只留 scheme://host，token/cookie 打码
        Log.d(TAG, "调用引擎：$method $path query=${query ?: ""} body=${LogRedactor.line(body ?: "").take(300)} id=$id")
        Libgopeed.invokeAsync(method, path, query ?: "", body ?: "", id, listener)
        if (!latch.await(60, TimeUnit.SECONDS)) {
            Log.e(TAG, "引擎调用超时（60 秒）：$method $path id=$id")
            throw IllegalStateException("引擎调用超时（60 秒）：$method $path")
        }
        if (!ok) {
            Log.e(TAG, "引擎调用失败：$method $path id=$id 返回=${LogRedactor.line(payload ?: "").take(300)}")
            throw IllegalStateException(payload?.takeIf { it.isNotBlank() } ?: "引擎调用失败：$method $path")
        }
        val text = payload ?: ""
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.e(TAG, "引擎返回内容无法解析：$method $path 原文=${LogRedactor.line(text).take(300)}", e)
            throw IllegalStateException("引擎返回内容无法解析：${text.take(200)}")
        }
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("msg").ifBlank { "引擎返回错误码 $code" }
            Log.e(TAG, "引擎返回错误：$method $path code=$code msg=$msg")
            throw IllegalStateException(msg)
        }
        return json
    }

    /** 列表/详情里的任务对象转成 UI 用的纯数据（字段名对照 Gopeed 的 Task/TaskRuntimeStatus） */
    data class TaskView(
        val id: String,
        val name: String,
        val status: String,
        val downloaded: Long,
        val total: Long,
        val speed: Long
    )

    /** 取单个任务的运行态（GET /api/v1/tasks/{id}/status） */
    fun taskStatus(id: String): TaskView {
        val data = invoke("GET", "/api/v1/tasks/$id/status").optJSONObject("data")
            ?: throw IllegalStateException("引擎没有返回任务状态")
        return TaskView(
            id = id,
            name = "",
            status = data.optString("status"),
            downloaded = data.optLong("downloaded"),
            total = data.optLong("total"),
            speed = data.optLong("speed")
        )
    }

    /**
     * 建任务（POST /api/v1/tasks），返回引擎任务 ID。
     *
     * @param headers 自定义请求头（字段名对照 Gopeed 的 `pkg/protocol/http.ReqExtra` 的 `header`）：
     *        网盘直链基本都要 UA / Referer / Cookie，缺了就 403
     * @param connections 分片并发连接数（`OptsExtra.connections`）；<=0 时用引擎默认值
     * @param name 文件名（`Options.name`）；空串时引擎自己从 URL 推导
     * @param labels 自定义标签（`Request.Labels`），可塞 YunX 侧任务 id 做反查
     */
    fun createTask(
        url: String,
        saveDir: File,
        headers: Map<String, String> = emptyMap(),
        connections: Int = 0,
        name: String = "",
        labels: Map<String, String> = emptyMap()
    ): String {
        val req = JSONObject().apply {
            put("url", url)
            if (headers.isNotEmpty()) {
                put("extra", JSONObject().apply { put("header", JSONObject(headers)) })
            }
            if (labels.isNotEmpty()) put("labels", JSONObject(labels))
        }
        val opts = JSONObject().apply {
            put("path", saveDir.absolutePath)
            if (name.isNotBlank()) put("name", name)
            if (connections > 0) {
                put("extra", JSONObject().apply { put("connections", connections) })
            }
        }
        val body = JSONObject().apply {
            put("req", req)
            put("opts", opts)
        }
        return invoke("POST", "/api/v1/tasks", null, body.toString()).optString("data")
    }

    /** 暂停 / 继续 / 删除任务 */
    fun pauseTask(id: String) {
        invoke("PUT", "/api/v1/tasks/$id/pause")
    }

    fun continueTask(id: String) {
        invoke("PUT", "/api/v1/tasks/$id/continue")
    }

    fun deleteTask(id: String) {
        invoke("DELETE", "/api/v1/tasks/$id")
    }

    /** 引擎版本（GET /api/v1/info → data.version） */
    fun engineVersion(): String = invoke("GET", "/api/v1/info").optJSONObject("data")?.optString("version") ?: ""

    // ---------- 内部实现 ----------

    /**
     * 启动失败时的排查提示（真机上出现过的两类失败，日志里都会带上这段）。
     *
     * ① UnsatisfiedLinkError: couldn't find "libgojni.so"：桥接类的静态初始化里有
     *    System.loadLibrary("gojni")，而 AAR 里的 .so 没有 DT_SONAME，linker 不会把
     *    先前的 System.load(绝对路径) 认成同一个库 ⇒ 必须用 tools/patch-gopeed-classes.py
     *    打过补丁的 app/libs/gopeed-classes.jar（补丁把那条 loadLibrary 指令换成 nop）。
     * ② NoClassDefFoundError: com.gopeed.libgopeed.Libgopeed：本进程里该类曾初始化失败，
     *    JVM 会永久记住失败结果，必须杀掉应用重开后再试。
     */
    private const val LOAD_HINT =
        "排查提示：① couldn't find \"libgojni.so\" ⇒ app/libs/gopeed-classes.jar 不是打过补丁的版本" +
            "（见 tools/patch-gopeed-classes.py）；② NoClassDefFoundError ⇒ 桥接类在本进程里已初始化失败，" +
            "必须杀掉应用重开后再试。"

    /** 引擎文件的诊断描述（日志用，含存在性/大小/权限位） */
    private fun describe(so: File): String =
        "path=${so.absolutePath} isFile=${so.isFile} size=${if (so.isFile) so.length() else -1L} " +
            "readable=${so.canRead()} executable=${so.canExecute()} writable=${so.canWrite()}"

    /** 首选 ABI：按系统上报顺序挑第一个 AAR 里可能有的目录名 */
    private fun preferredAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull { it in KNOWN_ABIS } ?: "arm64-v8a"

    /**
     * 加载引擎动态库。调用前绝不能触碰任何 go.* / com.gopeed.* 类：桥接类的静态初始化一旦抛错，
     * JVM 会把该类的初始化失败永久记住（同一进程内无法再重试，再点只会得到 NoClassDefFoundError），
     * 所以顺序必须是「先 load 成功，再用桥接类」。
     *
     * 另：AAR 里的 libgojni.so 没有 DT_SONAME（实测 dynamic 段只有 DT_NEEDED），linker 不会把
     * 先前的 System.load(绝对路径) 认成名为 "gojni" 的库，于是 gomobile 生成的那句
     * System.loadLibrary("gojni") 必然抛 UnsatisfiedLinkError（真机报错就是
     * couldn't find "libgojni.so"）。因此 app/libs/gopeed-classes.jar 必须是
     * tools/patch-gopeed-classes.py 处理过的版本——补丁把那条 loadLibrary 指令原地换成 nop。
     */
    private fun loadLibrary(so: File) {
        var directError = ""
        Log.d(TAG, "加载引擎动态库：${describe(so)}")
        try {
            System.load(so.absolutePath)
            Log.d(TAG, "System.load 成功：${so.absolutePath}")
            return
        } catch (e: UnsatisfiedLinkError) {
            directError = e.message ?: e.toString()
            Log.e(TAG, "System.load 失败：$directError", e)
        }
        // 回退：构建时若把 .so 放进 app/src/main/jniLibs/<abi>/，安装后系统已解到
        // nativeLibraryDir，这里可以按库名直接加载。
        try {
            System.loadLibrary("gojni")
            Log.d(TAG, "System.loadLibrary(\"gojni\") 成功（回退路径）")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "System.loadLibrary(\"gojni\") 失败：${e.message}", e)
            throw IllegalStateException(
                "引擎动态库加载失败（两种方式都失败）\n" +
                    "① System.load(${so.absolutePath})：$directError\n" +
                    "② System.loadLibrary(\"gojni\")：${e.message}"
            )
        }
    }

    /** 组装 Gopeed 启动配置（字段名对照 pkg/rest/model/server.go 的 StartConfig） */
    private fun buildConfig(context: Context, downloadDir: File): JSONObject {
        val root = File(context.filesDir, DIR_NAME)
        val store = File(root, "store").apply { mkdirs() }
        val temp = File(root, "tmp").apply { mkdirs() }
        downloadDir.mkdirs()
        return JSONObject().apply {
            put("storage", "bolt")
            // Go 侧默认值就是 "./"，目录必须以分隔符结尾
            put("storageDir", store.absolutePath + File.separator)
            put("tempDir", temp.absolutePath + File.separator)
            // 只走进程内分发：不开监听端口，也就不需要 apiToken
            put("apiEnable", false)
            put("refreshInterval", 500)
            put("downloadConfig", JSONObject().apply {
                put("downloadDir", downloadDir.absolutePath)
                put("maxRunning", 1)
                // 只表示「启动时恢复未完成任务」，新建任务照常立即开始
                put("autoStartTasks", false)
                put("protocolConfig", JSONObject())
            })
        }
    }
}
