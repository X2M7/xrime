/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.ClipboardImageHistory
import com.osfans.trime.data.db.Database
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.PrivacyModeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real source app -> system clipboard -> production listener -> private history. */
class ClipboardImageListenerInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val context = targetContext
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var failure: Throwable? = null
        try {
            waitForIdleSync()
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            check(File(context.getExternalFilesDir(null), "runtime-audit-dedicated").isFile) { "Dedicated emulator required" }
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "Fixture requires modern clipboard grants" }
            fun <T> main(block: () -> T): T {
                var value: Result<T>? = null
                runOnMainSync { value = runCatching(block) }
                return checkNotNull(value).getOrThrow()
            }
            val manager = main { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
            val previous = main { manager.primaryClip?.let(::ClipData) }
            check(previous != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "Cannot restore an empty clipboard on this Android version" }
            val prefs = AppPrefs.defaultInstance().clipboard.clipboardListening
            val previousListening = prefs.getValue()
            val source = Uri.parse("content://com.x2m7.xrime.test.imagefixture/image")
            val db = ClipboardHelper::class.java.getDeclaredField("clbDb").apply { isAccessible = true }.get(ClipboardHelper) as Database
            val dao = db.clipboardImagePayloadDao()
            val imageIds = mutableListOf<String>()
            var primary: Throwable? = null
            try {
                main { prefs.setValue(true) }
                runBlocking {
                    val repository = withTimeout(10_000) {
                        while (ClipboardImageHistory.repositoryOrNull() == null) delay(50)
                        checkNotNull(ClipboardImageHistory.repositoryOrNull())
                    }
                    val epoch = checkNotNull(PrivacyModeState.observeOrdinaryPersistence())
                    delay(250)
                    check(PrivacyModeState.observeOrdinaryPersistence() == epoch) { "Editor/privacy epoch is not stable" }
                    fun newPixel() = (UUID.randomUUID().leastSignificantBits.toInt() and 0x00ffffff) or -0x1000000
                    fun hashForPixel(pixel: Int): String {
                        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
                        val output = ByteArrayOutputStream()
                        try {
                            bitmap.setPixel(0, 0, pixel)
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        } finally {
                            bitmap.recycle()
                        }
                        return MessageDigest.getInstance("SHA-256").digest(output.toByteArray()).joinToString("") { "%02x".format(it) }
                    }
                    val pixel = newPixel()
                    val hash = hashForPixel(pixel)
                    check(dao.findByHash(hash) == null) { "Synthetic fixture already exists" }
                    context.contentResolver.call(source, "seed", pixel.toString(), null)
                    context.contentResolver.call(source, "copy", null, null)
                    val saved = withTimeout(10_000) {
                        var row = dao.findByHash(hash)
                        while (row == null) { delay(50); row = dao.findByHash(hash) }
                        row
                    }
                    imageIds.add(saved.id)
                    check(saved.ready && saved.width == 8 && saved.height == 6)
                    val thumbnail = checkNotNull(repository.loadThumbnail(saved.id) {})
                    thumbnail.recycle()
                    sendStatus(0, Bundle().apply { putString("stream", "Production listener saved external system image and loaded its thumbnail\n") })

                    val sensitivePixel = newPixel()
                    val sensitiveHash = hashForPixel(sensitivePixel)
                    check(dao.findByHash(sensitiveHash) == null)
                    context.contentResolver.call(source, "seed", sensitivePixel.toString(), null)
                    context.contentResolver.call(source, "copySensitive", null, null)
                    delay(700)
                    check(dao.findByHash(sensitiveHash) == null) { "Sensitive clipboard image was saved" }
                    main { prefs.setValue(false) }
                    val disabledPixel = newPixel()
                    val disabledHash = hashForPixel(disabledPixel)
                    check(dao.findByHash(disabledHash) == null)
                    context.contentResolver.call(source, "seed", disabledPixel.toString(), null)
                    context.contentResolver.call(source, "copy", null, null)
                    delay(700)
                    check(dao.findByHash(disabledHash) == null) { "Image saved while clipboard listening was disabled" }
                    main { prefs.setValue(true) }
                    sendStatus(0, Bundle().apply { putString("stream", "Sensitive and disabled-listening system images were not saved\n") })

                    val blockedPixel = newPixel()
                    val blockedHash = hashForPixel(blockedPixel)
                    check(dao.findByHash(blockedHash) == null)
                    context.contentResolver.call(source, "seed", blockedPixel.toString(), null)
                    context.contentResolver.call(source, "slow", null, null)
                    val opened = checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count")
                    context.contentResolver.call(source, "copy", null, null)
                    withTimeout(5_000) {
                        while (checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count") == opened) delay(20)
                    }
                    context.contentResolver.call(source, "copyEmpty", null, null)
                    delay(2_500)
                    check(dao.findByHash(blockedHash) == null) { "Replaced clipboard image was saved after revocation" }
                    context.contentResolver.call(source, "fast", null, null)
                    sendStatus(0, Bundle().apply { putString("stream", "Production listener rejected a blocked read after clipboard replacement\n") })
                }
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                var cleanupFailure: Throwable? = null
                runBlocking {
                    suspend fun cleanup(action: suspend () -> Unit) {
                        try { action() } catch (cleanup: Throwable) {
                            val first = cleanupFailure
                            if (first == null) cleanupFailure = cleanup else if (first !== cleanup) first.addSuppressed(cleanup)
                        }
                    }
                    var listenerDisabled = false
                    cleanup { main { prefs.setValue(false); listenerDisabled = !prefs.getValue() } }
                    cleanup { context.contentResolver.call(source, "fast", null, null) }
                    cleanup { context.contentResolver.call(source, "seed", "0", null) }
                    val repository = ClipboardImageHistory.repositoryOrNull()
                    for (id in imageIds) cleanup { check(checkNotNull(repository).deleteReady(id) {}) }
                    cleanup {
                        check(listenerDisabled) { "Clipboard restoration requires listening to be disabled" }
                        main {
                            if (previous != null) manager.setPrimaryClip(previous) else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.clearPrimaryClip()
                        }
                    }
                    cleanup { main { prefs.setValue(previousListening) } }
                }
                cleanupFailure?.let { if (primary != null) primary.addSuppressed(it) else throw it }
            }
            code = Activity.RESULT_OK
            result.putString("stream", "Production image clipboard capture passed\n")
        } catch (t: Throwable) {
            failure = t
        }
        failure?.let { result.putString("stream", android.util.Log.getStackTraceString(it)) }
        finish(code, result)
    }
}
