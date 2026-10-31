/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.clipboard.ClipboardLayout
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.util.UUID
import java.io.File

/** Exercises the built-in text editor using actual keyboard views and an external text field. */
internal object TextEditorPanelProbe {
    @RequiresApi(29)
    suspend fun verify(instrumentation: Instrumentation, service: TrimeInputMethodService, editor: EditText, input: () -> InputView?) {
        val keyEvents = mutableListOf<String>()
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        suspend fun until(label: String, condition: () -> Boolean) {
            try { withTimeout(30_000) { while (!main(condition)) delay(25) } }
            catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                val diagnostic = main {
                    "focused=${editor.hasFocus()} selection=${editor.selectionStart}:${editor.selectionEnd} events=$keyEvents"
                }
                throw IllegalStateException("Timed out waiting for $label; $diagnostic", failure)
            }
        }
        fun window() = checkNotNull(input()).di.direct.instance<KeyboardWindow>()
        fun keyboardId() = KeyboardWindow::class.java.getDeclaredField("currentKeyboardId")
            .apply { isAccessible = true }.get(window()) as String
        fun keyboardSignature(keyboard: com.osfans.trime.ime.keyboard.Keyboard): List<Any> = listOf(
            keyboard.isT9Layout,
            keyboard.keyboardHeight,
            keyboard.keys.map { key ->
                listOf(
                    key.x, key.y, key.width, key.height, key.gap, key.row, key.column,
                    key.keyActions.entries.sortedBy { it.key.name }.map { (behavior, action) ->
                        listOf(
                            behavior.name, action.code, action.modifier, action.text, action.command,
                            action.option, action.select, action.toggle, action.commit, action.shiftLock,
                            action.isFunctional, action.isRepeatable, action.isSticky,
                            action.isSlideCursor, action.isSlideDelete,
                        )
                    },
                )
            },
        )
        val prefs = AppPrefs.defaultInstance().clipboard.clipboardListening
        val oldHistory = prefs.getValue()
        val hook = AppPrefs.defaultInstance().keyboard.hookCtrlCV
        val oldHook = hook.getValue()
        val hadHook = hook.sharedPreferences.contains(hook.key)
        val clipboard = editor.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val oldClip = clipboard.primaryClip
        val oldText = main { editor.text.toString() }
        val oldStart = main { editor.selectionStart }
        val oldEnd = main { editor.selectionEnd }
        val synthetic = "panel" + UUID.randomUUID().toString().replace("-", "").take(12)
        try {
            main {
                prefs.setValue(false)
                hook.setValue(false)
                editor.setOnKeyListener { _, keyCode, event ->
                    if ((keyCode == KeyEvent.KEYCODE_C || keyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
                            keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) && keyEvents.size < 48
                    ) {
                        keyEvents.add(
                            "code=$keyCode action=${event.action} meta=${event.metaState} " +
                                "focused=${editor.hasFocus()} selection=${editor.selectionStart}:${editor.selectionEnd}",
                        )
                    }
                    false
                }
            }
            val previousOwner = main { service.editorToken }
            main { editor.setText(synthetic); editor.setSelection(synthetic.length) }
            until("editor text restart") { service.editorToken != previousOwner && service.currentInputEditorInfo?.fieldId == editor.id }
            val staleWindow = main {
                val root = checkNotNull(input())
                ClipboardWindow(root.di).also { root.di.direct.instance<BoardWindowManager>().attachWindow(it) }
            }
            val originalKeyboard = main { window().currentKeyboard }
            val staleMenu = main {
                val layout = ClipboardWindow::class.java.getDeclaredField("clipboardLayout").apply { isAccessible = true }.get(staleWindow) as ClipboardLayout
                check(layout.titleUi.moreActionsButton.performClick())
                ClipboardWindow::class.java.getDeclaredField("moreActionsMenu").apply { isAccessible = true }.get(staleWindow) as android.widget.PopupMenu
            }
            main {
                check(staleMenu.menu.getItem(0).title.toString() == service.getString(R.string.clipboard_text_editor))
                staleWindow.onCreateView()
                staleMenu.menu.performIdentifierAction(staleMenu.menu.getItem(0).itemId, 0)
            }
            delay(250)
            check(main { checkNotNull(input()).di.direct.instance<BoardWindowManager>().isAttached(staleWindow) && window().currentKeyboard === originalKeyboard }) {
                "A stale clipboard menu changed the keyboard after its view was rebuilt"
            }
            main { checkNotNull(input()).di.direct.instance<BoardWindowManager>().attachWindow(KeyboardWindow) }
            suspend fun enterPanel(): com.osfans.trime.ime.keyboard.Keyboard {
                val original = main { window().currentKeyboard }
                val originalId = main { keyboardId() }
                val originalSignature = main { keyboardSignature(original) }
                val beforeAscii = RimeDaemon.readCached { statusCached.isAsciiMode }
                val root = main { checkNotNull(input()) }
                val clipboardWindow = ClipboardWindow(root.di)
                main { root.di.direct.instance<BoardWindowManager>().attachWindow(clipboardWindow) }
                val layout = main {
                    ClipboardWindow::class.java.getDeclaredField("clipboardLayout").apply { isAccessible = true }.get(clipboardWindow) as ClipboardLayout
                }
                main { check(layout.titleUi.moreActionsButton.performClick()) }
                until("editing panel menu") {
                    WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants).filterIsInstance<TextView>().any {
                        it.isShown && it.text.toString() == service.getString(R.string.clipboard_text_editor)
                    }
                }
                val touches = mutableListOf<Int>()
                lateinit var targetRow: View
                var targetBounds = Rect()
                var originalViewGeneration = 0L
                val position = main {
                    val text = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants).filterIsInstance<TextView>().first {
                        it.isShown && it.text.toString() == service.getString(R.string.clipboard_text_editor)
                    }
                    var row: View = text
                    while (!row.isClickable) row = row.parent as? View ?: error("Editing menu row is not clickable")
                    targetRow = row
                    row.setOnTouchListener { _, event -> touches.add(event.actionMasked); false }
                    targetBounds = Rect().also { row.getGlobalVisibleRect(it) }
                    originalViewGeneration = ClipboardWindow::class.java.getDeclaredField("viewGeneration").apply { isAccessible = true }.getLong(clipboardWindow)
                    val xy = IntArray(2).also(text::getLocationOnScreen)
                    (xy[0] + text.width / 2f) to (xy[1] + text.height / 2f)
                }
                try {
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, position.first, position.second, 0)
                        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    }
                    until("editing panel keys") {
                        val keyboard = window().currentKeyboard
                        keyboard !== original && keyboard.keys.any { it.getAction(KeyBehavior.CLICK)?.select == ".return" } &&
                            descendants(checkNotNull(input())).filterIsInstance<KeyboardView>().any { it.isShown }
                    }
                } catch (failure: IllegalStateException) {
                    val screenshot = File(service.cacheDir, "text-editor-panel-timeout.png")
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        try { screenshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
                    }
                    val diagnostic = main {
                        val generation = ClipboardWindow::class.java.getDeclaredField("viewGeneration").apply { isAccessible = true }.getLong(clipboardWindow)
                        val attached = root.di.direct.instance<BoardWindowManager>().isAttached(clipboardWindow)
                        val windows = WindowInspector.getGlobalWindowViews().map { view ->
                            val bounds = Rect().also { view.getGlobalVisibleRect(it) }
                            "${view.javaClass.simpleName}:shown=${view.isShown}:bounds=$bounds"
                        }
                        "point=$position,rowBounds=$targetBounds,rowShown=${targetRow.isShown},touches=$touches," +
                            "attached=$attached,viewGeneration=$originalViewGeneration->$generation,editorFocus=${editor.hasFocus()}," +
                            "inputShown=${root.isShown},windows=$windows,screenshot=${screenshot.name}"
                    }
                    throw IllegalStateException("Editing menu input diagnostic: $diagnostic", failure)
                } finally {
                    main { targetRow.setOnTouchListener(null) }
                }
                fun click(predicate: (com.osfans.trime.ime.keyboard.KeyAction) -> Boolean) = main {
                    val keyboard = window().currentKeyboard
                    val index = keyboard.keys.indexOfFirst { it.getAction(KeyBehavior.CLICK)?.let(predicate) == true }
                    check(index >= 0)
                    val key = descendants(checkNotNull(input())).filterIsInstance<KeyboardView>().first { it.isShown }.getChildAt(index) as KeyView
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, key.width / 2f, key.height / 2f, 0)
                        try { check(key.dispatchTouchEvent(event)) } finally { event.recycle() }
                    }
                }
                main { clipboard.setPrimaryClip(ClipData.newPlainText("panel-fixture", "unrelated")) }
                click { it.code == KeyEvent.KEYCODE_A && it.modifier and KeyEvent.META_CTRL_ON != 0 }
                until("select-all key") { editor.selectionStart == 0 && editor.selectionEnd == synthetic.length }
                click { it.code == KeyEvent.KEYCODE_C && it.modifier and KeyEvent.META_CTRL_ON != 0 }
                until("copy key") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == synthetic }
                click { it.code == KeyEvent.KEYCODE_A && it.modifier and KeyEvent.META_CTRL_ON != 0 }
                until("select-all before cut") { editor.selectionStart == 0 && editor.selectionEnd == synthetic.length }
                click { it.code == KeyEvent.KEYCODE_X && it.modifier and KeyEvent.META_CTRL_ON != 0 }
                until("cut key") { editor.text.isEmpty() }
                click { it.code == KeyEvent.KEYCODE_V && it.modifier and KeyEvent.META_CTRL_ON != 0 }
                until("paste key") { editor.text.toString() == synthetic && editor.selectionStart == synthetic.length }
                click { it.code == KeyEvent.KEYCODE_DPAD_LEFT }
                until("left key") { editor.selectionStart == synthetic.length - 1 }
                click { it.code == KeyEvent.KEYCODE_DPAD_RIGHT }
                until("right key") { editor.selectionStart == synthetic.length }
                check(main {
                    val keys = window().currentKeyboard.keys
                    keys.any { it.getAction(KeyBehavior.CLICK)?.code == KeyEvent.KEYCODE_DPAD_UP } &&
                        keys.any { it.getAction(KeyBehavior.CLICK)?.code == KeyEvent.KEYCODE_DPAD_DOWN } &&
                        keys.any { it.getAction(KeyBehavior.CLICK)?.command == "clipboard_window" }
                })
                click { it.code == KeyEvent.KEYCODE_DPAD_UP }
                click { it.code == KeyEvent.KEYCODE_DPAD_DOWN }
                delay(250)
                check(main { editor.text.toString() == synthetic })
                click { it.select == ".return" }
                try {
                    until("editing panel restores original keyboard and language") {
                        keyboardId() == originalId && keyboardSignature(window().currentKeyboard) == originalSignature &&
                            RimeDaemon.readCached { statusCached.isAsciiMode } == beforeAscii
                    }
                } catch (failure: IllegalStateException) {
                    val details = main {
                        val current = window().currentKeyboard
                        "originalId=$originalId, currentId=${keyboardId()}, original=${System.identityHashCode(original)}, current=${System.identityHashCode(current)}, " +
                            "originalT9=${original.isT9Layout}, currentT9=${current.isT9Layout}, " +
                            "originalHeight=${original.keyboardHeight}, currentHeight=${current.keyboardHeight}, " +
                            "originalKeys=${original.keys.size}, currentKeys=${current.keys.size}, " +
                            "layoutMatches=${keyboardSignature(current) == originalSignature}, beforeAscii=$beforeAscii, " +
                            "currentAscii=${RimeDaemon.readCached { statusCached.isAsciiMode }}"
                    }
                    throw IllegalStateException("Panel return state: $details", failure)
                }
                return original
            }
            check(main { window().currentKeyboard.isT9Layout })
            enterPanel()
            main { window().switchKeyboard("default") }
            until("26-key layout") { !window().currentKeyboard.isT9Layout }
            enterPanel()
            main { window().switchKeyboard("luna_pinyin_t9") }
            until("T9 layout restored") { window().currentKeyboard.isT9Layout }
            main { hook.setValue(true) }
            enterPanel()
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: default theme editing panel actions, hook modes and T9/26 return\n") })
        } finally {
            val owner = main { service.editorToken }
            main {
                editor.setOnKeyListener(null)
                hook.setValue(oldHook)
                if (!hadHook) check(hook.sharedPreferences.edit().remove(hook.key).commit())
                prefs.setValue(false)
                if (oldClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(oldClip)
                prefs.setValue(oldHistory)
                editor.setText(oldText)
                editor.setSelection(oldStart.coerceIn(0, editor.length()), oldEnd.coerceIn(0, editor.length()))
            }
            until("original editor restored") { service.editorToken != owner && service.currentInputEditorInfo?.fieldId == editor.id }
            main { window().switchKeyboard("luna_pinyin_t9") }
            until("original T9 keyboard restored") { window().currentKeyboard.isT9Layout }
        }
    }
}
