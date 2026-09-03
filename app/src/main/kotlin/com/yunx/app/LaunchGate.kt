package com.yunx.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.yunx.app.crash.CrashHandler
import com.yunx.app.util.ArchiveProbe

class LaunchGate : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        runCatching {
            val hits = ArchiveProbe.fast(ctx.applicationContext).toMutableList()
            if (entryMismatch(ctx.applicationContext)) hits.add(4)
            if (hits.isNotEmpty()) {
                CrashHandler.terminate(hits.joinToString(","))
            }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}