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
 * GitHub 数据模型。
 *
 * 说明：账号/组织仓库列表 API（/users/{owner}/repos、/orgs/{owner}/repos）返回的条目
 * 结构与单仓库 API（/repos/{owner}/{repo}）基本一致（列表项也可能携带 parent 字段，
 * 也可能不携带），因此直接复用 [GitHubRepo]，[GitHubRepo.parentFullName] 允许为 null。
 * 即 [GitHubRepo] 同时承担「单仓库」与「账号仓库列表项」两种角色。
 */

/** GitHub 仓库信息（也作为账号/组织仓库列表项使用） */
data class GitHubRepo(
    val name: String,
    val fullName: String,
    val description: String?,
    val fork: Boolean,
    /** 仅 fork 仓库有值（从 API 的 parent.full_name 取）；非 fork 或列表项无 parent 时为 null */
    val parentFullName: String?,
    /** 默认分支名（如 main / master） */
    val defaultBranch: String,
    /** 主语言（API language 字段，可能为 null） */
    val language: String? = null,
    /** 仓库元数据更新时间（API updated_at，ISO8601，可能为 null） */
    val updatedAt: String? = null,
    /** 最近一次 push 时间（API pushed_at，ISO8601；用于 ZIP 条目时间，可能为 null） */
    val pushedAt: String? = null
) {
    /** 仓库属主（从 fullName = "owner/repo" 拆分）；异常 fullName 兜底为整串，避免返回空串导致后续 URL 拼错 */
    val owner: String get() {
        val idx = fullName.indexOf('/')
        return if (idx > 0) fullName.substring(0, idx) else fullName
    }
}

/** Git Tree 条目（目录或文件） */
data class GitHubTreeEntry(
    val path: String,
    /** "tree" = 文件夹，"blob" = 文件 */
    val type: String,
    val sha: String,
    /** 文件大小（字节）；文件夹条目可能为 null */
    val size: Long?,
    /** 该条目最后提交时间（tree 响应不含此字段，由 ViewModel 调 getLastCommitDate 填充；可能为 null） */
    val updatedAt: String? = null
)

/** GitHub Release 版本 */
data class GitHubRelease(
    val tagName: String,
    val name: String?,
    /** 发布时间（API published_at，ISO8601 字符串） */
    val publishedAt: String?,
    val assets: List<GitHubAsset>,
    /** 是否预发布（prerelease=true） */
    val prerelease: Boolean = false,
    /** 是否草稿（draft=true，不可公开下载） */
    val draft: Boolean = false
)

/** GitHub Release 资产（可下载文件） */
data class GitHubAsset(
    val name: String,
    /** browser_download_url */
    val downloadUrl: String,
    val size: Long,
    val contentType: String?,
    /** 资产更新时间（API updated_at，ISO8601，可能为 null） */
    val updatedAt: String? = null
)
