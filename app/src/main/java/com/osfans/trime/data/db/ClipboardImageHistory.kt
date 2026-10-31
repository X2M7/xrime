/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.io.IOException

/** One credential-protected repository shared by capture, image history and the provider. */
internal object ClipboardImageHistory {
    private data class Services(val repository: ClipboardImageRepository, val capture: ImageClipboardCapture)

    @Volatile private var candidate: Services? = null
    @Volatile private var services: Services? = null

    @Synchronized
    fun initialize(context: Context, database: Database, persistHistory: Boolean) {
        candidate = null
        services = null
        if (!persistHistory) return
        val base = context.noBackupFilesDir.canonicalFile
        if (!base.isDirectory) throw IOException("Private image storage is unavailable")
        val root = File(base, "clipboard-image-history")
        if (root.canonicalFile != root.absoluteFile) throw IOException("Unsafe image storage directory")
        if (!root.isDirectory && !root.mkdir()) throw IOException("Cannot create image storage directory")
        if (!root.isDirectory || root.canonicalFile != root.absoluteFile) throw IOException("Unsafe image storage directory")
        val store = PrivateImageByteStore(root)
        val clock = ImageSendLeaseClock(
            now = SystemClock::elapsedRealtime,
            boot = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                } else {
                    -1
                }
            },
        )
        val repository = ClipboardImageRepository(database, store, clock)
        candidate = Services(repository, ImageClipboardCapture(context.applicationContext, store, repository))
    }

    suspend fun recoverAndEnable() {
        val pending = candidate ?: return
        pending.repository.recover()
        synchronized(this) {
            if (candidate === pending) services = pending
        }
    }

    fun repositoryOrNull(): ClipboardImageRepository? = services?.repository

    /** Initialization itself never reads clipboard data. */
    fun captureOrNull(): ImageClipboardCapture? = services?.capture
}
