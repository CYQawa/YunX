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

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.yunx.app.ui.components.OperationSheet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.ui.components.rememberOperationSheetState
import com.yunx.app.ui.items.MultiSelectAction
import com.yunx.app.ui.items.MultiSelectBar
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class ExpressiveInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun wholeSettingsRowExposesSwitchRoleAndToggleState() {
        val checked = mutableStateOf(false)
        rule.setContent {
            ComposeEmptyActivityTheme {
                SettingsItem(
                    icon = Icons.Outlined.Download,
                    title = "开关测试",
                    description = "点击整行切换",
                    onClick = { checked.value = !checked.value },
                    checked = checked.value,
                    trailing = { Switch(checked.value, onCheckedChange = null) }
                )
            }
        }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff().performClick().assertIsOn()
    }

    @Test fun colorChoiceIsNamedAndSelectedAtLargeFontScale() {
        rule.setContent {
            ComposeEmptyActivityTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    ColorSelectionItem(0xFFECCB75, "浅金", true, {})
                }
            }
        }
        rule.onNodeWithContentDescription("浅金").assertIsSelected().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    @Test fun colorPickerCanBeAdjustedThroughAccessibilityActions() {
        var selected: Long? = null
        rule.setContent {
            ComposeEmptyActivityTheme {
                ColorPickerDialog(0xFF000000, {}, { selected = it })
            }
        }
        rule.onNodeWithContentDescription("十六进制颜色").assertExists()
        rule.onNodeWithContentDescription("色相").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        rule.onNodeWithContentDescription("饱和度").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.onNodeWithContentDescription("明度").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.onNodeWithText("应用").performScrollTo().performClick()
        assertEquals(0xFF00FF00L, selected)
    }

    @Test fun toolbarDisablesEmptySelectionAndBusyActions() {
        val count = mutableStateOf(0)
        val busy = mutableStateOf(false)
        var clicks = 0
        rule.setContent {
            ComposeEmptyActivityTheme {
                MultiSelectBar(count.value, listOf(MultiSelectAction("下载", Icons.Outlined.Download, Color.Blue) { clicks++ }), busy.value)
            }
        }
        rule.onNodeWithContentDescription("下载").assertIsNotEnabled()
        rule.runOnIdle { count.value = 1 }
        rule.onNodeWithContentDescription("下载").assertIsEnabled().performClick()
        rule.runOnIdle { busy.value = true }
        rule.onNodeWithContentDescription("下载").assertIsNotEnabled()
        assertEquals(1, clicks)
    }

    @Test fun sheetVetoUsesCurrentBusyStateBeforeHiding() {
        val busy = mutableStateOf(true)
        var state: androidx.compose.material3.SheetState? = null
        var scope: CoroutineScope? = null
        rule.setContent {
            ComposeEmptyActivityTheme {
                val sheet = rememberOperationSheetState(busy.value)
                state = sheet
                scope = rememberCoroutineScope()
                OperationSheet(busy.value, {}, sheet) { Column {} }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { scope!!.launch { state!!.hide() } }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(state!!.isVisible); busy.value = false }
        rule.waitForIdle()
        rule.runOnIdle { scope!!.launch { state!!.hide() } }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(SheetValue.Hidden, state!!.currentValue) }
    }

    @Test fun renameFailureKeepsInputAndAllowsRetry() {
        val busy = mutableStateOf(false)
        var submissions = 0
        var dismissals = 0
        rule.setContent {
            ComposeEmptyActivityTheme {
                FileActionSheet(
                    file = ShareFile(fid = "测试", fname = "原名.txt", fsize = 1, isdir = false, pdirFid = "0", fidToken = "测试"),
                    operating = busy.value,
                    onDownload = {}, onDownloadFolder = {}, onShare = { _, _, _ -> },
                    onRename = { submissions++; busy.value = true },
                    onConfirmDelete = {}, onDismiss = { dismissals++ },
                    moveStep = { _, _ -> Column {} }
                )
            }
        }
        rule.onNodeWithText("重命名").performScrollTo().performClick()
        rule.onNodeWithText("新文件名").performTextReplacement("保留的新名.txt")
        rule.onNodeWithText("确认重命名").performScrollTo().performClick()
        rule.onNodeWithContentDescription("返回").assertIsNotEnabled()
        rule.runOnIdle { busy.value = false }
        rule.onNodeWithText("保留的新名.txt").assertExists()
        rule.onNodeWithText("确认重命名").performClick()
        assertEquals(2, submissions)
        assertEquals(0, dismissals)
    }
}
