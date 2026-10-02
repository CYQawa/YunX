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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 运行时权限状态的唯一判断入口（引导页「权限准备」、设置页、下载/保存兜底都从这里取）。
 *
 * ★ 三处权限的**系统版本差异集中在本文件**，调用处不要再自己写 `Build.VERSION.SDK_INT` 分支：
 *   - 通知：Android 13+（TIRAMISU）才有 `POST_NOTIFICATIONS` 运行时权限；低版本没有这个权限，
 *     只能看「通知总开关」是否被关闭（国产 ROM 常默认关闭）；
 *   - 存储：Android 10+（Q）写公共目录走 MediaStore，**不需要**任何存储权限；
 *     只有 Android 9- 需要 `WRITE_EXTERNAL_STORAGE`（Manifest 里带 `maxSdkVersion="29"`）；
 *   - 后台运行：「忽略电池优化」白名单（API 23+ 可用，minSdk 24 恒满足），
 *     加入后息屏/后台不会被系统冻结，是「锁屏后保持下载」生效的前提。
 *
 * ★ 这些检查都是"读状态"，只有 `ActivityResultContracts.RequestPermission` 才能真的弹授权框，
 *   所以本文件只提供状态查询 + 跳系统设置页的工具方法。
 */
object PermissionState {

    /** 通知是否可用（13+ 需运行时授权且总开关未关；低版本只看总开关） */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 是否能弹通知的运行时授权框：Android 13+ 且 `POST_NOTIFICATIONS` 还没有授权。
     * 返回 false 时想开启通知只能跳系统设置页（`openNotificationSettings`）。
     */
    fun canRequestNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /** 当前系统保存到公共目录是否需要存储权限（只有 Android 9- 需要） */
    fun storagePermissionRequired(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** 存储权限是否已就绪；Android 10+ 恒为 true（走 MediaStore，不需要权限） */
    fun storageGranted(context: Context): Boolean =
        !storagePermissionRequired() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** 是否已加入「忽略电池优化」白名单（查询失败时按"已加入"处理，避免反复打扰用户） */
    fun batteryOptimizationIgnored(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 跳系统「本应用通知设置」页；部分 ROM 没有该页时退回应用详情页 */
    fun openNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }.onFailure { openAppDetails(context) }
    }

    /** 跳系统「请求忽略电池优化」弹窗；部分 ROM 没有该页时退回电池优化列表 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    /** 跳应用详情页（权限被"永久拒绝"后的兜底入口） */
    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }
    }
}
