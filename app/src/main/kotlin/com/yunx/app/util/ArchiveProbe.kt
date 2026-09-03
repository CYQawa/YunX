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

package com.yunx.app.util

import android.content.Context
import dalvik.system.DexFile
import java.security.MessageDigest
import java.util.zip.ZipFile

internal object ArchiveProbe {

    @Volatile
    private var fastDone: List<Int>? = null

    @Volatile
    private var deepDone: List<Int>? = null

    fun fast(ctx: Context): List<Int> {
        fastDone?.let { return it }
        val hits = ArrayList<Int>(2)
        if (hasMarkerAsset(ctx)) hits.add(1)
        if (hasLarkAssets(ctx)) hits.add(6)
        if (hasForeignSo(ctx)) hits.add(3)
        fastDone = hits
        return hits
    }

    fun deep(ctx: Context): List<Int> {
        deepDone?.let { return it }
        val hits = ArrayList<Int>(2)
        if (hasForeignType(ctx)) hits.add(2)
        if (hasDexPattern(ctx)) hits.add(5)
        deepDone = hits
        return hits
    }

    private fun hasMarkerAsset(ctx: Context): Boolean {
        val name = TextCipher.assetDecl
        val md5 = TextCipher.assetDeclMd5
        return try {
            val found = ctx.assets.list("")?.any { it == name } == true
            if (!found) return false
            val calc = md5Of(ctx.assets.open(name).readBytes())
            calc.isNotEmpty() && calc.equals(md5, ignoreCase = true)
        } catch (t: Throwable) {
            false
        }
    }

    private fun hasLarkAssets(ctx: Context): Boolean {
        val a = TextCipher.pLarkApk
        val b = TextCipher.pPluginApk
        return try {
            val sourceDir = ctx.applicationInfo?.sourceDir ?: return false
            ZipFile(sourceDir).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val n = entries.nextElement().name
                    if (n == "assets/$a" || n == "assets/$b") return true
                }
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun hasForeignType(ctx: Context): Boolean {
        val prefixes = arrayOf(
            TextCipher.pCloudInject, TextCipher.pSadfxg, TextCipher.pPx, TextCipher.pHelper, TextCipher.pMgcsq,
            TextCipher.pLarkInject, TextCipher.pLarkShadow
        ).filter { it.isNotEmpty() }
        val sourceDir = ctx.applicationInfo?.sourceDir ?: ctx.packageCodePath
        if (sourceDir.isNullOrEmpty()) return false
        return try {
            @Suppress("DEPRECATION")
            val df = DexFile(sourceDir)
            var hit = false
            val entries = df.entries()
            while (entries.hasMoreElements()) {
                val n = entries.nextElement()
                for (p in prefixes) {
                    if (n.startsWith(p)) { hit = true; break }
                }
                if (hit) break
            }
            df.close()
            hit
        } catch (t: Throwable) {
            false
        }
    }

    private fun hasForeignSo(ctx: Context): Boolean {
        val token = TextCipher.pNativeLib
        return try {
            val sourceDir = ctx.applicationInfo?.sourceDir ?: return false
            ZipFile(sourceDir).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val n = entries.nextElement().name
                    if (n.startsWith("lib/") && n.contains(token)) return true
                }
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun hasDexPattern(ctx: Context): Boolean {
        val tokens = arrayOf(
            TextCipher.pCloudInject.replace('.', '/'),
            TextCipher.pSadfxg.replace('.', '/'),
            TextCipher.pMgcsq,
            TextCipher.pNativeLib,
            TextCipher.pSpoof,
            TextCipher.pKami,
            TextCipher.pLarkInjectPath,
            TextCipher.pLarkShadowPath,
            TextCipher.pLarkDevCode,
            TextCipher.pLarkShadowAuth,
            TextCipher.pLarkApk,
            TextCipher.pPluginApk
        ).filter { it.isNotEmpty() }
        return try {
            val sourceDir = ctx.applicationInfo?.sourceDir ?: return false
            ZipFile(sourceDir).use { zip ->
                val list = ArrayDeque<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val n = entries.nextElement().name
                    if (n.startsWith("classes") && n.endsWith(".dex")) list.addLast(n)
                }
                for (name in list) {
                    val hit = zip.getInputStream(zip.getEntry(name)).use { ins ->
                        val buf = ByteArray(1024 * 1024)
                        val read = ins.read(buf)
                        if (read <= 0) {
                            false
                        } else {
                            val chunk = String(buf, 0, read, Charsets.ISO_8859_1)
                            tokens.any { chunk.contains(it) }
                        }
                    }
                    if (hit) return true
                }
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun md5Of(data: ByteArray): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            md.update(data)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            ""
        }
    }
}