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

package com.yunx.app.data.repository

import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.db.MultiAccountEntity
import com.yunx.app.data.download.DownloadPlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 平台账号目录：把 10 张账号表统一成「列表 / 设主账号 / 改备注 / 退出」四件事。
 *
 * ★ 为什么集中在这里而不是给 10 个 `*AccountRepository` 各加一套：账号管理 UI 与多账号
 *   下载轮询只关心公共字段（见 [MultiAccountEntity]），各平台的凭证差异（cookie / token /
 *   设备号…）不该渗进这些逻辑；集中后加新平台只需在下面几个 when 里各加一条分支。
 *
 * 真正的凭证读写仍然走各平台的 Repository（那里才有加密、会话刷新等平台语义），
 * 这里只做**账号维度**的增删改查。
 */
class AccountDirectory(private val db: AppDatabase) {

    /** 该平台全部已登录账号（主账号在前） */
    fun observe(platform: String): Flow<List<MultiAccountEntity>> = when (platform) {
        DownloadPlatform.QUARK -> db.quarkAccountDao().observeAccounts()
        DownloadPlatform.UC -> db.ucAccountDao().observeAccounts()
        DownloadPlatform.XUNLEI -> db.xunleiAccountDao().observeAccounts()
        DownloadPlatform.BAIDU -> db.baiduAccountDao().observeAccounts()
        DownloadPlatform.C139 -> db.c139AccountDao().observeAccounts()
        DownloadPlatform.PAN123 -> db.pan123AccountDao().observeAccounts()
        DownloadPlatform.PAN115 -> db.pan115AccountDao().observeAccounts()
        DownloadPlatform.GUANGYA -> db.guangyaAccountDao().observeAccounts()
        DownloadPlatform.ILANZOU -> db.ilanzouAccountDao().observeAccounts()
        DownloadPlatform.LANZOU -> db.lanzouAccountDao().observeAccounts()
        else -> flowOf(emptyList())
    }

    /** 该平台全部已登录账号（主账号在前），一次性读取 */
    suspend fun list(platform: String): List<MultiAccountEntity> = when (platform) {
        DownloadPlatform.QUARK -> db.quarkAccountDao().getAccounts()
        DownloadPlatform.UC -> db.ucAccountDao().getAccounts()
        DownloadPlatform.XUNLEI -> db.xunleiAccountDao().getAccounts()
        DownloadPlatform.BAIDU -> db.baiduAccountDao().getAccounts()
        DownloadPlatform.C139 -> db.c139AccountDao().getAccounts()
        DownloadPlatform.PAN123 -> db.pan123AccountDao().getAccounts()
        DownloadPlatform.PAN115 -> db.pan115AccountDao().getAccounts()
        DownloadPlatform.GUANGYA -> db.guangyaAccountDao().getAccounts()
        DownloadPlatform.ILANZOU -> db.ilanzouAccountDao().getAccounts()
        DownloadPlatform.LANZOU -> db.lanzouAccountDao().getAccounts()
        else -> emptyList()
    }

    /** 设为主账号（列目录 / 转存 / 上传等需要单一身份的操作改用它） */
    suspend fun setPrimary(platform: String, id: String) {
        writeOps(platform)?.setPrimary?.invoke(id)
    }

    /** 改账号备注（空串 = 界面回退显示昵称） */
    suspend fun setRemark(platform: String, id: String, remark: String) {
        writeOps(platform)?.setRemark?.invoke(id, remark)
    }

    /**
     * 退出某个账号：只删这一行，其余账号不受影响。
     *
     * ★ 删掉的若是主账号，把同平台「最近更新」的那一个补成主账号：否则会留下
     *   「还有账号、但一条都不是主账号」的状态 —— 界面上的主账号徽标消失，
     *   取链时 `getAccount()` 只能靠 `updatedAt` 兜底，行为变得不可预期。
     */
    suspend fun remove(platform: String, id: String) {
        val ops = writeOps(platform) ?: return
        ops.delete(id)
        val rest = list(platform)
        if (rest.isNotEmpty() && rest.none { it.isPrimary }) {
            ops.setPrimary(rest.first().id)
        }
    }

    /**
     * 10 个平台账号 DAO 的写操作方法名完全一致，这里统一取出来，
     * 免得 setPrimary / setRemark / remove 各写一遍 10 分支的 when。
     */
    private fun writeOps(platform: String): WriteOps? = when (platform) {
        DownloadPlatform.QUARK -> db.quarkAccountDao().let { d ->
            WriteOps({ d.setPrimary(it) }, { id, r -> d.updateRemark(id, r) }, { d.deleteById(it) })
        }

        DownloadPlatform.UC -> db.ucAccountDao().let { d ->
            WriteOps({ d.setPrimary(it) }, { id, r -> d.updateRemark(id, r) }, { d.deleteById(it) })
        }

        DownloadPlatform.XUNLEI -> db.xunleiAccountDao().let { d ->
            WriteOps({ d.setPrimary(it) }, { id, r -> d.updateRemark(id, r) }, { d.deleteById(it) })
        }

        DownloadPlatform.BAIDU -> db.baiduAccountDao().let { d ->
            WriteOps({ d.setPrimary(it) }, { id, r -> d.updateRemark(id, r) }, { d.deleteById(it) })
        }

        DownloadPlatform.C139 -> db.c139AccountDao().let { d ->
            WriteOps({ d.setPrimary(it) }, { id, r -> d.updateRemark(id, r) }, { d.deleteById(it) })
        }

        DownloadPlatform.PAN123 -> db.pan123AccountDao().let { d ->
            WriteOps({ d.setPrimary(it) }, { id, r -> d.updateRemark(id, r) }, { d.deleteById(it) })
        }

        DownloadPlatform.PAN115 -> db.pan115AccountDao().let { d ->
            WriteOps({ d.setPrimary(it) }, { id, r -> d.updateRemark(id, r) }, { d.deleteById(it) })
        }

        DownloadPlatform.GUANGYA -> db.guangyaAccountDao().let { d ->
            WriteOps({ d.setPrimary(it) }, { id, r -> d.updateRemark(id, r) }, { d.deleteById(it) })
        }

        DownloadPlatform.ILANZOU -> db.ilanzouAccountDao().let { d ->
            WriteOps({ d.setPrimary(it) }, { id, r -> d.updateRemark(id, r) }, { d.deleteById(it) })
        }

        DownloadPlatform.LANZOU -> db.lanzouAccountDao().let { d ->
            WriteOps({ d.setPrimary(it) }, { id, r -> d.updateRemark(id, r) }, { d.deleteById(it) })
        }

        else -> null
    }

    /** 一个平台账号表的三个写操作，见 [writeOps] */
    private class WriteOps(
        val setPrimary: suspend (String) -> Unit,
        val setRemark: suspend (String, String) -> Unit,
        val delete: suspend (String) -> Unit
    )
}
