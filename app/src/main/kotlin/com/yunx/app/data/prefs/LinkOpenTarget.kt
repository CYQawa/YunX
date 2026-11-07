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

package com.yunx.app.data.prefs

import androidx.annotation.StringRes
import com.yunx.app.R

/**
 * 深链接（网盘分享链接一键唤起）的注册项。
 *
 * ★ [alias] 必须与 `AndroidManifest.xml` 里 `com.yunx.app.link.*` 的 activity-alias **逐字一致**：
 *   清单里的 intent-filter 改不了，用户在设置里改「哪些链接由云析接管」只能靠启停这些组件
 *   （见 [com.yunx.app.util.LinkOpener]），类名对不上就会静默失效。
 *
 * 默认全部启用；[prefKey] 里存的是用户意愿（true/false）。
 */
enum class LinkOpenTarget(
    /** activity-alias 的完整类名（清单里的注册项） */
    val alias: String,
    /** 设置页显示的名字 */
    @StringRes val labelRes: Int,
    /** 设置页显示的链接样例 */
    val sample: String,
    /** 需要本地化的样例说明（优先于 [sample]）；0 表示无 */
    @StringRes val sampleRes: Int = 0
) {
    QUARK("com.yunx.app.link.QuarkLink", R.string.vm_link_target_quark, "pan.quark.cn/s/…"),
    UC("com.yunx.app.link.UcLink", R.string.vm_link_target_uc, "drive.uc.cn/s/…"),
    XUNLEI("com.yunx.app.link.XunleiLink", R.string.vm_link_target_xunlei, "pan.xunlei.com/s/…"),
    BAIDU("com.yunx.app.link.BaiduLink", R.string.vm_link_target_baidu, "pan.baidu.com/s/…"),
    C139("com.yunx.app.link.C139Link", R.string.vm_link_target_c139, "yun.139.com/shareweb/…"),
    PAN123("com.yunx.app.link.Pan123Link", R.string.vm_link_target_pan123, "www.123pan.com/s/…"),
    PAN115("com.yunx.app.link.Pan115Link", R.string.vm_link_target_pan115, "115.com/s/…"),
    GUANGYA("com.yunx.app.link.GuangyaLink", R.string.vm_link_target_guangya, "www.guangyapan.com/s/…"),
    LANZOU("com.yunx.app.link.LanzouLink", R.string.vm_link_target_lanzou, "lanzou*.com/…"),
    MAGNET("com.yunx.app.link.MagnetLink", R.string.vm_link_target_magnet, "magnet:?xt=…"),
    YUNX("com.yunx.app.link.YunxLink", R.string.vm_link_target_yunx, "yunx://resolve?url=…"),
    SHARE("com.yunx.app.link.ShareLink", R.string.vm_link_target_share, "", R.string.vm_link_target_share_sample),
    PROCESS_TEXT("com.yunx.app.link.ProcessTextLink", R.string.vm_link_target_process_text, "", R.string.vm_link_target_process_text_sample);

    /** SharedPreferences 键（默认开） */
    val prefKey: String
        get() = "link_open_${name.lowercase()}"
}
