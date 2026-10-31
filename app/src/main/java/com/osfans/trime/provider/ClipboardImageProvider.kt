/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.osfans.trime.data.db.ClipboardImageHistory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.FileNotFoundException
import java.util.UUID

/** Read-only, token-scoped provider. No URI is issued by production UI yet. */
class ClipboardImageProvider : ContentProvider() {
    override fun onCreate() = true

    private fun caller(uri: Uri): Pair<String, Int>? {
        val ctx = context ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1 || uri.scheme != "content" ||
            uri.authority != "${ctx.packageName}.clipboard-image" || uri.query != null || uri.fragment != null
        ) return null
        val parts = uri.pathSegments
        if (parts.size != 2 || parts[0] != "send") return null
        val token = parts[1]
        if (runCatching { UUID.fromString(token).toString() != token }.getOrDefault(true)) return null
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        if (ctx.checkUriPermission(uri, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) return null
        return token to uid
    }

    private fun metadata(uri: Uri): com.osfans.trime.data.db.ClipboardImagePayload? {
        return try {
            val (token, uid) = caller(uri) ?: return null
            val repository = ClipboardImageHistory.repositoryOrNull() ?: return null
            runBlocking { withTimeout(4_000) { repository.sendLeaseMetadata(token, uid) } }
        } catch (_: Exception) {
            null
        }
    }

    override fun getType(uri: Uri): String? = metadata(uri)?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (selection != null || selectionArgs != null || sortOrder != null) return null
        val metadata = metadata(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        if (columns.any { it != OpenableColumns.DISPLAY_NAME && it != OpenableColumns.SIZE }) return null
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) metadata.byteCount else if (metadata.mimeType == "image/png") "image.png" else "image.jpg" })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Image is unavailable")
        val (token, uid) = caller(uri) ?: throw FileNotFoundException("Image is unavailable")
        val repository = ClipboardImageHistory.repositoryOrNull() ?: throw FileNotFoundException("Image is unavailable")
        var opened: ParcelFileDescriptor? = null
        return try {
            runBlocking { withTimeout(4_000) { repository.openSendLease(token, uid).also { opened = it } } }
                ?: throw FileNotFoundException("Image is unavailable")
        } catch (_: Exception) {
            runCatching { opened?.close() }
            throw FileNotFoundException("Image is unavailable")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
