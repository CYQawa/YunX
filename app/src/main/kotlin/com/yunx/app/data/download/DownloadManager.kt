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

import android.content.Context
import android.util.Log
import com.yunx.app.util.LogRedactor
import com.yunx.app.data.db.DownloadTaskDao
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.security.AndroidKeystoreCredentialCipher
import com.yunx.app.data.security.CredentialCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min

/** 实时下载统计（用于 UI 展示速度/剩余时间/线程数） */
data class DownloadStats(
    val speed: Long = 0L,        // 字节/秒
    val remainMillis: Long = -1L, // 剩余时间（毫秒），未知为 -1
    val chunkCount: Int = 1,      // 分片（线程）数
    /**
     * 分片合并进度（0~100）：-1 表示不在合并阶段。
     * 合并只活在内存里、不写 DB —— 进程被杀时合并本来就会中断，无需在库里留状态。
     */
    val mergePercent: Int = -1
)

private const val TAG = "YunX-DL"

/** 单文件 Range 分片的安全并发上限。迅雷等 CDN 对单文件并发 Range 有阈值，
 *  超过约 8 个并发会把多余请求降级为 200 整文件（忽略 Range），
 *  进而触发整任务回退单流、速度暴跌。压在安全上限内，所有分片都能稳定拿到 206。 */
private const val RANGE_WORKERS_CAP = 8

/** 错峰建连上限（序号）：第 i 个分片首次请求前延迟 (min(i, STAGGER_CAP) * STAGGER_MS) */
private const val STAGGER_CAP = 8
private const val STAGGER_MS = 25L

/** 慢连接抢占的采样间隔：看门狗每隔这么久刷新一次每路瞬时速度，再据此判定是否换连接 */
private const val PREEMPT_TICK_MS = 5_000L

// ---------- ★ 慢连接抢占（治「收尾塌到 KB 级」，勿删）----------
// 网盘 CDN 是**按连接**限速的，且个别连接会落在慢节点上：真机日志（70.6MB 文件）实测多数连接
// 40~80KB/s，少数只有 3~7KB/s。慢分片如果正好是收尾时唯一在跑的那几路，总速就塌到 KB 级
// （日志实测：64 路里 61 路空转 37 秒，只为等最后 500KB）。抢占 = 断开这条慢连接、换一条新连接续传。
/** 判定「慢连接」的绝对下限：低于 12KB/s 才算慢（实测正常连接 40~80KB/s） */
private const val PREEMPT_MIN_BPS = 12 * 1024L
/** 分片至少跑这么久才允许被抢占（避开建连与 TCP 爬坡期，否则会误杀刚起步的正常分片） */
private const val PREEMPT_MIN_AGE_MS = 15_000L
/** 同一分片两次抢占之间的冷却期（换完连接要给新连接爬坡时间，避免反复重连） */
private const val PREEMPT_COOLDOWN_MS = 10_000L
/** 剩余不足这个数就不再折腾（换连接本身也有握手成本） */
private const val PREEMPT_MIN_REMAIN = 128 * 1024L
/** 单个分片最多被抢占几次：全局都慢时（如整站限速）避免无意义的重连风暴 */
private const val PREEMPT_MAX = 3
/** 每轮看门狗最多抢占几路，避免同一时刻大批连接同时重建 */
private const val PREEMPT_PER_TICK = 2
/** 收尾判定：在飞分片不超过这个数就认为「其余连接已无事可做」，放宽抢占门槛（真机日志：收尾只剩 1~3 路在磨） */
private const val PREEMPT_ENDGAME_INFLIGHT = 3
/** 收尾时的最小存活时间：比常规门槛短得多，但也要避开刚建连的爬坡期 */
private const val PREEMPT_ENDGAME_MIN_AGE_MS = 3_000L

/** RANGE_IGNORED 容忍次数：CDN 偶发 200（限流中间态）前 N 次不触发整任务回退，继续领新片；超过才回退单流 */
private const val RANGE_IGNORED_TOLERANCE = 3

/** 暂停/删除时等待任务协程退出的上限（毫秒）：阻塞式 IO 不响应取消，不能无限等，否则按钮「点不动」 */
private const val JOB_EXIT_WAIT_MS = 10_000L

/** 重试区间（主池 part_i 或弹性区间 seg_{start}_{end}） */
private data class RetryRange(val start: Long, val end: Long, val file: File)

/**
 * 弹性区动态分片分配器（IDM 式）。
 * 核心矛盾：块太大会让尾部只剩少数几个大块 → 空闲线程空转（"最后一点特别慢"）；
 * 块太小会产生海量 Range 请求 → 拖慢快连接。故不再用固定块，改为**实时速度自适应 + 尾部收缩**：
 * - 按字节顺序发块（物理相邻），保持连接复用（不搞"中点劈分"，避免中后段掉速）；
 * - 起步块 = 主池 chunkSize（快连接不会一上来被切成碎片）；
 * - 稳定后块 ≈ 单 worker 下载 TARGET_SECONDS 秒的量：快则大块（省 Range 开销），慢则小块（充分并行摊平拖尾）；
 * - 尾部收缩：剩余字节 <= workers*块 时，把块压到 remaining/workers，让全部连接并行冲完最后一段，
 *   彻底消除"其他分片下完、只剩最后一个线程慢慢爬"的拖尾塌缩。
 */
private class ElasticAllocator(
    private val total: Long,
    private val elasticStart: Long,
    private val workers: Int,
    private val chunkSize: Long
) {
    private val lock = Any()
    private var nextStart = elasticStart

    /** 最近实测总速度（字节/秒），由外部实时注入；<=0 表示尚未探测到 */
    @Volatile
    var recentSpeedBps: Long = 0L

    /** 领取下一个弹性块（按字节顺序；块大小随速度与剩余量动态收缩） */
    fun take(): LongRange? = synchronized(lock) {
        if (nextStart >= total) return null
        val remaining = total - nextStart
        val base = baseBlockSize()
        // ★ 尾部收缩：剩余不足 workers 个整块时，均分到约 workers 份，让全部连接忙到最后；
        //   下限 MIN_TAIL_BLOCK（不碎成海量请求），上限 base（块不会越滚越大）。
        val w = workers.coerceAtLeast(1)
        val block = if (remaining <= w * base) {
            (remaining / w).coerceIn(MIN_TAIL_BLOCK, base)
        } else {
            base
        }
        val s = nextStart
        val e = minOf(s + block - 1, total - 1)
        nextStart = e + 1
        s..e
    }

    /** 速度自适应块大小：块 ≈ 单 worker 下载 TARGET_SECONDS 秒的量，夹在 [MIN, MAX]；未探测到速度时沿用主池 chunkSize */
    private fun baseBlockSize(): Long {
        val w = workers.coerceAtLeast(1)
        val perWorker = (recentSpeedBps / w).coerceAtLeast(0L)
        if (perWorker <= 0L) return chunkSize.coerceIn(MIN_ELASTIC_BLOCK, MAX_ELASTIC_BLOCK)
        return (perWorker * TARGET_SECONDS).coerceIn(MIN_ELASTIC_BLOCK, MAX_ELASTIC_BLOCK)
    }

    /** 断点续传：跳过已下载前缀（nextStart 只前进） */
    fun skipTo(start: Long) = synchronized(lock) {
        if (start > nextStart) nextStart = start
    }

    companion object {
        /** 常态块下限：慢连接稳定态每块至少 256KB（避免过多请求）；快连接起步也不低于此 */
        const val MIN_ELASTIC_BLOCK = 256 * 1024L
        /** 常态块上限：快连接单块封顶 4MB（过大块会降低动态性/连接复用友好度） */
        const val MAX_ELASTIC_BLOCK = 4 * 1024 * 1024L
        /** 尾部收缩下限：最后一段可细到 64KB，尽量榨干全部连接；再小则 Range 开销不划算 */
        const val MIN_TAIL_BLOCK = 64 * 1024L
        /** 自适应支点：每块 ≈ 单 worker 下载此秒数（快则大块、慢则小块） */
        const val TARGET_SECONDS = 5L
    }
}

/**
 * 一个在飞分片的采样状态（慢连接抢占的判定依据，**永久结构，勿删**）。
 *
 * 累加已收字节（每个读块一次 `AtomicLong.addAndGet`，开销可忽略）、记住起点/块大小，供看门狗协程
 * 每 PREEMPT_TICK_MS 刷新一次瞬时速度（[lastBps]）；[preempt] / [preemptCount] / [lastPreemptAtMs]
 * 决定该路是否换连接续传——删掉它们收尾长尾就会回来（见 PREEMPT_MIN_BPS 注释）。
 */
private class InflightChunk(val start: Long, val size: Long) {
    val bytes = AtomicLong(0L)
    val startedAtMs = System.currentTimeMillis()

    /** 上一次快照时的字节数与时刻（只由看门狗协程读写） */
    var lastBytes = 0L
    var lastAtMs = startedAtMs

    /** 本次快照算出的瞬时速度（只由看门狗协程读写） */
    var lastBps = 0L

    /** ★ 慢连接抢占标志：置位后 ChunkDownloader 断开当前连接、换新连接从已收字节续传（不丢数据） */
    val preempt = AtomicBoolean(false)

    /** 本分片已被抢占次数（上限 PREEMPT_MAX） */
    var preemptCount = 0

    /** 上次被抢占的时刻（冷却期用） */
    var lastPreemptAtMs = 0L

    val elapsedMs: Long get() = System.currentTimeMillis() - startedAtMs
}

/**
 * 下载任务管理器：
 * - 任务持久化（Room），状态流转 PENDING → DOWNLOADING → COMPLETED / PAUSED / FAILED；
 * - 分片多线程下载（每片一个协程，信号量限并发）；
 * - 断点续传：part 文件保留，暂停/重启后从已有大小继续；
 * - 完成后合并分片并保存到公共 Download 目录。
 */
class DownloadManager(
    private val context: Context,
    private val dao: DownloadTaskDao,
    private val downloader: ChunkDownloader,
    /** 下载线程数提供者（按平台，可在设置中修改，动态生效），默认 32 */
    private val threadProvider: (String) -> Int = { 32 },
    /** 自定义下载保存目录提供者（SAF tree Uri，可空）；null 时保存到系统默认 Download */
    private val saveDirProvider: () -> String? = { null },
    /** 最大同时下载任务数提供者（默认 3）：限制后台并发任务，避免占满带宽/耗尽路由器连接 */
    private val concurrencyProvider: () -> Int = { 3 },
    /** 全局下载速度限制提供者（字节/秒；0 = 不限速） */
    private val speedLimitProvider: () -> Long = { 0L },
    /** 下载失败后自动重试次数提供者（默认 3，上限 10） */
    private val retryCountProvider: () -> Int = { 3 },
    /** 锁屏后保持下载开关（开启时获取 WakeLock 维持 Wi-Fi/CPU） */
    private val keepWhenLockedProvider: () -> Boolean = { true },
    /** 通知栏显示下载速度开关（false 时仅显示通知，隐藏速度） */
    private val showSpeedProvider: () -> Boolean = { true }
) {
    private val credentialCipher: CredentialCipher = AndroidKeystoreCredentialCipher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 下载引擎开关等设置（DownloadManager 是应用级单例，读一次足够） */
    private val settings by lazy { SettingsRepository(context) }

    /** 引擎任务 ID 的内存索引：DB 的 `engineTaskId` 是持久化真源，这里只给 pause/start/remove 做同步判断 */
    private val taskEngineIds = ConcurrentHashMap<Long, String>()

    /**
     * 已经计入「前台服务保活」的引擎任务。
     *
     * 引擎任务不走 [onTaskStarted]/[onTaskFinished] 的下载协程路径，保活要自己配对：**加入时**才
     * 拉起前台服务 + WakeLock，**移除时**才允许释放。用集合的 add/remove 返回值去重，
     * 保证无论从哪条路径终结（完成 / 失败 / 用户暂停 / 用户删除 / 引擎侧自己变 pause）都只扣一次。
     */
    private val engineKeepAliveIds = ConcurrentHashMap.newKeySet<Long>()

    /** 引擎任务进度同步协程（同一时刻只跑一个；没有可同步任务时自己退出） */
    private var engineSyncJob: Job? = null

    /** 引擎任务同步间隔（毫秒）：引擎调用是本进程内分发，不存在网络开销，可以问得勤一点 */
    private val engineSyncIntervalMs = 700L

    init {
        // 应用重启后历史引擎任务也要继续同步（引擎重启后它们一般是 pause，会被回写成本地「已暂停」）
        startEngineSync()
    }

    /** 当前实际下载中的任务数（用于最大同时下载任务数限制） */
    private val activeDownloads = java.util.concurrent.atomic.AtomicInteger(0)

    /** 全局限速器（令牌桶）：所有任务合计不超过 speedLimitProvider 的字节/秒 */
    private val speedLimiter = SpeedLimiter()

    /**
     * ★ 全进程在飞分片信号量（跨任务共享）。
     *   这里钉的是「同时在飞的下载请求数」，而不是设置里的线程数：分片 IO 都是**同步阻塞**的
     *   `call.execute()`，设置写 512 也只是「允许 512 路同时下」，真正的闸门是这道信号量
     *   （容量 [MAX_INFLIGHT_CHUNKS]，按最大堆预算推导、封顶 512）。
     *   OOM（见 log/oom）的历史机制在 OkHttp 侧：HTTP/2 客户端对外声明 **每流 16MB** 窗口
     *   （`OKHTTP_CLIENT_WINDOW_SIZE`），消费端一慢（写盘慢、限速挂起、落盘节流），读线程仍会把数据
     *   填进该流 readBuffer 直到 16MB 用完 —— 几十路叠加就能撑满 256MB 堆，两份 OOM 报告的栈正好
     *   落在 `Http2Stream$FramingSource.receive` / okio `SegmentPool` 上。现已从源头拆掉：
     *   ① 下载客户端固定 HTTP/1.1（无应用层流窗口，读多少由 TCP 背压决定，见 HttpClients.buildDownload）；
     *   ② 每路读缓冲 64KB（原本 256KB，[ChunkDownloader.BUFFER_SIZE]）；
     *   ③ 在飞路数由本信号量钉住：512 路 × 64KB = 32MB，占 256MB 堆的 1/8（FD 实测软限 32768，1100 个连接+句柄远未用满）；
     *   ④ 分片 IO 走专用线程池 `chunkIoDispatcher`（不再受 `Dispatchers.IO` 的 `max(64, 核数)` 限制）。
     *   ★ 绝不手动 release；也不要改回「每任务一个信号量」。
     */
    private val inflightLimiter = Semaphore(MAX_INFLIGHT_CHUNKS)

    /**
     * 保存前存储权限检查（Android 9- 写公共 Download 需 WRITE_EXTERNAL_STORAGE 运行时授权）。
     * UI 层注入：无权限时动态申请并等待授权结果；已授权/Android 10+ 直接返回 true。
     * 授权后会自动继续保存（同一协程 await 授权结果再往下走）。
     */
    var storagePermissionProvider: suspend () -> Boolean = { true }

    /**
     * 运行中的任务 Job：value 为 CompletableDeferred，注册/移除全程由 jobsLock 保护，
     * 保证 start/pause/remove 之间无 TOCTOU 竞态（防止"暂停/删除瞬间任务继续跑"）。
     */
    private val activeJobs = ConcurrentHashMap<Long, CompletableDeferred<Job>>()
    private val jobsLock = Any()

    /** 前台服务计数：有任务在下载时保持前台（避免切后台限速/进程被杀） */
    private val activeTaskCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 前台通知进度节流（毫秒）：2 秒更新一次，避免频繁刷新系统通知 */
    private val notifyThrottleMs = 2000L
    private val lastNotifyTs = AtomicLong(0)

    /** 合并阶段进度上报节流（毫秒）：分片合并回调很密，百分比不变时最多这么久报一次 */
    private val mergeReportIntervalMs = 300L

    /** 更新前台通知进度（2 秒节流；total<=0 时不确定进度，只更新标题；可显示下载速度） */
    private fun notifyProgress(id: Long, fileName: String, new: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTs.get() >= notifyThrottleMs) {
            lastNotifyTs.set(now)
            // ★ 内存观测（复用同一条 2 秒节流，无额外开销）：已用堆超 3/4 时打一行，
            //   让「日志导出」带上 OOM 前的堆时间线，事后不依赖 HPROF 也能看出趋势
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            if (used * 4 > rt.maxMemory() * 3) {
                Log.w(TAG, "内存高压 used=${used / 1024 / 1024}MB max=${rt.maxMemory() / 1024 / 1024}MB " +
                    "tasks=${_stats.value.size} inflightCap=$MAX_INFLIGHT_CHUNKS " +
                    "inflightUsed=${MAX_INFLIGHT_CHUNKS - inflightLimiter.availablePermits}")
            }
            val percent = if (total > 0) ((new * 100 / total).toInt().coerceIn(0, 100)) else -1
            val speed = _stats.value[id]?.speed ?: 0L
            val speedText = if (speed > 0) formatSpeed(speed) else ""
            DownloadService.update(context, fileName, percent, speedText, showSpeedProvider())
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSec.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return String.format("%.1f %s", value, units[i])
    }

    /**
     * 刷新每路在飞分片的瞬时速度（抢占判定的依据，不产生日志）。
     *
     * 每个采样周期调一次：`lastBps = 本周期新增字节 / 本周期耗时`，随后由 [preemptSlowChunks]
     * 挑出「远低于同伴」的那几路换连接续传。真实速度必须来自窗口差值（累计均速看不出刚变慢的连接）。
     */
    private fun sampleInflightChunks(diag: Map<String, InflightChunk>) {
        val now = System.currentTimeMillis()
        for (d in diag.values) {
            val bytes = d.bytes.get()
            d.lastBps = (bytes - d.lastBytes).coerceAtLeast(0L) * 1000 / (now - d.lastAtMs).coerceAtLeast(1L)
            d.lastBytes = bytes
            d.lastAtMs = now
        }
    }

    /**
     * ★ 慢连接抢占（永久逻辑）：把「跑得远低于同伴」的在飞分片换到新连接上续传。
     *
     * 判定阈值 = max(绝对下限 PREEMPT_MIN_BPS, 本任务平均单连接速度 / 2)：
     * 用相对值是为了适配不同 CDN 的限速档次（夸克单连接几十 KB/s、迅雷更低），绝对下限兜住
     * 「收尾只剩一两路、平均值被自己拉低」的退化情况。命中后只把 [InflightChunk.preempt] 置位，
     * 由 ChunkDownloader 断开连接并从已收字节续传——不丢数据、不退避、不改变对外结果。
     *
     * 每轮最多抢 PREEMPT_PER_TICK 路、单路最多 PREEMPT_MAX 次，避免全局慢时变成重连风暴。
     * 收尾（在飞 ≤ PREEMPT_ENDGAME_INFLIGHT）时放宽年龄/剩余两条门槛——真机日志里最后几百 KB
     * 常常只剩 1~3 路在磨，那几路的瞬时速度就是用户看到的「掉到 KB 级」。
     */
    private fun preemptSlowChunks(
        id: Long,
        downloaded: Long,
        elapsedMs: Long,
        workers: Int,
        diag: Map<String, InflightChunk>
    ) {
        if (diag.isEmpty()) return
        val avgPerConn = if (elapsedMs > 0 && workers > 0) downloaded * 1000 / elapsedMs / workers else 0L
        val floor = maxOf(PREEMPT_MIN_BPS, avgPerConn / 2)
        val now = System.currentTimeMillis()
        var taken = 0
        for (d in diag.values.sortedBy { it.lastBps }) {
            if (taken >= PREEMPT_PER_TICK) break
            if (d.preemptCount >= PREEMPT_MAX) continue
            if (now - d.lastPreemptAtMs < PREEMPT_COOLDOWN_MS) continue
            // 收尾（在飞 ≤ PREEMPT_ENDGAME_INFLIGHT）时放宽「跑够久」和「剩余够多」两条门槛：
            // 此时其余连接已经没活干，再等下去毫无意义，重连握手（~0.5s）远比慢连接磨完剩余字节便宜。
            if (diag.size <= PREEMPT_ENDGAME_INFLIGHT) {
                if (d.elapsedMs < PREEMPT_ENDGAME_MIN_AGE_MS) continue
            } else {
                if (d.elapsedMs < PREEMPT_MIN_AGE_MS) continue
                if (d.size - d.bytes.get() < PREEMPT_MIN_REMAIN) continue
            }
            if (d.lastBps >= floor) continue
            d.preemptCount++
            d.lastPreemptAtMs = now
            d.preempt.set(true)
            taken++
            Log.w(TAG, "runTask: id=$id 抢占慢连接 起点=${d.start} 块=${diagSize(d.size)} 已收=${diagSize(d.bytes.get())} " +
                "瞬时=${formatSpeed(d.lastBps)} 阈值=${formatSpeed(floor)} 第${d.preemptCount}/$PREEMPT_MAX 次（换连接续传）")
        }
    }

    /** 字节数转可读文本（抢占日志用） */
    private fun diagSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2fGB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format("%.1fMB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }

    /**
     * 进度落盘节流：多 worker 并发回调下，每 progressPersistIntervalMs 最多写一次 DB。
     * - force / (total>0 且 new>=total)：完成时强制写，确保最终进度准确；
     * - total<=0（大小未知）时仅按时间节流；
     * - 用 lastAt 的 CAS 保证并发下同一任务只有一个回调写库（避免多线程重复 UPDATE）。
     */
    private suspend fun persistProgressIfDue(
        id: Long,
        new: Long,
        total: Long,
        force: Boolean,
        lastAt: AtomicLong
    ) {
        val now = System.currentTimeMillis()
        val last = lastAt.get()
        if (force || (total > 0 && new >= total) || now - last >= progressPersistIntervalMs) {
            if (lastAt.compareAndSet(last, now)) {
                dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
            }
        }
    }

    /** 完成任务并写入平均速度（字节/秒）：avg = total / 本次运行耗时 */
    private suspend fun completeWithAvg(id: Long, savedPath: String, total: Long) {
        val start = taskStartTimes.remove(id) ?: 0L
        val elapsedSec = ((System.currentTimeMillis() - start) / 1000.0).coerceAtLeast(1.0)
        val avg = if (total > 0 && elapsedSec > 0) (total / elapsedSec).toLong() else 0L
        dao.complete(id, DownloadTaskEntity.STATUS_COMPLETED, savedPath, avg)
    }

    /** 每个任务一把互斥锁：暂停后立即恢复时避免新旧协程并发写分片 */
    private val taskLocks = ConcurrentHashMap<Long, Mutex>()

    /** 任务请求头（Cookie/UA），暂停后恢复仍需使用 */
    private val taskHeaders = ConcurrentHashMap<Long, Map<String, String>>()

    /** 已知文件大小（API 返回，避免探测失败）；-1 表示未知 */
    private val taskSizes = ConcurrentHashMap<Long, Long>()

    /** 镜像主 URL 的回退直连（仅 GitHub 等显式传入）；主 URL 探测失败时整任务切到此 URL 重下 */
    private val taskFallbackUrls = ConcurrentHashMap<Long, String>()

    /** 任务开始时间（毫秒）：完成时计算平均速度用（暂停/恢复会重置，表示最近一次运行段均值） */
    private val taskStartTimes = ConcurrentHashMap<Long, Long>()

    /** 任务下载完成后的清理回调（如删除网盘临时转存文件；下载成功后才触发） */
    private val taskCallbacks = ConcurrentHashMap<Long, suspend () -> Unit>()

    /** 实时下载统计（速度/剩余时间/线程数） */
    private val _stats = MutableStateFlow<Map<Long, DownloadStats>>(emptyMap())
    val stats: StateFlow<Map<Long, DownloadStats>> = _stats.asStateFlow()

    /** 进度落盘节流（毫秒）：updateProgress 写库会触发全表 Flow 重发 → 主线程全列表重组；
     *  按字节（256KB）节流时高速下载每秒写库几十次，主线程重组洪峰 → ANR。
     *  改为按时间节流落盘，UI 进度由内存 _stats 高频展示、DB 低频持久化（断点续传最多丢几百 ms 进度）。 */
    private val progressPersistIntervalMs = 500L

    val tasks: Flow<List<DownloadTaskEntity>> = dao.observeAll()

    /** 入队并立即开始下载 */
    suspend fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        /** 已知文件大小（字节）；-1 表示未知，需探测 */
        size: Long = -1L,
        /** 下载来源平台标识（按平台应用下载线程数设置）；通用/手动添加传空串 */
        platform: String = "",
        /** 镜像主 URL 不可达时的回退直连（默认空=不回退）；仅 GitHub 等镜像下载传入 */
        fallbackUrl: String = "",
        /** 下载成功完成后的清理回调（如删除网盘临时转存文件）；失败/取消不触发。
         * 注意：必须是最后一个参数（调用点有大量尾随 lambda 用法，放它之后会编译失败）。 */
        onComplete: suspend () -> Unit = {}
    ): Long {
        // 文件名兜底：空白时从 URL 推导，避免保存时变成时间戳
        val safeName = fileName.ifBlank {
            url.substringAfterLast('/').substringBefore('?')
                .ifBlank { "download_${System.currentTimeMillis()}" }
        }
        Log.d(TAG, "enqueue: origin=${LogRedactor.url(url)} fileName=$safeName headers=${headers.keys} size=$size")
        val id = dao.insert(
            DownloadTaskEntity(
                url = url,
                fileName = safeName,
                requestHeadersJson = encodeHeaders(headers),
                platform = platform
            )
        )
        // 保存请求头（Cookie/UA），暂停后恢复仍需携带
        if (headers.isNotEmpty()) taskHeaders[id] = headers
        if (size > 0) taskSizes[id] = size
        if (fallbackUrl.isNotBlank()) taskFallbackUrls[id] = fallbackUrl
        taskCallbacks[id] = onComplete
        if (shouldUseEngine(platform)) {
            // 任务登记、下载页展示、完成清理回调都与内置下载器完全一致，只有「执行」交给 Gopeed 引擎
            startViaEngine(id, url, safeName, headers, platform)
        } else {
            start(id, headers)
        }
        return id
    }

    /** 是否把任务交给 Gopeed 引擎：设置里选了引擎 + 引擎已导入；GitHub 走镜像回退（引擎不支持），始终用内置下载器 */
    private fun shouldUseEngine(platform: String): Boolean =
        platform != DownloadPlatform.GITHUB &&
            settings.downloadEngine == SettingsRepository.ENGINE_GOPEED &&
            GopeedEngine.isInstalled(context)

    /**
     * 用 Gopeed 引擎开始下载：确保引擎在跑 → 建任务 → 引擎任务 ID 写回本地记录 → 拉起进度同步。
     *
     * 建任务失败按普通失败任务落库（errorMsg 写引擎原文），不做静默回退到内置下载器——
     * 用户明确选了引擎，悄悄换下载器比报错更难排查。
     */
    private suspend fun startViaEngine(
        id: Long,
        url: String,
        fileName: String,
        headers: Map<String, String>,
        platform: String
    ) {
        val engineTaskId = try {
            withContext(Dispatchers.IO) {
                val dir = GopeedEngine.resolveDownloadDir(context)
                if (GopeedEngine.state.value != GopeedEngine.State.RUNNING) {
                    GopeedEngine.start(context, dir)
                }
                GopeedEngine.createTask(
                    url = url,
                    saveDir = dir,
                    headers = headers,
                    connections = threadProvider(platform),
                    name = fileName,
                    labels = mapOf("yunxTaskId" to id.toString())
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "引擎建任务失败：id=$id ${e.message}", e)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(id, e.message ?: e.toString())
            return
        }
        Log.d(TAG, "引擎任务已创建：yunxId=$id engineId=$engineTaskId")
        taskEngineIds[id] = engineTaskId
        // 记录本段运行起点：完成时用它算平均速度
        taskStartTimes[id] = System.currentTimeMillis()
        // 保活：第一个引擎任务拉起前台服务 + WakeLock，锁屏/退后台引擎才不会被系统收掉
        if (engineKeepAliveIds.add(id)) onTaskStarted(id)
        dao.updateEngineTaskId(id, engineTaskId)
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        startEngineSync()
    }

    /**
     * 引擎任务进度同步：把引擎侧状态回写到本地记录（下载页读的还是 Room，所以必须回写），
     * 完成时触发 onComplete 清理回调。没有可同步任务就自动退出，下次建引擎任务时再拉起。
     *
     * 每轮先确保引擎在运行：进程重启后引擎里可能还有未完成任务（引擎自己持久化在 bolt 里），
     * 引擎重启后这些任务一般是 pause 状态，会被回写成本地「已暂停」，由用户决定是否继续。
     */
    private fun startEngineSync() {
        if (engineSyncJob?.isActive == true) return
        engineSyncJob = scope.launch {
            while (isActive) {
                val pending = runCatching { dao.listSyncableEngineTasks() }.getOrDefault(emptyList())
                if (pending.isEmpty()) return@launch
                if (GopeedEngine.state.value != GopeedEngine.State.RUNNING) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            GopeedEngine.start(context, GopeedEngine.resolveDownloadDir(context))
                        }
                    }.onFailure { Log.e(TAG, "引擎同步时启动引擎失败：${it.message}", it) }
                }
                for (task in pending) {
                    taskEngineIds[task.id] = task.engineTaskId
                    val status = runCatching {
                        withContext(Dispatchers.IO) { GopeedEngine.taskStatus(task.engineTaskId) }
                    }.getOrNull() ?: continue
                    when (status.status) {
                        "done" -> completeEngineTask(task, status.total)
                        "error" -> {
                            Log.e(TAG, "引擎任务失败：yunxId=${task.id} engineId=${task.engineTaskId}")
                            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_FAILED)
                            dao.updateError(task.id, "Gopeed 引擎下载失败")
                            _stats.update { it - task.id }
                            taskEngineIds.remove(task.id)
                            taskStartTimes.remove(task.id)
                            releaseEngineTaskMemory(task.id)
                            if (engineKeepAliveIds.remove(task.id)) onTaskFinished()
                            DownloadService.notifyResult(
                                context, task.id, task.fileName,
                                success = false, error = "Gopeed 引擎下载失败",
                                promote = showSpeedProvider()
                            )
                        }
                        "pause" -> {
                            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PAUSED)
                            _stats.update { it - task.id }
                            // 引擎侧自己变暂停（进程重启后未续传等）：保活到此为止，用户点继续时会重新拉起
                            if (engineKeepAliveIds.remove(task.id)) onTaskFinished()
                        }
                        else -> {
                            val total = if (status.total > 0L) status.total else task.totalSize
                            dao.updateProgress(
                                task.id,
                                DownloadTaskEntity.STATUS_DOWNLOADING,
                                status.downloaded,
                                total
                            )
                            // 实时速度直接取引擎给的（TaskRuntimeStatus.speed），剩余时间自己算
                            _stats.update {
                                it + (task.id to DownloadStats(
                                    speed = status.speed,
                                    remainMillis = if (status.speed > 0L && total > status.downloaded) {
                                        (total - status.downloaded) * 1000L / status.speed
                                    } else {
                                        -1L
                                    },
                                    chunkCount = threadProvider(task.platform).coerceAtLeast(1)
                                ))
                            }
                            // 前台通知：与内置下载器共用同一条 2 秒节流和速度来源（_stats），所以写在 _stats 之后
                            notifyProgress(task.id, task.fileName, status.downloaded, total)
                        }
                    }
                }
                delay(engineSyncIntervalMs)
            }
        }
    }

    /** 引擎任务完成：状态/保存路径/平均速度落库 + 触发清理回调（语义与内置下载器的完成路径一致） */
    private suspend fun completeEngineTask(task: DownloadTaskEntity, size: Long) {
        val savedPath = File(GopeedEngine.resolveDownloadDir(context), task.fileName).absolutePath
        // 平均速度 = 引擎给出的总大小 ÷ 本段运行时长（暂停/继续会重置起点，与内置下载器口径一致）
        val startedAt = taskStartTimes.remove(task.id) ?: 0L
        val avgSpeed = if (startedAt > 0L && size > 0L) {
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            size * 1000L / elapsed
        } else {
            0L
        }
        dao.complete(task.id, DownloadTaskEntity.STATUS_COMPLETED, savedPath, avgSpeed)
        _stats.update { it - task.id }
        Log.d(TAG, "引擎任务完成：yunxId=${task.id} engineId=${task.engineTaskId} path=$savedPath size=$size avg=$avgSpeed")
        taskEngineIds.remove(task.id)
        releaseEngineTaskMemory(task.id)
        // 保活收尾 + 结果通知（与内置下载器走同一条前台通知通道）
        if (engineKeepAliveIds.remove(task.id)) onTaskFinished()
        DownloadService.notifyResult(
            context, task.id, task.fileName,
            success = true, promote = showSpeedProvider()
        )
        taskCallbacks.remove(task.id)?.let { cb -> runCatching { cb() } }
    }

    private fun releaseEngineTaskMemory(id: Long) {
        taskHeaders.remove(id)
        taskSizes.remove(id)
        taskFallbackUrls.remove(id)
    }

    /** 引擎任务暂停：异步转发给引擎，状态由同步循环回写 */
    private fun pauseEngineTask(id: Long, engineId: String) {
        _stats.update { it - id }
        // 用户主动暂停：保活立刻收尾，前台服务/WakeLock 不再为它保持
        if (engineKeepAliveIds.remove(id)) onTaskFinished()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GopeedEngine.pauseTask(engineId) } }
                .onFailure { Log.e(TAG, "引擎暂停失败：id=$id ${it.message}", it) }
            dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
        }
    }

    /** 引擎任务继续：转发 continue 并重新拉起同步 */
    private fun resumeEngineTask(id: Long, engineId: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GopeedEngine.continueTask(engineId) } }
                .onFailure { Log.e(TAG, "引擎继续失败：id=$id ${it.message}", it) }
            // 本段运行起点重置：平均速度口径与内置下载器一致（最近一段运行）
            taskStartTimes[id] = System.currentTimeMillis()
            // 继续下载：重新拉起前台服务保活（暂停时刚刚收尾过）
            if (engineKeepAliveIds.add(id)) onTaskStarted(id)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
            startEngineSync()
        }
    }

    /**
     * 重新下载：用原直链新建任务（任务卡长按菜单「重新下载」）。
     * 先做 Range 探测校验直链有效性：403/404/网络错误视为直链已过期，返回 false 由 UI 提示。
     */
    suspend fun redownload(id: Long): Boolean {
        val task = dao.get(id) ?: return false
        val headers = loadPersistedHeaders(id)
        val valid = runCatching { downloader.getTotalSize(task.url, headers) != null }.getOrDefault(false)
        if (!valid) return false
        enqueue(task.url, task.fileName, headers, task.totalSize, task.platform)
        return true
    }

    /** 开始/恢复下载（断点续传） */
    fun start(id: Long, headers: Map<String, String> = emptyMap()) {
        // 引擎任务绝不能落到内置分片下载器（会重复下载同一个 URL）：转发 continue 即可
        taskEngineIds[id]?.let { engineId ->
            resumeEngineTask(id, engineId)
            return
        }
        // 恢复时未传 headers：沿用入队时保存的（Cookie/UA 对直链下载是必需的）
        val effectiveHeaders = headers.ifEmpty { taskHeaders[id] ?: emptyMap() }
        Log.d(TAG, "start: id=$id headers=${effectiveHeaders.keys}")
        synchronized(jobsLock) {
            // 原子注册：检查 + 占位 + launch + complete 在同一锁内完成，
            // pause/remove 要么拿到已注册的 job，要么拿不到（视为未运行）
            val existing = activeJobs[id]
            if (existing != null) {
                // job 仍活跃（正在下载/收尾）：忽略本次 start，避免重复启动
                if (existing.isCompleted && existing.getCompleted().isActive) return
                // job 已结束但 finally 尚未清理（暂停后立即恢复的残留）：
                // 移除旧引用，继续注册新 job，保证"点开始"立即生效
                activeJobs.remove(id)
            }
            val deferred = CompletableDeferred<Job>()
            activeJobs[id] = deferred
            val job = scope.launch {
                try {
                    // 任务开始：有任务在下载时保持前台服务（避免切后台限速/进程被杀）
                    onTaskStarted(id)
                    // 任务级互斥：同一任务串行执行，暂停后立刻恢复不会并发写分片
                    taskLocks.getOrPut(id) { Mutex() }.withLock {
                        val restoredHeaders = if (effectiveHeaders.isNotEmpty()) {
                            effectiveHeaders
                        } else {
                            loadPersistedHeaders(id)
                        }
                        if (restoredHeaders.isNotEmpty()) taskHeaders[id] = restoredHeaders
                        runTaskWithRetry(id, restoredHeaders)
                    }
                } catch (e: CancellationException) {
                    // 主动暂停/删除：part 文件保留（或由 remove 清理）；状态已由调用方设置
                    _stats.update { it - id }
                } catch (e: Exception) {
                    _stats.update { it - id }
                    // 协程已被取消（暂停/删除）：不标记失败，避免覆盖 PAUSED 状态
                    if (isTaskActive()) {
                        val reason = e.message ?: e.javaClass.simpleName
                        Log.e(TAG, "task $id failed: $reason", e)
                        dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
                        dao.updateError(id, reason)
                        // 终态通知：失败同样先出流体云胶囊，随后转为可划掉的普通通知
                        DownloadService.notifyResult(
                            context, id, dao.get(id)?.fileName ?: "下载任务",
                            success = false, error = reason, promote = showSpeedProvider()
                        )
                    } else {
                        Log.w(TAG, "task $id cancelled: ${e.message}")
                    }
                } finally {
                    // 任务结束（成功/失败/暂停/删除）：无任务时停止前台服务
                    onTaskFinished()
                    // 只移除自己注册的 deferred：
                    // 若暂停后立即恢复（新 job 已注册到同一 id），不能误删新任务的注册，
                    // 否则新任务将无法再被暂停/删除（后台继续下载）
                    synchronized(jobsLock) {
                        if (activeJobs[id] === deferred) activeJobs.remove(id)
                    }
                    // 注意：taskLocks 不在此清理 —— 若新任务已 getOrPut 拿到锁，
                    // 旧任务 finally 的 remove 会误删新任务的锁导致并发写分片
                }
            }
            // launch 是同步返回 Job 的，锁内 complete，pause/remove 的 await 立即返回
            deferred.complete(job)
        }
    }

    /** 任务开始/结束计数：控制前台服务生命周期（有任务在下载即保持前台） */
    private suspend fun onTaskStarted(id: Long) {
        if (activeTaskCount.getAndIncrement() == 0) {
            val name = runCatching { dao.get(id)?.fileName }.getOrNull() ?: "下载任务"
            DownloadService.start(context, name)
        }
        // 锁屏保持下载：开启时获取 PARTIAL_WAKE_LOCK（息屏维持 CPU/网络）
        acquireWakeLockIfNeeded()
    }

    private fun onTaskFinished() {
        if (activeTaskCount.decrementAndGet() <= 0) {
            activeTaskCount.set(0)
            DownloadService.stop(context)
            releaseWakeLock()
        }
    }

    // ---------- 锁屏保持下载（WakeLock） ----------

    @Volatile
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLockIfNeeded() {
        if (!keepWhenLockedProvider()) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "yunx:download"
            ).apply { setReferenceCounted(false) }
        }
        wakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /** 暂停下载（保留 part 文件与请求头） */
    fun pause(id: Long) {
        Log.d(TAG, "pause: id=$id")
        // 引擎任务：额外通知引擎暂停；本地状态与清理继续走下面的原逻辑（引擎任务没有分片文件，两步互不干扰）
        taskEngineIds.remove(id)?.let { engineId -> pauseEngineTask(id, engineId) }
        // 立即中断该任务所有分片网络请求（不依赖协程取消传播，阻塞 IO 马上停止）
        downloader.cancelCalls(id)
        val deferred = synchronized(jobsLock) { activeJobs.remove(id) }
        _stats.update { it - id }
        scope.launch {
            // 等协程真正退出（确保没有半截写入）后，以磁盘 part/seg 真实大小为准回写进度：
            // 暂停瞬间最后一次 onBytes 可能被取消丢弃，DB 落后于磁盘 → 恢复时进度回跳
            cancelAndAwaitExit(id, deferred)
            val real = chunkDirOf(id).listFiles()
                ?.filter {
                    it.name.startsWith("part_") ||
                        (it.name.startsWith("seg_") && it.name.endsWith(".part"))
                }
                ?.sumOf { it.length() } ?: 0L
            val t = dao.get(id)
            if (t != null && real > t.downloadedSize) {
                dao.updateProgress(id, DownloadTaskEntity.STATUS_PAUSED, real, t.totalSize)
            } else {
                dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
            }
        }
    }

    /**
     * 删除任务：取消下载 + 清 DB + 清 part 文件。
     * @param deleteLocal 同时删除已保存到本地的文件（savePath）
     */
    fun remove(id: Long, deleteLocal: Boolean = false) {
        Log.d(TAG, "remove: id=$id deleteLocal=$deleteLocal")
        // 引擎任务：额外通知引擎删除；本地清理（DB 记录 + 已下载文件）继续走下面的原逻辑
        taskEngineIds.remove(id)?.let { engineId ->
            releaseEngineTaskMemory(id)
            // 用户删除：保活收尾（未完成的任务被删掉后，前台服务不该继续挂着）
            if (engineKeepAliveIds.remove(id)) onTaskFinished()
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { GopeedEngine.deleteTask(engineId) } }
                    .onFailure { Log.e(TAG, "引擎删除任务失败：id=$id ${it.message}", it) }
            }
        }
        // 立即中断该任务所有分片网络请求
        downloader.cancelCalls(id)
        _stats.update { it - id }
        taskHeaders.remove(id)
        taskFallbackUrls.remove(id)
        // 删除任务同样触发清理回调（如删除网盘临时转存文件）：
        // 用户放弃下载时云盘里已转存的临时文件也应一并清理
        val cleanup = taskCallbacks.remove(id)
        taskLocks.remove(id)
        val deferred = synchronized(jobsLock) { activeJobs.remove(id) }
        scope.launch {
            // 若任务正在下载：取消并**有界**等待协程退出，
            // 确保没有后台残留下载、part 文件无 fd 占用（否则删了仍占空间）。
            // 超时也继续清理：写满磁盘时 MediaProvider/FUSE 操作可能长时间不返回，
            // 旧实现的无界等待会让「删除」看起来完全没反应（幽灵任务）。
            cancelAndAwaitExit(id, deferred)
            if (deleteLocal) {
                dao.get(id)?.savePath?.let {
                    val deleted = DownloadSaver.delete(context, it)
                    Log.d(TAG, "remove: id=$id 删除本地文件 ${if (deleted) "成功" else "失败/未找到"} ($it)")
                }
            }
            dao.delete(id)
            chunkDirOf(id).deleteRecursively()
            // 旧版本遗留的私有合并副本（本版本已不再产生，见 finishDownload）；失败不阻断
            File(context.cacheDir, "merged_$id").delete()
            // 删除任务后清理云盘转存（与下载成功完成同语义）；失败不阻断
            cleanup?.let { runCatching { it() } }
        }
    }

    /**
     * 取消任务协程并等待其退出。
     * 阻塞式 IO（大文件写盘、MediaProvider 调用）不响应取消，无界等待会让暂停/删除按钮「点不动」，
     * 故等待设上限：超时后照常执行后续清理，残留协程会因分片目录被删而自行失败。
     */
    private suspend fun cancelAndAwaitExit(id: Long, deferred: CompletableDeferred<Job>?) {
        val job = deferred?.await() ?: return
        job.cancel()
        if (withTimeoutOrNull(JOB_EXIT_WAIT_MS) { job.join() } == null) {
            Log.w(TAG, "等待任务协程退出超时 id=$id（阻塞式 IO 未响应取消），继续清理")
        }
    }

    // ---------- 内部实现 ----------

    private fun encodeHeaders(headers: Map<String, String>): String {
        val json = JSONObject().apply { headers.forEach { (name, value) -> put(name, value) } }.toString()
        return credentialCipher.encrypt(json, "download.requestHeaders")
    }

    private suspend fun loadPersistedHeaders(id: Long): Map<String, String> {
        val stored = dao.get(id)?.requestHeadersJson.orEmpty()
        if (stored.isBlank()) return emptyMap()
        return runCatching {
            val jsonText = credentialCipher.decrypt(stored, "download.requestHeaders")
            val json = JSONObject(jsonText)
            buildMap {
                json.keys().forEach { name -> put(name, json.getString(name)) }
            }.also {
                if (!credentialCipher.isEncrypted(stored)) {
                    dao.updateRequestHeaders(id, encodeHeaders(it))
                }
            }
        }.getOrElse {
            dao.updateRequestHeaders(id, encodeHeaders(emptyMap()))
            emptyMap()
        }
    }

    /** 当前协程是否仍活跃（暂停/删除触发取消后为 false） */
    private suspend fun isTaskActive(): Boolean = coroutineContext[Job]?.isActive == true

    /** 等待并发许可：当前下载任务数 >= 上限时轮询等待（暂停/取消可退出等待） */
    private suspend fun awaitConcurrencySlot() {
        val max = concurrencyProvider().coerceAtLeast(1)
        while (isTaskActive() && activeDownloads.get() >= max) {
            delay(300)
        }
    }

    /**
     * 执行任务并支持失败自动重试（断点续传，part 文件保留）。
     * 同时负责「最大同时下载任务数」并发许可的获取/释放。
     */
    private suspend fun runTaskWithRetry(id: Long, headers: Map<String, String>) {
        var attempts = 0
        val maxRetries = retryCountProvider().coerceIn(0, 10)
        while (true) {
            // 并发许可：排队等待，直到有空闲下载槽位（或任务被暂停/取消）
            awaitConcurrencySlot()
            if (!isTaskActive()) return
            activeDownloads.incrementAndGet()
            try {
                try {
                    runTask(id, headers)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempts++
                    if (isTaskActive() && attempts <= maxRetries) {
                        Log.d(TAG, "runTaskWithRetry: id=$id 失败，自动重试 $attempts/$maxRetries：${e.message}")
                        // 逐次递增延迟，避免失败风暴
                        delay(1200L * attempts)
                    } else {
                        throw e
                    }
                }
            } finally {
                activeDownloads.decrementAndGet()
            }
        }
    }

    private suspend fun runTask(id: Long, headers: Map<String, String>) {
        // 协程已被取消（暂停/删除）：直接退出，不写状态
        if (!isTaskActive()) return
        var task = dao.get(id) ?: return
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        taskStartTimes[id] = System.currentTimeMillis()
        Log.d(TAG, "runTask: id=$id fileName=${task.fileName}")

        // HLS（m3u8 转码流，如 UC play）：不走 Range 分片，直接拉分片合并
        if (task.url.contains(".m3u8", true) || task.url.contains(".m3u", true)) {
            Log.d(TAG, "runTask: id=$id HLS 转码流下载 origin=${LogRedactor.url(task.url)}")
            hlsDownload(id, task, headers)
            return
        }

        // 总大小以服务器探测为准（Range0-0 的 Content-Range 是真实总大小），
        // 避免各平台传入的 size 与实际不符导致分片区间错误 → 文件截断/膨胀损坏
        // 镜像主 URL 探测失败且带回退直连时（GitHub 下载），整任务切到原始直连重下，避免镜像挂掉整任务失败
        val fallbackUrl = taskFallbackUrls[id]
        var probedSize = downloader.getTotalSize(task.url, headers)
        if (probedSize == null && !fallbackUrl.isNullOrBlank()) {
            Log.w(TAG, "runTask: id=$id 镜像主 URL 不可达，回退原始直连下载")
            task = task.copy(url = fallbackUrl)
            probedSize = downloader.getTotalSize(task.url, headers)
        }
        val total = probedSize
            ?: taskSizes[id]?.takeIf { it > 0 }
        if (total == null) {
            // 服务器不返回文件大小（Range/Content-Length 均缺失）：降级为流式下载（开放区间 Range）
            Log.w(TAG, "runTask: id=$id 无法获取总大小，降级流式下载 origin=${LogRedactor.url(task.url)}")
            streamDownload(id, task, headers)
            return
        }
        Log.d(TAG, "getTotalSize: id=$id total=$total origin=${LogRedactor.url(task.url)}")
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, total)
        // 取到大小后再次检查取消（暂停可能发生在 getTotalSize 期间）
        if (!isTaskActive()) return

        val threadCount = threadProvider(task.platform).coerceAtLeast(1)
        val chunkCount = chunkCountFor(total, threadCount)
        val chunkSize = ceil(total.toDouble() / chunkCount).toLong()
        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        // ★ 分片计划签名：part_$i 按索引命名，但区间由 chunkCount/total 推导。
        //   若跨会话改了线程数或服务器探测大小变化 → 旧 part 区间错位 → 续传膨胀/损坏。
        //   检测到计划不一致时整目录清空重下（旧 part 不可信）。
        val mainPoolCount = (chunkCount * 0.7).toInt().coerceIn(1, chunkCount) // 主池片数（70%）
        val elasticStart = mainPoolCount * chunkSize                          // 弹性区起始字节
        val planFile = File(chunkDir, "plan.txt")
        val plan = "chunks=$chunkCount total=$total main=$mainPoolCount"
        if (planFile.exists() && planFile.readText() != plan) {
            Log.w(TAG, "runTask: id=$id 分片计划变化（$plan），清空旧 part 重下")
            chunkDir.deleteRecursively()
            chunkDir.mkdirs()
        } else {
            // 计划一致（断点续传）：主池 part_i 与弹性区 seg_{start}_{end} 均按文件已有长度续传
            // （seg 文件名携带区间信息，downloadChunk 按长度续传，不再删除重下）
        }
        planFile.writeText(plan)
        // 有效并发：仅迅雷（CDN 对单文件并发 Range 有阈值，约 8 个，超过会降级 200 整文件）封顶安全上限；
        // 其他平台保持用户设置的线程数（满并发）
        val isXunlei = headers["User-Agent"]?.contains("xunlei", ignoreCase = true) == true ||
            task.url.contains("xunlei", ignoreCase = true)
        val effectiveWorkers = if (isXunlei) {
            min(threadCount, RANGE_WORKERS_CAP).coerceAtLeast(1)
        } else {
            threadCount.coerceAtLeast(1)
        }
        // ★ 实际 worker 再钳一道「全进程在飞上限」（设置页档位已同步收到 64，这里是兜底）：
        //   真实并行度受 Dispatchers.IO（默认 64）与内存预算约束，超出的 worker 只会排队等
        //   信号量、白白多占协程与排队 Call；钳掉后吞吐不变（分片盈余仍由任务池 + 弹性区提供）。
        //   注意：threadCount 仍原样传给 chunkCountFor —— plan.txt 签名不能变，否则断点续传失效。
        val actualWorkers = min(effectiveWorkers, MAX_INFLIGHT_CHUNKS)
        Log.d(TAG, "分片规划: id=$id chunks=$chunkCount main=$mainPoolCount elasticStart=$elasticStart " +
            "size=$chunkSize threads=$threadCount effectiveWorkers=$effectiveWorkers " +
            "actualWorkers=$actualWorkers inflightCap=$MAX_INFLIGHT_CHUNKS isXunlei=$isXunlei")

        // 注册实时统计：线程数 = 实际并发（受安全上限与内存预算约束）
        _stats.update { it + (id to DownloadStats(0L, -1L, actualWorkers)) }

        // 统计已有 part/seg 大小（断点续传起点；主池 + 弹性区均按磁盘真实长度）
        val downloaded = AtomicLong(0)
        (0 until mainPoolCount).forEach { i ->
            downloaded.addAndGet(File(chunkDir, "part_$i").length())
        }
        chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.forEach { downloaded.addAndGet(it.length()) }
        // ★ 钳制到 total：防旧 job 残留累加导致显示"已下载 > 总大小"
        val init = minOf(downloaded.get(), total)
        downloaded.set(init)
        // ★ 恢复时 DB 旧值可能滞后于磁盘（暂停瞬间未上报的字节）：以磁盘真实大小为准回写，避免进度回跳
        if (init > task.downloadedSize) {
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, init, total)
        }
        val lastPersistAt = AtomicLong(0L)
        val speedRecorder = SpeedRecorder()

        // ---------- 任务池（主池 70% 等分）+ 弹性区（30%，空闲线程中点劈分） ----------
        val results = arrayOfNulls<ChunkResult?>(mainPoolCount)
        val nextIdx = AtomicInteger(0)
        val fallback = AtomicBoolean(false)              // 任一分片检测到「服务器忽略 Range」→ 整任务回退单流
        val failReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val rangeIgnoredCount = AtomicInteger(0)         // RANGE_IGNORED 累计次数（偶发 200 容忍）

        // ★ 弹性区分配器（IDM 式动态分片）：按字节顺序发块、区间物理相邻，替代中点劈分（根治中后段掉速）。
        //   续传：不完整 seg 删除重下；完整 seg 前缀推进 nextStart（弹性区按序分配，完成块天然是字节前缀）。
        //   块大小随实时速度自适应并尾部收缩，保证全部连接忙到最后一块，消除"末尾只剩少数大块 → 拖尾特别慢"。
        val elasticAllocator = ElasticAllocator(total, elasticStart, actualWorkers, chunkSize)
        if (elasticStart < total) {
            // 不完整 seg 删除（重下）
            chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }?.forEach { f ->
                val name = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = name.substringBefore('_').toLongOrNull() ?: return@forEach
                val e = name.substringAfter('_').toLongOrNull() ?: return@forEach
                if (f.length() < (e - s + 1)) f.delete()
            }
            // 推进到已完整前缀末尾（只前进，跳过已下载弹性块）
            val doneSegs = chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
                ?.mapNotNull { f ->
                    val name = f.name.removePrefix("seg_").removeSuffix(".part")
                    val s = name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                    val e = name.substringAfter('_').toLongOrNull() ?: return@mapNotNull null
                    if (f.length() >= (e - s + 1)) s to e else null
                }?.sortedBy { it.first } ?: emptyList()
            var resumeNext = elasticStart
            for ((s, e) in doneSegs) {
                if (s == resumeNext) resumeNext = e + 1 else break
            }
            elasticAllocator.skipTo(resumeNext)
        }
        val elasticResults = ConcurrentHashMap<String, ChunkResult>()
        // 在飞分片表（key = m<片号> / seg@<起点> / retry@<起点>）：仅供看门狗算瞬时速度与抢占判定
        val inflightChunks = ConcurrentHashMap<String, InflightChunk>()

        val allOk = coroutineScope {
            // ★ worker 数已钳到 actualWorkers；在飞槽位由全进程共享的 inflightLimiter 控制
            //   （见字段注释：绝不手动 release，也不要改回「每任务一个信号量」）
            val workers = List(actualWorkers) {
                async(chunkIoDispatcher) {
                    // 阶段 1：主池循环领取
                    while (true) {
                        if (fallback.get()) break
                        val i = nextIdx.getAndIncrement()
                        if (i >= mainPoolCount) break
                        // 错峰建连：首请求前按序号微延迟，平摊 TCP/TLS 突发（仅影响首请求，不影响稳态并发）
                        if (i > 0) delay(min(i.toLong(), STAGGER_CAP.toLong()) * STAGGER_MS)
                        inflightLimiter.withPermit {
                            if (fallback.get()) return@withPermit
                            val start = i * chunkSize
                            val end = min(start + chunkSize - 1, total - 1)
                            // 登记在飞分片（key=m<片号>）：看门狗据此算单路瞬时速度、判定慢连接抢占
                            val diagKey = "m${i + 1}"
                            val diag = InflightChunk(start, end - start + 1)
                            inflightChunks[diagKey] = diag
                            val res = try {
                                downloader.downloadChunk(
                                    taskId = id, url = task.url, start = start, end = end,
                                    partFile = File(chunkDir, "part_$i"), headers = headers,
                                    preempt = diag.preempt
                                ) { bytes ->
                                    speedLimiter.awaitAllow(bytes)
                                    diag.bytes.addAndGet(bytes)   // 采样：供看门狗算瞬时速度
                                    // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
                                    val new = minOf(downloaded.addAndGet(bytes), total)
                                    if (!isTaskActive()) return@downloadChunk
                                    speedRecorder.onBytes(new)?.let { speed ->
                                        // ★ 实时速度注入弹性分配器：块大小随真实吞吐自适应（IDM 式动态分片）
                                        elasticAllocator.recentSpeedBps = speed
                                        val remain = if (speed > 0) (total - new) * 1000 / speed else -1L
                                        _stats.update { it + (id to DownloadStats(speed, remain, actualWorkers)) }
                                    }
                                    notifyProgress(id, task.fileName, new, total)
                                    persistProgressIfDue(id, new, total, force = false, lastAt = lastPersistAt)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failReason.compareAndSet(null, "分片 ${i + 1}/$mainPoolCount：${e.message ?: e.javaClass.simpleName}")
                                ChunkResult.FAILED
                            } finally {
                                inflightChunks.remove(diagKey)
                            }
                            results[i] = res
                            when (res) {
                                ChunkResult.RANGE_IGNORED -> {
                                    // 偶发 200（CDN 限流中间态）不算真降级：前 N 次不触发回退，继续领新片；
                                    // 持续 RANGE_IGNORED 才回退单流
                                    val n = rangeIgnoredCount.incrementAndGet()
                                    Log.w(TAG, "runTask: id=$id 分片${i + 1} 检测到服务器忽略Range（累计 $n/$RANGE_IGNORED_TOLERANCE）")
                                    if (n >= RANGE_IGNORED_TOLERANCE) fallback.compareAndSet(false, true)
                                }
                                ChunkResult.FAILED -> failReason.compareAndSet(null, "分片 ${i + 1}/$mainPoolCount 下载失败")
                                else -> {}
                            }
                        }
                    }
                    // 阶段 2：主池取空 → 弹性区按字节顺序领取 4MB 块（空闲线程逐个平滑转入，并发形态不突变）
                    while (!fallback.get()) {
                        val range = elasticAllocator.take() ?: break
                        val s = range.first
                        val e = range.last
                        val key = "${s}_${e}"
                        // 登记在飞弹性块（key=seg@<起点>）：看门狗据此算单路瞬时速度、判定慢连接抢占
                        val diagKey = "seg@$s"
                        val diag = InflightChunk(s, e - s + 1)
                        inflightChunks[diagKey] = diag
                        val res = try {
                            inflightLimiter.withPermit {
                                if (fallback.get()) return@withPermit ChunkResult.FAILED
                                downloader.downloadChunk(
                                    taskId = id, url = task.url, start = s, end = e,
                                    partFile = File(chunkDir, "seg_$key.part"), headers = headers,
                                    preempt = diag.preempt
                                ) { bytes ->
                                    speedLimiter.awaitAllow(bytes)
                                    diag.bytes.addAndGet(bytes)   // 采样：供看门狗算瞬时速度
                                    // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
                                    val new = minOf(downloaded.addAndGet(bytes), total)
                                    if (!isTaskActive()) return@downloadChunk
                                    speedRecorder.onBytes(new)?.let { speed ->
                                        // ★ 实时速度注入弹性分配器：块大小随真实吞吐自适应（IDM 式动态分片）
                                        elasticAllocator.recentSpeedBps = speed
                                        val remain = if (speed > 0) (total - new) * 1000 / speed else -1L
                                        _stats.update { it + (id to DownloadStats(speed, remain, actualWorkers)) }
                                    }
                                    notifyProgress(id, task.fileName, new, total)
                                    persistProgressIfDue(id, new, total, force = false, lastAt = lastPersistAt)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ChunkResult.FAILED
                        } finally {
                            inflightChunks.remove(diagKey)
                        }
                        elasticResults[key] = res
                        when (res) {
                            ChunkResult.RANGE_IGNORED -> {
                                val n = rangeIgnoredCount.incrementAndGet()
                                Log.w(TAG, "runTask: id=$id 弹性区间 $key 检测到服务器忽略Range（累计 $n/$RANGE_IGNORED_TOLERANCE）")
                                if (n >= RANGE_IGNORED_TOLERANCE) fallback.compareAndSet(false, true)
                            }
                            ChunkResult.FAILED -> failReason.compareAndSet(null, "弹性区间 ${s}-${e} 下载失败")
                            else -> {}
                        }
                    }
                }
            }
            // 看门狗：周期刷新每路瞬时速度，并判定是否把慢连接换掉（worker 全部跑完即停）
            val preemptJob = launch(Dispatchers.IO) {
                val runStartMs = System.currentTimeMillis()
                while (true) {
                    delay(PREEMPT_TICK_MS)
                    // ★ 慢连接抢占（永久逻辑，勿删）：先刷新瞬时速度，再以本任务的平均单连接速度为参照
                    sampleInflightChunks(inflightChunks)
                    preemptSlowChunks(id, downloaded.get(), System.currentTimeMillis() - runStartMs, actualWorkers, inflightChunks)
                }
            }
            workers.awaitAll()
            preemptJob.cancel()
            !fallback.get() && results.all { it == ChunkResult.OK } &&
                elasticResults.values.all { it == ChunkResult.OK }
        }

        // ---------- 三种结局 ----------
        if (fallback.get()) {
            // 服务器忽略 Range：回退单条整文件流（只下一次，不按分片重复下载整文件）
            Log.w(TAG, "runTask: id=$id 回退单流整文件下载（避免重复下载整文件）")
            singleStreamFallback(id, task, headers, total, chunkDir, failReason)
            return
        }
        if (!allOk) {
            // 失败区间并行重试：收集主池缺失片 + 弹性区失败区间，复用 worker 池并发补下
            val missing = buildList {
                for (i in 0 until mainPoolCount) {
                    val f = File(chunkDir, "part_$i")
                    val s = i * chunkSize
                    val e = min(s + chunkSize - 1, total - 1)
                    if (f.length() < (e - s + 1)) add(RetryRange(s, e, f))
                }
                elasticResults.forEach { (key, res) ->
                    if (res != ChunkResult.OK) {
                        val s = key.substringBefore('_').toLong()
                        val e = key.substringAfter('_').toLong()
                        add(RetryRange(s, e, File(chunkDir, "seg_$key.part")))
                    }
                }
            }
            Log.e(TAG, "runTask: id=$id 缺失区间 ${missing.size} 个 reason=${failReason.get()}，并行重试")
            val retryOk = if (missing.isEmpty()) true else coroutineScope {
                val retryIdx = AtomicInteger(0)
                val retryResults = arrayOfNulls<ChunkResult?>(missing.size)
                val retryWorkers = List(min(actualWorkers, missing.size)) {
                    async(chunkIoDispatcher) {
                        while (true) {
                            if (!isTaskActive()) break
                            val pos = retryIdx.getAndIncrement()
                            if (pos >= missing.size) break
                            val m = missing[pos]
                            // 重试区间同样登记：重试期间的慢连接也会被看门狗采样、抢占
                            val diagKey = "retry@${m.start}"
                            val diag = InflightChunk(m.start, m.end - m.start + 1)
                            inflightChunks[diagKey] = diag
                            val res = try {
                                // ★ 重试同样走全进程在飞信号量：少这一处会让「主池 + 弹性区 + 重试」
                                //   三路并发叠加，正是 OOM 的成因之一
                                inflightLimiter.withPermit {
                                    downloader.downloadChunk(
                                        taskId = id, url = task.url, start = m.start, end = m.end,
                                        partFile = m.file, headers = headers,
                                        preempt = diag.preempt
                                    ) { bytes ->
                                        speedLimiter.awaitAllow(bytes)
                                        diag.bytes.addAndGet(bytes)   // 采样：供看门狗算瞬时速度
                                        // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
                                        val new = minOf(downloaded.addAndGet(bytes), total)
                                        if (!isTaskActive()) return@downloadChunk
                                        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
                                        notifyProgress(id, task.fileName, new, total)
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                ChunkResult.FAILED
                            } finally {
                                inflightChunks.remove(diagKey)
                            }
                            retryResults[pos] = res
                            if (res != ChunkResult.OK) {
                                failReason.compareAndSet(null, "区间 ${m.start}-${m.end} 重试仍失败")
                            }
                        }
                    }
                }
                retryWorkers.awaitAll()
                retryResults.all { it == ChunkResult.OK }
            }
            if (retryOk) {
                Log.d(TAG, "runTask: id=$id 重试补齐所有区间，开始合并")
                finishDownload(id, chunkDir, finalChunkFiles(chunkDir, mainPoolCount), task.fileName, total)
                return
            }
            // 重试仍失败：回退单流
            Log.w(TAG, "runTask: id=$id 分片重试失败，回退单流整文件下载")
            singleStreamFallback(id, task, headers, total, chunkDir, failReason)
            return
        }
        Log.d(TAG, "runTask: id=$id 所有区间完成，开始合并")
        finishDownload(id, chunkDir, finalChunkFiles(chunkDir, mainPoolCount), task.fileName, total)
    }

    /** 最终合并文件列表：主池 part_0..part_{n-1}（连续前半段）+ 弹性区 seg_{start}_{end} 按 start 排序（后半段） */
    private fun finalChunkFiles(chunkDir: File, mainPoolCount: Int): List<File> {
        val mainFiles = (0 until mainPoolCount).map { File(chunkDir, "part_$it") }
        val elasticFiles = chunkDir.listFiles { f ->
            f.name.startsWith("seg_") && f.name.endsWith(".part")
        }?.sortedBy { it.name.removePrefix("seg_").substringBefore('_').toLong() }
            ?: emptyList()
        return mainFiles + elasticFiles
    }

    /**
     * 回退：单条整文件流下载（服务器忽略 Range 时）。
     * 写入**独立**的 full_single.bin（从 0 开始），不复用 part_0，避免与已下分片错位/重复。
     */
    private suspend fun singleStreamFallback(
        id: Long,
        task: DownloadTaskEntity,
        headers: Map<String, String>,
        total: Long,
        chunkDir: File,
        failReason: java.util.concurrent.atomic.AtomicReference<String?>
    ) {
        val fullFile = File(chunkDir, "full_single.bin").apply { delete() } // 全新整文件，从 0 开始
        val fullDownloaded = AtomicLong(0)
        val fullLastAt = AtomicLong(0L)
        val ok = downloader.downloadFull(id, task.url, fullFile, headers, total) { bytes ->
            speedLimiter.awaitAllow(bytes)
            // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
            val new = minOf(fullDownloaded.addAndGet(bytes), total)
            if (!isTaskActive()) return@downloadFull
            persistProgressIfDue(id, new, total, force = false, lastAt = fullLastAt)
            notifyProgress(id, task.fileName, new, total)
        }
        if (!ok) throw IllegalStateException(failReason.get() ?: "分片与单流下载均失败")
        finishDownload(id, chunkDir, listOf(fullFile), task.fileName, total)
    }

    /** 流式降级下载：总大小未知时单分片开放区间下载（Range: bytes=from-），读到 EOF */
    private suspend fun streamDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, 0)
        if (!isTaskActive()) return
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        val partFile = File(chunkDir, "part_0")
        val downloaded = AtomicLong(partFile.length())
        val streamLastAt = AtomicLong(0L)
        val ok = downloader.downloadChunk(
            taskId = id,
            url = task.url,
            start = 0,
            end = Long.MAX_VALUE,
            partFile = partFile,
            headers = headers
        ) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = downloaded.addAndGet(bytes)
            if (!isTaskActive()) return@downloadChunk
            // 大小未知：只更新已下载量（total=0 表示未知）
            persistProgressIfDue(id, new, 0, force = false, lastAt = streamLastAt)
            // 前台通知进度（2 秒节流，total 未知时仅更新标题）
            notifyProgress(id, task.fileName, new, 0)
        }
        if (ok != ChunkResult.OK) {
            // Range 被 CDN 拒绝（416/403）或忽略（200 整文件）：回退为无 Range 完整 GET
            Log.w(TAG, "streamDownload: id=$id Range 失败，回退完整 GET 下载")
            downloaded.set(0)
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, 0, 0)
            val ok2 = downloader.downloadFull(
                taskId = id,
                url = task.url,
                partFile = partFile,
                headers = headers
            ) { bytes ->
                speedLimiter.awaitAllow(bytes)
                val new = downloaded.addAndGet(bytes)
                if (!isTaskActive()) return@downloadFull
                persistProgressIfDue(id, new, 0, force = false, lastAt = streamLastAt)
            }
            if (!ok2) throw IllegalStateException("下载失败（Range 与完整下载均失败）")
        }
        if (!isTaskActive()) return
        finishDownload(id, chunkDir, listOf(partFile), task.fileName, 0)
    }

    /** HLS（m3u8 转码流，如 UC play）下载：拉取分片合并 → 保存 → 完成回调 */
    private suspend fun hlsDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        val hlsFile = File(context.cacheDir, "hls_$id")
        hlsFile.delete()
        val downloaded = AtomicLong(0)
        val hlsLastAt = AtomicLong(0L)
        val ok = HlsDownloader.download(task.url, headers, hlsFile) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = downloaded.addAndGet(bytes)
            persistProgressIfDue(id, new, 0, force = false, lastAt = hlsLastAt)
            notifyProgress(id, task.fileName, new, 0)
        }
        if (!isTaskActive()) return
        if (!ok) {
            hlsFile.delete()
            throw IllegalStateException("HLS 转码流下载失败")
        }
        // Android 9- 保存前检查存储权限（动态申请，授权后继续；无权限则报错提示）
        if (!storagePermissionProvider()) {
            hlsFile.delete()
            throw IllegalStateException("未授予存储权限，无法保存到下载目录")
        }
        val savedPath = withContext(Dispatchers.IO) {
            DownloadSaver.save(context, task.fileName, hlsFile, saveDirProvider())
        }
            ?: throw IllegalStateException("保存到下载目录失败")
        val hlsTotal = dao.get(id)?.totalSize ?: 0L
        completeWithAvg(id, savedPath, hlsTotal)
        Log.d(TAG, "hlsDownload: id=$id 下载完成 savedPath=$savedPath size=${hlsFile.length()}")
        // 终态通知：完成后流体云先显示「下载完成」胶囊，随后转为可划掉的普通通知
        DownloadService.notifyResult(
            context, id, task.fileName, success = true, promote = showSpeedProvider()
        )
        taskCallbacks.remove(id)?.let { cb -> runCatching { cb() } }
        _stats.update { it - id }
        hlsFile.delete()
    }

    /**
     * 分片流式合并 → **直接写入最终保存位置** → 完成后清空分片目录。
     *
     * ★ 旧实现先合并到私有缓存 `merged_$id` 再复制到公共目录，峰值占用 3 份
     *   （分片 + 合并副本 + 公共副本），6GB 级文件即便预留 2.4 倍空间也会 ENOSPC；
     *   现改为边合并边删分片（见 [ChunkDownloader.mergeChunksToStream]），峰值 ≈ 1 份。
     * ★ 完整性校验：分片非空 + 写入字节 == total，任一不符即 abort 半成品并抛错，绝不保存损坏文件。
     * ★ 失败/取消一律 abort：MediaStore 的 IS_PENDING 半成品在「下载」里看不见，却真实占空间。
     */
    private suspend fun finishDownload(
        id: Long,
        chunkDir: File,
        chunkFiles: List<File>,
        fileName: String,
        total: Long
    ) {
        if (!isTaskActive()) return
        // 1) 分片完整性
        for (part in chunkFiles) {
            if (!part.exists() || part.length() <= 0) {
                Log.e(TAG, "finishDownload: id=$id 分片缺失/为空 $part")
                throw IllegalStateException("分片文件缺失或为空，拒绝合并（防止文件损坏）")
            }
        }
        // 2) Android 9- 保存前检查存储权限（动态申请，授权后继续；无权限则报错提示）
        if (!storagePermissionProvider()) {
            throw IllegalStateException("未授予存储权限，无法保存到下载目录")
        }
        // 3) 流式写入最终位置（自定义目录经 SAF；默认目录走 MediaStore/传统路径）
        // ★ 同步阻塞写入必须切 IO 线程：任务跑在 Dispatchers.Default（CPU 池），
        //   大文件写盘若占满 Default 线程会让整个下载器协程饿死（"100% 卡死保存不了"）
        // 合并阶段单独上报进度（界面/通知显示「合并中 n%」）：大文件合并要几十秒，
        // 一直停在 100% 不动会让用户以为卡死。进度只走内存态 stats、不写 DB ——
        // 进程被杀时合并本就中断，库里不需要再多一个会卡住的状态。
        val mergeTotal = if (total > 0) total else chunkFiles.sumOf { it.length() }
        var mergeLastPercent = -1
        var mergeLastAtMs = 0L
        fun reportMergeProgress(done: Long) {
            if (mergeTotal <= 0) return
            val percent = (done * 100 / mergeTotal).toInt().coerceIn(0, 100)
            val now = System.currentTimeMillis()
            if (percent == mergeLastPercent && now - mergeLastAtMs < mergeReportIntervalMs) return
            mergeLastPercent = percent
            mergeLastAtMs = now
            _stats.update { it + (id to DownloadStats(mergePercent = percent)) }
            // 通知沿用下载中那条 2 秒节流（合并回调很密，别把系统通知刷爆）
            if (now - lastNotifyTs.get() >= notifyThrottleMs) {
                lastNotifyTs.set(now)
                DownloadService.update(
                    context, fileName, percent, DownloadService.MERGE_TEXT, showSpeedProvider()
                )
            }
        }
        reportMergeProgress(0L)
        val savedPath = withContext(Dispatchers.IO) {
            val dest = DownloadSaver.openDestination(context, fileName, saveDirProvider())
                ?: throw IllegalStateException("无法创建下载目标（下载目录不可用）")
            try {
                val out = dest.open() ?: throw IllegalStateException("无法打开下载目标输出流")
                val written = out.use {
                    downloader.mergeChunksToStream(chunkFiles, it, ::reportMergeProgress)
                }
                if (total > 0 && written != total) {
                    throw IllegalStateException("文件大小校验失败：期望 $total 字节，实际 $written 字节（已拒绝保存损坏文件）")
                }
                dest.commit()
                dest.path
            } catch (e: Exception) {
                // 失败（含 ENOSPC）/取消：删掉半成品，否则残留数据会一直占空间，重试时空间只减不增
                dest.abort()
                throw e
            }
        }
        completeWithAvg(id, savedPath, total)
        Log.d(TAG, "finishDownload: id=$id 下载完成 savedPath=$savedPath size=$total")
        // 终态通知：完成后流体云先显示「下载完成」胶囊，随后转为可划掉的普通通知
        DownloadService.notifyResult(
            context, id, fileName, success = true, promote = showSpeedProvider()
        )
        taskCallbacks.remove(id)?.let { cb ->
            runCatching { cb() }
        }
        _stats.update { it - id }
        chunkDir.deleteRecursively()
    }

    /**
     * 速度采样器：取近 [WINDOW_MS] 秒滑动窗口的平均速度，平滑多线程下载的速度波动。
     * 多线程并发下瞬时速率波动大，短窗口估算剩余时长会剧烈跳动；
     * 改用 5 秒窗口均值后，剩余时长更稳定可靠。
     */
    private class SpeedRecorder {
        private data class Sample(val timeMs: Long, val bytes: Long)

        private val samples = ArrayDeque<Sample>()
        private var lastEmit = 0L

        @Synchronized
        fun onBytes(total: Long): Long? {
            val now = System.currentTimeMillis()
            samples.addLast(Sample(now, total))
            // 剔除窗口外的旧样本，但始终保留至少 2 个（下载起步阶段窗口尚短）
            while (samples.size > 2 && now - samples.first().timeMs > WINDOW_MS) {
                samples.removeFirst()
            }
            // 250ms 发射一次，避免高频刷新 UI/通知
            if (now - lastEmit < 250) return null
            val first = samples.first()
            val elapsed = now - first.timeMs
            val speed = if (elapsed > 0) {
                ((total - first.bytes) * 1000 / elapsed).coerceAtLeast(0)
            } else 0L
            lastEmit = now
            return speed
        }

        private companion object {
            const val WINDOW_MS = 5000L
        }
    }

    /** 全局限速器（令牌桶）：所有任务合计不超过 speedLimitProvider 的字节/秒；0 = 不限速 */
    private inner class SpeedLimiter {
        @Volatile
        private var tokens = 0L
        @Volatile
        private var lastRefillNanos = System.nanoTime()

        @Synchronized
        private fun refill(limit: Long) {
            val now = System.nanoTime()
            val elapsedSec = ((now - lastRefillNanos).coerceAtLeast(0) / 1_000_000_000.0)
            lastRefillNanos = now
            tokens = minOf(limit, tokens + (elapsedSec * limit).toLong())
        }

        /** 消耗 bytes 字节额度；不足则挂起等待（限速生效） */
        suspend fun awaitAllow(bytes: Long) {
            val limit = speedLimitProvider().coerceAtLeast(0L)
            if (limit <= 0L) return
            while (true) {
                val waitMs = synchronized(this) {
                    refill(limit)
                    if (bytes <= tokens) {
                        tokens -= bytes
                        return
                    }
                    ((bytes - tokens) * 1000 / limit).coerceIn(1L, 200L)
                }
                // 锁外挂起等待，避免持锁阻塞其他任务
                delay(waitMs)
            }
        }
    }

    /** 下载临时文件缓存根目录：外部缓存（/storage/emulated/0/Android/data/com.yunx.app/cache），
     *  与最终保存目录解耦，系统可自动清理；外部存储不可用时回退内部缓存目录。 */
    private fun cacheBase(): File = context.externalCacheDir ?: context.cacheDir

    /** 分片临时文件目录：cacheBase()/download_tmp/$id */
    private fun chunkDirOf(id: Long): File = File(cacheBase(), "download_tmp/$id")

    /** 分片数规划（任务池模型）：分片数 = 线程数 × 8，远多于并发线程数。
     *  worker 循环领取盈余块，任一分片慢时其他线程继续领新片，根治"尾部并发塌缩"；
     *  保留 256KB 单片下限（小文件也能切出数倍于线程数的盈余片，同时避免过多小片）与 512 封顶。 */
    private fun chunkCountFor(total: Long, threads: Int): Int {
        if (total <= 0) return 1
        // ★ 单片下限 1MB → 256KB：1MB 会把小文件（如 33.7MB）的分片数夹到 ≈ 线程数，失去盈余；
        //   低单连接速率（如夸克 10KB/s）下每个 1MB 重片耗时约 100s，尾部极易空转。
        //   降到 256KB 后同样文件能切出 4~8 倍于线程数的片，工作窃取把尾部空转压到最后一个 256KB。
        //   大文件仍受 want / 512 封顶约束，单片自然变大，吞吐不受影响。
        val minChunkBytes = 256 * 1024L
        val bySize = when {
            total < 5 * 1024 * 1024 -> 1          // < 5MB 不分片
            total < 50 * 1024 * 1024 -> 8         // < 50MB
            total < 500 * 1024 * 1024 -> 32       // < 500MB
            else -> 64                            // ≥ 500MB 基础值
        }
        // 任务池：每线程平均领 8 片，天然抗慢片拖尾（比 1:1 映射多 8 倍盈余）
        val want = maxOf(bySize, threads * 8)
        return minOf(want, (total / minChunkBytes).toInt().coerceAtLeast(1), 512)
    }

    companion object {
        /**
         * 读缓冲预算占最大堆的比例：取 1/8（256MB 堆 → 32MB 预算）。
         * 原来是 1/16：下载客户端改用 HTTP/1.1 之后，每路在飞只占一份 64KB 读缓冲
         * （不再有 HTTP/2 那条 16MB 每流窗口），同样的堆预算可以安全地多放一倍路数。
         */
        private const val BUFFER_BUDGET_DIVISOR = 8

        /** 在飞分片下限：低于 8 路会让慢 CDN 明显掉速 */
        private const val INFLIGHT_MIN = 8

        /**
         * 在飞分片硬上限：512（与设置页最高档位一致 ⇒ 用户选 512 就是真的 512 路）。
         * 为什么敢放到 512：每路在飞 ≈ 一条 HTTP/1.1 连接 + 一个分片文件句柄（约 2 个 FD），
         * 512 路 ≈ 1100 个 FD —— 本机实测进程 FD 软限是 32768（`/proc/self/limits`），远未用满。
         * 内存侧 512 × 64KB 读缓冲 = 32MB，正好是 256MB 堆的 1/8（[BUFFER_BUDGET_DIVISOR] 的预算）。
         * 仍然要注意：线程是**按需创建**的（没人用 512 档位就不会有那么多线程），
         * 但路数越高越可能撞上 CDN 对同 IP 的连接数上限，表现为 429/连接重置
         * （日志里 `尝试N IO异常`），那时该往下调档位，而不是继续加。
         */
        private const val INFLIGHT_MAX = 512

        /**
         * 按堆预算推导「全进程在飞分片上限」：maxHeap / 8 / 单路读缓冲大小，夹在 [8, 512]。
         * 纯函数（无副作用），便于单测覆盖边界（见 InflightChunkBudgetTest）。
         */
        internal fun inflightChunksFor(maxHeapBytes: Long, bufferSize: Int = BUFFER_SIZE): Int =
            (maxHeapBytes / BUFFER_BUDGET_DIVISOR / bufferSize.coerceAtLeast(1))
                .toInt()
                .coerceIn(INFLIGHT_MIN, INFLIGHT_MAX)

        /**
         * 全进程在飞分片上限（进程启动时按最大堆算一次）。
         * 由 `inflightLimiter` 用作信号量容量：无论用户怎么调线程数、同时开几个任务，
         * 同时在飞的下载请求数都不会超过它 —— 这是 OOM（见 log/oom）的根治手段。
         */
        val MAX_INFLIGHT_CHUNKS: Int = inflightChunksFor(Runtime.getRuntime().maxMemory())

        /**
         * 分片阻塞 IO 的专用线程池（进程级），worker 与 [ChunkDownloader] 内部的 `withContext` 都跑在它上面。
         *
         * 为什么不能直接用 `Dispatchers.IO`：它的并行度被钉在 `max(64, 核数)`，超出的 worker 只能在队列里
         * 干等 —— 于是「设置里 256/512 线程」永远只跑得出 64 路。线程**按需创建**（最多 [MAX_INFLIGHT_CHUNKS]
         * 条）、空闲 30 秒回收，只有真用到大并发时才会存在那么多线程。
         * 必须 core = max 而不是 core = 0 + 无界队列：后者在 ThreadPoolExecutor 里只会养出 1 个 worker。
         */
        internal val chunkIoDispatcher: CoroutineDispatcher =
            ThreadPoolExecutor(
                MAX_INFLIGHT_CHUNKS,
                MAX_INFLIGHT_CHUNKS,
                30L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>()
            ) { r -> Thread(r, "yunx-chunk-io").apply { isDaemon = true } }
                .apply { allowCoreThreadTimeOut(true) }
                .asCoroutineDispatcher()
    }
}
