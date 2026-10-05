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

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yunx.app.ui.viewmodel.XunleiAccountViewModel
import kotlinx.coroutines.delay

/** 登录方式：0=账号密码（可能触发安全验证）、1=短信登录（不经过密码）。网页登录是独立页面。 */
private const val MODE_PASSWORD = 0
private const val MODE_SMS = 1

/**
 * 迅雷网盘登录页。
 *
 * 三种登录方式并存，各有各的适用场景：
 * - **账号密码**：最省事，但迅雷风控对新设备/异地登录会返回 review_panel，需要再补一次短信验证码；
 * - **短信登录**：不经过密码直接登录（老版本只有「密码登录触发风控后」才会走到短信，本页把短信提成一等入口，
 *   没设过密码、忘记密码、或密码登录被风控挡住的用户不用再绕）；
 * - **网页登录**：见 [XunleiWebLoginScreen]，在应用内打开 pan.xunlei.com 完成登录（可扫码），
 *   token 由网页签发，落库后与其它方式等价。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XunleiLoginScreen(
    viewModel: XunleiAccountViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onVerify: (url: String, deviceId: String) -> Unit = { _, _ -> },
    onWebLogin: () -> Unit = {}
) {
    val context = LocalContext.current
    val step = viewModel.loginStep
    val error = viewModel.loginError
    val smsSent = viewModel.smsSent
    val sendingSms = viewModel.sendingSms
    // collectAsState 订阅账号：登录成功后 account 变非空，必触发重组 → 自动关闭登录页
    val account by viewModel.xunleiAccount.collectAsState()

    var mode by rememberSaveable { mutableIntStateOf(MODE_PASSWORD) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var smsMobile by rememberSaveable { mutableStateOf("") }
    var smsCode by rememberSaveable { mutableStateOf("") }

    // 重发倒计时：ViewModel 记的是「冷却截止时刻」（只有真的发出去才开始算），这里只负责每秒刷新显示
    var resendCooldown by remember { mutableIntStateOf(0) }
    val cooldownUntil = viewModel.smsCooldownUntil
    LaunchedEffect(cooldownUntil) {
        while (true) {
            val left = ((cooldownUntil - System.currentTimeMillis()) / 1000).toInt()
            resendCooldown = left.coerceAtLeast(0)
            if (left <= 0) break
            delay(1_000)
        }
    }

    // 进入页面清掉上一次的中间态：登录页可能在「安全验证」那一步被关掉，重开时那个 creditkey
    // 早已失效，直接渲染成验证步骤只会让用户提交必然失败的验证码
    LaunchedEffect(Unit) { viewModel.resetLoginStep() }

    // 登录错误提示
    LaunchedEffect(error) {
        error?.let {
            SnackbarController.show(it)
            viewModel.consumeLoginError()
        }
    }
    // 登录成功后自动关闭登录页（短信/密码/网页任一方式成功，账号非空即关闭）
    LaunchedEffect(account) {
        if (account != null) onSaved()
    }

    BackHandler { onBack() }

    // 密码登录被风控拦下后进入的短信验证步骤（step 为空表示还在输入账号密码）
    val smsStep = step?.takeIf { it.needSms }

    // 全局 Snackbar 宿主
    val snackbarHostState = rememberGlobalSnackbarHostState()

    /** 切换登录方式：清掉上一步的中间态，避免把旧 creditkey/token 带进新流程 */
    fun switchMode(target: Int) {
        if (mode == target) return
        // 密码登录被风控拦下时，手机号大概率就是账号本身，切到短信模式顺手带过去
        if (target == MODE_SMS && smsMobile.isBlank()) smsMobile = username
        mode = target
        viewModel.resetLoginStep()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("迅雷网盘登录", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    onClick = { switchMode(MODE_PASSWORD) },
                    selected = mode == MODE_PASSWORD
                ) { Text("账号密码") }
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    onClick = { switchMode(MODE_SMS) },
                    selected = mode == MODE_SMS
                ) { Text("短信登录") }
            }

            if (mode == MODE_PASSWORD) {
                Text(
                    text = if (step?.needSms == true) "安全验证" else "使用迅雷账号登录",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (step?.needSms == true) {
                        if (smsSent) "账号密码登录触发安全验证，验证码已发送至 $username"
                        else "账号密码登录触发安全验证，请点击下方「发送验证码」"
                    } else {
                        "输入手机号 / 邮箱与密码，支持解析与下载分享文件"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "短信验证码登录",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "无需密码：输入手机号获取验证码即可登录（没设过密码、忘记密码都走这里）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when {
                // ---------- 账号密码：安全验证步骤（密码登录被风控拦下后） ----------
                mode == MODE_PASSWORD && smsStep != null -> {
                    OutlinedTextField(
                        value = smsCode,
                        onValueChange = { smsCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("短信验证码") },
                        leadingIcon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                    Button(
                        onClick = { viewModel.loginWithSms(username, smsCode, smsStep.smsCreditKey, smsStep.smsToken) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = smsCode.isNotBlank()
                    ) { Text("验证并登录") }
                    SmsSendButton(
                        sent = smsSent,
                        sending = sendingSms,
                        cooldown = resendCooldown,
                        onSend = { viewModel.sendSms(username) }
                    )
                    Text(
                        text = "若始终收不到短信，请确认手机号正确，或稍后重试 / 切换网络",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    // 短信发不出时的应用内验证兜底（应用内 WebView 承载验证页；核心验证仍走自有短信流）
                    if (smsStep.reviewUrl.isNotBlank()) {
                        TextButton(
                            onClick = {
                                // 用与登录请求一致的设备签名（deviceSign = div101.xxx）：
                                // 验证页会把 URL 里的 deviceid 原样当 devicesign 用，
                                // 必须与 v3/login 的 devicesign 字段一致，否则报"登录信息已过期"
                                onVerify(smsStep.reviewUrl, com.yunx.app.data.network.XunleiDeviceFingerprint.deviceSign())
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(
                                text = "短信收不到？应用内验证",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "应用内完成验证后，将自动重新登录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }

                // ---------- 短信登录 ----------
                mode == MODE_SMS -> {
                    OutlinedTextField(
                        value = smsMobile,
                        onValueChange = { smsMobile = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("手机号") },
                        leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                    // 验证码输入框在发送成功后出现：没发短信就填不了，少一个「点了没反应」的困惑
                    if (smsSent) {
                        OutlinedTextField(
                            value = smsCode,
                            onValueChange = { smsCode = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("短信验证码") },
                            leadingIcon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = MaterialTheme.shapes.large
                        )
                        Button(
                            onClick = {
                                viewModel.loginWithSms(
                                    smsMobile, smsCode,
                                    step?.smsCreditKey.orEmpty(), step?.smsToken.orEmpty()
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            enabled = smsCode.isNotBlank() && !step?.smsCreditKey.isNullOrBlank()
                        ) { Text("验证并登录") }
                    }
                    SmsSendButton(
                        sent = smsSent,
                        sending = sendingSms,
                        cooldown = resendCooldown,
                        enabled = smsMobile.isNotBlank(),
                        onSend = { viewModel.sendSms(smsMobile) }
                    )
                    Text(
                        text = "短信需由你主动发送并填写验证码；未注册的手机号会按迅雷官方流程处理",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                // ---------- 账号密码：输入步骤 ----------
                else -> {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("手机号 / 邮箱") },
                        leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = if (passwordVisible) KeyboardType.Text else KeyboardType.Password),
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                    Button(
                        onClick = { viewModel.login(username, password) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = username.isNotBlank() && password.isNotBlank()
                    ) { Text("登录") }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 网页登录入口：扫码 / 验证码都在官网页面里完成，不受本应用的登录接口风控影响
            OutlinedButton(
                onClick = onWebLogin,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(
                    Icons.Outlined.Language,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("网页登录（扫码 / 验证码）")
            }
            Text(
                text = "在应用内打开迅雷网页版登录，登录态自动保存；收不到短信或密码登录被拦截时用这个最稳",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            if (mode == MODE_PASSWORD && step?.needSms != true) {
                // 未设置密码：跳转迅雷官网设置（浏览器打开）
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://i.xunlei.com/xluser/validate/findpwd_acc.html")
                                )
                            )
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = "未设置密码，点我前往设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 发送 / 重新发送验证码按钮（密码登录触发风控、短信登录两条流程共用）：
 * 冷却期内显示剩余秒数并禁用，避免连点造成多条短信。
 * 必须是 ColumnScope 的扩展——内部要用 `Modifier.align` 居中。
 */
@Composable
private fun ColumnScope.SmsSendButton(
    sent: Boolean,
    sending: Boolean,
    cooldown: Int,
    enabled: Boolean = true,
    onSend: () -> Unit
) {
    val label = when {
        cooldown > 0 -> "$cooldown 秒后重新发送"
        sent -> "重新发送验证码"
        else -> "发送验证码"
    }
    val canClick = enabled && !sending && cooldown == 0
    // 首次发送用主色按钮（引导），已发送后降级为次要按钮（避免误点）
    if (!sent) {
        FilledTonalButton(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = canClick
        ) {
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(label)
            }
        }
    } else {
        TextButton(
            onClick = onSend,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = canClick
        ) { Text(label) }
    }
}
