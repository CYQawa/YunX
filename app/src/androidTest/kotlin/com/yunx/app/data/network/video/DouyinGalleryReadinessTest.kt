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

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/** 首张作品图挂载后仍须等待完整详情，SSR 与两种钩子图集都保持图片和动图的原序。 */
@RunWith(AndroidJUnit4::class)
class DouyinGalleryReadinessTest {

    @Test
    fun waitsForGalleryDetailBeforeExtractingAllImages() = runBlocking {
        withContext(Dispatchers.Main) {
            val webView = WebView(ApplicationProvider.getApplicationContext())
            try {
                webView.settings.javaScriptEnabled = true
                webView.settings.blockNetworkLoads = true
                withTimeout(5_000L) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                        webView.loadDataWithBaseURL(
                            "https://www.douyin.com/",
                            """<img src="https://p.douyinpic.com/1?biz_tag=aweme_images">
                                <script id="RENDER_DATA" type="application/json"></script>""",
                            "text/html", "UTF-8", null
                        )
                    }
                }
                for (source in listOf("ssr", "hook", "image_post_info")) {
                    webView.evaluate("""
                        window.__yunxAwemeDetail = '';
                        document.querySelector('#RENDER_DATA').textContent = '';
                    """.trimIndent())
                    assertEquals("false", webView.evaluate(DouyinParser.readyCheckJs()))
                    val initial = JSONObject(webView.evaluate(DouyinParser.EXTRACT_JS))
                    assertEquals("dom", initial.getString("src"))
                    assertEquals(1, initial.getJSONArray("images").length())

                    // 用生产字段形态注入三图详情，中间一张没有动图，防止过滤空值后下标错位。
                    webView.evaluate("""
                        (function () {
                          var images = [1, 2, 3].map(function (i) {
                            return {
                              url_list: ['https://p.douyinpic.com/' + i + '?biz_tag=aweme_images'],
                              video: i === 2 ? null : (${JSONObject.quote(source)} === 'ssr'
                                ? {playAddr: [{src: 'https://example.com/motion' + i + '.mp4'}]}
                                : {play_addr: {url_list: ['https://example.com/motion' + i + '.mp4']}})
                            };
                          });
                          if (${JSONObject.quote(source)} === 'ssr') {
                            document.querySelector('#RENDER_DATA').textContent =
                              encodeURIComponent(JSON.stringify({app: {videoDetail: {images: images}}}));
                          } else {
                            window.__yunxAwemeDetail = JSON.stringify({aweme_detail:
                              ${if (source == "image_post_info") "{image_post_info: {images: images}}" else "{images: images}"}});
                          }
                        })()
                    """.trimIndent())
                    assertEquals("true", webView.evaluate(DouyinParser.readyCheckJs()))
                    val result = JSONObject(webView.evaluate(DouyinParser.EXTRACT_JS))
                    assertEquals(if (source == "ssr") "ssr" else "hook", result.getString("src"))
                    assertEquals(3, result.getJSONArray("images").length())
                    assertEquals(3, result.getJSONArray("motions").length())
                    for (index in 0..2) {
                        assertEquals(
                            "https://p.douyinpic.com/${index + 1}?biz_tag=aweme_images",
                            result.getJSONArray("images").getString(index)
                        )
                        assertEquals(
                            if (index == 1) "" else "https://example.com/motion${index + 1}.mp4",
                            result.getJSONArray("motions").getString(index)
                        )
                    }
                }
            } finally {
                webView.destroy()
            }
        }
    }

    private suspend fun WebView.evaluate(script: String): String = withTimeout(3_000L) {
        suspendCancellableCoroutine { cont ->
            evaluateJavascript(script) { raw ->
                if (cont.isActive) {
                    val value = JSONTokener(raw).nextValue()
                    cont.resume(if (value is String) value else raw)
                }
            }
        }
    }
}
