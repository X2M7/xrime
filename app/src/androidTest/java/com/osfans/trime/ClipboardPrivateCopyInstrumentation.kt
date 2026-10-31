/* SPDX-License-Identifier: GPL-3.0-or-later */
@file:Suppress("DEPRECATION")
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.DatabaseDao
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.clipboard.ClipboardLayout
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.PrivacyModeState
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Two-UID private copy on a dedicated emulator; only generated content is used. */
class ClipboardPrivateCopyInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var failure: Throwable? = null
        val stateUri = Uri.parse("content://com.x2m7.xrime.test.imageeditor")
        val clipboard = targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var restoreClipboard: (() -> Unit)? = null
        val events = AtomicInteger()
        val listener = ClipboardManager.OnPrimaryClipChangedListener { events.incrementAndGet() }
        val prefix = "privatecopy" + UUID.randomUUID().toString().replace("-", "")
        val clipboardSentinel = "sentinel-$prefix"
        val selected = "selected-$prefix"
        val privateSelection = "privacy-$prefix"
        val staleSelection = "stale-$prefix"
        val disabledSelection = "disabled-$prefix"
        val reopenedSelection = "reopened-$prefix"
        val longSelection = "long-$prefix"
        val oversizedSelection = "oversized-$prefix" + "x".repeat(65537)
        val generated = listOf(selected, privateSelection, staleSelection, disabledSelection, reopenedSelection, longSelection, oversizedSelection, clipboardSentinel)
        var replacement: Activity? = null
        var restoreTheme: (suspend () -> Unit)? = null
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
        fun call(method: String, data: Bundle? = null): Bundle = checkNotNull(targetContext.contentResolver.call(stateUri, method, null, data))
        suspend fun until(label: String, condition: () -> Boolean) {
            try { withTimeout(30_000) { while (!main(condition)) delay(25) } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw IllegalStateException("Timed out waiting for $label", error) }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: $label\n") })
        }
        try {
            waitForIdleSync()
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(File(checkNotNull(targetContext.getExternalFilesDir(null)), "runtime-audit-dedicated").isFile)
            check(Settings.Secure.getString(targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).startsWith("com.x2m7.xrime/"))
            val prefs = AppPrefs.defaultInstance()
            val history = prefs.clipboard.clipboardListening
            val privacy = prefs.general.manualPrivacyMode
            val oldHistory = history.getValue()
            val oldPrivacy = privacy.getValue()
            val oldClip = clipboard.primaryClip
            restoreClipboard = {
                clipboard.removePrimaryClipChangedListener(listener)
                history.setValue(false)
                if (oldClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(oldClip)
                history.setValue(oldHistory)
                privacy.setValue(oldPrivacy)
            }
            val daoField = ClipboardHelper::class.java.getDeclaredField("clbDao").apply { isAccessible = true }
            val dao = daoField.get(ClipboardHelper) as DatabaseDao
            runBlocking {
                main { history.setValue(true); privacy.setValue(false); clipboard.setPrimaryClip(ClipData.newPlainText("fixture", clipboardSentinel)) }
                delay(1000)
                main { clipboard.addPrimaryClipChangedListener(listener) }
                suspend fun startEditor(text: String): TrimeInputMethodService {
                    val before = main { input()?.di?.direct?.instance<TrimeInputMethodService>()?.editorToken }
                    call("finish")
                    call("configure", Bundle().apply { putString("mime", "image/png"); putString("editorText", text) })
                    targetContext.startActivity(Intent().setClassName("com.x2m7.xrime.test", "com.osfans.trime.ClipboardImageExternalEditorActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    until("external editor bound") {
                        val service = input()?.di?.direct?.instance<TrimeInputMethodService>()
                        service != null && service.editorToken != before && service.currentInputEditorInfo?.packageName == "com.x2m7.xrime.test" &&
                            !service.isPrivacyModeActive && PrivacyModeState.observeOrdinaryPersistence() != null
                    }
                    val service = main { checkNotNull(input()).di.direct.instance<TrimeInputMethodService>() }
                    val barrier = main { service.postRimeJob { } }
                    withTimeout(15_000) { barrier.join() }
                    call("select", Bundle().apply { putInt("start", 0); putInt("end", text.length) })
                    val connection = main { checkNotNull(service.currentInputConnection) }
                    withTimeout(15_000) {
                        while (withContext(Dispatchers.IO) { connection.getSelectedText(0)?.toString() } != text) delay(25)
                    }
                    return service
                }
                fun requestCopy() {
                    main {
                        val root = checkNotNull(input())
                        val window = ClipboardWindow(root.di)
                        root.di.direct.instance<BoardWindowManager>().attachWindow(window)
                        val layout = ClipboardWindow::class.java.getDeclaredField("clipboardLayout").apply { isAccessible = true }.get(window) as ClipboardLayout
                        check(layout.titleUi.moreActionsButton.performClick())
                    }
                }
                suspend fun tapPrivateCopy() {
                    until("private copy menu is visible") {
                        WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants).filterIsInstance<TextView>()
                            .any { it.isShown && it.text.toString() == targetContext.getString(R.string.private_copy_action) }
                    }
                    val point = main {
                        val label = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants).filterIsInstance<TextView>()
                            .first { it.isShown && it.text.toString() == targetContext.getString(R.string.private_copy_action) }
                        val xy = IntArray(2).also(label::getLocationOnScreen)
                        (xy[0] + label.width / 2f) to (xy[1] + label.height / 2f)
                    }
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
                        try { sendPointerSync(event) } finally { event.recycle() }
                    }
                }
                startEditor(selected)
                requestCopy(); tapPrivateCopy()
                withTimeout(30_000) { while (dao.find(selected) == null) delay(25) }
                delay(500)
                check(events.get() == 0 && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == clipboardSentinel) {
                    "Private copy changed the Android clipboard"
                }
                sendStatus(0, Bundle().apply { putString("stream", "PASS: private copy saves selection without a system clipboard event\n") })
                startEditor(disabledSelection)
                main { history.setValue(false) }
                requestCopy(); tapPrivateCopy()
                delay(600)
                check(dao.find(disabledSelection) == null) { "Private copy ignored the disabled history setting" }
                main { history.setValue(true) }
                startEditor(oversizedSelection)
                requestCopy(); tapPrivateCopy()
                delay(1000)
                check(dao.find(oversizedSelection) == null) { "Oversized selection was persisted" }
                sendStatus(0, Bundle().apply { putString("stream", "PASS: private copy rejects disabled history and oversized selection\n") })
                startEditor(longSelection)
                val selectedTheme = ThemeManager.prefs.selectedTheme
                val oldTheme = selectedTheme.getValue()
                val hadTheme = selectedTheme.sharedPreferences.contains(selectedTheme.key)
                val rawTheme = selectedTheme.sharedPreferences.getString(selectedTheme.key, null)
                restoreTheme = {
                    ThemeManager.selectTheme(oldTheme)
                    val edit = selectedTheme.sharedPreferences.edit()
                    if (hadTheme) edit.putString(selectedTheme.key, checkNotNull(rawTheme)) else edit.remove(selectedTheme.key)
                    check(edit.commit())
                }
                check(ThemeManager.selectTheme("tongwenfeng.trime") == "tongwenfeng.trime")
                until("tongwenfeng editor is available") { input()?.di?.direct?.instance<TrimeInputMethodService>()?.currentInputEditorInfo?.packageName == "com.x2m7.xrime.test" }
                main { checkNotNull(input()).di.direct.instance<KeyboardWindow>().switchKeyboard("editor") }
                until("private copy long-press key is visible") {
                    val view = input() ?: return@until false
                    val keyboard = view.di.direct.instance<KeyboardWindow>().currentKeyboard
                    keyboard.keys.any { it.getAction(KeyBehavior.LONG_CLICK)?.command == "private_copy" } &&
                        descendants(view).filterIsInstance<KeyboardView>().any { it.isShown }
                }
                val key = main {
                    val root = checkNotNull(input())
                    val keyboard = root.di.direct.instance<KeyboardWindow>().currentKeyboard
                    val index = keyboard.keys.indexOfFirst { it.getAction(KeyBehavior.LONG_CLICK)?.command == "private_copy" }
                    descendants(root).filterIsInstance<KeyboardView>().first { it.isShown }.getChildAt(index) as KeyView
                }
                val pressed = SystemClock.uptimeMillis()
                main {
                    val event = MotionEvent.obtain(pressed, pressed, MotionEvent.ACTION_DOWN, key.width / 2f, key.height / 2f, 0)
                    try { check(key.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                delay(ViewConfiguration.getLongPressTimeout().toLong() + 300)
                main {
                    val event = MotionEvent.obtain(pressed, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, key.width / 2f, key.height / 2f, 0)
                    try { key.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                withTimeout(30_000) { while (dao.find(longSelection) == null) delay(25) }
                check(events.get() == 0 && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == clipboardSentinel)
                sendStatus(0, Bundle().apply { putString("stream", "PASS: real copy-key long press saves privately without changing Android clipboard\n") })
                val reopenedService = startEditor(reopenedSelection)
                call("armSelection")
                requestCopy(); tapPrivateCopy()
                withTimeout(15_000) { while (!call("state").getBoolean("selectionEntered")) delay(25) }
                val reopenedJob = main {
                    TrimeInputMethodService::class.java.getDeclaredField("privateCopyJob").apply { isAccessible = true }.get(reopenedService) as Job
                }
                val responsiveMark = SystemClock.uptimeMillis()
                main { check(reopenedService.isInputViewShown) }
                check(SystemClock.uptimeMillis() - responsiveMark < 1_500) { "Selection read blocked the IME main thread" }
                main { history.setValue(false); history.setValue(true) }
                call("releaseSelection")
                withTimeout(15_000) { reopenedJob.join() }
                check(dao.find(reopenedSelection) == null) { "Old selection survived disabling and reenabling history" }
                sendStatus(0, Bundle().apply { putString("stream", "PASS: history disable/reopen rejects an old selection without blocking the IME\n") })
                val privateService = startEditor(privateSelection)
                call("armSelection")
                requestCopy(); tapPrivateCopy()
                withTimeout(15_000) { while (!call("state").getBoolean("selectionEntered")) delay(25) }
                val privacyJob = main {
                    TrimeInputMethodService::class.java.getDeclaredField("privateCopyJob").apply { isAccessible = true }.get(privateService) as Job
                }
                main { privacy.setValue(true) }
                until("privacy activated during selection read") { privateService.isPrivacyModeActive }
                call("releaseSelection")
                withTimeout(15_000) { privacyJob.join() }
                check(dao.find(privateSelection) == null) { "Private selection persisted after privacy boundary" }
                main { privacy.setValue(false) }
                until("privacy restored") { !privateService.isPrivacyModeActive && PrivacyModeState.observeOrdinaryPersistence() != null }
                val staleService = startEditor(staleSelection)
                call("armSelection")
                requestCopy(); tapPrivateCopy()
                withTimeout(15_000) { while (!call("state").getBoolean("selectionEntered")) delay(25) }
                val staleJob = main {
                    TrimeInputMethodService::class.java.getDeclaredField("privateCopyJob").apply { isAccessible = true }.get(staleService) as Job
                }
                val oldOwner = main { staleService.editorToken }
                val host = startActivitySync(Intent(targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                replacement = host
                val field = main {
                    EditText(host).apply {
                        id = View.generateViewId()
                        setText("new-editor-sentinel")
                        host.setContentView(this)
                        requestFocus()
                        (host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
                    }
                }
                until("new editor owns input during selection read") { staleService.editorToken != oldOwner && staleService.currentInputEditorInfo?.fieldId == field.id }
                call("releaseSelection")
                withTimeout(15_000) { staleJob.join() }
                check(dao.find(staleSelection) == null && main { field.text.toString() == "new-editor-sentinel" }) {
                    "A stale private selection crossed an editor boundary"
                }
                check(events.get() == 0 && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == clipboardSentinel)
                sendStatus(0, Bundle().apply { putString("stream", "PASS: private copy rejects delayed privacy and editor boundaries\n") })
                generated.forEach { value -> dao.find(value)?.let { dao.delete(it.id) } }
            }
        } catch (error: Throwable) { failure = error }
        finally {
            try {
                call("releaseSelection")
                call("finish")
                main {
                    replacement?.finish()
                    restoreClipboard?.invoke()
                }
                runBlocking { restoreTheme?.invoke() }
                runBlocking {
                    val daoField = ClipboardHelper::class.java.getDeclaredField("clbDao").apply { isAccessible = true }
                    val dao = daoField.get(ClipboardHelper) as DatabaseDao
                    generated.forEach { value -> dao.find(value)?.let { dao.delete(it.id) } }
                }
            } catch (cleanup: Throwable) { if (failure == null) failure = cleanup else checkNotNull(failure).addSuppressed(cleanup) }
        }
        val result = Bundle().apply { putBoolean("passed", failure == null); putString("stream", failure?.stackTraceToString() ?: "PASS: private clipboard copy\n") }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
