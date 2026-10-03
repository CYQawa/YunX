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

package com.yunx.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #90：系统文件夹选择器缺失时，点击「下载保存目录」应提示恢复，且不写入目录。
 * 注册表在 onLaunch 抛出 [ActivityNotFoundException]，走设置页自己的 catch；没有 catch 时点击会失败。
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class SettingsDirectoryPickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingPickerShowsRecoveryAndKeepsDefaultDirectory() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(context)
        val previousDir = settings.downloadDirUri
        settings.downloadDirUri = null
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val backupManager = AuthBackupManager(
                database.rawQuarkAccountDao(),
                database.rawUcAccountDao(),
                database.rawXunleiAccountDao(),
                database.rawBaiduAccountDao(),
                database.rawC139AccountDao(),
                database.rawPan123AccountDao(),
                database.rawPan115AccountDao()
            )
            val owner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: ActivityOptionsCompat?
                    ) {
                        throw ActivityNotFoundException()
                    }
                }
            }
            composeRule.setContent {
                ComposeEmptyActivityTheme {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        Box(Modifier.fillMaxSize()) {
                            SettingsScreen(
                                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                                onThemeClick = {},
                                onAboutClick = {},
                                onSupportClick = {},
                                backupManager = backupManager,
                                onCheckUpdate = {},
                                onPreviewUpdateSheet = {}
                            )
                            GlobalSnackbarHost()
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            // Snackbar 几秒后自行消失；停住时钟，只推进到提示出现
            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithText("下载保存目录").performScrollTo().performClick()
            composeRule.mainClock.advanceTimeBy(800)
            composeRule.onNodeWithText("无法打开文件夹选择器，请恢复或启用系统文件选择器后重试")
                .assertIsDisplayed()
            composeRule.onNodeWithText("下载保存目录").assertIsDisplayed()
            composeRule.onNodeWithText("系统默认 Download（点击自定义）").assertIsDisplayed()
            assertNull(settings.downloadDirUri)
        } finally {
            settings.downloadDirUri = previousDir
            database.close()
            composeRule.mainClock.autoAdvance = true
        }
    }
}
