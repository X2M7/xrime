/* SPDX-License-Identifier: GPL-3.0-or-later */
@file:Suppress("DEPRECATION")
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.db.AdmittedClipboardImage
import com.osfans.trime.data.db.ClipboardImageHistory
import com.osfans.trime.data.db.ImageCaptureAuthorization
import com.osfans.trime.data.db.PrivateImageByteStore
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.clipboard.ClipboardImageAdapter
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.PrivacyModeState
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real editor and provider run in the test APK's separate UID. */
class ClipboardImageSendInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val stateUri = Uri.parse("content://com.x2m7.xrime.test.imageeditor")
        var failure: Throwable? = null
        var savedId: String? = null
        val leases = mutableListOf<Uri>()
        var repo: com.osfans.trime.data.db.ClipboardImageRepository? = null
        fun <T> main(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var result: Result<T>? = null
            runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        fun input(): InputView? = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
            .filterIsInstance<InputView>().firstOrNull { it.isShown }
        fun state() = checkNotNull(targetContext.contentResolver.call(stateUri, "state", null, null))
        fun configure(mime: String, accept: Boolean, hold: Boolean) {
            targetContext.contentResolver.call(stateUri, "finish", null, null)
            targetContext.contentResolver.call(stateUri, "configure", null, Bundle().apply {
                putString("mime", mime); putBoolean("accept", accept); putBoolean("hold", hold)
            })
            targetContext.startActivity(Intent().setClassName("com.x2m7.xrime.test", "com.osfans.trime.ClipboardImageExternalEditorActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        suspend fun until(label: String, condition: () -> Boolean) {
            try {
                withTimeout(30_000) { while (!main(condition)) delay(25) }
            } catch (failure: TimeoutCancellationException) {
                val detail = main {
                    val service = input()?.di?.direct?.instance<TrimeInputMethodService>()
                    "inputShown=${service?.isInputViewShown}, editorPackage=${service?.currentInputEditorInfo?.packageName}"
                }
                throw IllegalStateException("Timed out waiting for $label: $detail", failure)
            }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: $label\n") })
        }
        try {
            waitForIdleSync()
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(File(checkNotNull(targetContext.getExternalFilesDir(null)), "runtime-audit-dedicated").isFile)
            val targetUid = targetContext.packageManager.getApplicationInfo("com.x2m7.xrime.test", 0).uid
            check(targetUid != Process.myUid()) { "External editor must have a different UID" }
            runBlocking {
                withTimeout(30_000) { while (ClipboardImageHistory.repositoryOrNull() == null) delay(25) }
                repo = checkNotNull(ClipboardImageHistory.repositoryOrNull())
                val image = Bitmap.createBitmap(9, 7, Bitmap.Config.ARGB_8888)
                val bytes = ByteArrayOutputStream()
                try {
                    val color = 0xff000000.toInt() or (UUID.randomUUID().hashCode() and 0x00ffffff)
                    image.eraseColor(color)
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                } finally { image.recycle() }
                val data = bytes.toByteArray()
                val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
                val auth = checkNotNull(ImageCaptureAuthorization.acquire { true })
                val permit = auth.acquirePersistencePermit()
                val saved = try {
                    val root = File(targetContext.noBackupFilesDir.canonicalFile, "clipboard-image-history")
                    val staged = PrivateImageByteStore(root).stage(ByteArrayInputStream(data), auth::requireAllowed)
                    checkNotNull(repo).save(staged, AdmittedClipboardImage(Uri.parse("content://fixture/image"), setOf("image/png"), auth), auth, permit)
                } finally { PrivacyModeState.releasePersistencePermit(permit) }
                savedId = saved.id
                fun fixtureRow(): View? {
                    val root = input() ?: return null
                    for (list in descendants(root).filterIsInstance<RecyclerView>()) {
                        val adapter = list.adapter as? ClipboardImageAdapter ?: continue
                        val position = adapter.snapshot().indexOfFirst { it?.id == saved.id }
                        if (position < 0) continue
                        val holder = list.findViewHolderForAdapterPosition(position) as? ClipboardImageAdapter.Holder ?: continue
                        if (holder.row.isShown && holder.image.drawable is BitmapDrawable) return holder.row
                    }
                    return null
                }
                suspend fun openPage(mime: String, accept: Boolean, hold: Boolean = false): Pair<TrimeInputMethodService, View> {
                    val oldOwner = main { input()?.di?.direct?.instance<TrimeInputMethodService>()?.editorToken }
                    configure(mime, accept, hold)
                    until("external UID editor is bound") {
                        val service = input()?.di?.direct?.instance<TrimeInputMethodService>()
                        val info = service?.currentInputEditorInfo
                        service != null && service.editorToken != oldOwner && info != null && info.packageName == "com.x2m7.xrime.test" &&
                            EditorInfoCompat.getContentMimeTypes(info).contains(mime)
                    }
                    val service = main { checkNotNull(input()).di.direct.instance<TrimeInputMethodService>() }
                    check(main { service.currentInputBinding?.uid == targetUid })
                    val window = main {
                        val view = checkNotNull(input())
                        ClipboardWindow(view.di, 2, checkNotNull(repo)).also { view.di.direct.instance<BoardWindowManager>().attachWindow(it) }
                    }
                    until("external editor image page") {
                        val view = input() ?: return@until false
                        view.di.direct.instance<BoardWindowManager>().isAttached(window) && fixtureRow() != null
                    }
                    val row = main { checkNotNull(fixtureRow()) }
                    return service to row
                }
                val (_, row) = openPage("image/png", true)
                main { check(row.performClick()); row.performClick() }
                withTimeout(30_000) { while (!state().getBoolean("read")) delay(25) }
                withTimeout(10_000) { while (!state().getBoolean("completed")) delay(25) }
                val received = state()
                check(received.getInt("calls") == 1 && received.getBoolean("permissionRequested") &&
                    received.getString("mime") == "image/png" && received.getString("sha256") == hash)
                leases += Uri.parse(checkNotNull(received.getString("uri")))
                sendStatus(0, Bundle().apply { putString("stream", "PASS: external UID editor receives and reads one granted PNG after duplicate clicks\n") })
                val (_, unsupported) = openPage("text/plain", true)
                main { check(unsupported.performClick()) }
                delay(1000)
                check(state().getInt("calls") == 0) { "Unsupported MIME dispatched content" }
                sendStatus(0, Bundle().apply { putString("stream", "PASS: unsupported editor MIME receives no image request\n") })
                val (_, rejected) = openPage("image/png", false)
                main { check(rejected.performClick()) }
                withTimeout(30_000) { while (state().getInt("calls") == 0) delay(25) }
                withTimeout(10_000) { while (!state().getBoolean("completed")) delay(25) }
                val rejectedState = state()
                check(rejectedState.getInt("calls") == 1 && !rejectedState.getBoolean("read"))
                leases += Uri.parse(checkNotNull(rejectedState.getString("uri")))
                sendStatus(0, Bundle().apply { putString("stream", "PASS: remote rejection receives one dispatch without image read\n") })
                val (blockedService, blockedRow) = openPage("image/png", true, hold = true)
                main { check(blockedRow.performClick()) }
                withTimeout(30_000) { while (state().getInt("calls") == 0) delay(25) }
                val mark = SystemClock.uptimeMillis()
                main { check(blockedService.isSearchInputFrozen) }
                check(SystemClock.uptimeMillis() - mark < 1_500) { "Remote image editor blocked the IME main thread" }
                val oldOwner = main { blockedService.editorToken }
                val replacement = startActivitySync(Intent(targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val replacementField = main {
                    EditText(replacement).apply {
                        id = View.generateViewId()
                        setText("new-editor-sentinel")
                        replacement.setContentView(this)
                        requestFocus()
                        (replacement.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
                    }
                }
                until("new editor binds while old image receiver is blocked") {
                    blockedService.editorToken != oldOwner && blockedService.currentInputEditorInfo?.fieldId == replacementField.id
                }
                targetContext.contentResolver.call(stateUri, "release", null, null)
                withTimeout(10_000) { while (!state().getBoolean("completed")) delay(25) }
                state().getString("uri")?.let { leases += Uri.parse(it) }
                check(main { replacementField.text.toString() == "new-editor-sentinel" }) { "Old image reached the new editor" }
                main { replacement.finish() }
                sendStatus(0, Bundle().apply { putString("stream", "PASS: blocked receiver leaves IME responsive and new editor isolated\n") })
                val (service, stale) = openPage("image/png", true)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                try {
                    main { service.postRimeJob { entered.complete(Unit); release.await() } }
                    withTimeout(10_000) { entered.await() }
                    main { check(stale.performClick()) }
                    until("image send drains accepted input") { service.isSearchInputFrozen }
                    val owner = main { service.editorToken }
                    targetContext.contentResolver.call(stateUri, "finish", null, null)
                    until("editor boundary cancels image send") { service.editorToken != owner || !service.isInputViewShown }
                } finally { release.complete(Unit) }
                delay(1000)
                check(state().getInt("calls") == 0) { "Old editor received a delayed image" }
                sendStatus(0, Bundle().apply { putString("stream", "PASS: editor change rejects an image awaiting the input drain\n") })
            }
        } catch (error: Throwable) { failure = error }
        finally {
            try {
                targetContext.contentResolver.call(stateUri, "finish", null, null)
                targetContext.contentResolver.call(stateUri, "release", null, null)
                runBlocking {
                    delay(500)
                    val finalState = state()
                    if (finalState.getInt("calls") > 0 && !finalState.getBoolean("completed")) {
                        withTimeout(10_000) { while (!state().getBoolean("completed")) delay(25) }
                    }
                    state().getString("uri")?.let { value ->
                        val captured = Uri.parse(value)
                        if (captured !in leases) leases += captured
                    }
                    val repository = repo
                    val uid = targetContext.packageManager.getApplicationInfo("com.x2m7.xrime.test", 0).uid
                    for (uri in leases) {
                        targetContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        repository?.revokeSendLease(checkNotNull(uri.lastPathSegment), uid)
                    }
                    savedId?.let { id -> repository?.deleteReady(id) {} }
                }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else checkNotNull(failure).addSuppressed(cleanup)
            }
        }
        val result = Bundle().apply { putBoolean("passed", failure == null); putString("stream", failure?.stackTraceToString() ?: "PASS: cross-UID image send\n") }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
