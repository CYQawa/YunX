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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.yunx.app.MainActivity
import com.yunx.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载前台服务：下载进行中保持前台运行。
 * 前台服务让系统将应用视为「前台」，避免 Doze/后台省电限速、防止进程被杀，
 * 从而保证切后台后下载速度不受影响。
 * 生命周期由 DownloadManager 驱动：任务开始 → start()，全部结束 → stop()。
 *
 * ★ 同一时间只有**一条**前台通知，但保活的来源不止 DownloadManager 一家
 * （内核包下载 `KernelProvisioner` 也借它，见 Agent.md §3.34）。所以服务的起停必须走
 * [acquire]/[release] 这对**引用计数**接口：谁先来谁拉起，最后一个走的才关掉。
 * 直接调 [start]/[stop] 会出现「内核下完把用户正在跑的下载的保活一起关掉」这种事。
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "下载中…"
                val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
                val speed = intent?.getStringExtra(EXTRA_SPEED) ?: ""
                val showSpeed = intent?.getBooleanExtra(EXTRA_SHOW_SPEED, true) ?: true
                startAsForeground(title, progress, speed, showSpeed)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun startAsForeground(title: String, progress: Int, speed: String, showSpeed: Boolean) {
        ensureChannel(this)
        val notification = buildNotification(title, progress, speed, showSpeed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, progress: Int, speed: String, showSpeed: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val hasSpeed = showSpeed && speed.isNotBlank()
        builder
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(title)
            // 完整通知显示下载速度；合并阶段单独提示（大文件合并可能几十秒）；简化模式仅提示下载中
            .setContentText(
                if (speed == MERGE_TEXT) "正在合并分片，完成前请勿关闭应用"
                else if (hasSpeed) "下载速度 $speed"
                else "正在后台下载，完成前请勿关闭应用"
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // 仅「完整通知」模式显示进度条；简化模式隐藏进度条
        if (showSpeed && progress in 0..100) {
            builder.setProgress(100, progress, false)
        }
        // Android 16 实时更新（Live Updates）：OPPO ColorOS 16 流体云采用该规范，
        // 应用无需接入 OPPO 私有 SDK，按此实现即可在状态栏胶囊 / 卡片 / 锁屏 / 息屏展示下载进度。
        if (showSpeed && Build.VERSION.SDK_INT >= 36) {
            // ① 样式必须为 ProgressStyle / BigTextStyle 等标准样式之一（ProgressStyle 可呈现进度条）
            val style = Notification.ProgressStyle().setStyledByProgress(true)
            if (progress in 0..100) {
                // 单段进度条：段长用千分比（1000）而非文件字节数，避免大文件字节数超出 Int 上限
                style.setProgressSegments(listOf(Notification.ProgressStyle.Segment(PROGRESS_SCALE)))
                style.setProgress((progress * PROGRESS_SCALE / 100).coerceIn(0, PROGRESS_SCALE))
            } else {
                // 总大小未知（流式 / HLS 下载）：不确定态进度条
                style.setProgressIndeterminate(true)
            }
            builder.setStyle(style)
        }
        val notification = builder.build()
        if (showSpeed && Build.VERSION.SDK_INT >= 36) {
            // ② 请求提升为「进行中」通知（等价于 API 36.1 的 Notification.Builder.setRequestPromotedOngoing(true)）
            notification.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            // ③ 状态栏胶囊的简短文案（等价于 Notification.Builder.setShortCriticalText(...)）
            notification.extras.putCharSequence(
                EXTRA_SHORT_CRITICAL_TEXT,
                if (progress in 0..100) "$progress%" else "下载中"
            )
        }
        return notification
    }

    companion object {
        private const val CHANNEL_ID = "yunx_download"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.yunx.app.action.STOP_DOWNLOAD"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PROGRESS = "progress"
        private const val EXTRA_SPEED = "speed"
        private const val EXTRA_SHOW_SPEED = "show_speed"

        /** 进度条总刻度（千分比）：段长不用文件字节数，避免大文件字节数超出 Int 上限而溢出 */
        private const val PROGRESS_SCALE = 1000

        /**
         * 合并阶段速度栏占位文案：DownloadManager 合并分片时把它当作 speed 传进来，
         * 通知正文因此显示「正在合并分片」，而不是「下载速度 合并中」。
         */
        const val MERGE_TEXT = "合并中"

        /** 请求提升为「进行中」通知的 extras 键（与 Notification.EXTRA_REQUEST_PROMOTED_ONGOING 同值） */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        /** 状态栏胶囊简短文案的 extras 键（与 Notification.EXTRA_SHORT_CRITICAL_TEXT 同值） */
        private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

        /** 下载结果（完成/失败）通知 ID 基数：每个任务一条，避免并发任务互相覆盖 */
        private const val RESULT_ID_BASE = 2000

        /** 结果胶囊停留时长（毫秒）：让流体云有终态曝光，随后转为可划掉的普通通知 */
        private const val RESULT_LINGER_MS = 3000L

        /** 结果胶囊兜底自动取消时长（毫秒）：进程若在停留期内被杀，也不会留下无法划掉的 ongoing 通知 */
        private const val RESULT_LINGER_TIMEOUT_MS = 10_000L

        /** 结果通知派发用作用域：与服务生命周期无关（服务在任务结束时已停止） */
        private val resultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "下载任务", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }

        /**
         * 下载结束（完成/失败）：先在流体云保留一段终态胶囊，随后转为可划掉的普通通知。
         * 由 DownloadManager 在任务终态时调用；不依赖服务存活（此时服务可能已随最后一个任务停止）。
         *
         * @param promote 是否参与流体云（跟随「完整通知」模式，与下载中的提升保持一致）
         */
        fun notifyResult(
            context: Context,
            taskId: Long,
            fileName: String,
            success: Boolean,
            error: String = "",
            promote: Boolean = true
        ) {
            val appContext = context.applicationContext
            ensureChannel(appContext)
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val id = (RESULT_ID_BASE + taskId).toInt()
            if (!promote || Build.VERSION.SDK_INT < 36) {
                // 简化通知模式或系统不支持实时更新：直接给可划掉的普通结果通知
                nm.notify(id, buildResultNotification(appContext, fileName, success, error, linger = false))
                return
            }
            // 终态胶囊：实时更新要求 ongoing，故停留期内保持 ongoing（另有超时兜底）
            nm.notify(id, buildResultNotification(appContext, fileName, success, error, linger = true))
            resultScope.launch {
                delay(RESULT_LINGER_MS)
                // 转为普通通知：可划掉、点击打开应用；流体云胶囊随之收起
                nm.notify(id, buildResultNotification(appContext, fileName, success, error, linger = false))
            }
        }

        /** 构建下载结果通知：linger=true 为流体云终态胶囊（ongoing + 请求提升），false 为可划掉的普通通知 */
        private fun buildResultNotification(
            context: Context,
            fileName: String,
            success: Boolean,
            error: String,
            linger: Boolean
        ): Notification {
            val contentIntent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val title = if (success) "下载完成" else "下载失败"
            val text = if (success) fileName else if (error.isBlank()) fileName else "$fileName：$error"
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            builder
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(title)
                .setContentText(text)
                // 实时更新必须是标准样式之一：结果通知用 BigTextStyle
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
            if (linger) {
                builder.setOngoing(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setTimeoutAfter(RESULT_LINGER_TIMEOUT_MS)
                }
            } else {
                builder.setOngoing(false).setAutoCancel(true)
            }
            val notification = builder.build()
            if (linger && Build.VERSION.SDK_INT >= 36) {
                notification.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                notification.extras.putCharSequence(EXTRA_SHORT_CRITICAL_TEXT, if (success) "完成" else "失败")
            }
            return notification
        }

        /** 下载任务开始时调用（服务不存在则创建前台服务） */
        fun start(context: Context, title: String, progress: Int = 0) {
            start(context, title, progress, "", true)
        }

        /** 更新/启动前台通知（标题/进度/速度变化；调用方节流） */
        fun update(context: Context, title: String, progress: Int, speed: String, showSpeed: Boolean) {
            start(context, title, progress, speed, showSpeed)
        }

        private fun start(context: Context, title: String, progress: Int, speed: String, showSpeed: Boolean) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PROGRESS, progress)
                .putExtra(EXTRA_SPEED, speed)
                .putExtra(EXTRA_SHOW_SPEED, showSpeed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 全部任务结束：停止前台服务（stopService 无后台启动限制，安全） */
        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }

        /** 引用计数（跨来源共享：内置下载器的任务 + 内核包下载） */
        private val keepAliveUsers = AtomicInteger(0)

        /**
         * 申请保活：**第一个**申请者才真正拉起前台服务（后续的只加计数，不动通知）。
         * 与 [release] 必须配对，调用方自己保证（DownloadManager 用它的任务计数配对，
         * KernelProvisioner 用自己的 try/finally 配对）。
         */
        fun acquire(context: Context, title: String) {
            if (keepAliveUsers.incrementAndGet() == 1) start(context, title)
        }

        /** 释放保活：最后一个走的才关掉服务，避免把别人的保活一起关掉 */
        fun release(context: Context) {
            if (keepAliveUsers.decrementAndGet() <= 0) {
                keepAliveUsers.set(0)
                stop(context)
            }
        }
    }
}