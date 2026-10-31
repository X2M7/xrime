/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.ClipboardRepeatState
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.ClipboardShortcutDiagnostics
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.PrivacyModeState
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Uses an isolated emulator's real clipboard, history page, editor and input connection. */
internal object ClipboardRepeatProbe {
    suspend fun verify(instrumentation: Instrumentation, service: TrimeInputMethodService, editor: EditText, input: () -> InputView?) {
        val imeEvents = mutableListOf<String>()
        val editorEvents = mutableListOf<String>()
        fun <T> main(block: () -> T): T {
            var value: Result<T>? = null
            instrumentation.runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        suspend fun until(label: String, condition: () -> Boolean) {
            try { withTimeout(20_000) { while (!main(condition)) delay(25) } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                val diagnostic = main { "focused=${editor.hasFocus()}, imeEvents=$imeEvents, editorEvents=$editorEvents" }
                throw IllegalStateException("Timed out waiting for $label; $diagnostic", error)
            }
        }
        val prefs = AppPrefs.defaultInstance()
        val oldListening = prefs.clipboard.clipboardListening.getValue()
        val oldRepeat = prefs.clipboard.clipboardRepeatSelection.getValue()
        val oldLimit = prefs.clipboard.clipboardLimit.getValue()
        val oldMeta = prefs.clipboard.clipboardMetaV.getValue()
        val oldHook = prefs.keyboard.hookCtrlCV.getValue()
        val hadListening = prefs.clipboard.clipboardListening.sharedPreferences.contains(prefs.clipboard.clipboardListening.key)
        val hadRepeat = prefs.clipboard.clipboardRepeatSelection.sharedPreferences.contains(prefs.clipboard.clipboardRepeatSelection.key)
        val hadLimit = prefs.clipboard.clipboardLimit.sharedPreferences.contains(prefs.clipboard.clipboardLimit.key)
        val hadMeta = prefs.clipboard.clipboardMetaV.sharedPreferences.contains(prefs.clipboard.clipboardMetaV.key)
        val hadHook = prefs.keyboard.hookCtrlCV.sharedPreferences.contains(prefs.keyboard.hookCtrlCV.key)
        val clipboard = editor.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val oldClip = clipboard.primaryClip
        val oldText = main { editor.text.toString() }
        val oldStart = main { editor.selectionStart }
        val oldEnd = main { editor.selectionEnd }
        var callbackCount = 0
        var expectedCopy: String? = null
        val callbackTrace = mutableListOf<String>()
        val eventListener = ClipboardManager.OnPrimaryClipChangedListener {
            callbackCount++
            val clip = clipboard.primaryClip
            val timestamp = if (android.os.Build.VERSION.SDK_INT >= 26) clip?.description?.timestamp else null
            val matches = clip?.getItemAt(0)?.text?.toString() == expectedCopy
            val identity = ClipboardHelper::class.java.getDeclaredField("lastRepeatIdentity").apply { isAccessible = true }.get(ClipboardHelper)
            val event = identity?.javaClass?.getDeclaredField("event")?.apply { isAccessible = true }?.get(identity)
            callbackTrace.add("matches=$matches,timestamp=$timestamp,generation=${ClipboardHelper.repeatEvent()},identityEvent=$event")
        }
        val ids = mutableSetOf<Int>()
        val prefix = "repeat-" + UUID.randomUUID().toString().take(8)
        val first = "$prefix-first"
        val second = "$prefix-second"
        try {
            val owner = main { service.editorToken }
            main {
                editor.setText("")
                prefs.clipboard.clipboardLimit.setValue(1000)
                prefs.clipboard.clipboardListening.setValue(true)
                prefs.clipboard.clipboardRepeatSelection.setValue(true)
                prefs.clipboard.clipboardMetaV.setValue(true)
                prefs.keyboard.hookCtrlCV.setValue(true)
                clipboard.addPrimaryClipChangedListener(eventListener)
                ClipboardShortcutDiagnostics.observer = { code, action, meta ->
                    if (imeEvents.size < 32) imeEvents.add("code=$code,action=$action,meta=$meta")
                }
                editor.setOnKeyListener { _, code, event ->
                    if (code in setOf(KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
                            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT) && editorEvents.size < 32
                    ) editorEvents.add("code=$code,action=${event.action},meta=${event.metaState}")
                    false
                }
            }
            until("repeat fixture editor ready") {
                val nativeBound = TrimeInputMethodService::class.java.getDeclaredField("nativeBoundEditorToken").apply { isAccessible = true }.getLong(service)
                service.editorToken != owner && service.currentInputEditorInfo?.fieldId == editor.id &&
                    nativeBound == service.editorToken && PrivacyModeState.observeOrdinaryPersistence() != null
            }
            var copyNumber = 0
            suspend fun copy(text: String): ClipboardRepeatState.Selection {
                copyNumber++
                val beforeEvent = ClipboardHelper.repeatEvent()
                val beforeCallbacks = main { callbackCount }
                main { expectedCopy = text; callbackTrace.clear() }
                main { clipboard.setPrimaryClip(ClipData.newPlainText("repeat fixture", text)) }
                var selection: ClipboardRepeatState.Selection? = null
                try {
                    until("ordinary copy $copyNumber captured") {
                        val current = ClipboardHelper.repeatSnapshot() as? ClipboardRepeatState.Snapshot.Chosen
                        selection = current?.selection
                        current != null && current.selection.id !in ids
                    }
                } catch (failure: IllegalStateException) {
                    val info = main {
                        val registered = ClipboardHelper::class.java.getDeclaredField("listenerRegistered").apply { isAccessible = true }.getBoolean(ClipboardHelper)
                        val repeatState = ClipboardHelper::class.java.getDeclaredField("repeatState").apply { isAccessible = true }.get(ClipboardHelper)
                        fun field(name: String): Any? = repeatState.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(repeatState)
                        val identity = ClipboardHelper::class.java.getDeclaredField("lastRepeatIdentity").apply { isAccessible = true }.get(ClipboardHelper)
                        val identityEvent = identity?.javaClass?.getDeclaredField("event")?.apply { isAccessible = true }?.get(identity)
                        "copy=$copyNumber,registered=$registered,private=${service.isPrivacyModeActive}," +
                            "epoch=${PrivacyModeState.observeOrdinaryPersistence()},beforeEvent=$beforeEvent,event=${ClipboardHelper.repeatEvent()}," +
                            "state=${ClipboardHelper.repeatSnapshot()::class.simpleName},lastId=${ClipboardHelper.lastBean?.id}," +
                            "lastMatches=${ClipboardHelper.lastBean?.text == text},knownRows=${ids.size},callbacks=${callbackCount - beforeCallbacks}," +
                            "identityEvent=$identityEvent,previous=${field("previousGeneration")},aliases=${field("aliases")}," +
                            "mutations=${(field("rowMutations") as Map<*, *>).size},allMutations=${field("allMutations")},enabled=${field("enabled")}," +
                            "explicit=${field("explicitSelection")},trace=$callbackTrace"
                    }
                    throw IllegalStateException("Clipboard capture diagnostic: $info", failure)
                }
                return checkNotNull(selection).also { ids.add(it.id) }
            }
            val a = copy(first)
            val b = copy(second)
            check(a.id != b.id)
            val beforeA = checkNotNull(ClipboardHelper.get(a.id)).time
            val beforeB = checkNotNull(ClipboardHelper.get(b.id)).time
            fun chord(modifier: Int, meta: Int) {
                val now = SystemClock.uptimeMillis()
                for ((action, code, flags) in listOf(
                    Triple(KeyEvent.ACTION_DOWN, modifier, 0), Triple(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, meta),
                    Triple(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, meta), Triple(KeyEvent.ACTION_UP, modifier, 0),
                )) {
                    instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0, flags,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
                }
            }
            chord(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
            var metaFailure: IllegalStateException? = null
            try {
                until("physical Meta+V opens history with first row") {
                    input()?.let { root -> descendants(root).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == first } } == true
                }
            } catch (failure: IllegalStateException) {
                metaFailure = failure
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Meta+V diagnostic: ${failure.message}\n") })
                main {
                    val now = SystemClock.uptimeMillis()
                    val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                    val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, meta,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                    val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0, meta,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                    check(service.onKeyDown(KeyEvent.KEYCODE_V, down))
                    check(service.onKeyUp(KeyEvent.KEYCODE_V, up))
                }
                until("direct IME Meta+V opens history") {
                    input()?.let { root -> descendants(root).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == first } } == true
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: directly delivered Meta+V opens history in IME\n") })
            }
            main {
                var row: View = descendants(checkNotNull(input())).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == first }
                while (!row.isClickable) row = row.parent as? View ?: error("Clipboard row is not clickable")
                check(row.performClick())
            }
            until("history selection pasted") { editor.text.toString() == first }
            check((ClipboardHelper.repeatSnapshot() as ClipboardRepeatState.Snapshot.Chosen).selection.id == a.id)
            ClipboardHelper.pin(a.id)
            check((ClipboardHelper.repeatSnapshot() as ClipboardRepeatState.Snapshot.Chosen).selection.id == a.id)
            ClipboardHelper.unpin(a.id)
            check((ClipboardHelper.repeatSnapshot() as ClipboardRepeatState.Snapshot.Chosen).selection.id == a.id)
            chord(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            until("selected history repeated") { editor.text.toString() == first + first }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: keyboard-source Ctrl+V repeats selected history\n") })
            check(checkNotNull(ClipboardHelper.get(a.id)).time == beforeA && checkNotNull(ClipboardHelper.get(b.id)).time == beforeB)
            main { clipboard.setPrimaryClip(ClipData.newPlainText("repeat fixture", second)) }
            until("new copy invalidates old selection") {
                (ClipboardHelper.repeatSnapshot() as? ClipboardRepeatState.Snapshot.Chosen)?.selection?.id == b.id
            }
            main { check(service.repeatClipboardSelection()) }
            until("new copy is repeated") { editor.text.toString() == first + first + second }
            suspend fun mutateWhileDraining(label: String, mutation: suspend () -> Unit) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val original = main { editor.text.toString() }
                try {
                    main { service.postRimeJob { entered.complete(Unit); release.await() } }
                    withTimeout(10_000) { entered.await() }
                    main { check(service.repeatClipboardSelection()) }
                    until("$label drain is frozen") { service.isSearchInputFrozen }
                    mutation()
                    release.complete(Unit)
                    until("$label drain released") { !service.isSearchInputFrozen }
                    delay(300)
                    check(main { editor.text.toString() == original }) { "$label allowed a stale repeat" }
                } finally { release.complete(Unit) }
            }
            mutateWhileDraining("editing selected row") { ClipboardHelper.updateText(b.id, "$prefix-edited") }
            val c = copy("$prefix-third")
            mutateWhileDraining("deleting selected row") { ClipboardHelper.delete(c.id) }
            copy("$prefix-fourth")
            mutateWhileDraining("sensitive copy") {
                val previousEvent = ClipboardHelper.repeatEvent()
                main { ClipboardHelper.setSensitivePrimaryClip(clipboard, ClipData.newPlainText("sensitive fixture", "$prefix-sensitive")) }
                until("sensitive copy invalidates repeat") {
                    ClipboardHelper.repeatEvent() != previousEvent && ClipboardHelper.repeatSnapshot() == ClipboardRepeatState.Snapshot.Unavailable
                }
            }
            copy("$prefix-fifth")
            var secondEditor: EditText? = null
            try {
                mutateWhileDraining("editor focus transition") {
                    val field = main {
                        val parent = editor.parent as ViewGroup
                        EditText(editor.context).apply {
                            id = View.generateViewId()
                            parent.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72))
                            requestFocus()
                            (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
                        }
                    }
                    secondEditor = field
                    until("repeat target editor changed") { service.currentInputEditorInfo?.fieldId == field.id }
                }
                check(main { checkNotNull(secondEditor).text.isEmpty() }) { "Stale repeat reached the new editor" }
            } finally {
                main {
                    secondEditor?.let { (it.parent as? ViewGroup)?.removeView(it) }
                    editor.requestFocus()
                    (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, 0)
                }
            }
            until("repeat original editor restored") {
                val nativeBound = TrimeInputMethodService::class.java.getDeclaredField("nativeBoundEditorToken").apply { isAccessible = true }.getLong(service)
                service.currentInputEditorInfo?.fieldId == editor.id && nativeBound == service.editorToken &&
                    PrivacyModeState.observeOrdinaryPersistence() != null
            }
            copy("$prefix-sixth")
            val privacy = prefs.general.manualPrivacyMode
            val oldPrivacy = privacy.getValue()
            val hadPrivacy = privacy.sharedPreferences.contains(privacy.key)
            try {
                mutateWhileDraining("privacy transition") {
                    main { privacy.setValue(true) }
                    until("private mode entered") { service.isPrivacyModeActive }
                }
            } finally {
                main {
                    privacy.setValue(oldPrivacy)
                    if (!hadPrivacy) check(privacy.sharedPreferences.edit().remove(privacy.key).commit())
                }
            }
            until("ordinary editor after privacy") { !service.isPrivacyModeActive && PrivacyModeState.observeOrdinaryPersistence() != null }
            val beforePrivate = main { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
            main { editor.setSelection(0, first.length - 1); service.copySelectionPrivately() }
            var privateSelection: ClipboardRepeatState.Selection? = null
            until("private copy updates repeat selection") {
                privateSelection = (ClipboardHelper.repeatSnapshot() as? ClipboardRepeatState.Snapshot.Chosen)?.selection
                privateSelection?.id?.let { it !in ids } == true
            }
            ids.add(checkNotNull(privateSelection).id)
            check(main { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == beforePrivate })
            main { prefs.clipboard.clipboardListening.setValue(false) }
            check(ClipboardHelper.repeatSnapshot() == ClipboardRepeatState.Snapshot.Unavailable)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: history repeat, stable ordering and blocked edit/delete/editor/privacy transitions\n") })
            metaFailure?.let { throw it }
        } finally {
            main {
                ClipboardShortcutDiagnostics.observer = null
                editor.setOnKeyListener(null)
                clipboard.removePrimaryClipChangedListener(eventListener)
                prefs.clipboard.clipboardListening.setValue(false)
            }
            ids.forEach { ClipboardHelper.delete(it) }
            main {
                if (oldClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(oldClip)
                prefs.clipboard.clipboardLimit.setValue(oldLimit)
                prefs.clipboard.clipboardRepeatSelection.setValue(oldRepeat)
                prefs.clipboard.clipboardMetaV.setValue(oldMeta)
                prefs.keyboard.hookCtrlCV.setValue(oldHook)
                prefs.clipboard.clipboardListening.setValue(oldListening)
                val edit = prefs.clipboard.clipboardListening.sharedPreferences.edit()
                if (!hadListening) edit.remove(prefs.clipboard.clipboardListening.key)
                if (!hadRepeat) edit.remove(prefs.clipboard.clipboardRepeatSelection.key)
                if (!hadLimit) edit.remove(prefs.clipboard.clipboardLimit.key)
                if (!hadMeta) edit.remove(prefs.clipboard.clipboardMetaV.key)
                check(edit.commit())
                if (!hadHook) check(prefs.keyboard.hookCtrlCV.sharedPreferences.edit().remove(prefs.keyboard.hookCtrlCV.key).commit())
                editor.setText(oldText)
                editor.setSelection(oldStart.coerceIn(0, editor.length()), oldEnd.coerceIn(0, editor.length()))
            }
        }
    }
}
