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

import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 迅雷网页登录凭据（pan.xunlei.com 的 localStorage 对象）。
 *
 * @param accessToken 云盘接口用的 access_token（网页版 token）
 * @param refreshToken 刷新令牌；网页 token 只能配**网页** client_id 刷新（见 [CLIENT_ID]）
 * @param deviceId 网页侧 device_id（取自 deviceid Cookie；可能为空，调用方自行兜底）
 * @param captchaToken 网页侧 captcha_token（可能为空）
 * @param userId 用户 ID（JWT sub / user_id），用于后续换号检测
 * @param nickname 昵称（网页对象里通常没有，可能为空）
 */
data class XunleiWebTokens(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String,
    val captchaToken: String,
    val userId: String,
    val nickname: String
)

/**
 * 迅雷网页登录：站点私有格式的解析（纯逻辑，便于单测）。
 *
 * 网页版把登录态写进 localStorage 的 `credentials_<clientId>`（子账号时是 `credentials_<clientId>@<sub>`），
 * 形如 `{"access_token":"…","refresh_token":"…","sub":"…"}`。这里不依赖任何 Android API，
 * 因此「页面结构变了导致登录不上」这类问题可以直接用单测锁住解析行为。
 *
 * ⚠️ 这依赖迅雷网页版的私有实现（键名、字段名）。改版失效时，用户仍可走「手动粘贴」兜底
 * （[parse] 同时支持整段 JSON、带引号的 JSON 字符串、裸 token 三种粘贴形态）。
 */
object XunleiWebCredential {

    /**
     * 网页版 OAuth client_id（抓包自 pan.xunlei.com，与 App 端 Xp6vsxz_7IYVw2BB 不同）。
     * 网页 token 只能用这个 client_id 刷新，且**不需要** client_secret。
     */
    const val CLIENT_ID = "Xqp0kJBXWhwaTpB6"

    /** 登录态在 localStorage 中的键名 */
    const val STORAGE_KEY = "credentials_$CLIENT_ID"

    /** 验证码 token 在 localStorage 中的键名（有效期由页面自己维护，过期就忽略） */
    const val CAPTCHA_KEY = "captcha_$CLIENT_ID"

    /** 网页登录页（未登录会自动进入登录流程；扫码 / 验证码都由官网页面自己处理） */
    const val LOGIN_URL = "https://pan.xunlei.com/"

    /** 落库时写入的认证方式：网页 token 的刷新路径与 App token 不同 */
    const val AUTH_TYPE = "webToken"

    /** 网页版桌面 UA（网页 token 的刷新请求必须带，否则被风控拒绝） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * 与 [DESKTOP_UA] 配套的客户端提示头（Sec-CH-UA 系，版本号必须与 UA 里的 Chrome 大版本一致）。
     * 桌面 UA 若不同步这些头，服务端会按移动端 client hints 判定，与 UA 冲突导致页面降级/被拒。
     *
     * ⚠️ 源码里的 `\"` 是 **Kotlin 转义写法**：运行时字符串里就是普通双引号 `"`、**不含反斜杠**
     * （品牌名必须带引号，这是 client hints 的规范格式，与真实 Chrome 一致，也被 [ILanzouApi] 同样使用）。
     * 因此不要在 `\` 与 `"` 之间加空格——那会变成「反斜杠 + 字符串结束」，直接编译不过。
     * 该点由 `XunleiWebCredentialTest.desktopClientHintConstantsMatchChrome` 锁定。
     */
    const val DESKTOP_SEC_CH_UA = "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\""
    const val DESKTOP_SEC_CH_UA_MOBILE = "?0"
    const val DESKTOP_SEC_CH_UA_PLATFORM = "\"Windows\""

    /**
     * 让 WebView 的**客户端提示**（Sec-CH-UA / Sec-CH-UA-Mobile / Sec-CH-UA-Platform）与
     * [DESKTOP_UA] 保持一致。
     *
     * 只把 `settings.userAgentString` 改成桌面 UA、而不同步 client hints 时，服务端会按 Android/
     * 移动端提示判定请求，与 UA 冲突 —— 实测表现为 pan.xunlei.com 只渲染出「迅雷云盘 / 打开APP」
     * 的移动版落地页（issues #152）。这里用 `WebSettingsCompat.setUserAgentMetadata` 把 client
     * hints 的 UA 元数据也改成桌面 Chrome 131 / Windows（同时影响 HTTP 头与 `navigator.userAgentData`）。
     *
     * 依赖 WebView 的 `USER_AGENT_METADATA` 特性；不支持时静默跳过（页面回退到仅靠 UA 判定）。
     */
    fun applyDesktopClientHints(settings: WebSettings) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        // 品牌顺序/版本与 DESKTOP_UA（Chrome 131 / Windows 64 位）保持一致
        val brandVersions = listOf(
            Triple("Google Chrome", "131", "131.0.0.0"),
            Triple("Chromium", "131", "131.0.0.0"),
            Triple("Not_A Brand", "24", "24.0.0.0")
        ).map { (brand, major, full) ->
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand(brand)
                .setMajorVersion(major)
                .setFullVersion(full)
                .build()
        }
        val metadata = UserAgentMetadata.Builder()
            .setBrandVersionList(brandVersions)
            .setFullVersion("131.0.0.0")
            .setPlatform("Windows")
            .setPlatformVersion("10.0.0")
            .setArchitecture("x86")
            .setModel("")
            .setMobile(false)
            .setWow64(false)
            .build()
        WebSettingsCompat.setUserAgentMetadata(settings, metadata)
    }

    /** 允许 WebView 停留 / 跳转的域名（登录过程会经 i.xunlei.com 等官方域，统一放行 *.xunlei.com） */
    private val TRUSTED_HOSTS = listOf("xunlei.com")

    /**
     * 桌面版登录页的「渲染修补」脚本（页面加载完成后注入）。迅雷这个登录页在手机 WebView 里有三个坑，
     * 实测必须同时处理才可正常使用（改动仅作用于登录页 WebView，登录成功后即关闭，不影响主应用）：
     *
     * 1) 页面 CSS 依赖 `html,body{height:100%}`，但在 WebView 里会被算成 **0px**，再叠加 `overflow:hidden`
     *    → 整页（含登录框）被裁掉，肉眼就是"一片空白"；这里强制高度自适应、溢出可见。
     * 2) 外层是桌面版布局（左侧宣传图 + 右侧登录框）且**不做响应式**：按手机宽度（≈364px）会把登录框
     *    压成 11px。这里固定一个较窄的桌面宽度 520，并隐藏左侧宣传图，让登录框占满、字号也够看。
     * 3) 真正的登录表单在一个来自 `i.xunlei.com` 的 OAuth **iframe** 里，其内容宽 400px，但页面只给了
     *    300px → 右侧（"获取验证码"等）被裁掉；这里把 iframe 放宽到 400px。
     */
    val DESKTOP_RENDER_FIX_JS: String = """
        (function(){try{
          var m=document.querySelector('meta[name="viewport"]');
          if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');(document.head||document.documentElement).appendChild(m);}
          m.setAttribute('content','width=520');
          var s=document.getElementById('yunx-render-fix');
          if(!s){s=document.createElement('style');s.id='yunx-render-fix';(document.head||document.documentElement).appendChild(s);}
          s.textContent=[
            'html,body{height:auto !important;overflow:visible !important;}',
            '#__nuxt,#__layout,.wrapper,.login-page{height:auto !important;overflow:visible !important;}',
            '.pan-share-web-content .banner-wrap{display:none !important;}',
            '.pan-share-web-content .login-web{width:100% !important;max-width:100% !important;}',
            '.login-web iframe,iframe{width:400px !important;max-width:100% !important;}'
          ].join('');
          window.dispatchEvent(new Event('resize'));
        }catch(e){}})()
    """.trimIndent()

    /** 点击「保存」/自动检测时读取登录态的 JS：返回 encodeURIComponent 后的 JSON（空串=还没登录） */
    val READ_SCRIPT: String = """
        (function(){try{
          var prefix='$STORAGE_KEY';
          var sub=localStorage.getItem('current_sub');
          var raw=sub?localStorage.getItem(prefix+'@'+sub):localStorage.getItem(prefix);
          if(!raw)return '';
          var data=JSON.parse(raw);
          if(!data||typeof data!=='object'||Array.isArray(data))return '';
          if(sub&&data.sub&&String(data.sub)!==sub)return '';
          try{
            var c=JSON.parse(localStorage.getItem('$CAPTCHA_KEY')||'{}');
            if(c&&c.token&&Date.parse(c.expires_at)>Date.now())data.captcha_token=c.token;
          }catch(e){}
          try{
            var all=document.cookie.split(';');
            for(var i=0;i<all.length;i++){
              var s=all[i].trim();
              if(s.indexOf('deviceid=')!==0)continue;
              var d=decodeURIComponent(s.slice(9));
              data.device_id=(d.indexOf('.')>-1&&d.length>32)?d.split('.')[1].slice(0,32):d;
              break;
            }
          }catch(e){}
          return encodeURIComponent(JSON.stringify(data));
        }catch(e){return ''}})()
    """.trimIndent()

    /** WebView 是否允许停留在该 URL：仅 https 的 xunlei.com 及其子域、443 端口（about:blank 放行） */
    fun isTrustedUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || value == "about:blank") return true
        val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        // ⚠️ android.net.Uri 的 port 在 URL **没写端口**时返回 -1，**不会**补 scheme 默认端口
        // （和 java.net.URL.getDefaultPort() 不是一回事）。所以这里必须把 -1 当成 443 放行，
        // 否则 `https://pan.xunlei.com/` 这种最常见的地址会被判成不可信 ⇒ WebView 拦掉所有跳转，
        // 网页登录一步都走不动。
        val port = uri.port
        if (port != -1 && port != 443) return false
        val host = uri.host?.lowercase().orEmpty()
        return TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 解析网页登录凭据。支持三种输入：
     * 1. 完整 JSON（localStorage 原文 / 抓包复制）；
     * 2. 被引号包起来的 JSON 字符串（某些工具复制出来是这种）；
     * 3. 裸 access_token（手动粘贴兜底，此时没有 refresh_token，过期后只能重新登录）。
     *
     * 解析失败（含字段校验不过）一律返回 null，由调用方继续轮询或提示用户。
     *
     * 结构上刻意分成两层：这一层只管「怎么把文本变成键值对」，判定逻辑全在 [fieldsFrom] /
     * [parseRawToken] 里——那两个是纯函数，能脱离 Android 的 org.json 做单测
     * （JVM 单测里 org.json 是空壳，任何 JSON 调用都只会返回 null，测不了）。
     */
    fun parse(raw: String): XunleiWebTokens? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        // 形态 2：外层是 JSON 字符串 → 还原成里面的内容
        unwrapQuoted(text)?.let { text = it.trim() }
        if (text.isEmpty()) return null
        // 形态 3：不是 JSON 对象 → 按裸 token 处理（纯字符串逻辑，不碰 JSON）
        if (!text.startsWith("{")) return parseRawToken(text)

        val root = parseObject(text) ?: return null
        return fieldsFrom(toFieldMap(unwrapNested(root)))
    }

    /**
     * 裸 token（手动粘贴 access_token 的场景）：
     * 去掉 `Bearer ` 前缀后过一遍 token 校验，通过就只填 access（没有 refresh，过期只能重登）。
     */
    internal fun parseRawToken(text: String): XunleiWebTokens? {
        val token = stripBearer(text)
        return if (isValidToken(token)) XunleiWebTokens(token, "", "", "", "", "") else null
    }

    /**
     * 从「键 → 值」映射里挑出凭据字段（**纯逻辑，可单测**）。
     *
     * 兼容点：snake_case 与 camelCase 两种键名、嵌套包装已由调用方拆掉、
     * 任一 token 非法（长度/字符集不对）即整体判失败——网页登录过程中会把中间态写进
     * localStorage，不能当成登录成功。
     */
    internal fun fieldsFrom(fields: Map<String, String>): XunleiWebTokens? {
        val access = pick(fields, "access_token", "accessToken")
        val refresh = pick(fields, "refresh_token", "refreshToken")
        if (access.isNotEmpty() && !isValidToken(access)) return null
        if (refresh.isNotEmpty() && !isValidToken(refresh)) return null
        if (access.isEmpty() && refresh.isEmpty()) return null
        val captcha = pick(fields, "captcha_token", "captchaToken")
        return XunleiWebTokens(
            accessToken = access,
            refreshToken = refresh,
            deviceId = pick(fields, "device_id", "deviceId"),
            captchaToken = if (isValidToken(captcha)) captcha else "",
            userId = pick(fields, "user_id", "userId", "sub"),
            nickname = pick(fields, "nick_name", "nickname", "name", "user_name")
        )
    }

    /** 去掉 `Bearer ` 前缀与首尾空白（手动粘贴 token 时的常见形态；**必须先 trim**，否则前缀匹配不上） */
    private fun stripBearer(value: String): String =
        value.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "").trim()

    /** 把 JSONObject 拍平成「键 → 字符串值」；非字符串/数字的值直接丢掉（token 一定是字符串） */
    private fun toFieldMap(data: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = data.opt(key)
            if (value is String || value is Number) map[key] = value.toString()
        }
        return map
    }

    /** 外层是 JSON 字符串字面量时还原内容；不是则返回 null */
    private fun unwrapQuoted(text: String): String? {
        if (!text.startsWith("\"")) return null
        return runCatching {
            val parsed = JSONTokener(text).nextValue()
            parsed as? String
        }.getOrNull()
    }

    /** 解析成 JSONObject；不是对象（裸 token / 数组 / 非法 JSON）返回 null */
    private fun parseObject(text: String): JSONObject? {
        if (!text.startsWith("{")) return null
        return runCatching { JSONTokener(text).nextValue() as? JSONObject }.getOrNull()
    }

    /**
     * 兼容嵌套包装：有些版本把 token 包在 `credentials` / `token` / `data` 里。
     * 最多向下找两层，找到含 token 字段的那层为止。
     */
    private fun unwrapNested(root: JSONObject): JSONObject {
        var current = root
        repeat(2) {
            if (current.has("access_token") || current.has("accessToken") ||
                current.has("refresh_token") || current.has("refreshToken")
            ) {
                return current
            }
            val nested = current.optJSONObject("credentials")
                ?: current.optJSONObject("token")
                ?: current.optJSONObject("data")
                ?: return current
            current = nested
        }
        return current
    }

    /** 取第一个非空字符串字段（数字也接受，与网页侧 pick 行为一致） */
    private fun pick(fields: Map<String, String>, vararg keys: String): String {
        for (key in keys) {
            val text = fields[key]?.trim().orEmpty()
            if (text.isEmpty()) continue
            if (CONTROL_CHARS.containsMatchIn(text)) continue
            return text
        }
        return ""
    }

    /**
     * token 合法性：长度 16~16384、仅 JWT/Base64 允许的字符集、且不是 "null"/"undefined" 这类占位串。
     * 与网页侧的 validToken 保持一致——中间态写进 localStorage 的垃圾值不会当成登录成功。
     */
    fun isValidToken(value: String): Boolean =
        value.length in 16..16384 &&
            TOKEN_CHARS.matches(value) &&
            value != "null" &&
            value != "undefined"

    private val TOKEN_CHARS = Regex("^[A-Za-z0-9._~+/=-]+$")
    private val CONTROL_CHARS = Regex("[\\x00-\\x1f\\x7f]")
}
