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

package com.yunx.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.yunx.app.ui.theme.readableSeedForeground
import com.yunx.app.ui.theme.useNavigationRail
import org.junit.Assert.*
import org.junit.Test

class ExpressiveLayoutTest {
    @Test fun navigationUsesAvailableWindowRatherThanDeviceOrientation() {
        assertFalse(useNavigationRail(320, 800))
        assertFalse(useNavigationRail(599, 900))
        assertTrue(useNavigationRail(600, 900))
        assertTrue(useNavigationRail(840, 1200))
        assertTrue(useNavigationRail(560, 320))
        assertFalse(useNavigationRail(400, 600))
    }

    @Test fun seedIconsKeepReadableContrastAcrossLightAndDarkColors() {
        val colors = listOf(Color.White, Color.Black, Color(0xFFECCB75), Color(0xFF00639A), Color(0xFF888888))
        colors.forEach { background ->
            val foreground = readableSeedForeground(background)
            val high = maxOf(background.luminance(), foreground.luminance())
            val low = minOf(background.luminance(), foreground.luminance())
            assertTrue("前景对比度不足：$background", (high + 0.05f) / (low + 0.05f) >= 4.5f)
        }
    }
}
