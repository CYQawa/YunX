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

import com.yunx.app.data.download.DownloadPathPolicy
import com.yunx.app.data.network.HttpClients
import com.yunx.app.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 抖音解析器（视频 + 图文/图集）。
 *
 * - **平台**：抖音（`VideoPlatform.DOUYIN`）。
 * - **数据来源手法**：请求 iesdouyin 分享页的 SSR，从 HTML 里抠出 `window._ROUTER_DATA`
 *   （抖音给移动端 Web 的预渲染数据，字段语义见参考实现 `douyin/video.py`），再对视频走
 *   `aweme/v1/play` 端点探测**无水印**直链。
 * - **是否需要登录**：不需要。但抖音要求先带上一枚匿名 `ttwid` Cookie，否则返回验证页
 *   （见 [ensureTtwid]）。
 * - **产出形态**：视频命中 → 单条 [VideoMediaKind.VIDEO] 直链；图文命中 → 多条
 *   [VideoMediaKind.IMAGE] 原图。二者互斥（与参考实现的 `if/elif` 一致）。
 *
 * 已知限制：
 * 1. `slides`（幻灯片 / 动图集）：不再单列一条路 —— `www.douyin.com/video/<id>` 会被抖音 302 到真正的
 *    作品页（图文/动图落到 `/note/<id>`），详情从 SSR 的 `#RENDER_DATA`（`app.videoDetail`）取，
 *    动图资源在 `images[].video.playAddr`（2026-10-22 实测可用）；
 * 2. `ttwid` 用进程内缓存（见 [ensureTtwid]）；缓存那枚被风控标记时由 [fetchWorkData] 自动重注册
 *    重试一次（**不需要用户重启 App**）。
 *
 * ⚠️ **重要现状（2026-10 实测：本机 curl 直连 + 无头浏览器双核对）**：分享页这条路已经走不通 ——
 *    - `iesdouyin.com/share/{video|note}/{id}/` 会 **302 到 `www.douyin.com/{video|note}/{id}`**；
 *      纯 HTTP 拿到的是一张 **`byted_acrawler` JS 挑战页**（约 72KB，内容是
 *      `window.byted_acrawler.init({aid:99999999})` + 用 `__ac_nonce` 算出 `__ac_signature` 写回
 *      Cookie 再 `location.reload()`），页面里**没有作品数据**。
 *    - **真实浏览器跑完这段 JS 后** reload 到的页面是**服务端直出**的：`<script id="RENDER_DATA">`
 *      （约 200KB URL 编码 JSON）、`window.SSR_RENDER_DATA` 里就有图文/视频与作者数据，
 *      图片直链是 DOM 里带 `s=PackSourceEnum_AWEME_DETAIL` 的 `p*-pc-sign.douyinpic.com` 链接。
 *      也就是说**数据是有的，只是被 acrawler 挑战挡在纯 HTTP 之外**。
 *      另注：`share/slides/{id}/` 连 `_ROUTER_DATA` 都没有。
 *    - 页面里那几个 JSON 接口（`/web/api/v2/aweme/iteminfo/`（参数 `item_ids`/`aweme_type`/
 *      `reflow_id`/`share_scene`/`share_token`/`scene_from`）、`/web/api/v2/aweme/slidesinfo/`、
 *      `/aweme/v1/web/aweme/detail/`）**一律要签名**：不带签名是 `200 + Content-Length: 0`，
 *      detail 直接 `403 Blocked by ArgusSecurityPlugin Uifid Not Found`；页面实际请求里能看到的
 *      签名料是 `a_bogus`（每请求不同）、`verifyFp`/`fp`、`uifid`、`webid`，部分接口还要
 *      `msToken` 与 `x-secsdk-web-signature` + `timestamp`。
 *    - 光带 `ttwid` 解锁不了（实测 ttwid 注册接口当前返回 `{"status_code":1001,"parse params fail"}`）。
 *
 *    结论：要让抖音恢复可用，**得让 JS 跑起来**（用 WebView 打开分享页/详情页，等 acrawler 挑战过完
 *    与页面直出后，从页面里取直链；或者让 WebView 只负责把 `__ac_signature` 等 Cookie 算出来，再交回
 *    OkHttp 走 HTTP）。在本文件里改字段名是没用的。
 */
object DouyinParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.DOUYIN

    private const val TTWID_REGISTER_URL = "https://ttwid.bytedance.com/ttwid/union/register/"
    private const val IESDOUYIN_REFERER = "https://www.iesdouyin.com/"

    /** 探测无水印直链时依次尝试的清晰度（顺序与参考实现一致，最终取体积最大的一路） */
    private val PLAY_RATIOS = listOf("1080p", "720p", "540p", "360p")

    /** 从原始链接里还原分享页类型（识别层把 video/note/slides 都压成了 kind=video） */
    private val TYPE_REGEX = Regex("""/(?:share/)?(video|note|slides)/""")

    /** 从 play_addr 直链里反查 video_id 查询参数 */
    private val VIDEO_ID_REGEX = Regex("""[?&]video_id=([^&\s]+)""")

    /** 内容区间 `bytes 0-1/12345` 里的总长度 */
    private val CONTENT_RANGE_TOTAL_REGEX = Regex("""/(\d+)\s*$""")

    /**
     * 进程内缓存的匿名 ttwid。
     *
     * 之所以缓存而不每次解析都注册：ttwid 有效期较长，重复注册是白白多挨一次风控。
     * 并发下最坏情况是两个解析同时注册并互相覆盖缓存，结果等价、无副作用，故不加锁。
     *
     * ★ 这枚可能被风控标记，**不需要用户重启 App**：[fetchWorkData] 在「服务端没给作品数据」时会
     *   调 [renewTtwid] 丢掉缓存、重注册一枚再试。
     */
    @Volatile
    private var cachedTtwid: String? = null

    private data class ResolvedMedia(val url: String, val size: Long, val headers: Map<String, String>)

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        // ★ 主路线：隐藏 WebView 真跑一遍页面。纯 HTTP 拿不到数据 —— 先要过 byted_acrawler 挑战，
        //   而真页面里数据又只渲染在 DOM 里（见类注释的「重要现状」）。
        fetchViaWebView(link)?.let { return buildFromWebView(link, it) }

        // 回退：老的 HTTP + SSR 路线。2026-10 起基本必然拿不到数据，保留只为抖音万一回退回去。
        val shareUrl = buildShareUrl(link)
        val data = fetchWorkData(link, shareUrl, cookie)
        val mediaHeaders = buildMediaHeaders(shareUrl)

        val title = data.optStringAny("desc")
        val medias = mutableListOf<VideoMedia>()
        var cover = ""

        val images = data.optArrayAny("images")
        val video = data.optObjectAny("video")
        if (images != null && images.length() > 0) {
            // 图文：参考实现对每张图随机取一条 url_list，这里固定取第一条以保证结果可复现
            for (i in 0 until images.length()) {
                val image = images.optJSONObject(i) ?: continue
                val url = firstUrl(image) ?: continue
                if (cover.isBlank()) cover = url
                medias += VideoMedia(
                    url = url,
                    kind = VideoMediaKind.IMAGE,
                    fileName = imageFileName(link.id, title, i + 1),
                    quality = "原图",
                    headers = mediaHeaders
                )
            }
        } else if (video != null) {
            val resolved = resolveVideo(video, mediaHeaders)
            if (resolved != null) {
                cover = firstUrl(video.optObjectAny("cover")).orEmpty()
                medias += VideoMedia(
                    url = resolved.url,
                    kind = VideoMediaKind.VIDEO,
                    fileName = videoFileName(link.id, title),
                    size = resolved.size,
                    headers = resolved.headers
                )
            }
        }

        val author = data.optObjectAny("author")
        return VideoParseResult(
            platform = platform,
            title = title,
            author = author?.optStringAny("nickname").orEmpty(),
            // 参考实现优先 avatar_thumb，退化到 avatar_medium
            avatar = firstUrl(author?.optObjectAny("avatar_thumb", "avatar_medium")).orEmpty(),
            cover = cover,
            timestampSeconds = data.optLongAny("create_time", "createTime"),
            url = link.url,
            medias = medias
        )
    }

    // ------------------------------------------------------------------ 分享页

    /**
     * 拉分享页并取出作品对象；**服务端没给作品数据时，换一枚新的匿名 ttwid 再试一次**。
     *
     * ★ 为什么要重试：抖音是按**匿名 Cookie（ttwid）**决定要不要在 SSR 里塞作品数据的。缓存里那枚
     *   一旦被风控标记，后续每次都只会返回「有元数据、没有 `videoInfoRes`」的页面 —— 表象就是
     *   「videoInfoRes.item_list 为空」。旧实现只在进程内缓存、被标记后要**重启 App** 才能恢复
     *   （见 [cachedTtwid] 原来的说明），用户完全无从下手；这里改成自己重注册一枚自愈。
     * ★ 只在**失败路径**上多花一次请求：第一次就成功的话行为和以前完全一致。
     */
    private suspend fun fetchWorkData(
        link: DetectedVideoLink,
        shareUrl: String,
        cookie: String
    ): JSONObject {
        for (attempt in 0..1) {
            val ttwid = if (attempt == 0) ensureTtwid() else renewTtwid()
            // 抖音无需登录，cookie 正常为空；带上只是兼容万一上层塞了账号 Cookie 的情况
            val cookieHeader = if (cookie.isBlank()) ttwid else "$ttwid; $cookie"
            try {
                val html = VideoHttp.getText(
                    url = shareUrl,
                    headers = mapOf(
                        "User-Agent" to VideoHttp.UA_IPHONE,
                        "Referer" to IESDOUYIN_REFERER,
                        "Cookie" to cookieHeader
                    ),
                    what = "抖音分享页"
                )
                val routerJson = VideoHttp.extractInlineJson(html, "window._ROUTER_DATA")
                    ?: throw VideoParseException(
                        VideoParseErrorKind.UNKNOWN,
                        "抖音分享页里没有 window._ROUTER_DATA（作品可能已删除或页面改版）"
                    )
                return extractVideoData(routerJson, link.id)
            } catch (e: VideoParseException) {
                // 只有「页面/数据不对」这一类值得换 ttwid 重试；网络失败再试一次也是白搭。
                // 第二次仍失败就把它抛出去（带上面那串实测事实）。
                if (e.kind != VideoParseErrorKind.UNKNOWN || attempt == 1) throw e
            }
        }
        // 上面每一轮都以 return 或 throw 结束，这里只是为了满足编译器的可达性检查
        throw VideoParseException(VideoParseErrorKind.UNKNOWN, "抖音解析失败")
    }

    // ------------------------------------------------------------------ 隐藏 WebView 路线

    /** 隐藏 WebView 从**页面自己的详情响应**里取回的作品事实 */
    private data class WebPayload(
        val title: String,
        val author: String,
        val images: List<String>,
        val video: String,
        val cover: String,
        /** 与 [images] 一一对应的**动图资源**（Live Photo 的短视频）；没有动图时是等长的空串 */
        val motions: List<String> = emptyList()
    )

    /**
     * 用隐藏 WebView 打开作品页并取回事实；拿不到（超时 / 页面没渲染出东西 / 没有前台 Activity）返回 null。
     *
     * ★ 走 `www.douyin.com/{note|video}/{id}`：分享域 `iesdouyin.com/share/...` 只是 302 到这里，
     *   直接打开终点域名可省一次跳转。
     * ★ 用桌面 UA（`UA_CHROME`）：站点是 PC 版详情页，桌面 UA 才走那条渲染分支。
     */
    private suspend fun fetchViaWebView(link: DetectedVideoLink): WebPayload? {
        val type = pathType(link)
        val url = "https://www.douyin.com/$type/${link.id}"
        val json = HeadlessWebView.loadAndExtract(
            url = url,
            userAgent = VideoHttp.UA_CHROME,
            readyCheckJs = readyCheckJs(),
            extractJs = EXTRACT_JS,
            // 页面要在 WebView 里跑完 acrawler 挑战 → 重载 → 挂载 → 再自己发详情请求才拿到数据，
            // 实测视频页冷启动要 25s 上下，所以留足余量（比默认值大）
            timeoutMs = 40_000L,
            // 钩子必须在文档开始前挂上，才能拿到页面自己发出的详情响应（视频那条路要用）
            extraDocumentStartJs = listOf(XHR_DETAIL_HOOK_JS)
        ) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        obj.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { err ->
            DiagnosticLog.warn(DiagnosticLog.NETWORK, "douyin_webview_extract_failed", err)
            return null
        }
        // 图片与动图资源由脚本**成对**产出（同一个数据源、下标一致）
        val images = obj.optJSONArray("images")?.toStringList().orEmpty()
        val motions = obj.optJSONArray("motions")?.toStringList().orEmpty()
        // 诊断：用了哪个数据源、拿到几张图/几张动图、SSR 状态、首项字段名 —— 排查「为什么没有动图」全靠这一行
        DiagnosticLog.warn(
            DiagnosticLog.NETWORK, "douyin_webview_media",
            "src=${obj.optString("src")} images=${images.size} motions=${obj.optString("motionCount")} " +
                "ssr=${obj.optString("ssr")} imgKeys=${obj.optString("imgKeys")} " +
                "videoKeys=${obj.optString("videoKeys")}"
        )
        val video = obj.optString("video").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        if (images.isEmpty() && video.isBlank()) {
            DiagnosticLog.warn(DiagnosticLog.NETWORK, "douyin_webview_no_media", "页面没有给出可用的图/视频直链")
            return null
        }
        return WebPayload(
            title = obj.optString("title").takeIf { it != "null" }.orEmpty(),
            author = obj.optString("author").takeIf { it != "null" }.orEmpty(),
            images = images,
            video = video,
            cover = obj.optString("cover").takeIf { it != "null" }.orEmpty(),
            motions = motions
        )
    }

    /** `JSONArray` → `List<String>`（丢掉空值；解析脚本给的字段都是字符串） */
    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    /** 把 WebView 取回的事实拼成解析结果（图文优先：详情里 `images` 有值就是图文） */
    private fun buildFromWebView(link: DetectedVideoLink, payload: WebPayload): VideoParseResult {
        val mediaHeaders = buildMediaHeaders("https://www.douyin.com/")
        val base = safeTitle(payload.title, "douyin_${link.id}")
        val medias = mutableListOf<VideoMedia>()
        if (payload.images.isNotEmpty()) {
            payload.images.forEachIndexed { index, url ->
                val motionUrl = payload.motions.getOrNull(index).orEmpty()
                val isMotion = motionUrl.isNotBlank()
                medias += VideoMedia(
                    url = url,
                    kind = VideoMediaKind.IMAGE,
                    // 动图下载后是「动态照片」JPEG（静图会由 MotionPhotoWriter 转码），扩展名要按产物给，
                    // 否则会存成 `xxx_1.webp` 再被 MediaStore 补一个扩展名，看着很别扭
                    fileName = "${base}_${index + 1}${if (isMotion) ".jpg" else imageExtension(url)}",
                    headers = mediaHeaders,
                    // 动图（Live Photo）：带上「会动的那一半」，下载时合成动态照片
                    quality = if (isMotion) "动图" else "",
                    motionUrl = motionUrl
                )
            }
        } else {
            medias += VideoMedia(
                url = payload.video,
                kind = VideoMediaKind.VIDEO,
                fileName = "$base.mp4",
                headers = mediaHeaders
            )
        }
        return VideoParseResult(
            platform = platform,
            title = payload.title,
            author = payload.author,
            // 详情里给了封面就用它，没给（图文）退回首图
            cover = payload.cover.ifBlank { payload.images.firstOrNull().orEmpty() },
            url = link.url,
            medias = medias
        )
    }

    /** 从直链里猜图片扩展名（抖音图集是 `~tplv-dy-aweme-images:q75.webp` 这类，不能一律当 jpg） */
    private fun imageExtension(url: String): String = when {
        url.contains(".webp") -> ".webp"
        url.contains(".png") -> ".png"
        url.contains(".jpeg") || url.contains(".jpg") -> ".jpg"
        else -> ".jpg"
    }

    /**
     * 「文档开始前」注入的 XHR 钩子：把页面**自己发出的**作品详情响应原文存到 `window.__yunxAwemeDetail`。
     *
     * ★ 为什么不再抓 DOM（实测 2026-10 的教训）：详情接口的响应才是干净的结构化数据 ——
     *   图文页 `body.innerText` 只有 186 字（8 张图全在轮播里，`<img>` 上抓不到），
     *   视频页播放器还可能走 MSE（`blob:`）拿不到直链。钩住这个响应后，图文与视频都能从**同一条 JSON**
     *   取数，而且认的一定是**本条作品**（不会是「相关推荐」里的别人）。
     * ★ 必须挂在文档开始前：挂晚了页面自己的请求已经发完了。
     * ★ 只认 `aweme/v1/web/aweme/detail`（CDP 抓包实测页面就是请求这个接口拿详情的），且只收 200 且响应体非空。
     */
    private const val XHR_DETAIL_HOOK_JS = """
(function () {
  try {
    if (window.__yunxDetailHookInstalled) return;
    window.__yunxDetailHookInstalled = true;
    var rawOpen = XMLHttpRequest.prototype.open;
    var rawSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      try { this.__yunxUrl = String(url || ''); } catch (e) { this.__yunxUrl = ''; }
      return rawOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      var xhr = this;
      try {
        xhr.addEventListener('load', function () {
          try {
            var url = xhr.__yunxUrl || '';
            if (url.indexOf('aweme/v1/web/aweme/detail') < 0) return;
            if (xhr.status !== 200) return;
            // ★ 不能只读 responseText：页面若把 responseType 设成 'json'，读 responseText 会抛
            //   InvalidStateError（被 try 吞掉 → 钩子永远拿不到值，表现就是「一直不就绪」）。
            var text = '';
            try { text = xhr.responseText || ''; } catch (e) { text = ''; }
            if (!text) {
              try {
                var r = xhr.response;
                if (r) text = (typeof r === 'string') ? r : JSON.stringify(r);
              } catch (e) { text = ''; }
            }
            if (text) window.__yunxAwemeDetail = text;
          } catch (e) {}
        });
      } catch (e) {}
      return rawSend.apply(this, arguments);
    };
  } catch (e) {}
})()
"""

    /**
     * 就绪判断：**两条路任一条成立即可**。
     *
     * ★ 不能按链接里的类型分支（2026-10-22 修）：`/video/<id>`、`?modal_id=<id>` 这类链接抖音会自行
     *   302 到真正的作品页（图文 / 动图会落到 `/note/<id>`），于是「按链接判类型」必然判错 ——
     *   视频那条判据（等 XHR 钩子）在图文页上**永远不会成立**，只能干等到 40s 超时后整条解析失败。
     * ★ DOM 那条判据只认**真正的作品图**：`biz_tag=aweme_images` 或 `tplv-dy-aweme-images`。
     *   封面是 `biz_tag=pcweb_cover`、「相关推荐」带 `WEBPC_RELATED`，两者都不算 —— 否则视频页会
     *   刚渲染出封面就「就绪」，拿回一堆封面当作品图（视频直链只在钩子数据里，早退就丢了）。
     */
    private fun readyCheckJs(): String = """
(function () {
  var raw = window.__yunxAwemeDetail;
  if (typeof raw === 'string' && raw.length > 0) return 'true';
  var imgs = document.querySelectorAll('img');
  for (var i = 0; i < imgs.length; i++) {
    if (isWorkImage(imgs[i].src || '')) return 'true';
  }
  return 'false';
  function isWorkImage(s) {
    if (!s || s.indexOf('douyinpic.com') < 0) return false;
    if (s.indexOf('WEBPC_RELATED') >= 0) return false;
    return s.indexOf('biz_tag=aweme_images') >= 0 || s.indexOf('tplv-dy-aweme-images') >= 0;
  }
})()
"""

    /**
     * 取数：**多条数据源都试**，返回一个**小对象**（`evaluateJavascript` 会把它序列化成 JSON）。
     *
     * ★ 关键取舍（2026-10-22 修：动图资源一直取不到）：**图片与动图资源必须成对、来自同一个源**，
     *   否则下标会错位。优先级：
     *   1. **SSR**（`<script id="RENDER_DATA">` 里 URL 编码的 `app.videoDetail`）—— 图文/动图的
     *      完整清单与每张的动图资源（`images[].video.playAddr[].src`）都在这里，**动图只有这条路能拿到**；
     *   2. **XHR 钩子**（`window.__yunxAwemeDetail`）—— 视频页的详情，视频直链在这里；
     *   3. **DOM** 里的作品图 —— 最后兜底，**拿不到动图资源**（所以动图会退化成静态图）。
     *
     * ★ 不把整份详情（100KB+）回传：`evaluateJavascript` 传大字符串又慢又容易出问题，只回传必要字段；
     *   同时回传用了哪个源（`src`）与动图条数，排查「为什么没动图」时一眼可见。
     */
    private const val EXTRACT_JS = """
(function () {
  try {
    function firstUrl(node) {
      if (!node) return '';
      var list = node.url_list || node.urlList || null;
      if (list && list.length) return String(list[0] || '');
      return '';
    }

    function keysOf(node) {
      if (!node || typeof node !== 'object') return '';
      var keys = [];
      for (var p in node) keys.push(p);
      return keys.join(',');
    }

    // 动图资源：每张图各自的短视频（Live Photo 的「会动的那一半」）。两种数据源形态不同：
    // - 网页 SSR：`video.playAddr` 是 [{src: '...'}]
    // - 接口 JSON（XHR 钩子）：`video.play_addr` 是 {uri, url_list: [...]}（snake_case）
    function motionUrl(item) {
      if (!item) return '';
      var v = item.video;
      if (!v) return '';
      var direct = firstUrl(v.playAddr) || firstUrl(v.play_addr)
          || firstUrl(v.playApi) || firstUrl(v.play_addr_h264)
          || firstUrl(v.downloadAddr) || firstUrl(v.download_addr);
      if (direct) return direct;
      var list = v.playAddr || v.play_addr || null;
      if (list && list.length) {
        var first = list[0];
        if (first) return String(first.src || first.url || '');
      }
      return '';
    }

    // 钩子那份详情（视频/图文页页面自己会请求 aweme/v1/web/aweme/detail）
    function hookDetail() {
      var raw = window.__yunxAwemeDetail || '';
      if (!raw) return null;
      try {
        var root = JSON.parse(raw);
        return root.aweme_detail || root.awemeDetail || (root.data && root.data.aweme_detail) || null;
      } catch (e) { return null; }
    }

    // SSR 详情：抖音把作品详情 URL 编码后塞在 <script id="RENDER_DATA"> 里（app.videoDetail）。
    // 记录失败原因（ssrNote），下次「为什么没走到 SSR」直接看日志即可。
    var ssrNote = 'none';
    function readSsrDetail() {
      var node = document.querySelector('#RENDER_DATA');
      if (node && node.textContent) {
        try {
          var app = JSON.parse(decodeURIComponent(node.textContent)).app;
          if (app && app.videoDetail) { ssrNote = 'rd-ok'; return app.videoDetail; }
          ssrNote = 'rd-no-videoDetail';
        } catch (e) {
          ssrNote = 'rd-' + (e && e.name ? e.name : 'parse-fail');
        }
      } else {
        ssrNote = 'rd-missing';
      }
      try {
        var ssr = window.SSR_RENDER_DATA;
        if (ssr && ssr.app && ssr.app.videoDetail) { ssrNote += '+global-ok'; return ssr.app.videoDetail; }
        ssrNote += '+global-' + (typeof ssr);
      } catch (e) {}
      return null;
    }

    // 图片 + 动图资源**成对**抽取（保证下标一致）；同时带上首项的字段名，便于排查字段改版
    function imagesFrom(node) {
      if (!node || !node.length) return null;
      var list = [];
      var mlist = [];
      for (var i = 0; i < node.length; i++) {
        var item = node[i];
        var u = firstUrl(item);
        if (!u && item.display_image) u = firstUrl(item.display_image);
        if (!u && item.owner_watermark_image) u = firstUrl(item.owner_watermark_image);
        if (u) {
          list.push(u);
          mlist.push(motionUrl(item));
        }
      }
      if (!list.length) return null;
      return {
        images: list,
        motions: mlist,
        imgKeys: keysOf(node[0]),
        videoKeys: keysOf(node[0] && node[0].video)
      };
    }

    // DOM 里的作品图兜底（只认 aweme_images / tplv-dy-aweme-images，排除封面与「相关推荐」）
    function domWorkImages() {
      var result = [];
      var all = document.querySelectorAll('img');
      for (var i = 0; i < all.length; i++) {
        var src = all[i].src || '';
        if (src.indexOf('douyinpic') < 0) continue;
        if (src.indexOf('WEBPC_RELATED') >= 0) continue;
        var isWork = src.indexOf('biz_tag=aweme_images') >= 0 || src.indexOf('tplv-dy-aweme-images') >= 0;
        if (isWork && result.indexOf(src) < 0) result.push(src);
      }
      return result;
    }

    var hooked = hookDetail();
    var ssr = readSsrDetail();

    var images = [];
    var motions = [];
    var src = '';
    var picked = imagesFrom(ssr && ssr.images);
    if (picked) { src = 'ssr'; }
    if (!picked) {
      picked = imagesFrom(hooked && hooked.images);
      if (picked) { src = 'hook'; }
    }
    if (!picked) {
      picked = imagesFrom(hooked && hooked.image_post_info && hooked.image_post_info.images);
      if (picked) { src = 'hook'; }
    }
    if (picked) {
      images = picked.images;
      motions = picked.motions;
    } else {
      images = domWorkImages();
      src = images.length ? 'dom' : 'none';
    }

    // 视频直链：钩子优先（视频页的详情在这里），其次 SSR
    var v = (hooked && hooked.video) || (ssr && ssr.video) || null;
    var video = '';
    if (v) {
      video = firstUrl(v.play_addr) || firstUrl(v.playAddr) || firstUrl(v.play_addr_h264);
      if (!video && v.bit_rate && v.bit_rate.length) {
        for (var i = 0; i < v.bit_rate.length; i++) {
          var candidate = firstUrl(v.bit_rate[i].play_addr);
          if (candidate) { video = candidate; break; }
        }
      }
      video = video.replace('/playwm/', '/play/');
    }

    var d = hooked || ssr;
    var author = '';
    if (d && d.author) author = d.author.nickname || d.author.unique_id || '';
    if (!author && d && d.authorInfo) author = d.authorInfo.nickname || d.authorInfo.nickName || '';

    // 标题：优先详情里的 desc；没有就退到 document.title —— 抖音详情页标题就是「<文案> - 抖音」，
    // 去掉尾巴即可，实测与 desc 等价。
    var title = (d && d.desc) || '';
    if (!title) {
      var pageTitle = document.title || '';
      var tail = pageTitle.lastIndexOf(' - 抖音');
      if (tail > 0) pageTitle = pageTitle.substring(0, tail);
      title = pageTitle;
    }

    var motionCount = 0;
    for (var m = 0; m < motions.length; m++) {
      if (motions[m]) motionCount++;
    }

    return {
      title: title,
      author: author,
      video: video,
      images: images,
      motions: motions,
      src: src,
      motionCount: motionCount,
      // 诊断字段：没取到动图时靠它们定位（数据源、SSR 状态、图片项与 video 节点的字段名）
      ssr: ssrNote,
      imgKeys: picked ? picked.imgKeys : '',
      videoKeys: picked ? picked.videoKeys : '',
      cover: (v && (firstUrl(v.cover) || firstUrl(v.origin_cover))) || ''
    };
  } catch (e) {
    return { error: 'js: ' + e };
  }
})()
"""

    /** 作品页类型（`note` = 图文 / `video` = 视频）：其它形态（slides/弹层 id）都先按 `video` 打开 —— */
    /** 抖音会自己 302 到真正的作品页（图文/动图落到 `/note/<id>`），详情统一从 SSR 取数。 */
    private fun pathType(link: DetectedVideoLink): String =
        if (TYPE_REGEX.find(link.url)?.groupValues?.getOrNull(1) == "note") "note" else "video"

    /** 老的分享页地址（仅回退路线用；主路线直接打开 `www.douyin.com/{type}/{id}`） */
    private fun buildShareUrl(link: DetectedVideoLink): String =
        "https://www.iesdouyin.com/share/${pathType(link)}/${link.id}/"

    /**
     * 从 `window._ROUTER_DATA` 里取出作品对象。
     *
     * 字段名按参考实现的 msgspec 结构走：显式 `name=` 过的键是 camelCase（`videoInfoRes`、
     * `video_(id)/page`），其余字段直接用 Python 字段名（snake_case，如 `item_list`）。
     * 为容忍不同端点的 casing 差异，叶子键用 [optObjectAny]/[optArrayAny] 做候选匹配。
     *
     * @param expectedId 识别层给出的作品 id，只用于失败时把「服务端回显的 id」和它对照
     */
    private fun extractVideoData(routerJson: String, expectedId: String): JSONObject {
        val router = try {
            JSONObject(routerJson)
        } catch (e: Exception) {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "window._ROUTER_DATA 不是合法 JSON", e)
        }
        val loaderData = router.optJSONObject("loaderData")
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "window._ROUTER_DATA 里没有 loaderData")
        val page = loaderData.optJSONObject("video_(id)/page")
            ?: loaderData.optJSONObject("note_(id)/page")
            ?: throw VideoParseException(
                VideoParseErrorKind.UNKNOWN,
                "window._ROUTER_DATA 里既没有 video_(id)/page 也没有 note_(id)/page"
            )
        val videoInfoRes = page.optObjectAny("videoInfoRes", "video_info_res")
        val itemList = videoInfoRes?.optArrayAny("item_list", "itemList")
        // 参考实现是 choice(item_list) 随机取一条，这里固定取第一条（通常也只有一条）
        itemList?.optJSONObject(0)?.let { return it }
        // ★ 走到这里说明**服务端没给作品数据**：这种页面本身不报错（HTTP 200），只是没有 videoInfoRes。
        //   把实测到的事实带进报错里，让「哪一层缺了 / id 对不对 / 是否被判成爬虫」一眼可辨 ——
        //   字段名取自真实返回（不存在的 id 也会回显 itemId 与 isSpider）。
        val echoedId = page.optStringAny("itemId", "item_id")
        val detail = buildString {
            append("videoInfoRes=").append(if (videoInfoRes == null) "缺失" else "存在")
            append(", item_list=").append(if (itemList == null) "缺失" else "长度 ${itemList.length()}")
            append(", itemId=").append(echoedId.ifBlank { "缺失" })
            append(", 请求id=").append(expectedId)
            append(", isSpider=").append(page.optBoolean("isSpider"))
        }
        throw VideoParseException(
            VideoParseErrorKind.UNKNOWN,
            "抖音没有返回该作品的数据（作品可能已删除或不可见，也可能被风控拦截）[$detail]"
        )
    }

    // ------------------------------------------------------------------ 直链

    private suspend fun resolveVideo(video: JSONObject, mediaHeaders: Map<String, String>): ResolvedMedia? {
        val playAddr = video.optObjectAny("play_addr", "playAddr")
        extractPlayToken(playAddr)?.let { token ->
            // 优先走 play 端点：能同时拿到无水印直链与真实文件大小
            probePlay(token, mediaHeaders)?.let { return it }
        }
        // 回退：直接用 play_addr 直链，把 playwm 换成 play 去水印（参考实现的关键一步）
        val fallback = firstUrl(playAddr)?.replace("playwm", "play") ?: return null
        return ResolvedMedia(fallback, -1L, mediaHeaders)
    }

    /** 有 `uri` 用 `uri`，否则遍历 play_addr 直链从 `video_id` 查询参数里取（与参考实现一致） */
    private fun extractPlayToken(playAddr: JSONObject?): String? {
        val uri = playAddr?.optStringAny("uri").orEmpty()
        if (uri.isNotBlank()) return uri
        val urlList = playAddr?.optArrayAny("url_list", "urlList") ?: return null
        for (i in 0 until urlList.length()) {
            val videoId = VIDEO_ID_REGEX.find(urlList.optString(i))?.groupValues?.getOrNull(1)
            if (!videoId.isNullOrBlank()) return videoId
        }
        return null
    }

    /**
     * 逐个清晰度探测 play 端点，取**总文件体积最大**的一路。
     *
     * 各 ratio 指向同一视频的不同转码，体积大即更清晰；用 `Range: bytes=0-1` 只取 1 字节即可
     * 从 `Content-Range`/`Content-Length` 拿到总长。单路失败（超时/被拒）不影响其它清晰度。
     */
    private suspend fun probePlay(videoId: String, mediaHeaders: Map<String, String>): ResolvedMedia? {
        var best: ResolvedMedia? = null
        for (ratio in PLAY_RATIOS) {
            val url = "https://aweme.snssdk.com/aweme/v1/play/?video_id=$videoId&ratio=$ratio"
            val response = try {
                VideoHttp.request(url, headers = mediaHeaders + ("Range" to "bytes=0-1"))
            } catch (e: VideoParseException) {
                continue
            }
            if (response.status !in 200..299) continue
            val size = extractResponseSize(response.headers)
            if (size <= 0L) continue
            val candidate = ResolvedMedia(response.finalUrl, size, mediaHeaders)
            if (best == null || candidate.size > best.size) best = candidate
        }
        return best
    }

    private fun extractResponseSize(headers: Map<String, String>): Long {
        headerIgnoreCase(headers, "Content-Range")?.let { contentRange ->
            CONTENT_RANGE_TOTAL_REGEX.find(contentRange)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        }
        return headerIgnoreCase(headers, "Content-Length")?.trim()?.toLongOrNull() ?: 0L
    }

    private fun headerIgnoreCase(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** 媒体直链请求头：参考实现只带 iOS UA + Referer（不含 Cookie，直链是 CDN 静态文件） */
    private fun buildMediaHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to VideoHttp.UA_IPHONE,
        "Referer" to referer
    )

    // ------------------------------------------------------------------ ttwid

    private suspend fun ensureTtwid(): String {
        cachedTtwid?.let { return it }
        return registerTtwid().also { cachedTtwid = it }
    }

    /** 丢掉缓存里的 ttwid 并重新注册一枚（缓存那枚可能已被风控标记，见 [fetchWorkData]） */
    private suspend fun renewTtwid(): String {
        cachedTtwid = null
        return registerTtwid().also { cachedTtwid = it }
    }

    /**
     * 注册匿名 ttwid。
     *
     * ★ 这是本解析器**唯一**不走 [VideoHttp.request] 的请求：ttwid 藏在响应的多条 `Set-Cookie`
     *   里，而 VideoHttp 把响应头拍平成「同名互相覆盖」的 Map，取不全所有 Set-Cookie。
     *   因此这里直接用底层 OkHttp 客户端读 `headers.values("Set-Cookie")`。
     */
    private suspend fun registerTtwid(): String = withContext(Dispatchers.IO) {
        val client = HttpClients.apiClient()
        val body = """{"region":"cn","aid":1768,"needFid":false,"service":"www.iesdouyin.com","union":true,"fid":""}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(TTWID_REGISTER_URL)
            .header("User-Agent", VideoHttp.UA_IPHONE)
            .header("Referer", IESDOUYIN_REFERER)
            .post(body)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw VideoParseException(VideoParseErrorKind.NETWORK, "ttwid 注册请求失败", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw VideoParseException(VideoParseErrorKind.UNKNOWN, "ttwid 注册失败（HTTP ${resp.code}）")
            }
            val registered = resp.headers.values("Set-Cookie").firstNotNullOfOrNull { extractTtwid(it) }
            val redirectUrl = try {
                JSONObject(resp.body?.string().orEmpty()).optString("redirect_url")
            } catch (e: Exception) {
                ""
            }
            // 参考实现会再请求一次 redirect_url 补全 union 注册；这里尽力而为，失败不影响本次解析
            val fromCallback = if (redirectUrl.isBlank()) null else try {
                callbackTtwid(client, redirectUrl)
            } catch (e: IOException) {
                null
            }
            fromCallback ?: registered
                ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "ttwid 注册未返回可用 Cookie")
        }
    }

    /** 跟随 register 返回的 `redirect_url` 再收一次 Set-Cookie（阻塞调用，仅在 IO 线程里用） */
    private fun callbackTtwid(client: OkHttpClient, url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", VideoHttp.UA_IPHONE)
            .header("Referer", IESDOUYIN_REFERER)
            .get()
            .build()
        return client.newBuilder().followRedirects(false).build().newCall(request).execute().use { resp ->
            resp.headers.values("Set-Cookie").firstNotNullOfOrNull { extractTtwid(it) }
        }
    }

    private fun extractTtwid(setCookie: String): String? =
        Regex("""ttwid=([^;]+)""").find(setCookie)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ 工具

    private fun firstUrl(node: JSONObject?): String? {
        val urlList = node?.optArrayAny("url_list", "urlList") ?: return null
        for (i in 0 until urlList.length()) {
            val url = urlList.optString(i)
            if (url.isNotBlank()) return url
        }
        return null
    }

    private fun videoFileName(id: String, title: String): String = "${safeTitle(title, "douyin_$id")}.mp4"

    private fun imageFileName(id: String, title: String, index: Int): String =
        "${safeTitle(title, "douyin_$id")}_$index.jpg"

    /** 清洗标题里的非法文件名字符并截断（清洗交给共享的 [DownloadPathPolicy]） */
    private fun safeTitle(title: String, fallback: String): String =
        DownloadPathPolicy.sanitizeName(title).take(80).ifBlank { fallback }
}

// ---------------------------------------------------------------------- JSON 取值
// 抖音分享页同一字段在不同端点上 casing 不一致（snake_case / camelCase 混用），
// 这里统一按「候选键」取值，避免因改版把整条解析打挂。

private fun JSONObject.optStringAny(vararg keys: String): String {
    for (key in keys) {
        if (isNull(key)) continue
        val value = optString(key, "")
        if (value.isNotBlank()) return value
    }
    return ""
}

private fun JSONObject.optLongAny(vararg keys: String): Long {
    for (key in keys) {
        if (!isNull(key)) return optLong(key, 0L)
    }
    return 0L
}

private fun JSONObject.optObjectAny(vararg keys: String): JSONObject? {
    for (key in keys) {
        if (isNull(key)) continue
        optJSONObject(key)?.let { return it }
    }
    return null
}

private fun JSONObject.optArrayAny(vararg keys: String): JSONArray? {
    for (key in keys) {
        if (isNull(key)) continue
        optJSONArray(key)?.let { return it }
    }
    return null
}
