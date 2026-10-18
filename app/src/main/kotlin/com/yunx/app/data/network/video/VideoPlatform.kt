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

package com.yunx.app.data.network.video

/**
 * 视频解析支持的平台。
 *
 * 与网盘平台的区别：视频平台**只需要一个 Cookie**（不需要分享链接、目录树那一整套），
 * 所以账号体系比网盘薄得多（见 `VideoAccountEntity`）。
 *
 * [loginUrl] 为空表示**无需登录**（匿名即可解析）；非空表示该平台的解析能力依赖网页登录态，
 * 账号页会显示「登录」入口，[cookieDomain] 用于从 WebView 的 CookieManager 里读登录态。
 *
 * 界面上的平台图标（账号页、登录弹窗、解析结果页）由 `ui/items/PlatformLogos.kt` 的
 * `VideoPlatform.logoRes()` 提供 —— **不在这个数据模型里放 drawable 资源 id**（数据层不依赖 `R`）。
 *
 * 能力边界见 Agent.md「视频解析」一节：
 * - 抖音/小红书/快手/微博/AcFun 匿名可解析（抖音/小红书/快手读分享页 SSR，微博读 m 站 JSON API，
 *   AcFun 是 m3u8，交给已有 HLS 下载路径）；
 * - 哔哩哔哩登录后取高清（DASH 音视频分离，下载时由 DashMuxer 合轨）；
 * - Pixiv 需要登录态（`PHPSESSID`）才能拿全量，但**不提供网页登录**、只支持手动填 Cookie
 *   （见 [manualCookieOnly]）；未登录仍能解析一部分公开作品。
 */
enum class VideoPlatform(
    /** 展示名 */
    val displayName: String,
    /** 登录页地址；为空 = 无需登录 */
    val loginUrl: String = "",
    /** 读取 WebView 登录态用的 Cookie 域（如 `bilibili.com`） */
    val cookieDomain: String = "",
    /** 账号页展示的「登录后能得到什么」 */
    val loginBenefit: String = "",
    /**
     * 该平台**只需要账号、但没有网页登录页**：账号页照常显示「登录」入口，但打开的是
     * **手动填写 Cookie** 页，不加载任何网页。
     *
     * ★ 目前只有 Pixiv：它的登录页背后是 reCAPTCHA Enterprise 风控，在 App 的 WebView 里走不完 ——
     *   二段验证提交固定被服务端拒成 `common:error.general.pleaseReloadAndRetry`（页面显示
     *   「服务器错误。请刷新网页。」）。实测（2026-10）同一台手机的系统浏览器可以正常登录，而
     *   换 UA、把 client hints 与 UA 对齐、放开第三方 Cookie 都没能绕过，硬抓包也确认会话 Cookie
     *   与验证码 token 都正常发出、是服务端在验码前就拒了。既然网页登录必然失败，就不要摆一个
     *   永远失败的人口；**账号能力保留** —— 用户可以从浏览器复制 Cookie 粘进来。
     */
    val manualCookieOnly: Boolean = false,
    /**
     * 登录页 WebView 用的 UA。
     *
     * ★ 默认**桌面** Chrome：小红书 / B站 的桌面登录页在手机 WebView 里是完整可用的那一版，
     *   而小红书的移动端根本没有登录入口（换成移动 UA 后 `/login` 直接 404）。
     */
    val loginUserAgent: String = VideoHttp.UA_CHROME,
    /**
     * 登录页要**强制覆盖成的 viewport 桌面宽度**（CSS px）；null = 不动页面自己的 viewport。
     *
     * ★ 为什么小红书必须设它（实测 2026-10）：它的登录页是**固定 440px 宽容器**的布局，且自带
     *   `maximum-scale=1, user-scalable=no`，而 `html`/`body` 是 `overflow-x: hidden`。
     *   手机 WebView 的布局宽度通常只有 360~412px，于是右侧那个「继续」（确认）按钮被直接裁掉
     *   （实测容器 left=-24.8 / right=415.2，按钮 right=391.2），而且**缩不了也滚不到** ——
     *   表现就是用户报的「有二维码和手机号，但手机号根本用不了，因为没有确认按钮」。
     *   覆盖成桌面宽度后页面会切到**桌面双栏登录页**（二维码 + 手机号 + 获取验证码 + 登录），
     *   WebView 自动缩放到整页可见，用户可以双指放大、左右拖动 —— 与百度网盘登录页同一套做法。
     */
    val loginViewportWidth: Int? = null
) {
    DOUYIN("抖音"),
    XHS(
        displayName = "小红书",
        // ★ 实测（2026-10）：小红书网页端的笔记页对**未登录会话**一律拦截 —— explore 路径 302 到
        //   /login，discovery 路径 302 到 /website-login/captcha（风控验证码），两种情况页面里都没有
        //   window.__INITIAL_STATE__。所以它必须算「需要登录」：拿到 web_session 后才能解析。
        // ★ 登录地址必须**直指 /login**，别用站点根地址：根地址会被 302 到 /explore（桌面信息流，
        //   1024px 宽的布局）再往上叠登录弹窗，手机上看到的是被裁切的信息流。
        // ★ 也**不要**为它换移动 UA：移动端根本没有登录页（/login 在移动 UA 下是 404「你访问的页面不见了」，
        //   H5 首页只有「打开 App」没有登录入口）。这里必须用桌面 UA，见 VideoLoginScreen。
        loginUrl = "https://www.xiaohongshu.com/login",
        cookieDomain = "xiaohongshu.com",
        loginBenefit = "小红书网页端要求登录后才能查看笔记，未登录解析会失败",
        // 窄屏下它的登录表单会被裁掉确认按钮（见 loginViewportWidth 注释），强制按桌面宽度渲染
        loginViewportWidth = 1280
    ),
    KUAISHOU("快手"),
    WEIBO("微博"),
    ACFUN("AcFun"),
    BILIBILI(
        displayName = "哔哩哔哩",
        loginUrl = "https://passport.bilibili.com/login",
        cookieDomain = "bilibili.com",
        loginBenefit = "登录后可解析更高清晰度；未登录只能拿到很低清的单文件"
    ),
    PIXIV(
        displayName = "Pixiv",
        cookieDomain = "pixiv.net",
        loginBenefit = "未登录只能拿到一部分公开作品；粘贴浏览器里的 Cookie 后可解析全部作品（含 R18 与受限作品）",
        // ★ 不给网页登录页（见 manualCookieOnly 的说明）：它的风控不允许在 App 内置网页里登录，
        //   登录入口改成「手动填写 Cookie」页。解析仍然照常：匿名能拿到一部分公开作品。
        manualCookieOnly = true
    );

    /** 是否需要登录态：有网页登录页，或没有网页登录页但支持手动填 Cookie */
    val needsLogin: Boolean get() = loginUrl.isNotBlank() || manualCookieOnly

    companion object {
        /** 按枚举名反查（用于从数据库里存的 platform 字段还原） */
        fun fromName(name: String): VideoPlatform? = entries.firstOrNull { it.name == name }
    }
}
