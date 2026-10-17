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

package com.yunx.app.data.db

import com.yunx.app.data.download.DownloadPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多账号「一次登录落到哪一行」的规则（[resolveAccountSlot]）。
 * 纯函数，不碰 Room / Android，可在 JVM 单测里直接跑。
 */
class AccountSlotTest {

    /** 只为验证公共字段：真实实体字段更多，但规则只关心这几个 */
    private data class FakeAccount(
        override val id: String,
        override val nickname: String = "n",
        override val remark: String = "",
        override val isPrimary: Boolean = false,
        override val updatedAt: Long = 0L
    ) : MultiAccountEntity

    @Test
    fun firstLoginUsesPlatformKeyAndBecomesPrimary() {
        val slot = resolveAccountSlot(DownloadPlatform.QUARK, existing = null, newAccount = false)
        // 首个账号沿用平台常量主键：与老数据、与 MIGRATION_19_20 把老行标成主账号的做法一致
        assertEquals(DownloadPlatform.QUARK, slot.id)
        assertTrue(slot.isPrimary)
        assertEquals("", slot.remark)
    }

    @Test
    fun addingSecondAccountCreatesNewRowAndIsNotPrimary() {
        val existing = FakeAccount(id = "quark", isPrimary = true, remark = "工作号")
        val slot = resolveAccountSlot(DownloadPlatform.QUARK, existing, newAccount = true)
        assertTrue(slot.id.startsWith("${DownloadPlatform.QUARK}-"))
        assertFalse(slot.isPrimary)
        assertEquals("", slot.remark)
    }

    @Test
    fun reloginOverwritesPrimaryRowAndKeepsItsRemark() {
        val existing = FakeAccount(id = "quark-abc", isPrimary = true, remark = "工作号")
        val slot = resolveAccountSlot(DownloadPlatform.QUARK, existing, newAccount = false)
        // 日常重登（含会话过期后重登）必须覆盖同一行，并继承主账号标记与备注：
        // 否则每重登一次就多堆一个失效账号出来
        assertEquals("quark-abc", slot.id)
        assertTrue(slot.isPrimary)
        assertEquals("工作号", slot.remark)
    }
}
