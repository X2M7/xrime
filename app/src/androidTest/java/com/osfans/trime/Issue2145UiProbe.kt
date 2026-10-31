/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.core.view.children
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.runBlocking
import org.kodein.di.direct
import org.kodein.di.instance

/** Exercises the reporter's actual hint keyboard and Markdown action in a disposable editor. */
internal object Issue2145UiProbe {
    fun verify(instrumentation: Instrumentation, themeId: String) {
        fun <T> main(block: () -> T): T {
            var value: Result<T>? = null
            instrumentation.runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
        }
        fun input(): InputView? = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
            .filterIsInstance<InputView>().firstOrNull { it.isShown }
        fun waitUntil(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (main(condition)) return
                SystemClock.sleep(50)
            }
            error("Timed out waiting for $label")
        }
        val selected = ThemeManager.prefs.selectedTheme
        val original = selected.getValue()
        val hadPreference = selected.sharedPreferences.contains(selected.key)
        val originalRaw = selected.sharedPreferences.getString(selected.key, null)
        var activity: Activity? = null
        try {
            runBlocking {
                ThemeManager.init(instrumentation.targetContext.resources.configuration)
                check(ThemeManager.selectTheme(themeId) == themeId)
            }
            val sourceText = ThemeManager.activeTheme.resolveAction("md_fencedcodeblock_commit").text
            val sourceUnits = sourceText.map { "U+%04X".format(it.code) }.joinToString(" ")
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "Markdown source code units: $sourceUnits\n")
            })
            val host = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            activity = host
            val field = main {
                EditText(host).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    setSingleLine(false)
                    host.setContentView(this)
                    requestFocus()
                }
            }
            val imm = host.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            main { imm.showSoftInput(field, 0) }
            waitUntil("issue 2145 editor and IME") {
                input()?.di?.direct?.instance<TrimeInputMethodService>()?.currentInputEditorInfo?.fieldId == field.id
            }
            fun window() = checkNotNull(input()).di.direct.instance<KeyboardWindow>()
            main { window().switchKeyboard("five_strokes") }
            waitUntil("original hint keyboard") {
                window().currentKeyboard.keys.any { key -> key.hint.any(Char::isHighSurrogate) } &&
                    descendants(checkNotNull(input())).filterIsInstance<KeyboardView>().any { it.isShown }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: original supplementary-plane hint keyboard is visible\n") })
            main { window().switchKeyboard("markdown_Block") }
            waitUntil("original Markdown keyboard") {
                window().currentKeyboard.keys.any { it.getLabel() == "围栏代码块" } &&
                    RimeDaemon.readCached { statusCached.isAsciiMode } == true
            }
            main {
                val root = checkNotNull(input())
                val model = window().currentKeyboard
                val index = model.keys.indexOfFirst { it.getLabel() == "围栏代码块" }
                check(index >= 0)
                val view = descendants(root).filterIsInstance<KeyboardView>().single { it.isShown }
                val key = view.getChildAt(index) as KeyView
                check(key.isShown && key.width > 0 && key.height > 0)
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, key.width / 2f, key.height / 2f, 0)
                    try { check(key.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
            }
            try {
                waitUntil("fenced code block output") { field.text.toString() == "```\n\n```" }
            } catch (failure: IllegalStateException) {
                val observed = main {
                    "inputType=${field.inputType}, selection=${field.selectionStart}:${field.selectionEnd}, codeUnits=" +
                        field.text.toString().map { "U+%04X".format(it.code) }.joinToString(" ")
                }
                throw IllegalStateException("Fenced block output: $observed", failure)
            }
            // Text can arrive before the subsequent End/Up key events. Observe the
            // settled cursor, not an intermediate position between those events.
            SystemClock.sleep(1_500)
            try {
                waitUntil("fenced block cursor after both Up keys") { field.selectionStart == 3 && field.selectionEnd == 3 }
            } catch (failure: IllegalStateException) {
                val position = main { "${field.selectionStart}:${field.selectionEnd}" }
                throw IllegalStateException("Fenced block cursor: $position", failure)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: original Markdown fence commits exact backticks and newlines\n") })
        } finally {
            activity?.let { main { it.finish() } }
            runBlocking { ThemeManager.selectTheme(original) }
            val edit = selected.sharedPreferences.edit()
            if (hadPreference) edit.putString(selected.key, checkNotNull(originalRaw)) else edit.remove(selected.key)
            check(edit.commit())
        }
    }
}
