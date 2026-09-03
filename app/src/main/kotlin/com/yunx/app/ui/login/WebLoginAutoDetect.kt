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

package com.yunx.app.ui.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yunx.app.ui.SnackbarController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 自动登录检测：轮询网页登录凭证的间隔 */
private const val AUTO_DETECT_POLL_MS = 1_500L

/** 自动登录检测：相邻两次「真实网络校验」的最小间隔（页面登录过程会先出现中间态 Cookie） */
private const val AUTO_DETECT_VALIDATE_THROTTLE_MS = 5_000L

/** 自动登录检测：同一份凭证校验失败后，间隔多久才允许用相同凭证重试（网络闪断恢复后能自动补登） */
private const val AUTO_DETECT_SAME_CREDENTIAL_RETRY_MS = 10_000L

/**
 * 网页登录自动登录检测（供所有「在 WebView 打开官网网页手动登录」的平台复用）：
 * 用户在网页完成登录后，页面侧会出现登录凭证（Cookie 或 localStorage Token）。
 * 本组件轮询读取该凭证：一旦凭证「形态可疑」且不同于上次失败值，就调用与右上角「保存」按钮
 * 相同的校验+落库路径；校验通过即自动完成登录并回调 [onAutoSaved]（调用方负责关闭登录页）；
 * 校验失败视为登录中间态，继续轮询等待真正的登录态。
 *
 * 右上角「保存」按钮保留作手动兜底：自动检测未触发时（页面结构变化、凭证形态异常等）用户仍可手动保存。
 *
 * ⚠️ 内部含 remember/LaunchedEffect：必须在组合函数体顶层**无条件**调用（放入条件分支会导致轮询反复重启）。
 *
 * @param sampleCredential 每次轮询读取网页侧登录凭证；无登录态时返回 null/空串。实现需自行保证线程安全
 * @param isPlausible 廉价预检：凭证关键字段是否齐全（绝不发网络请求，用于挡掉登录前的初始化中间态）
 * @param validateAndSave 网络校验 + 落库（必须与「保存」按钮共用同一入口），成功返回 true
 * @param isPaused 暂停检测的外部条件（如手动保存/手动粘贴弹窗打开中）；每次轮询都会读取最新值
 * @param onInFlightChange 自动校验在途/结束回调（true=开始校验）。调用方应借此同步禁用右上角「保存」按钮，
 *   避免自动校验期间用户再点「保存」造成并发重复写库/重复「登录成功」提示
 * @param onAutoSaved 自动登录成功（已落库），由调用方关闭登录页
 */
@Composable
fun rememberWebLoginAutoDetect(
    sampleCredential: suspend () -> String?,
    isPlausible: (String) -> Boolean,
    validateAndSave: suspend (String) -> Boolean,
    isPaused: () -> Boolean = { false },
    onInFlightChange: (Boolean) -> Unit = {},
    onAutoSaved: () -> Unit
) {
    // 检测是否已结束（自动保存成功 / 页面已关闭）；校验在途时置 true，防止与手动保存并发重复写库
    var finished by remember { mutableStateOf(false) }
    // 上次真实网络校验的时间（节流）
    var lastValidateTs by remember { mutableLongStateOf(0L) }
    // 最近一次「校验失败」的凭证原文与时间：相同凭证不立即重打接口，等凭证变化或超时后再试
    var lastFailedCredential by remember { mutableStateOf("") }
    var lastFailedTs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (!finished) {
            delay(AUTO_DETECT_POLL_MS)
            if (finished || isPaused()) continue
            val credential = try {
                sampleCredential()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (credential.isNullOrBlank() || !isPlausible(credential)) continue

            val now = System.currentTimeMillis()
            // 同一份凭证刚校验失败过：跳过（登录中间态的值一般是临时的，等页面写入最终凭证）
            if (credential == lastFailedCredential &&
                now - lastFailedTs < AUTO_DETECT_SAME_CREDENTIAL_RETRY_MS
            ) {
                continue
            }
            // 节流：校验走真实网络，不能随轮询频率高频触发
            if (now - lastValidateTs < AUTO_DETECT_VALIDATE_THROTTLE_MS) continue
            lastValidateTs = now
            // 上锁：校验期间不再轮询，也避免与手动「保存」同时写库
            finished = true
            onInFlightChange(true)
            val saved = try {
                validateAndSave(credential)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            // 结束校验：恢复「保存」按钮可用（页面随后关闭，或回到轮询）
            onInFlightChange(false)
            if (saved) {
                SnackbarController.show("登录成功")
                onAutoSaved()
            } else {
                // 中间态/凭证无效：记下原文继续轮询，凭证变化或超时后才会重试
                lastFailedCredential = credential
                lastFailedTs = System.currentTimeMillis()
                finished = false
            }
        }
    }
}
