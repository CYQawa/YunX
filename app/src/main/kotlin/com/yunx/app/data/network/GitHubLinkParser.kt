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

/**
 * GitHub 链接解析结果。
 * - [Repository]：仓库链接（含 tree / blob / releases 等子路径），进入仓库浏览；
 * - [Account]：用户 / 组织主页链接，进入其仓库列表；
 * - [DirectFile]：文件直链，直接触发下载。
 */
sealed class GitHubLinkType {
    /** 仓库链接；subPath 为 tree/blob/releases 后的路径部分（可空） */
    data class Repository(val owner: String, val repo: String, val subPath: String?) : GitHubLinkType()

    /** 用户 / 组织主页链接 */
    data class Account(val owner: String) : GitHubLinkType()

    /** 文件直链（raw / releases download / github raw），直接下载 */
    data class DirectFile(val url: String, val fileName: String) : GitHubLinkType()
}

/**
 * 从一段文本中识别 GitHub 链接。
 * 匹配优先级：DirectFile > Repository > Account，都不匹配返回 null。
 */
object GitHubLinkParser {

    private val urlRegex = Regex("""https?://[^\s]+""")

    // raw.githubusercontent.com/{owner}/{repo}/{branch}/{path...}
    private val rawRegex = Regex(
        """raw\.githubusercontent\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/[^/]+/(.+)""",
        RegexOption.IGNORE_CASE
    )

    // github.com/{owner}/{repo}/releases/download/{tag}/{file...}
    private val releaseDownloadRegex = Regex(
        """github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/releases/download/[^/]+/(.+)""",
        RegexOption.IGNORE_CASE
    )

    // github.com/{owner}/{repo}/raw/{branch}/{path...}（会 302 到 raw.githubusercontent.com）
    private val githubRawRegex = Regex(
        """github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/raw/[^/]+/(.+)""",
        RegexOption.IGNORE_CASE
    )

    // github.com/{owner}/{repo}（及其后续子路径 tree/blob/releases 等）
    private val repoRegex = Regex(
        """github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/(.*))?""",
        RegexOption.IGNORE_CASE
    )

    // github.com/{owner}（仅一段路径）
    private val accountRegex = Regex(
        """github\.com/([A-Za-z0-9_.-]+)/?(?:[?#]|$)""",
        RegexOption.IGNORE_CASE
    )

    // github.com 本身的功能/营销页路径，不应识别为账号
    private val specialPaths = setOf(
        "features", "topics", "trending", "collections", "events", "sponsors",
        "about", "pricing", "login", "signup", "join", "marketplace", "explore",
        "notifications", "settings", "new", "orgs", "sites", "customer-stories",
        "team", "enterprise", "readme", "contact", "security", "resources"
    )

    fun parse(text: String): GitHubLinkType? {
        val url = urlRegex.find(text.trim())?.value
            ?.trimEnd('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'')
            ?: return null

        // 1) 文件直链（优先级最高）
        rawRegex.find(url)?.let { m ->
            fileNameOf(m.groupValues[3])?.let { name ->
                return GitHubLinkType.DirectFile(url, name)
            }
        }
        releaseDownloadRegex.find(url)?.let { m ->
            fileNameOf(m.groupValues[3])?.let { name ->
                return GitHubLinkType.DirectFile(url, name)
            }
        }
        githubRawRegex.find(url)?.let { m ->
            fileNameOf(m.groupValues[3])?.let { name ->
                return GitHubLinkType.DirectFile(url, name)
            }
        }

        // 2) 仓库链接
        repoRegex.find(url)?.let { m ->
            val owner = m.groupValues[1]
            // owner 是 GitHub 功能页（features/topics/trending 等）时，github.com/{owner}/{repo}
            // 实际是功能页带子路径，既非仓库也非用户/组织，直接丢弃，避免后续 getRepo 无谓报错。
            if (owner.lowercase() in specialPaths) return null
            val repo = m.groupValues[2]
            val subPath = m.groupValues[3]
                .substringBefore('?').substringBefore('#')
                .takeIf { it.isNotBlank() }
            return GitHubLinkType.Repository(owner, repo, subPath)
        }

        // 3) 账号 / 组织链接
        accountRegex.find(url)?.let { m ->
            val owner = m.groupValues[1]
            if (owner.lowercase() in specialPaths) return null
            return GitHubLinkType.Account(owner)
        }

        return null
    }

    /** 从路径段取最后一段作为文件名，去掉 query/fragment；空则返回 null */
    private fun fileNameOf(path: String): String? {
        val clean = path.substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/')
        return name.takeIf { it.isNotBlank() }
    }
}
