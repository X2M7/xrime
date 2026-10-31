/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeApi
import com.osfans.trime.ime.core.EditorOwnership
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Search teardown must clear only the native editors owned before the boundary. */
internal object SearchOwnershipProbe {
    suspend fun verify(
        instrumentation: Instrumentation,
        service: TrimeInputMethodService,
        editor: EditText,
        api: suspend (suspend RimeApi.() -> Unit) -> Unit,
    ) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        suspend fun until(block: () -> Boolean) = withTimeout(15_000) { while (!main(block)) delay(20) }
        val before = main { editor.text.toString() }
        val selection = main { editor.selectionStart to editor.selectionEnd }
        val ownership = TrimeInputMethodService::class.java.getDeclaredField("editorOwnership").apply { isAccessible = true }
        var finished = false
        try {
            main { service.beginClipboardSearch() }
            until { service.isClipboardSearchEditing }
            api { check(Rime.isRimePrivacyGuardEnabledForTesting()) { "Search did not enable native privacy" } }
            finished = true
            val oldOwners = main {
                service.onFinishInputView(true)
                service.onFinishInput()
                (ownership.get(service) as EditorOwnership).snapshot()
            }
            api { check(!Rime.isRimePrivacyGuardEnabledForTesting()) { "Search native privacy survived finished input" } }
            check(main { editor.text.toString() == before }) { "Search teardown changed the physical editor" }
            main {
                (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor)
                (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, 0)
            }
            until { service.currentInputEditorInfo?.fieldId == editor.id && service.isCurrentEditor(service.editorToken) }
            finished = false
            val newOwner = main { service.editorToken }
            check(newOwner !in oldOwners) { "New editor reused a retired owner" }
            api {
                check(candidateSnapshotCached.editorToken == newOwner) { "New native editor did not bind" }
                finishOwnedEditors(oldOwners)
                check(candidateSnapshotCached.editorToken == newOwner) { "Late old-editor cleanup invalidated the new editor" }
                check(!Rime.isRimePrivacyGuardEnabledForTesting())
            }
            main { service.commitText("ownership-ok") }
            until { editor.text.toString() == before + "ownership-ok" }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: finished search releases native privacy without invalidating the next editor\n") })
        } finally {
            main {
                editor.text.replace(0, editor.text.length, before)
                editor.setSelection(selection.first.coerceIn(0, editor.text.length), selection.second.coerceIn(0, editor.text.length))
                if (finished) {
                    (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor)
                    (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, 0)
                }
            }
            if (finished) until { service.currentInputEditorInfo?.fieldId == editor.id && service.isCurrentEditor(service.editorToken) }
        }
    }
}
