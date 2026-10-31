/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.room.Room
import com.osfans.trime.data.db.ClipboardImageEventGeneration
import com.osfans.trime.data.db.ClipboardImageRepository
import com.osfans.trime.data.db.ClipboardImageUriAdmission
import com.osfans.trime.data.db.Database
import com.osfans.trime.data.db.ImageCaptureAuthorization
import com.osfans.trime.data.db.ImageClipboardCapture
import com.osfans.trime.data.db.PrivateImageByteStore
import com.osfans.trime.ime.core.PrivacyModeState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/** Real system clipboard and a separately installed provider, on a dedicated emulator only. */
class ClipboardImageCaptureInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val context = targetContext
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        val root = File(context.cacheDir, "image-capture-test-${UUID.randomUUID()}")
        val name = "image-capture-test-${UUID.randomUUID()}.db"
        val source = Uri.parse("content://com.x2m7.xrime.test.imagefixture/image")
        var database: Database? = null
        var previous: ClipData? = null
        var clipboardCaptured = false
        var clipboard: ClipboardManager? = null
        var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
        var primaryFailure: Throwable? = null
        try {
            waitForIdleSync()
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            check(File(context.getExternalFilesDir(null), "runtime-audit-dedicated").isFile) { "Dedicated emulator required" }
            val epoch = checkNotNull(PrivacyModeState.observeOrdinaryPersistence())
            Thread.sleep(250)
            check(PrivacyModeState.observeOrdinaryPersistence() == epoch) { "Privacy epoch is not stable" }
            var mainClipboard: ClipboardManager? = null
            runOnMainSync { mainClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
            val manager = checkNotNull(mainClipboard)
            clipboard = manager
            previous = manager.primaryClip?.let(::ClipData)
            clipboardCaptured = true
            val events = ClipboardImageEventGeneration()
            val callback = ClipboardManager.OnPrimaryClipChangedListener { events.advance() }
            manager.addPrimaryClipChangedListener(callback)
            listener = callback
            check(root.mkdir())
            database = Room.databaseBuilder(context, Database::class.java, name).build()
            val db = checkNotNull(database)
            val store = PrivateImageByteStore(root)
            val capture = ImageClipboardCapture(context, store, ClipboardImageRepository(db, store))
            context.contentResolver.call(source, "revoke", null, null)
            val beforeCopy = events.snapshot()
            context.contentResolver.call(source, "copy", null, null)
            runBlocking {
                withTimeout(5_000) { while (events.isCurrent(beforeCopy)) delay(20) }
                val auth = checkNotNull(events.acquireAuthorization(events.snapshot()) { true })
                try {
                    // The source app has copied its own URI; getPrimaryClip performs the system grant.
                    val actual = checkNotNull(manager.primaryClip) { "System clipboard was not readable" }
                    check(actual.itemCount == 1 && actual.getItemAt(0).uri == source) { "Fixture did not become the system clip" }
                    val provider = context.packageManager.resolveContentProvider(checkNotNull(source.authority), 0)
                    val grant = context.checkUriPermission(source, android.os.Process.myPid(), android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val providerPermission = provider?.readPermission?.let {
                        context.checkPermission(it, android.os.Process.myPid(), android.os.Process.myUid())
                    }
                    val openResult = try {
                        context.contentResolver.openFileDescriptor(source, "r")?.use { "opened" } ?: "null"
                    } catch (_: SecurityException) {
                        "security-denied"
                    } catch (_: java.io.IOException) {
                        "io-failure"
                    }
                    val allowed = ClipboardImageUriAdmission.admit(context, actual, auth) != null
                    sendStatus(0, Bundle().apply {
                        putString("stream", "Clipboard fixture: targetUid=${android.os.Process.myUid()} " +
                            "providerUid=${provider?.applicationInfo?.uid} explicitGrant=$grant admission=$allowed " +
                            "providerPermission=$providerPermission openResult=$openResult " +
                            "targetPackage=${context.packageName} operationPackage=${context.opPackageName} " +
                            "mimeCount=${actual.description.mimeTypeCount} hasIntent=${actual.getItemAt(0).intent != null}\n")
                    })
                    check(allowed) { "System clipboard did not provide an admissible read grant" }
                    val saved = checkNotNull(capture.capture(actual, auth)) { "System-granted image was rejected" }
                    check(saved.ready && saved.mimeType == "image/png" && saved.width == 8 && saved.height == 6)
                    check(db.clipboardImagePayloadDao().get(saved.id) == saved)
                    check(File(root, "clipboard-image-published/${saved.id}").isFile)
                    val opened = checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count")
                    check(opened > 0) { "Foreign provider was not actually opened" }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val description = android.content.ClipDescription("sensitive fixture", arrayOf("image/png"))
                        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        val sensitive = ClipData(description, ClipData.Item(source))
                        check(capture.capture(sensitive, auth) == null)
                        check(checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count") == opened)
                    }
                    val malformed = object : ClipData(
                        android.content.ClipDescription("broken", arrayOf("image/png")), ClipData.Item(source),
                    ) {
                        override fun getDescription(): android.content.ClipDescription =
                            throw android.os.BadParcelableException("broken fixture")
                    }
                    check(com.osfans.trime.data.db.ClipboardReadSafety.sensitiveOrUnreadable(malformed))
                    check(capture.capture(malformed, auth) == null)
                    check(checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count") == opened)
                    val cancelled = object : ClipData(
                        android.content.ClipDescription("cancelled", arrayOf("image/png")), ClipData.Item(source),
                    ) {
                        override fun getDescription(): android.content.ClipDescription =
                            throw java.util.concurrent.CancellationException("fixture cancellation")
                    }
                    check(runCatching { com.osfans.trime.data.db.ClipboardReadSafety.sensitiveOrUnreadable(cancelled) }
                        .exceptionOrNull() is java.util.concurrent.CancellationException)
                    check(runCatching { capture.capture(cancelled, auth) }.exceptionOrNull() is java.util.concurrent.CancellationException)
                    context.contentResolver.call(source, "slow", null, null)
                    val listening = java.util.concurrent.atomic.AtomicBoolean(true)
                    val slowAuth = checkNotNull(ImageCaptureAuthorization.acquire { listening.get() })
                    try {
                        val blocked = async { runCatching { capture.capture(actual, slowAuth) } }
                        withTimeout(5_000) {
                            while (checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count") == opened) delay(20)
                        }
                        listening.set(false)
                        val rejected = withTimeout(8_000) { blocked.await() }
                        check(rejected.isFailure || rejected.getOrNull() == null) { "Revoked in-flight capture completed" }
                        check(db.clipboardImagePayloadDao().pendingForRecovery().isEmpty())
                        check(File(root, "clipboard-image-staging").listFiles()!!.isEmpty())
                        check(db.clipboardImagePayloadDao().allForRecovery().size == 1)
                    } finally {
                        slowAuth.close()
                        context.contentResolver.call(source, "fast", null, null)
                    }
                    // Two blocked sources exhaust slots before scheduling a third coroutine.
                    context.contentResolver.call(source, "slow", null, null)
                    val saturationAllowed = java.util.concurrent.atomic.AtomicBoolean(true)
                    val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun newSaturationAuthorization() = checkNotNull(ImageCaptureAuthorization.acquire { saturationAllowed.get() })
                    val firstAuth = newSaturationAuthorization()
                    val secondAuth = newSaturationAuthorization()
                    val thirdAuth = newSaturationAuthorization()
                    val beforeSaturation = checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count")
                    val firstJob = checkNotNull(capture.tryStart(this, actual, firstAuth, { completed.set(true) }, {}))
                    val secondJob = checkNotNull(capture.tryStart(this, actual, secondAuth, { completed.set(true) }, {}))
                    check(capture.tryStart(this, actual, thirdAuth, { completed.set(true) }, {}) == null)
                    check(runCatching { thirdAuth.requireAllowed() }.isFailure)
                    try {
                        withTimeout(5_000) {
                            while (checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count") < beforeSaturation + 2) delay(20)
                        }
                    } finally {
                        saturationAllowed.set(false)
                    }
                    withTimeout(8_000) { firstJob.join(); secondJob.join() }
                    context.contentResolver.call(source, "fast", null, null)
                    check(!completed.get() && db.clipboardImagePayloadDao().allForRecovery().size == 1)
                    check(db.clipboardImagePayloadDao().pendingForRecovery().isEmpty())
                    check(File(root, "clipboard-image-staging").listFiles()!!.isEmpty())

                    // A real callback for replacement, empty or sensitive content must revoke
                    // an old in-flight image read even without an editor/privacy transition.
                    val replacements = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        listOf("copy", "copyEmpty", "copySensitive")
                    } else {
                        listOf("copy", "copyEmpty")
                    }
                    for (replacement in replacements) {
                        val beforeReset = events.snapshot()
                        context.contentResolver.call(source, "copy", null, null)
                        withTimeout(5_000) { while (events.isCurrent(beforeReset)) delay(20) }
                        val ticket = events.snapshot()
                        val eventAuth = checkNotNull(events.acquireAuthorization(ticket) { true })
                        try {
                            val eventClip = checkNotNull(manager.primaryClip)
                            check(eventClip.itemCount == 1 && eventClip.getItemAt(0).uri == source)
                            val beforeOpen = checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count")
                            context.contentResolver.call(source, "slow", null, null)
                            val blocked = async { runCatching { capture.capture(eventClip, eventAuth) } }
                            withTimeout(5_000) {
                                while (checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count") == beforeOpen) delay(20)
                            }
                            context.contentResolver.call(source, replacement, null, null)
                            withTimeout(5_000) { while (events.isCurrent(ticket)) delay(20) }
                            val rejected = withTimeout(8_000) { blocked.await() }
                            check(rejected.isFailure || rejected.getOrNull() == null) { "Prior image survived $replacement event" }
                            check(db.clipboardImagePayloadDao().pendingForRecovery().isEmpty())
                            check(File(root, "clipboard-image-staging").listFiles()!!.isEmpty())
                            check(db.clipboardImagePayloadDao().allForRecovery().size == 1)
                        } finally {
                            eventAuth.close()
                            context.contentResolver.call(source, "fast", null, null)
                        }
                    }
                    val beforeFinal = events.snapshot()
                    context.contentResolver.call(source, "copy", null, null)
                    withTimeout(5_000) { while (events.isCurrent(beforeFinal)) delay(20) }
                    val finalAuth = checkNotNull(events.acquireAuthorization(events.snapshot()) { true })
                    try {
                        val finalClip = checkNotNull(manager.primaryClip)
                        // Provider reads finish in a bounded IO staging slot before any Rime
                        // maintenance operation is called. Cancellation must never start it.
                        context.contentResolver.call(source, "slow", null, null)
                        val operations = java.util.concurrent.atomic.AtomicInteger()
                        val beforeStaging = checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count")
                        fun stage() = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            com.osfans.trime.data.userdict.DictionaryDocumentStager.read(context, source, 1024 * 1024) {
                                operations.incrementAndGet()
                            }
                        }
                        val stageOne = stage()
                        val stageTwo = stage()
                        try {
                            val busy = runCatching {
                                com.osfans.trime.data.userdict.DictionaryDocumentStager.read(context, source, 1024 * 1024) {
                                    operations.incrementAndGet()
                                }
                            }.exceptionOrNull()
                            check(busy is java.io.IOException) { "Dictionary staging did not reject saturation" }
                            withTimeout(5_000) {
                                while (checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count") < beforeStaging + 2) delay(20)
                            }
                        } finally {
                            stageOne.cancel()
                            stageTwo.cancel()
                        }
                        withTimeout(8_000) { stageOne.join(); stageTwo.join() }
                        check(operations.get() == 0) { "Cancelled provider data reached dictionary maintenance" }
                        context.contentResolver.call(source, "fast", null, null)
                        val bytes = com.osfans.trime.data.userdict.DictionaryDocumentStager.read(context, source, 1024 * 1024) { it.readBytes() }
                        check(bytes.isNotEmpty())
                        check(File(context.cacheDir, "dictionary-import-staging").listFiles()!!.none { it.name.endsWith(".tmp") })

                        // Export generation completes before the external provider is opened.
                        // A slow external writer occupies only a bounded transfer slot.
                        val writesBeforeLimit = checkNotNull(context.contentResolver.call(source, "writeCount", null, null)).getInt("count")
                        val oversized = runCatching {
                            com.osfans.trime.data.userdict.DictionaryDocumentStager.write(context, source, 1024) { staged ->
                                staged.writeBytes(ByteArray(1025))
                            }
                        }.exceptionOrNull()
                        check(oversized is java.io.IOException)
                        check(checkNotNull(context.contentResolver.call(source, "writeCount", null, null)).getInt("count") == writesBeforeLimit) {
                            "Oversized export opened the external destination"
                        }
                        context.contentResolver.call(source, "slowWrite", null, null)
                        val generated = java.util.concurrent.atomic.AtomicInteger()
                        fun export() = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            com.osfans.trime.data.userdict.DictionaryDocumentStager.write(context, source, 1024 * 1024) { staged ->
                                staged.writeBytes(ByteArray(512 * 1024))
                                generated.incrementAndGet()
                            }
                        }
                        val exportOne = export()
                        val exportTwo = export()
                        try {
                            withTimeout(5_000) {
                                while (checkNotNull(context.contentResolver.call(source, "writeCount", null, null)).getInt("count") < writesBeforeLimit + 2) delay(20)
                            }
                            check(generated.get() == 2) { "Provider opened before local generation completed" }
                            val busyExport = runCatching {
                                com.osfans.trime.data.userdict.DictionaryDocumentStager.write(context, source, 1024) { it.writeBytes(byteArrayOf(1)) }
                            }.exceptionOrNull()
                            check(busyExport is java.io.IOException) { "Dictionary export did not reject saturation" }
                        } finally {
                            exportOne.cancel()
                            exportTwo.cancel()
                        }
                        withTimeout(8_000) { exportOne.join(); exportTwo.join() }
                        context.contentResolver.call(source, "fastWrite", null, null)
                        com.osfans.trime.data.userdict.DictionaryDocumentStager.write(context, source, 1024) { it.writeBytes(byteArrayOf(1, 2, 3)) }
                        check(File(context.cacheDir, "dictionary-import-staging").listFiles()!!.none { it.name.endsWith(".tmp") })
                        val afterSlow = checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count")
                        context.contentResolver.call(source, "revoke", null, null)
                        check(capture.capture(finalClip, finalAuth) == null) { "Revoked URI was accepted" }
                        check(checkNotNull(context.contentResolver.call(source, "openCount", null, null)).getInt("count") == afterSlow)
                    } finally {
                        finalAuth.close()
                    }
                } finally {
                    auth.close()
                }
            }
            code = Activity.RESULT_OK
            result.putString("stream", "PASS: external system clipboard image copied through read grant into private READY storage; revoked source rejected\n")
        } catch (failure: Throwable) {
            primaryFailure = failure
            result.putString("stream", android.util.Log.getStackTraceString(failure))
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun clean(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            clean {
                val manager = clipboard
                val callback = listener
                if (manager != null && callback != null) manager.removePrimaryClipChangedListener(callback)
            }
            clean {
                val manager = clipboard
                val original = previous
                if (clipboardCaptured && manager != null) {
                    if (original != null) manager.setPrimaryClip(ClipData(original))
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.clearPrimaryClip()
                    else manager.setPrimaryClip(ClipData.newPlainText("fixture cleanup", ""))
                }
            }
            clean { context.contentResolver.call(source, "revoke", null, null) }
            clean { context.contentResolver.call(source, "fastWrite", null, null) }
            clean { database?.close() }
            clean {
                val dbRemoved = !context.getDatabasePath(name).exists() || context.deleteDatabase(name)
                val filesRemoved = !root.exists() || root.deleteRecursively()
                check(dbRemoved && filesRemoved) { "Cannot remove isolated test fixture" }
            }
            if (cleanupFailures.isNotEmpty()) {
                code = Activity.RESULT_CANCELED
                val cleanup = cleanupFailures.joinToString("\n") { android.util.Log.getStackTraceString(it) }
                val message = if (primaryFailure == null) cleanup else result.getString("stream") + "\nCleanup failure:\n" + cleanup
                result.putString("stream", message)
            }
        }
        finish(code, result)
    }
}
