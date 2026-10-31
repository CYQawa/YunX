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

package com.yunx.app.data.network

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.util.Locale
import java.util.UUID

/**
 * 139 个人云盘口令 XML 接口使用的运行时设备信息。
 *
 * **不要在常量里写死厂商、型号、分辨率或设备 ID**：这些字段既因设备而异，也可能暴露
 * 协作者/抓包者的真实设备。厂商、型号、系统版本、屏幕分辨率和语言全部从 Android
 * 系统 API 读取；设备 ID 使用本应用安装时随机生成并持久化的值，不读取
 * `Settings.Secure.ANDROID_ID`、IMEI 等硬件标识。
 *
 * 该动态生成只用于本次新增的口令 XML 请求；`fbcbd0b` 及以前已有的普通分享/个人盘固定
 * 设备头保持原样，避免改变线上协议行为。
 */
data class C139DeviceInfo(
    val appVersion: String,
    val manufacturer: String,
    val model: String,
    val deviceId: String,
    val osVersion: String,
    val screen: String,
    val language: String,
    val channel: String,
    val mmSource: String
) {
    /** 口令 XML 接口的 `x-DeviceInfo` / `x-yun-client-info` 共用的 App 格式。 */
    fun appDeviceInfo(): String =
        "1|127.0.0.1|1|$appVersion|$manufacturer|$model|$deviceId|" +
            "02-00-00-00-00-00|android $osVersion|$screen|$language||||$mmSource|0|"

    /** 口令 XML 接口的 `x-yun-user-agent`。 */
    fun appUserAgent(): String = "android|$model|android $osVersion|mCloud$appVersion-$mmSource"

    companion object {
        private const val PREFS = "c139_device"
        private const val KEY_DEVICE_ID = "installation_id"
        private const val FALLBACK_ID = "00000000000000000000000000000000"

        @Volatile
        private var cachedDeviceId: String? = null

        /** 从系统 API 与本地安装 ID 生成请求头信息。 */
        fun from(context: Context): C139DeviceInfo {
            val appContext = context.applicationContext
            val metrics = appContext.resources.displayMetrics
            val configuration = appContext.resources.configuration
            val locale = runCatching { configuration.locales[0] }.getOrDefault(Locale.getDefault())
            val osVersion = Build.VERSION.RELEASE.takeIf { it.isNotBlank() }
                ?: Build.VERSION.SDK_INT.toString()
            return C139DeviceInfo(
                appVersion = C139Constants.SHARE_CODE_APP_VERSION,
                manufacturer = sanitizeField(Build.MANUFACTURER.ifBlank { "Android" }),
                model = sanitizeField(Build.MODEL.ifBlank { "Android Device" }),
                deviceId = installationId(appContext),
                osVersion = sanitizeField(osVersion),
                screen = "${metrics.widthPixels}X${metrics.heightPixels}",
                language = sanitizeField(locale.language.ifBlank { "zh" }),
                channel = C139Constants.SHARE_CODE_APP_CHANNEL,
                mmSource = C139Constants.SHARE_CODE_MM_SOURCE
            )
        }

        /** 仅供未注入 Context 的测试/异常路径；不会读取或伪造真实硬件信息。 */
        fun fallback(): C139DeviceInfo = C139DeviceInfo(
            appVersion = C139Constants.SHARE_CODE_APP_VERSION,
            manufacturer = "Android",
            model = "Android Device",
            deviceId = FALLBACK_ID,
            osVersion = "Unknown",
            screen = "0X0",
            language = "zh",
            channel = C139Constants.SHARE_CODE_APP_CHANNEL,
            mmSource = C139Constants.SHARE_CODE_MM_SOURCE
        )

        private fun installationId(context: Context): String {
            cachedDeviceId?.let { return it }
            synchronized(this) {
                cachedDeviceId?.let { return it }
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val saved = prefs.getString(KEY_DEVICE_ID, null)
                    ?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
                val value = saved ?: UUID.randomUUID().toString().replace("-", "")
                if (saved == null) prefs.edit { putString(KEY_DEVICE_ID, value) }
                cachedDeviceId = value
                return value
            }
        }

        private fun sanitizeField(value: String): String =
            value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim()
    }
}
