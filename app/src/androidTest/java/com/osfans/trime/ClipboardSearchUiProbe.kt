/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.TextView
import com.osfans.trime.core.RimeApi
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.db.DatabaseDao
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.clipboard.ClipboardLayout
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.InputTabLayout
import com.osfans.trime.ime.core.PrivacyModeState
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.util.UUID

/** Uses a disposable Room row and the visible search controls against a real editor. */
internal object ClipboardSearchUiProbe {
    suspend fun verify(
        instrumentation: Instrumentation,
        service: TrimeInputMethodService,
        editor: EditText,
        input: () -> InputView?,
        api: suspend (suspend RimeApi.() -> Unit) -> Unit,
    ) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        fun descendants(root: View): Sequence<View> = sequence {
            yield(root)
            if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
        }
        suspend fun until(block: () -> Boolean) = withTimeout(20_000) {
            while (!main(block)) delay(25)
        }
        val daoField = ClipboardHelper::class.java.getDeclaredField("clbDao").apply { isAccessible = true }
        val dao = daoField.get(ClipboardHelper) as DatabaseDao
        val query = "zxq" + UUID.randomUUID().toString().replace("-", "").take(6)
        val content = "search-$query-result"
        val rowId = dao.insert(DatabaseBean(text = content, pinned = true)).toInt()
        val before = main { editor.text.toString() }
        var oldAscii = false
        val heightPreference = AppPrefs.defaultInstance().keyboard.keyboardHeight
        val oldHeight = heightPreference.getValue()
        val privacyPreference = AppPrefs.defaultInstance().general.manualPrivacyMode
        val oldPrivacy = privacyPreference.getValue()
        val returnPreference = AppPrefs.defaultInstance().clipboard.clipboardReturnAfterPaste
        val oldReturn = returnPreference.getValue()
        var checkedNarrowTitle = false
        suspend fun startQuery() {
            main {
                val view = checkNotNull(input())
                view.di.direct.instance<BoardWindowManager>().attachWindow(ClipboardWindow(view.di))
            }
            until {
                input()?.let { root -> descendants(root).any { it.isShown && it.contentDescription == service.getString(R.string.clipboard_search) } } == true
            }
            if (!checkedNarrowTitle) {
                main {
                    val toggle = descendants(checkNotNull(input())).filterIsInstance<ToolButton>().first {
                        it.isShown && it.accessibilityLabel == service.getString(R.string.clipboard_return_after_paste)
                    }
                    val title = toggle.parent as ViewGroup
                    val originalWidth = title.width
                    val originalHeight = title.height
                    check(originalWidth > 0 && originalHeight > 0)
                    val narrowWidth = (240 * title.resources.displayMetrics.density).toInt()
                    try {
                        title.measure(View.MeasureSpec.makeMeasureSpec(narrowWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(originalHeight, View.MeasureSpec.EXACTLY))
                        title.layout(0, 0, narrowWidth, originalHeight)
                        val visible = (0 until title.childCount).map(title::getChildAt).filter { it.visibility == View.VISIBLE }.sortedBy { it.left }
                        check(visible.any { it is InputTabLayout && it.width > 0 }) { "Narrow title lost its tabs" }
                        check(visible.all { it.left >= 0 && it.right <= narrowWidth && it.width > 0 }) { "Narrow title clipped a control" }
                        check(visible.zipWithNext().all { (left, right) -> left.right <= right.left }) { "Narrow title controls overlap" }
                    } finally {
                        title.measure(View.MeasureSpec.makeMeasureSpec(originalWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(originalHeight, View.MeasureSpec.EXACTLY))
                        title.requestLayout()
                    }
                }
                main {
                    val toggle = descendants(checkNotNull(input())).filterIsInstance<ToolButton>().first {
                        it.isShown && it.accessibilityLabel == service.getString(R.string.clipboard_return_after_paste)
                    }
                    check(toggle.performClick())
                    check(!returnPreference.getValue())
                    returnPreference.setValue(true)
                }
                until {
                    input()?.let { root -> descendants(root).filterIsInstance<ToolButton>().any {
                        it.isShown && it.accessibilityLabel == service.getString(R.string.clipboard_return_after_paste) && it.accessibilityChecked == true
                    } } == true
                }
                checkedNarrowTitle = true
            }
            main {
                val root = checkNotNull(input())
                val button = descendants(root).first { it.isShown && it.contentDescription == service.getString(R.string.clipboard_search) }
                check(button.performClick())
            }
            until { service.isClipboardSearchEditing }
            main { checkNotNull(input()).di.direct.instance<CommonKeyboardActionListener>().listener.onText(query) }
            until {
                input()?.let { root -> descendants(root).filterIsInstance<TextView>().any {
                    it.isShown && it.hint?.toString() == service.getString(R.string.clipboard_search_hint) && it.text.toString() == query
                } } == true
            }
            check(main { editor.text.toString() == before }) { "Search query reached the application editor" }
        }
        suspend fun showResults() {
            main {
                val root = checkNotNull(input())
                check(descendants(root).filterIsInstance<TextView>().first {
                    it.isShown && it.text.toString() == service.getString(R.string.clipboard_search_results)
                }.performClick())
            }
            until {
                input()?.let { root -> descendants(root).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == content } } == true
            }
        }
        fun clickResult() = main {
            val root = checkNotNull(input())
            var row: View = descendants(root).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == content }
            while (!row.isClickable) row = row.parent as? View ?: error("Clipboard result has no clickable row")
            check(row.performClick())
        }
        fun returnToggle(): ToolButton = descendants(checkNotNull(input())).filterIsInstance<ToolButton>().first {
            it.isShown && it.accessibilityLabel == service.getString(R.string.clipboard_return_after_paste)
        }
        try {
            main { returnPreference.setValue(true) }
            api { clearComposition(); oldAscii = statusCached.isAsciiMode; setRuntimeOption("ascii_mode", true) }
            val staleWindow = main {
                val view = checkNotNull(input())
                ClipboardWindow(view.di).also { view.di.direct.instance<BoardWindowManager>().attachWindow(it) }
            }
            val staleLayout = main {
                ClipboardWindow::class.java.getDeclaredField("clipboardLayout").apply { isAccessible = true }.get(staleWindow) as ClipboardLayout
            }
            until { input()?.let { root -> descendants(root).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == content } } == true }
            val staleOrdinaryRow = main {
                var row: View = descendants(checkNotNull(input())).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == content }
                while (!row.isClickable) row = row.parent as? View ?: error("Clipboard row has no clickable ancestor")
                row
            }
            main { check(staleLayout.titleUi.deleteAllButton.performClick()) }
            val dialog = main {
                ClipboardWindow::class.java.getDeclaredField("deleteAllDialog").apply { isAccessible = true }.get(staleWindow) as AlertDialog
            }
            val staleConfirm = main { check(dialog.isShowing); dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE) }
            val oldOwner = main { service.editorToken }
            main { (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor) }
            until { service.editorToken != oldOwner && !dialog.isShowing }
            main { staleConfirm.performClick(); staleLayout.titleUi.searchButton.performClick(); staleOrdinaryRow.performClick() }
            delay(250)
            check(dao.get(rowId) != null && !main { service.isClipboardSearchEditing } && main { editor.text.toString() == before }) {
                "A stale clipboard window performed an action on a new editor"
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: stale clipboard search and delete confirmation cannot affect a new editor\n") })
            startQuery()
            showResults()
            main {
                check(returnToggle().accessibilityChecked == true)
                check(returnToggle().performClick())
                check(!returnPreference.getValue())
            }
            until { returnToggle().accessibilityChecked == false }
            main { returnPreference.setValue(true) }
            until { returnToggle().accessibilityChecked == true }
            clickResult()
            until { editor.text.toString() == before + content }
            until { input()?.let { view -> view.di.direct.instance<BoardWindowManager>().isAttached(view.di.direct.instance<KeyboardWindow>()) } == true }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: clipboard search isolates query and returns to keyboard after paste\n") })

            main { editor.text.replace(0, editor.text.length, before); editor.setSelection(editor.text.length); returnPreference.setValue(true) }
            api { clearComposition(); setRuntimeOption("ascii_mode", true) }
            startQuery()
            showResults()
            main { check(returnToggle().performClick()); check(!returnPreference.getValue()) }
            clickResult()
            until { editor.text.toString() == before + content }
            until {
                input()?.let { root -> descendants(root).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == service.getString(R.string.collection) } } == true
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: search paste can return to the ordinary clipboard list\n") })

            main { editor.text.replace(0, editor.text.length, before); editor.setSelection(editor.text.length); returnPreference.setValue(true) }
            api { clearComposition(); setRuntimeOption("ascii_mode", true) }
            startQuery()
            showResults()
            main { service.requestHideSelf(0) }
            until { !service.isInputViewShown }
            main { (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, 0) }
            until { service.isInputViewShown && input() != null }
            check(main { input()?.let { root -> descendants(root).none { it is TextView && it.isShown && it.text.toString() == content } } == true }) {
                "Hidden search results returned after showing the keyboard"
            }

            api { clearComposition(); setRuntimeOption("ascii_mode", true) }
            startQuery()
            main {
                val connection = checkNotNull(service.currentInputConnection)
                service.updateComposingText("ab", 1)
                val localBefore = checkNotNull(connection.getExtractedText(null, 0))
                service.onUpdateSelection(0, 0, 0, 0, 0, 2)
                val localAfter = checkNotNull(connection.getExtractedText(null, 0))
                check(localAfter.text.toString() == localBefore.text.toString() && localAfter.selectionStart == localBefore.selectionStart &&
                    localAfter.selectionEnd == localBefore.selectionEnd) { "Physical selection changed the search editor" }
                service.updateComposingText("")
            }
            val heldEntered = CompletableDeferred<Unit>()
            val heldRelease = CompletableDeferred<Unit>()
            try {
                main {
                    service.postRimeJob {
                        heldEntered.complete(Unit)
                        heldRelease.await()
                        service.commitText("stale-search-write")
                    }
                }
                withTimeout(10_000) { heldEntered.await() }
                val oldView = main { checkNotNull(input()) }
                main { heightPreference.setValue(if (oldHeight == 0) 1 else 0) }
                until { input() != null && input() !== oldView && !service.isClipboardSearchEditing }
            } finally {
                heldRelease.complete(Unit)
            }
            api { }
            check(main { editor.text.toString() == before }) { "View rebuild committed the search query or stale work" }
            main { heightPreference.setValue(oldHeight) }
            until { input() != null && !service.isClipboardSearchEditing }

            api { clearComposition(); setRuntimeOption("ascii_mode", true) }
            startQuery()
            showResults()
            val staleRow = main {
                var row: View = descendants(checkNotNull(input())).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == content }
                while (!row.isClickable) row = row.parent as? View ?: error("Clipboard result has no clickable row")
                row
            }
            val oldEditor = main { service.editorToken }
            main { (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor) }
            until { service.editorToken != oldEditor && input() != null }
            main { staleRow.performClick() }
            check(main { editor.text.toString() == before }) { "Stale search row wrote after editor restart" }
            check(main { input()?.let { root -> descendants(root).none { it is TextView && it.isShown && it.text.toString() == content } } == true }) {
                "Search results remained visible after editor restart"
            }

            api { clearComposition(); setRuntimeOption("ascii_mode", true) }
            startQuery()
            showResults()
            main { privacyPreference.setValue(true) }
            until { service.isPrivacyModeActive }
            check(main { input()?.let { root -> descendants(root).none { it is TextView && it.isShown && it.text.toString() == content } } == true }) {
                "Search results remained visible after entering privacy mode"
            }
            main { privacyPreference.setValue(oldPrivacy) }
            until { !service.isPrivacyModeActive && !PrivacyModeState.active }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: search is cleared on hide, rebuild and privacy boundaries\n") })

            val rejectCommits = java.util.concurrent.atomic.AtomicBoolean(true)
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val parent = main { editor.parent as ViewGroup }
            val rejectingEditor = main {
                object : EditText(editor.context) {
                    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                        val base = super.onCreateInputConnection(outAttrs) ?: return null
                        return object : InputConnectionWrapper(base, false) {
                            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                                attempts.incrementAndGet()
                                return if (rejectCommits.get()) false else super.commitText(text, newCursorPosition)
                            }
                        }
                    }
                }.apply {
                    id = View.generateViewId()
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    parent.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72))
                    requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
                }
            }
            try {
                until { service.currentInputEditorInfo?.fieldId == rejectingEditor.id && input() != null }
                api { clearComposition(); setRuntimeOption("ascii_mode", true) }
                startQuery()
                showResults()
                val beforeAttempt = attempts.get()
                clickResult()
                until { attempts.get() > beforeAttempt }
                check(main { rejectingEditor.text.isEmpty() }) { "Rejected paste changed the editor" }
                // Android's remote InputConnection reports dispatch, not the editor's Boolean.
                // Preserve that platform behavior as explicit evidence rather than pretending
                // that the IME can detect the app-side rejection.
                until { input()?.let { view -> view.di.direct.instance<BoardWindowManager>().isAttached(view.di.direct.instance<KeyboardWindow>()) } == true }
                check(attempts.get() == beforeAttempt + 1) { "Remote rejection caused a duplicate dispatch" }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: remote editor rejection is not acknowledged to the IME; dispatch occurred once\n") })
                rejectCommits.set(false)
                api { clearComposition(); setRuntimeOption("ascii_mode", true) }
                startQuery()
                showResults()
                clickResult()
                until { rejectingEditor.text.toString() == content }
                check(attempts.get() == beforeAttempt + 2) { "Successful retry was dispatched more than once" }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: user can search again after a remote editor rejects a dispatched paste\n") })
            } finally {
                main {
                    parent.removeView(rejectingEditor)
                    editor.requestFocus()
                    (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, 0)
                }
                until { service.currentInputEditorInfo?.fieldId == editor.id && input() != null }
            }
        } finally {
            main {
                heightPreference.setValue(oldHeight)
                privacyPreference.setValue(oldPrivacy)
                returnPreference.setValue(oldReturn)
                input()?.di?.direct?.instance<BoardWindowManager>()?.attachWindow(KeyboardWindow)
                editor.text.replace(0, editor.text.length, before)
                editor.setSelection(editor.text.length)
            }
            ClipboardHelper.delete(rowId)
            api { clearComposition(); setRuntimeOption("ascii_mode", oldAscii) }
        }
        ClipboardSearchChineseProbe.verify(instrumentation, service, editor, input, api)
    }
}
