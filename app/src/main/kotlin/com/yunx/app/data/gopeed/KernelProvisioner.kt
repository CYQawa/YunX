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
import android.util.Log
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.ChunkResult
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.DownloadService
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.util.LogRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Gopeed 内核（libgojni.so）的云端获取：**取包 → 下载 → 导入**一条链路。
 *
 * 为什么单独一个对象：这条链路和普通下载任务有三处根本不同，塞进 [com.yunx.app.data.download.DownloadManager]
 * 会把两者的语义搅在一起：
 *  ① 落地位置是**应用私有目录**（[GopeedEngine.kernelTempDir]，Android/data/…），不是公共 Download：
 *     内核包只是导入用的临时物件，导入完就删，用户不需要在文件管理器里看到它，也就不需要任何存储权限；
 *  ② 下载过程**不进「下载」页**：界面是一个不可关闭的进度弹窗（见 DownloadEngineScreen），
 *     所以这里只暴露 [phase] 一个状态流，不落库、不留记录；
 *  ③ 完成即**自动导入**（解出 `jni/<abi>/libgojni.so` 写进私有目录）并清理临时包。
 *
 * 复用而不重写的部分：分片下载器 [ChunkDownloader]（Range 分片 + 断点续传 + 严格字节校验）、
 * Release 抓取 [UpdateChecker]（仓库参数化）、网盘解析 [QuarkResolveRepository]、
 * 导入 [GopeedEngine.installFromAar]。
 *
 * 取链口径（用户指定）：**已登录夸克走转存优先**（转存到自己网盘再取直链），
 * 转存失败才回退免转存取链；未登录走游客取链（夸克/UC 的小文件直链不要求账号）。
 * 实测这个 Release 的四个包都在 24.8–26.3 MB，**全部低于夸克游客约 50MB 的上限**，
 * 所以「没登录也能自动下」是常态，「超出上限」才是兜底提示。
 */
object KernelProvisioner {

    /** 内核包仓库：Release 里按 CPU 架构分包（`libgopeed-<abi>.aar`），说明里带「网盘下载」条目 */
    const val KERNEL_REPO = "CYQawa/yunx_gopeed_build"

    private const val TAG = "YunX-Kernel"

    /** 分片下载用的合成任务 id（只给 ChunkDownloader 的取消登记表用） */
    private const val TASK_ID = -20261004L

    /** 分片数上限：内核包才 25MB，再多的分片只会把单块切得太碎 */
    private const val MAX_CHUNKS = 16

    /** 单块下限：分片数按「总大小 / 这个值」收敛，避免 25MB 被切成 32 份 700KB 的小块 */
    private const val MIN_CHUNK_BYTES = 1L * 1024 * 1024

    /** 进度上报节流：分片回调非常密（每 64KB 一次），不节流会把 StateFlow 刷爆 */
    private const val PROGRESS_INTERVAL_MS = 250L

    /** 通知栏进度节流：与内置下载器同一个口径（2 秒），别把系统通知刷爆 */
    private const val NOTIFY_INTERVAL_MS = 2000L

    /** 前台通知标题（保活期间不变，只有副标题里的进度在动） */
    private const val NOTIFY_TITLE = "正在下载 Gopeed 内核"

    /** 下载阶段（界面按它渲染那个不可关闭的进度弹窗） */
    sealed interface Phase {
        /** 空闲：没有弹窗要显示 */
        object Idle : Phase

        /** 正在问 GitHub 要最新 Release */
        object FetchingRelease : Phase

        /** 正在解析分享 / 取直链 */
        object Resolving : Phase

        /** 分片下载中（total <= 0 表示大小未知，界面显示不确定进度） */
        data class Downloading(
            val downloaded: Long,
            val total: Long,
            val speedBps: Long,
            val chunks: Int
        ) : Phase

        /** 分片合并写盘（mergePercent 0..100） */
        data class Merging(val percent: Int) : Phase

        /** 解包导入中 */
        object Importing : Phase

        /** 导入完成 */
        data class Done(val fileName: String, val bytes: Long) : Phase

        /** 失败（message 直接给用户看，服务端原文不加工） */
        data class Failed(val message: String) : Phase
    }

    /** 已按本机 ABI 挑好包的内核 Release */
    data class KernelRelease(
        val tagName: String,
        /** Release 说明里命中的「网盘下载」链接；没有该条目时为 null（弹窗里就不显示「网盘自动下载」） */
        val panUrl: String?,
        /** 本机 ABI 对应的包名，形如 `libgopeed-arm64-v8a.aar` */
        val assetName: String,
        /** 包大小（字节）；0 = 未知 */
        val assetSize: Long,
        /** GitHub 直链 */
        val directUrl: String,
        /** 资产摘要 `sha256:…`；空串 = 未知（网盘通道拿不到，只有 GitHub 通道能校验） */
        val digest: String,
        /** Release 页面地址 */
        val htmlUrl: String
    )

    /** 下载来源 */
    enum class Source {
        /** 网盘自动下载（转存/游客取链，自动按 ABI 挑包） */
        PAN,

        /** GitHub 直链 */
        GITHUB,

        /** GitHub 镜像站（失败自动回退直链） */
        GITHUB_MIRROR
    }

    /** 一次下载的执行计划：直链 + 请求头 + 回退地址 + 转存临时目录（用完要清） */
    private data class Plan(
        val url: String,
        val size: Long,
        val headers: Map<String, String>,
        val label: String,
        val fallbackUrl: String = "",
        val digest: String = "",
        val cleanupDirFid: String? = null,
        val cleanupCookie: String = "",
        val repo: QuarkResolveRepository? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)

    /** 下载/导入阶段：界面唯一的状态源（切页/退后台都不影响，任务跑在 [scope] 上） */
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private var job: Job? = null
    private var downloader: ChunkDownloader? = null

    /**
     * 保活用的应用上下文：只在一次下载期间持有（`applicationContext`，不会拖住 Activity）。
     * 内核下载借内置下载器那套前台服务（[DownloadService]，`foregroundServiceType="dataSync"`）
     * 顶住「退到后台被系统收掉」——下载器本身不带保活，这是它与普通任务唯一的差别。
     */
    @Volatile
    private var keepAliveContext: Context? = null

    /** 通知栏节流时间戳（[NOTIFY_INTERVAL_MS]） */
    private var lastNotifyAt = 0L

    /**
     * 本任务是否已经申请过保活（[DownloadService.acquire] 成功与否都算，因为计数器已经加了）。
     * `release` 必须与 `acquire` **严格配对**：一次都没申请过就去 release，会把计数打到负数，
     * 那时 `decrementAndGet() <= 0` 成立 → 把用户正在跑的内置下载的保活一起关掉。
     */
    @Volatile
    private var keepAliveAcquired = false

    // 进度采样（只有下载线程在写，reportProgress 用 @Synchronized 兜住并发）
    private var lastReportAt = 0L
    private var lastReportBytes = 0L
    private var lastSpeed = 0L

    fun isRunning(): Boolean = job?.isActive == true

    /** 关掉「完成/失败」弹窗时调用：只在没有任务在跑时才允许回到空闲 */
    fun reset() {
        if (isRunning()) return
        _phase.value = Phase.Idle
    }

    /**
     * 取内核仓库的最新 Release，并按**本机首选 ABI** 挑出对应的包。
     *
     * ★ 挑包必须用 [GopeedEngine.kernelAssetName]（= `preferredAbi()`），和导入时
     *   「精确匹配 `jni/<abi>/libgojni.so`」是同源口径；缺本机架构的包要直接报错，
     *   绝不静默换个架构下（那样导入必然失败，还会让人以为是包坏了）。
     */
    suspend fun loadRelease(): Result<KernelRelease> = withContext(Dispatchers.IO) {
        _phase.value = Phase.FetchingRelease
        val result = when (val r = UpdateChecker.fetchLatestRelease(repo = KERNEL_REPO)) {
            is UpdateChecker.CheckResult.Failure ->
                Result.failure(IllegalStateException("获取内核版本失败：${r.reason}"))
            is UpdateChecker.CheckResult.Success -> buildRelease(r.release)
        }
        result
            .onSuccess {
                _phase.value = Phase.Idle
                Log.d(
                    TAG,
                    "内核 Release 就绪：tag=${it.tagName} asset=${it.assetName} size=${it.assetSize} " +
                        "网盘=${if (it.panUrl != null) "有" else "无"}"
                )
            }
            .onFailure {
                _phase.value = Phase.Failed(it.message ?: "获取内核版本失败")
                Log.e(TAG, "获取内核 Release 失败：${it.message}", it)
            }
        result
    }

    private fun buildRelease(release: UpdateChecker.Release): Result<KernelRelease> {
        val want = GopeedEngine.kernelAssetName()
        val asset = release.assets.firstOrNull { it.name.equals(want, ignoreCase = true) }
            ?: return Result.failure(
                IllegalStateException(
                    "这个版本（${release.tagName}）里没有 $want" +
                        "（本机 ABI：${GopeedEngine.preferredAbi()}）；" +
                        "现有包：${release.assets.joinToString("、") { it.name }.ifBlank { "无" }}"
                )
            )
        return Result.success(
            KernelRelease(
                tagName = release.tagName,
                panUrl = UpdateChecker.netdiskDownloadUrl(release.body),
                assetName = asset.name,
                assetSize = asset.size,
                directUrl = asset.downloadUrl,
                digest = asset.digest,
                htmlUrl = release.htmlUrl
            )
        )
    }

    /**
     * 开始下载并导入。[source] 决定从哪条路拿直链；两条路都自动按本机 ABI 挑包。
     * 重复调用（已有任务在跑）直接忽略 —— 界面那个弹窗是不可关闭的，正常也点不到第二次。
     */
    fun start(context: Context, release: KernelRelease, source: Source) {
        if (isRunning()) return
        val appContext = context.applicationContext
        keepAliveContext = appContext
        job = scope.launch {
            var cleanupDirFid: String? = null
            var cleanupCookie = ""
            var cleanupRepo: QuarkResolveRepository? = null
            try {
                _phase.value = Phase.Resolving
                val plan = when (source) {
                    Source.PAN -> {
                        val panUrl = release.panUrl
                            ?: throw IllegalStateException("这个版本的说明里没有网盘链接，请改用 GitHub 下载")
                        resolveFromPan(appContext, panUrl, release.assetName).also {
                            cleanupDirFid = it.cleanupDirFid
                            cleanupCookie = it.cleanupCookie
                            cleanupRepo = it.repo
                        }
                    }
                    Source.GITHUB -> Plan(
                        url = release.directUrl,
                        size = release.assetSize,
                        headers = emptyMap(),
                        label = "GitHub 直链",
                        digest = release.digest
                    )
                    Source.GITHUB_MIRROR -> Plan(
                        url = mirrorUrl(appContext, release.directUrl),
                        size = release.assetSize,
                        headers = emptyMap(),
                        label = "GitHub 镜像站",
                        // 镜像站失效/超时时回退直连：与「网盘更新」的 APK 下载同一口径
                        fallbackUrl = release.directUrl,
                        digest = release.digest
                    )
                }

                val dir = GopeedEngine.kernelTempDir(appContext)
                val chunkDir = File(dir, "chunks")
                val target = File(dir, release.assetName)
                val bytes = downloadToPrivate(appContext, plan, target, chunkDir)

                _phase.value = Phase.Importing
                notifyStage("正在导入 Gopeed 内核", 100)
                // 导入前必须先停引擎：installFromAar 不允许在 RUNNING 时覆盖 .so（Go 侧还占着它）
                val wasRunning = GopeedEngine.state.value == GopeedEngine.State.RUNNING
                val downloadDir = GopeedEngine.resolveDownloadDir(appContext)
                if (wasRunning) withContext(Dispatchers.IO) { GopeedEngine.stop() }
                val soBytes = try {
                    withContext(Dispatchers.IO) {
                        GopeedEngine.installFromAar(appContext, Uri.fromFile(target))
                    }
                } finally {
                    // 之前就在跑就把它拉回来，别因为更新内核把正在下载的任务晾着。
                    // 新 .so 只有**全新进程**才 dlopen 得进来（已加载的库无法卸载），所以这次重启用的是
                    // 进程里那份**旧**内核；「要不要重启应用」这条状态由
                    // GopeedEngine.pendingRestartForNewKernel 单独承载 —— 不能借 lastError 传，
                    // 因为 start() 开头会把 lastError 清掉（这里正好是 stop → 导入 → start 连着走）。
                    if (wasRunning) {
                        runCatching {
                            withContext(Dispatchers.IO) { GopeedEngine.start(appContext, downloadDir) }
                        }.onFailure { Log.w(TAG, "导入后重启引擎失败：${it.message}") }
                    }
                }
                // 导入成功：临时包与分片一起清掉（内核已经解到私有目录，这两样都没用了）
                runCatching { chunkDir.deleteRecursively() }
                runCatching { target.delete() }
                _phase.value = Phase.Done(release.assetName, soBytes)
                Log.d(TAG, "内核已导入：${release.assetName} ${bytes}B → libgojni.so ${soBytes}B")
            } catch (e: CancellationException) {
                Log.d(TAG, "内核下载被取消")
                _phase.value = Phase.Idle
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "内核下载失败：${e.message}", e)
                _phase.value = Phase.Failed(e.message ?: e.toString())
            } finally {
                // 转存出来的临时目录必须清掉（用户网盘里留垃圾比失败更糟）；
                // 协程被取消后普通挂起调用会立刻抛异常，所以整段放进 NonCancellable
                val dirFid = cleanupDirFid
                val repo = cleanupRepo
                if (dirFid != null && repo != null && cleanupCookie.isNotBlank()) {
                    withContext(NonCancellable) {
                        runCatching { repo.cleanupTempDir(dirFid, cleanupCookie) }
                            .onFailure { Log.w(TAG, "清理转存临时目录失败：${it.message}") }
                    }
                }
                // 收工（无论完成/失败/取消）：释放本任务的保活引用，别留一条不会自己消失的通知
                // （引用计数归零才会真的关掉服务，见 DownloadService.acquire/release）
                if (keepAliveAcquired) {
                    keepAliveAcquired = false
                    runCatching { DownloadService.release(appContext) }
                }
                keepAliveContext = null
            }
        }
    }

    /** 取消下载（界面那个弹窗上的「取消」）。已下载的分片保留在私有目录，下次重下按断点续传接着走。 */
    fun cancel() {
        val running = job ?: return
        runCatching { downloader?.cancelCalls(TASK_ID) }
        running.cancel()
        job = null
        _phase.value = Phase.Idle
    }

    /** 镜像站前缀：与「检查更新」共用同一个设置（未配置则用内置默认） */
    private fun mirrorUrl(context: Context, url: String): String {
        val prefix = SettingsRepository(context).githubMirrorPrefix
        return if (prefix.isNullOrBlank()) {
            UpdateChecker.mirrorUrl(url)
        } else {
            UpdateChecker.mirrorUrl(url, prefix)
        }
    }

    // ---------- 网盘通道 ----------

    /**
     * 从 Release 说明里的网盘链接取本机 ABI 那个包：
     * 打开分享 → 列目录（根目录没有就下钻一层）→ 按**文件名**挑包 → 取直链。
     *
     * 网盘分享是 GitHub 资产的**同名镜像**（实测四个包名字、大小逐一对应），所以这里匹配的唯一键就是
     * [GopeedEngine.kernelAssetName]，不需要猜大小或顺序。
     */
    private suspend fun resolveFromPan(
        context: Context,
        panUrl: String,
        assetName: String
    ): Plan {
        val parsed = ShareLinkParser.parse(panUrl)
            ?: throw IllegalStateException("看不懂这个网盘链接：$panUrl")
        if (parsed.platform != SharePlatform.QUARK) {
            throw IllegalStateException(
                "目前只支持夸克网盘的自动下载（这个链接是 ${parsed.platform}），请改用 GitHub 下载"
            )
        }
        val api = QuarkApi()
        // 账号 Cookie：走仓库的 getFreshCookie（带 __puus 刷新 + Set-Cookie 回写），
        // 未登录/刷新失败时为空串 —— 空串即游客态，后面自动改走游客取链
        val cookie = runCatching {
            QuarkAccountRepository(AppDatabase.get(context).quarkAccountDao(), api).getFreshCookie()
        }.getOrNull().orEmpty()
        val repo = QuarkResolveRepository(api)
        val session: ShareSession = repo.createSession(panUrl, parsed.pwd, cookie).getOrElse {
            throw IllegalStateException("打开分享失败：${it.message ?: "未知错误"}")
        }
        val file = findAsset(repo, session, cookie, assetName)
        val link: DownloadLink = if (cookie.isBlank()) {
            Log.d(TAG, "夸克未登录，走游客取链")
            repo.getGuestShareDownloadLink(session, file).getOrElse {
                throw IllegalStateException("游客取链失败：${it.message ?: "未知错误"}")
            }
        } else {
            // 用户口径：转存优先，免转存兜底
            val saved = repo.getShareDownloadLink(session, file, cookie)
            saved.getOrNull() ?: run {
                Log.w(TAG, "转存取链失败，回退免转存：${saved.exceptionOrNull()?.message}")
                repo.getShareDownloadLinkWithoutSave(session, file, cookie).getOrElse { e ->
                    throw IllegalStateException(
                        "取链失败：${e.message ?: saved.exceptionOrNull()?.message ?: "未知错误"}"
                    )
                }
            }
        }
        val guestCookie = link.guestCookie
        // 直链请求头与「网盘下载」完全一致：防盗链要 Referer，游客态必须回带 __pugs（缺了 CDN 412）
        val headers = mapOf(
            "Cookie" to (if (guestCookie.isNotBlank()) guestCookie else cookie),
            "User-Agent" to QuarkConstants.API_USER_AGENT,
            "Referer" to QuarkConstants.DOWNLOAD_REFERER
        )
        Log.d(
            TAG,
            "网盘直链就绪：${file.fname} size=${link.size} 转存临时目录=${link.cleanupDirFid ?: "无"}"
        )
        return Plan(
            url = link.downloadUrl,
            size = if (link.size > 0L) link.size else file.fsize,
            headers = headers,
            label = if (cookie.isBlank()) "夸克网盘（游客）" else "夸克网盘（已登录）",
            cleanupDirFid = link.cleanupDirFid,
            cleanupCookie = if (cookie.isBlank()) guestCookie else cookie,
            repo = repo
        )
    }

    /** 在分享里找本机 ABI 的包：先看根目录，再兼容「放在一层文件夹里」的排法；找不到就把实际内容报出来 */
    private suspend fun findAsset(
        repo: QuarkResolveRepository,
        session: ShareSession,
        cookie: String,
        assetName: String
    ): ShareFile {
        fun List<ShareFile>.pick(): ShareFile? =
            firstOrNull { !it.isdir && it.fname.equals(assetName, ignoreCase = true) }

        val root = repo.listFiles(session, "0", cookie).getOrElse {
            throw IllegalStateException("列出分享目录失败：${it.message ?: "未知错误"}")
        }
        root.pick()?.let { return it }
        val sub = root.firstOrNull { it.isdir } ?: throw IllegalStateException(
            "分享里没有 $assetName（根目录：${names(root)}）"
        )
        val inner = repo.listFiles(session, sub.fid, cookie).getOrNull().orEmpty()
        inner.pick()?.let { return it }
        throw IllegalStateException("分享里没有 $assetName（${sub.fname}/ 下：${names(inner)}）")
    }

    private fun names(files: List<ShareFile>): String =
        files.joinToString("、") { it.fname }.ifBlank { "空" }

    // ---------- 分片下载（私有目录） ----------

    /**
     * 下载到私有目录。主地址失败（镜像站连不上、直链过期）时自动换 [Plan.fallbackUrl] 重试一遍。
     * @return 实际写出的字节数
     */
    private suspend fun downloadToPrivate(
        context: Context,
        plan: Plan,
        target: File,
        chunkDir: File
    ): Long = coroutineScope {
        val engine = ChunkDownloader({ HttpClients.downloadClient() })
        downloader = engine
        chunkDir.mkdirs()
        // 借内置下载器的前台服务保活：内核包也是几十 MB 的东西，退到后台/锁屏被系统收掉就等于白下。
        // 用引用计数版（acquire/release）：用户同时还在跑别的下载时，内核下完不能把别人的保活一起关掉。
        runCatching { DownloadService.acquire(context, NOTIFY_TITLE) }
        // 不论 startForegroundService 成没成功，计数器都已经加过了 → release 必须还回去
        keepAliveAcquired = true
        lastNotifyAt = 0L
        val urls = buildList {
            if (plan.url.isNotBlank()) add(plan.url)
            if (plan.fallbackUrl.isNotBlank()) add(plan.fallbackUrl)
        }
        var lastFailure: Throwable? = null
        for ((index, url) in urls.withIndex()) {
            if (index > 0) Log.w(TAG, "主地址失败，回退备用地址：${LogRedactor.url(url)}")
            try {
                val written = downloadFrom(engine, context, url, plan, target, chunkDir)
                verifyDigest(target, plan.digest)
                return@coroutineScope written
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastFailure = e
                Log.e(TAG, "${plan.label} 下载失败（${index + 1}/${urls.size}）：${e.message}", e)
                // 换地址重来：分片全清，避免和上一份的半成品拼在一起
                runCatching { chunkDir.listFiles()?.forEach { it.delete() } }
                runCatching { target.delete() }
            }
        }
        throw lastFailure ?: IllegalStateException("下载失败")
    }

    /** 单地址下载：能探到大小就分片并发，探不到就退回单流整文件 */
    private suspend fun downloadFrom(
        engine: ChunkDownloader,
        context: Context,
        url: String,
        plan: Plan,
        target: File,
        chunkDir: File
    ): Long {
        // 分片数按来源平台取设置页那个「下载线程数」：网盘通道用夸克档，GitHub 通道用 GitHub 档
        val platform = if (plan.cleanupCookie.isNotBlank() || plan.repo != null) {
            DownloadPlatform.QUARK
        } else {
            DownloadPlatform.GITHUB
        }
        val threads = SettingsRepository(context).downloadThreadsFor(platform)
        val total = if (plan.size > 0L) plan.size else (engine.getTotalSize(url, plan.headers) ?: -1L)
        lastReportAt = 0L
        lastReportBytes = 0L
        lastSpeed = 0L

        if (total <= 0L) {
            Log.d(TAG, "拿不到大小，退回单流整文件：${LogRedactor.url(url)}")
            val counter = AtomicLong(0L)
            _phase.value = Phase.Downloading(0L, -1L, 0L, 1)
            val ok = engine.downloadFull(TASK_ID, url, target, plan.headers, -1L) { bytes ->
                reportProgress(counter, bytes, -1L, 1)
            }
            if (!ok) throw IllegalStateException("下载失败（单流）")
            if (target.length() <= 0L) throw IllegalStateException("下载下来的文件是空的")
            return target.length()
        }

        val count = minOf(threads.coerceAtLeast(1), MAX_CHUNKS, ((total + MIN_CHUNK_BYTES - 1) / MIN_CHUNK_BYTES).toInt().coerceAtLeast(1))
        val block = (total + count - 1) / count
        val parts = (0 until count).mapNotNull { i ->
            val start = i.toLong() * block
            val end = minOf(start + block, total) - 1
            if (start > end) null else Triple(File(chunkDir, "part_$i"), start, end)
        }
        Log.d(TAG, "分片下载开始：total=$total chunks=${parts.size} threads=$threads")

        // 断点续传：上次取消/中断留下的分片算进初始进度，downloadChunk 自己从 partFile.length() 接着写
        val counter = AtomicLong(0L)
        parts.forEach { (part, start, end) ->
            counter.addAndGet(part.length().coerceIn(0L, end - start + 1))
        }
        _phase.value = Phase.Downloading(counter.get(), total, 0L, parts.size)

        // ★ async 是 CoroutineScope 的扩展：这里必须自己开一个 coroutineScope 当接收者，
        //   否则在普通 suspend 函数里 async 根本解析不到（downloadToPrivate 那层虽然有
        //   coroutineScope，但它不是调用点的隐式接收者）。写法与 DownloadManager 的分片池一致。
        val results = coroutineScope {
            parts.map { (part, start, end) ->
                async(Dispatchers.IO) {
                    engine.downloadChunk(TASK_ID, url, start, end, part, plan.headers, null) { bytes ->
                        reportProgress(counter, bytes, total, parts.size)
                    }
                }
            }.awaitAll()
        }

        if (results.any { it == ChunkResult.RANGE_IGNORED }) {
            // 服务器不认 Range：绝不按分片写整文件，清掉重来走单流
            Log.w(TAG, "服务器忽略 Range，回退单流整文件")
            runCatching { chunkDir.listFiles()?.forEach { it.delete() } }
            val single = AtomicLong(0L)
            _phase.value = Phase.Downloading(0L, total, 0L, 1)
            val ok = engine.downloadFull(TASK_ID, url, target, plan.headers, total) { bytes ->
                reportProgress(single, bytes, total, 1)
            }
            if (!ok) throw IllegalStateException("下载失败（单流）")
            return target.length()
        }
        if (results.any { it == ChunkResult.FAILED }) {
            throw IllegalStateException("分片下载失败（$url）")
        }

        // 合并：边写边删分片，峰值 ≈ 目标文件本身
        _phase.value = Phase.Merging(0)
        notifyStage("正在合并 Gopeed 内核分片", 100)
        val written = withContext(Dispatchers.IO) {
            FileOutputStream(target).use { out ->
                engine.mergeChunksToStream(parts.map { it.first }, out) { done ->
                    val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                    _phase.value = Phase.Merging(percent)
                }
            }
        }
        if (written != total) {
            throw IllegalStateException("文件大小校验失败：期望 $total 字节，实际 $written 字节（已拒绝导入损坏的包）")
        }
        Log.d(TAG, "分片合并完成：$written 字节 → ${target.absolutePath}")
        return written
    }

    /**
     * 进度上报：[ChunkDownloader] 的 onBytes 给的是**增量**字节，这里累加到总量并节流上报，
     * 顺带用滑动窗口内的平均速度填 [Phase.Downloading.speedBps]。多分片并发调用，故加锁。
     */
    @Synchronized
    private fun reportProgress(counter: AtomicLong, delta: Long, total: Long, chunks: Int) {
        val done = counter.addAndGet(delta)
        val now = System.currentTimeMillis()
        if (lastReportAt == 0L) {
            lastReportAt = now
            lastReportBytes = done
        } else if (now - lastReportAt >= PROGRESS_INTERVAL_MS) {
            val elapsed = now - lastReportAt
            val gained = done - lastReportBytes
            if (elapsed > 0 && gained >= 0) lastSpeed = gained * 1000 / elapsed
            lastReportAt = now
            lastReportBytes = done
        } else {
            return
        }
        _phase.value = Phase.Downloading(done, total, lastSpeed, chunks)
        notifyProgress(done, total)
    }

    /** 通知栏进度（进度弹窗的镜像，顺带把前台服务顶住）：[NOTIFY_INTERVAL_MS] 一次 */
    private fun notifyProgress(done: Long, total: Long) {
        val context = keepAliveContext ?: return
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
        lastNotifyAt = now
        val percent = if (total > 0L) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        runCatching {
            DownloadService.update(context, NOTIFY_TITLE, percent, formatSpeed(lastSpeed), true)
        }
    }

    /** 把通知切到某个阶段的文案（合并/导入这些没有百分比的阶段用） */
    private fun notifyStage(title: String, percent: Int) {
        val context = keepAliveContext ?: return
        lastNotifyAt = System.currentTimeMillis()
        runCatching { DownloadService.update(context, title, percent, "", false) }
    }

    /** 速度文本（与内置下载器同一个格式）；0 返回空串 */
    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSec.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return String.format("%.1f %s", value, units[i])
    }

    /** 校验 GitHub 给的 sha256 摘要（网盘通道拿不到摘要，跳过）。失败即视为损坏，绝不拿去导入。 */
    private fun verifyDigest(target: File, digest: String) {
        val expected = digest.removePrefix("sha256:").trim().lowercase()
        if (expected.length != 64) return
        val actual = withDigest(target)
        if (actual != expected) {
            throw IllegalStateException("内核包校验失败（sha256 不匹配），已丢弃：$actual")
        }
        Log.d(TAG, "sha256 校验通过：$actual")
    }

    private fun withDigest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        // 按字节逐位取十六进制：Byte 是有符号的，先 and 0xff 再格式化成两位，避免出现 ffffff 前缀
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
