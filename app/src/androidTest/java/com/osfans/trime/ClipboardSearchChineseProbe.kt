/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.children
import com.osfans.trime.core.RimeApi
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.db.DatabaseDao
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.util.UUID

/** Exercises the visible keys and candidate bar against a real search session. */
internal object ClipboardSearchChineseProbe {
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
            if (root is ViewGroup) root.children.forEach { yieldAll(descendants(it)) }
        }
        suspend fun until(label: String, condition: () -> Boolean) {
            try {
                withTimeout(20_000) { while (!main(condition)) delay(25) }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Timed out waiting for $label", failure)
            }
        }
        suspend fun tap(view: View) {
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                main {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, view.width / 2f, view.height / 2f, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            api { }
        }
        val daoField = ClipboardHelper::class.java.getDeclaredField("clbDao").apply { isAccessible = true }
        val dao = daoField.get(ClipboardHelper) as DatabaseDao
        val inserted = mutableListOf<Int>()
        val before = main { editor.text.toString() }
        val returnPreference = AppPrefs.defaultInstance().clipboard.clipboardReturnAfterPaste
        val oldReturn = returnPreference.getValue()
        var oldSchema = ""
        var oldAscii = false
        api { oldSchema = selectedSchemaId(); oldAscii = statusCached.isAsciiMode }
        try {
            main { returnPreference.setValue(true) }
            for ((schema, keys, isT9) in listOf(
                Triple("luna_pinyin_simp", "nihao", false),
                Triple("luna_pinyin_t9", "64", true),
            )) {
                api { clearComposition(); check(selectSchema(schema)); setRuntimeOption("ascii_mode", false) }
                until("$schema keyboard") {
                    input()?.let { it.di.direct.instance<KeyboardWindow>().currentKeyboard.isT9Layout == isT9 } == true
                }
                main {
                    val view = checkNotNull(input())
                    view.di.direct.instance<BoardWindowManager>().attachWindow(ClipboardWindow(view.di))
                }
                until("$schema search button") {
                    input()?.let { root -> descendants(root).any { it.isShown && it.contentDescription == service.getString(R.string.clipboard_search) } } == true
                }
                main {
                    val root = checkNotNull(input())
                    check(descendants(root).first { it.isShown && it.contentDescription == service.getString(R.string.clipboard_search) }.performClick())
                }
                until("$schema search editor") {
                    service.isClipboardSearchEditing && input()?.let {
                        it.di.direct.instance<KeyboardWindow>().currentKeyboard.isT9Layout == isT9 &&
                            descendants(it).filterIsInstance<KeyboardView>().any { keyboard -> keyboard.isShown && keyboard.width > 0 }
                    } == true
                }
                for (character in keys) {
                    val code = if (isT9) KeyEvent.KEYCODE_0 + (character - '0') else KeyEvent.KEYCODE_A + (character - 'a')
                    val key = main {
                        val root = checkNotNull(input())
                        val keyboard = descendants(root).filterIsInstance<KeyboardView>().first { it.isShown }
                        val index = root.di.direct.instance<KeyboardWindow>().currentKeyboard.keys.indexOfFirst { it.click?.code == code }
                        check(index >= 0) { "$schema missing input key $code" }
                        keyboard.children.filterIsInstance<KeyView>().toList()[index]
                    }
                    tap(key)
                }
                api { check(getRawInput() == keys) { "$schema did not receive its actual key sequence" } }
                check(main { editor.text.toString() == before }) { "$schema preedit reached the application editor" }
                until("$schema Chinese candidate") {
                    input()?.di?.direct?.instance<CompactCandidateDelegate>()?.view?.let { list ->
                        (list.findViewHolderForAdapterPosition(0) as? CandidateViewHolder)?.let { holder ->
                            holder.itemView.isShown && holder.text.any { it in '\u4e00'..'\u9fff' }
                        }
                    } == true
                }
                val (candidate, candidateView) = main {
                    val list = checkNotNull(input()).di.direct.instance<CompactCandidateDelegate>().view
                    val holder = list.findViewHolderForAdapterPosition(0) as CandidateViewHolder
                    holder.text to holder.itemView
                }
                tap(candidateView)
                until("$schema selected candidate in search query") {
                    input()?.let { root -> descendants(root).filterIsInstance<TextView>().any {
                        it.isShown && it.hint?.toString() == service.getString(R.string.clipboard_search_hint) && it.text.toString() == candidate
                    } } == true
                }
                check(main { editor.text.toString() == before }) { "$schema candidate reached the application editor" }
                val unique = UUID.randomUUID().toString()
                val matching = "match-$candidate-$unique"
                val unrelated = "unrelated-$unique"
                inserted += dao.insert(DatabaseBean(text = matching, pinned = true)).toInt()
                inserted += dao.insert(DatabaseBean(text = unrelated, pinned = true)).toInt()
                main {
                    val root = checkNotNull(input())
                    check(descendants(root).filterIsInstance<TextView>().first {
                        it.isShown && it.text.toString() == service.getString(R.string.clipboard_search_results)
                    }.performClick())
                }
                until("$schema filtered result") {
                    input()?.let { root -> descendants(root).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == matching } } == true
                }
                check(main { descendants(checkNotNull(input())).filterIsInstance<TextView>().none { it.isShown && it.text.toString() == unrelated } }) {
                    "$schema search included a nonmatching row"
                }
                check(main { editor.text.toString() == before }) { "$schema search results changed the application editor" }
                main {
                    var row: View = descendants(checkNotNull(input())).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == matching }
                    while (!row.isClickable) row = row.parent as? View ?: error("Search result has no clickable row")
                    check(row.performClick())
                }
                until("$schema result paste") { editor.text.toString() == before + matching }
                main { editor.text.replace(0, editor.text.length, before); editor.setSelection(editor.text.length) }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: $schema search keys, Chinese candidate, filtering and editor isolation\n") })
            }
        } finally {
            val oldEditor = main { service.editorToken }
            main {
                returnPreference.setValue(oldReturn)
                // A restart disposes a partially completed search before restoring the schema.
                (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor)
                editor.text.replace(0, editor.text.length, before)
                editor.setSelection(editor.text.length)
            }
            until("restored editor after Chinese search") {
                service.editorToken != oldEditor && service.currentInputEditorInfo?.fieldId == editor.id && input() != null
            }
            inserted.forEach { ClipboardHelper.delete(it) }
            api { clearComposition(); if (oldSchema.isNotEmpty()) check(selectSchema(oldSchema)); setRuntimeOption("ascii_mode", oldAscii) }
        }
    }
}
