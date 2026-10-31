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

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 139 云盘口令。
 *
 * [codeType] 与官方协议一致：普通个人云盘口令为 1，纯中文口令为 2。
 */
data class C139Kouling(
    val code: String,
    val codeType: Int,
    val password: String = ""
)

/** 口令查询到的分享信息（用于第二段链接查询与错误兜底）。 */
data class C139KoulingInfo(
    val title: String = "",
    val password: String = ""
)

/** 口令最终换出的真实分享链接。 */
data class C139ResolvedKouling(
    val shareUrl: String,
    val linkId: String,
    val title: String,
    val password: String
)

/**
 * 139 云盘口令的纯文本识别。
 *
 * 只认以斜杠星号开头、以冒号斜杠收尾的口令片段；不拿整段文案去猜链接，避免把
 * 普通文本里的短数字误判成提取码。形如“提取码：abcd”的显式文字仍会一并提取。
 */
object C139KoulingParser {

    // 只捕获口令本体，前后由复制产生的标题、说明、表情或标点都会被 find 自动跳过。
    private val commandRegex = Regex("""/\*([\p{L}\p{N}_-]{1,64}):/""")
    private val explicitPasswordRegex =
        Regex("""(?:提取码|访问码|密码)\s*[：:]\s*([A-Za-z0-9]{4,8})""", RegexOption.IGNORE_CASE)

    fun parse(text: String): C139Kouling? {
        val code = commandRegex.find(text)?.groupValues?.getOrNull(1) ?: return null
        val password = explicitPasswordRegex.find(text)?.groupValues?.getOrNull(1).orEmpty()
        val codeType = if (code.all { it in '\u4e00'..'\u9fff' }) 2 else 1
        return C139Kouling(code = code, codeType = codeType, password = password)
    }
}

/**
 * 第一段查询：口令 + 用户提供的提取码。
 *
 * 带密码查询时必须同时把 `isPasswd` 置为 1；否则服务端不会使用 `passwd`，
 * 对设有提取码的口令会返回 9188。
 */
internal fun buildC139KoulingInfoRequest(
    command: C139Kouling,
    account: String,
    password: String
): String = """
    <getOutLinkInfoV6>
      <getOutLinkInfoReq>
        <account>${xmlText(account)}</account>
        <bNum>1</bNum>
        <caSrt>0</caSrt>
        <coSrt>0</coSrt>
        <eNum>100</eNum>
        <isPasswd>${if (password.isBlank()) 0 else 1}</isPasswd>
        <passwd>${xmlCdata(password)}</passwd>
        <linkCode>${xmlCdata(command.code)}</linkCode>
        <linkCodeType>${command.codeType}</linkCodeType>
        <pCaID>${xmlCdata("root")}</pCaID>
        <srtDr>1</srtDr>
      </getOutLinkInfoReq>
    </getOutLinkInfoV6>
""".trimIndent()

/** 第二段查询：口令 + 最终提取码 → 正式分享链接。 */
internal fun buildC139KoulingGeneralRequest(
    command: C139Kouling,
    account: String,
    password: String
): String = """
    <getOutLinkGeneralV4>
      <getOutLinkGeneralReq>
        <account>${xmlText(account)}</account>
        <linkID></linkID>
        <passwd>${xmlCdata(password)}</passwd>
        <pCaID>${xmlCdata("root")}</pCaID>
        <caSrt>0</caSrt>
        <coSrt>0</coSrt>
        <srtDr>1</srtDr>
        <bNum>1</bNum>
        <eNum>100</eNum>
        <isLinkCode>0</isLinkCode>
        <linkCode>${xmlCdata(command.code)}</linkCode>
        <linkCodeType>${command.codeType}</linkCodeType>
      </getOutLinkGeneralReq>
    </getOutLinkGeneralV4>
""".trimIndent()

private fun xmlText(value: String): String =
    value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

private fun xmlCdata(value: String): String =
    "<![CDATA[" + value.replace("]]>", "]]]]><![CDATA[>") + "]]>"

/** 解析第一段查询响应；调用方已确认口令格式，错误会把服务端描述带出去。 */
internal fun parseC139KoulingInfo(xml: String): C139KoulingInfo {
    val root = parseC139Xml(xml)
    checkC139Result(root)
    val info = root.firstElement("getOutLinkInfoRes")
        ?: throw IllegalStateException("139 云盘口令解析失败：响应缺少分享信息")
    return C139KoulingInfo(
        title = info.childText("lkName"),
        password = info.childText("password")
    )
}

/** 解析第二段查询响应，提取真实分享链接与 linkID。 */
internal fun parseC139KoulingGeneral(xml: String): C139ResolvedKouling {
    val root = parseC139Xml(xml)
    checkC139Result(root)
    val outLink = root.firstElement("outLink")
        ?: throw IllegalStateException("139 云盘口令解析失败：响应缺少分享链接")
    val shareUrl = outLink.childText("url")
    if (shareUrl.isBlank()) {
        throw IllegalStateException("139 云盘口令解析失败：未返回分享链接")
    }
    return C139ResolvedKouling(
        shareUrl = shareUrl,
        linkId = outLink.childText("linkID"),
        title = outLink.childText("lkName"),
        password = ""
    )
}

private fun parseC139Xml(xml: String): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        // 响应来自固定 HTTPS 端点，仍然关闭外部实体，避免把解析器当成 XXE 入口。
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    return runCatching {
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }.getOrElse {
        throw IllegalStateException("139 云盘口令解析失败：服务端响应格式异常", it)
    }
}

private fun checkC139Result(root: Element) {
    val code = root.getAttribute("resultCode")
    if (code.isBlank() || code == "0") return
    val desc = root.getAttribute("desc").trim()
    throw IllegalStateException(
        if (desc.isBlank()) "139 云盘口令解析失败（$code）"
        else "139 云盘口令解析失败：$desc（$code）"
    )
}

private fun Element.firstElement(tagName: String): Element? =
    getElementsByTagName(tagName).item(0) as? Element

private fun Element.childText(tagName: String): String {
    val children = childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child is Element && child.tagName == tagName) {
            return child.textContent?.trim().orEmpty()
        }
    }
    return ""
}
