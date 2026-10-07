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
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.LogRedactor
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 下载调试日志（设置 → 关于云析 → 长按 → 开发调试 → 下载调试）。
 *
 * 与 `DiagnosticLog`（按模块的全局诊断日志、落盘轮转）不同：这里是**按下载任务**记录的事件流，
 * 面向「这个任务为什么慢 / 为什么失败」的排查 —— 调试页里能实时跟随，也能整体导出成文件分享。
 *
 * ★ 三条硬约束（改之前先读）：
 * 1. **关闭即清空**：[setEnabled] 传 false 会把所有任务的事件全部丢掉 ⇒ 设置页关开关前有二次确认弹窗；
 * 2. **只在开启时记录**：[record] 第一行就是 `if (!enabled) return`，所以调用点可以随手埋，
 *    关闭时零开销、也不会留下任何新日志；
 * 3. **只活在内存**：不落盘（进程被杀即丢）。关闭必须能立刻清空，落盘反而还要处理残留文件的清理，
 *    调试日志不值得为它引入一整套文件生命周期。
 */
object DownloadDebugLog {

    /** 单任务最多保留的事件条数：超出丢最旧的（调试只关心「最近发生了什么」） */
    private const val MAX_ENTRIES_PER_TASK = 2000

    /** 最多保留多少个任务的事件：超出丢最早登记的那个（长时间挂着开关也不至于无限涨内存） */
    private const val MAX_TASKS = 50

    /** 导出文件的目录（与 LogExporter 同一处，file_paths.xml 的 cache_logs 已覆盖，可直接分享） */
    private const val EXPORT_DIR = "logs"

    private val enabled = AtomicBoolean(false)

    /** 所有可变状态由这把锁保护（事件低频写，不值得给每个任务单独加锁） */
    private val lock = Any()

    /** taskId → 事件行（时间戳已格式化，直接可读、可直接导出） */
    private val logs = HashMap<Long, ArrayDeque<String>>()

    /** taskId → 该任务的事件序号（自增，从 1 开始）：日志量大时便于引用某一条 */
    private val seqs = HashMap<Long, Int>()

    /** 任务登记顺序：超过 [MAX_TASKS] 时按此丢最早的 */
    private val order = ArrayDeque<Long>()

    /** 时间戳格式：只在锁内使用（SimpleDateFormat 非线程安全），事件低频，竞争可忽略 */
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun isEnabled(): Boolean = enabled.get()

    /**
     * 开关下载调试。**传 false 会清空全部日志**（用户已确认该行为，设置页有二次确认）。
     * 立即生效，不需要重启应用。
     */
    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) clear()
    }

    /** 启动时按持久化开关装上（与 DiagnosticLog.install 同款；必须在任何下载开始前调用） */
    fun install(context: Context) {
        setEnabled(SettingsRepository(context).downloadDebug)
    }

    // ---------- 网络 / 电源环境（下载很依赖网络环境，调试日志要把这一层也记下来）----------

    /**
     * 网络特征串（便宜、只含「类型 + 是否计费」）：变化即说明换了网络，
     * 供看门狗做「是否切换网络」的检测。**不要**拿 [environmentSnapshot] 来比——它含信号强度，会频繁变。
     */
    fun networkSignature(context: Context): String = runCatching {
        val caps = activeNetworkCapabilities(context)
        "${transportName(caps)}|metered=${meteredLabel(context, caps)}"
    }.getOrDefault("未知")

    /**
     * 采集当前网络 / 电源环境：网络类型（Wi-Fi/蜂窝/以太网/VPN）、是否计费、链路速率估计、蜂窝代际、省电模式。
     * 全程 runCatching：任何系统服务读取失败都只退化成「未知」，绝不影响下载。
     *
     * 权限与取舍：网络类型 / 是否计费 / 链路估计只需要 `ACCESS_NETWORK_STATE`（普通权限）。
     * ★ 没有记「信号强度」：Android 上真正的信号强度（RSSI / dBm）要走 `WifiManager` 或
     * `TelephonyManager.getSignalStrength()`，前者要 `ACCESS_WIFI_STATE`、后者要 `READ_PHONE_STATE`
     * （危险权限，本项目不申请），所以改用系统给出的**链路速率估计**作为网络质量指标。
     * 蜂窝代际（5G/4G/3G/2G）在 Android 11+ 同样要 `READ_PHONE_STATE`，未授权时只记「未区分」。
     */
    fun environmentSnapshot(context: Context): String {
        val caps = activeNetworkCapabilities(context)
        val parts = mutableListOf<String>()
        parts += "网络=${runCatching { transportName(caps) }.getOrDefault("未知")}"
        parts += "计费=${meteredLabel(context, caps)}"
        runCatching {
            val down = caps?.linkDownstreamBandwidthKbps ?: 0
            val up = caps?.linkUpstreamBandwidthKbps ?: 0
            if (down > 0 || up > 0) parts += "链路估计=下行${down}kbps/上行${up}kbps"
        }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            parts += "蜂窝代际=${cellularGeneration(context)}"
        }
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            parts += "省电模式=${if (pm?.isPowerSaveMode == true) "开" else "关"}"
        }
        return parts.joinToString(" ")
    }

    private fun activeNetworkCapabilities(context: Context): NetworkCapabilities? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        cm.getNetworkCapabilities(cm.activeNetwork)
    }.getOrNull()

    /** 当前网络是否计费（ConnectivityManager.isActiveNetworkMetered，API 16+，无需额外权限） */
    private fun activeNetworkMetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.isActiveNetworkMetered == true
    }.getOrDefault(false)

    /**
     * 计费字段的可读值：**没有活动网络时写「未知」**——此时 `isActiveNetworkMetered` 会给出误导值
     * （常返回 true），日志里出现「网络=无网络 计费=是」这种自相矛盾的组合。
     */
    private fun meteredLabel(context: Context, caps: NetworkCapabilities?): String = when {
        caps == null -> "未知"
        activeNetworkMetered(context) -> "是"
        else -> "否"
    }

    private fun transportName(caps: NetworkCapabilities?): String = when {
        caps == null -> "无网络"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "其他"
    }

    /** 蜂窝代际（5G/4G/3G/2G）：Android 11+ 需要 READ_PHONE_STATE，未授权时明说「未区分」而不是猜 */
    private fun cellularGeneration(context: Context): String = runCatching {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            "未区分（需电话权限）"
        } else {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            when (tm?.networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
                else -> "其他"
            }
        }
    }.getOrDefault("未知")

    /**
     * 记一条事件。[node] 是「发生了什么」（入队 / 开始 / 分片规划 / 抢占 / 重试 / 合并 / 完成 / 失败…），
     * [detail] 是补充信息。★ [detail] 必须**已经脱敏**：URL 走 [LogRedactor.url]，
     * 绝不写入 Cookie / token 原文（与 §2 的脱敏规范一致）。
     */
    fun record(taskId: Long, node: String, detail: String = "") {
        if (!enabled.get()) return
        synchronized(lock) {
            val seq = (seqs[taskId] ?: 0) + 1
            seqs[taskId] = seq
            val stamp = timeFormat.format(Date())
            val head = "#$seq $stamp | $node"
            val line = if (detail.isBlank()) head else "$head | $detail"
            val deque = logs[taskId] ?: ArrayDeque<String>().also {
                logs[taskId] = it
                order.addLast(taskId)
                while (order.size > MAX_TASKS) {
                    val oldest = order.removeFirst()
                    logs.remove(oldest)
                    seqs.remove(oldest)
                }
            }
            deque.addLast(line)
            while (deque.size > MAX_ENTRIES_PER_TASK) deque.removeFirst()
        }
    }

    /** 读某任务的全部事件（时间正序），供调试页实时跟随 */
    fun entries(taskId: Long): List<String> = synchronized(lock) {
        logs[taskId]?.toList() ?: emptyList()
    }

    /** 任务被删除时丢掉它的事件（留着也永远不会再被查看） */
    fun drop(taskId: Long) {
        synchronized(lock) {
            logs.remove(taskId)
            seqs.remove(taskId)
            order.remove(taskId)
        }
    }

    /** 清空全部日志（关闭开关时调用） */
    fun clear() {
        synchronized(lock) {
            logs.clear()
            seqs.clear()
            order.clear()
        }
    }

    /**
     * 该任务的**完整下载日志**文本：设备/应用信息 + 任务原始字段（直链脱敏）+ 事件流。
     * 「主要变更节点 / 错误节点」就是事件流里各条 [record] 的结果。
     */
    fun buildExportText(context: Context, task: DownloadTaskEntity): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            appendLine("云析（YunX）下载调试日志")
            appendLine("导出时间：${sdf.format(Date())}")
            appendLine("应用版本：${appVersion(context)}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
            appendLine()
            appendLine("========== 任务信息 ==========")
            appendLine("任务 id：${task.id}")
            appendLine("文件名：${task.fileName}")
            appendLine("状态：${DownloadTaskEntity.statusText(task.status)}")
            appendLine("平台：${task.platform.ifBlank { "-" }}")
            appendLine("总大小：${task.totalSize} 字节")
            appendLine("已下载：${task.downloadedSize} 字节")
            appendLine("平均速度：${task.avgSpeed} B/s")
            appendLine("执行器：${task.engineTaskId.ifBlank { "内置分片下载器" }}")
            appendLine("直链：${LogRedactor.url(task.url)}")
            appendLine("保存路径：${task.savePath.ifBlank { "-" }}")
            if (task.errorMsg.isNotBlank()) appendLine("失败原因：${task.errorMsg}")
            appendLine()
            appendLine("========== 事件流（主要变更节点 / 错误节点）==========")
            val list = entries(task.id)
            if (list.isEmpty()) appendLine("（无：任务创建时「下载调试」未开启）") else list.forEach { appendLine(it) }
        }
    }

    /** 把完整下载日志写到 cacheDir/logs 下并返回文件（失败返回 null）；供 FileProvider 分享 */
    fun exportToFile(context: Context, task: DownloadTaskEntity): File? = runCatching {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "yunx_download_debug_${task.id}_$stamp.txt")
        file.writeText(buildExportText(context, task))
        file
    }.getOrNull()

    private fun appVersion(context: Context): String = runCatching {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pkg.versionName}（${pkg.versionCode}）"
    }.getOrDefault("-")
}
