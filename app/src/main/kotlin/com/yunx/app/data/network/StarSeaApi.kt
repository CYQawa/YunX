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

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 星海「中文口令解析」客户端（夸克 / UC）。
 *
 * 中文口令不是分享链接（如「绝对有效 咐置松君杂货铺叩苓」），本地解析不出来，
 * 这里问一次星海接口拿到 `pwdId`，再合成 App 现有链路认得的分享链接
 * （`pan.quark.cn/s/<pwdId>` / `drive.uc.cn/s/<pwdId>`）——**后续提取码、登录态、列表、取链全部走既有逻辑**。
 *
 * ★ 只走 `step=parse`（推荐）：只拿 `pwdId`，不索取 stoken、不消耗用户 Cookie，
 *   取链全在 App 本地完成，安全面最小。要 `step=full` 的 `files` 也没必要——
 *   本地链路本来就会列目录，还多一次第三方请求。
 *
 * ★ 隐私：口令原文会发往第三方服务（服务端会把完整口令写进访问日志），所以这里刻意**不打日志**，
 *   只在失败时记「长度 + HTTP 码 + 服务端原因」，不落口令本体。
 */
object StarSeaApi {

    private const val TAG = "YunX-StarSea"

    /** 解析深度：只要 pwd_id（见类注释，刻意不用 full） */
    private const val STEP_PARSE = "parse"

    /** 口令解析结果（只保留 App 需要的两个字段） */
    data class CommandResolve(
        /** `qk` / `uc` */
        val platform: String,
        /** 分享 ID，可直接喂给 App 现有夸克/UC 链路 */
        val pwdId: String
    )

    /**
     * 该不该把这段文本按「中文口令」去问星海（纯逻辑，可单测）。
     *
     * 否则用户随手粘贴一段普通文案也会打一次第三方接口、并把正文发出去。
     * 判定：非空、不超过 [StarSeaConstants.MAX_COMMAND_LENGTH]、且**自身不是 URL**
     * （是 URL 的话说明本地 [ShareLinkParser] 没认出来，那不是口令，问星海也没用）。
     */
    fun looksLikeCommand(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > StarSeaConstants.MAX_COMMAND_LENGTH) return false
        if (t.contains("http://", ignoreCase = true) || t.contains("https://", ignoreCase = true)) return false
        return true
    }

    /**
     * `pwdId` → 分享链接（纯逻辑，可单测）。
     *
     * 合成出来的就是该分享**真实存在**的地址（口令背后的分享本来就在夸克/UC 上），
     * 所以它可以照常被收藏、复制、重新解析，不需要为「口令」单独存一份来源。
     */
    fun shareLinkFor(platform: String, pwdId: String): String =
        if (platform.equals("uc", ignoreCase = true)) {
            "https://drive.uc.cn/s/$pwdId"
        } else {
            "https://pan.quark.cn/s/$pwdId"
        }

    /**
     * 中文口令 → 分享链接（夸克/UC）。失败时 [Result.failure] 里带**可直接展示**的中文原因
     * （限流 / 鉴权失败 / 口令无效或已过期 / 网络异常…）。
     */
    suspend fun resolveToShareLink(command: String): Result<String> =
        resolve(command).mapCatching { shareLinkFor(it.platform, it.pwdId) }

    /** 调 [StarSeaConstants.RESOLVE_URL] 取 pwd_id */
    suspend fun resolve(
        command: String,
        passcode: String? = null,
        step: String = STEP_PARSE
    ): Result<CommandResolve> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("clipboard", command)
                .put("platform", "auto")   // 服务端按机器码/标记词自动判定夸克还是 UC
                .put("step", step)
            // 口令自带提取码时可由调用方传入；一般留空，提取码仍走 App 输入框
            if (!passcode.isNullOrBlank()) payload.put("passcode", passcode)
            val request = Request.Builder()
                .url(StarSeaConstants.RESOLVE_URL)
                .header("X-API-Key", StarSeaConstants.API_KEY)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            HttpClients.apiClient().newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                // ★ 先读 body 再判成败：业务失败与 HTTP 状态码是分离的（非 2xx 也可能带合法 JSON 的原因）
                val json = runCatching { JSONObject(body) }.getOrNull()
                val serverMsg = json?.optString("msg").orEmpty()
                if (json == null || !json.optBoolean("success")) {
                    throw IllegalStateException(describeFailure(resp.code, serverMsg))
                }
                val data = json.optJSONObject("data")
                    ?: throw IllegalStateException("口令解析失败：响应缺少 data")
                val pwdId = data.optString("pwdId")
                if (pwdId.isBlank()) throw IllegalStateException("口令解析失败：未取到分享 ID")
                CommandResolve(platform = data.optString("platform"), pwdId = pwdId)
            }
        }.onFailure {
            // 只记长度与原因，不记口令原文（它会发往第三方，本地日志同样不该留）
            Log.w(TAG, "口令解析失败（长度 ${command.length}）：${it.message}")
        }
    }

    /** 把「HTTP 码 + 服务端 msg」翻译成给用户看的中文原因：服务端有话说就用它的 */
    private fun describeFailure(code: Int, serverMsg: String): String {
        if (serverMsg.isNotBlank()) return serverMsg
        return when (code) {
            400 -> "口令为空或格式不对"
            401 -> "口令解析服务鉴权失败（Key 无效或已轮换）"
            413 -> "口令过长"
            429 -> "口令解析服务限流，请稍后再试"
            404 -> "口令解析服务地址不可用"
            else -> "口令解析失败（HTTP $code）"
        }
    }
}
