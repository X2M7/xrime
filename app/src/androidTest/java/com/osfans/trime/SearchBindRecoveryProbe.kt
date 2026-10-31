/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeInputAdmissionContext
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.RimeMessageBus
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.core.StatusProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import org.kodein.di.direct
import org.kodein.di.instance

/** A timed-out search bind must release the input queue and restore the real editor. */
internal object SearchBindRecoveryProbe {
    private enum class Failure { TIMEOUT, UNTAGGED, EXCEPTION }

    suspend fun verify(instrumentation: Instrumentation, service: TrimeInputMethodService, editor: EditText) {
        for (failure in Failure.entries) verifyCase(instrumentation, service, editor, failure)
        verifyLateStateMessages(instrumentation, service, editor)
    }

    private suspend fun verifyLateStateMessages(instrumentation: Instrumentation, service: TrimeInputMethodService, editor: EditText) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        fun descendants(root: View): Sequence<View> = sequence {
            yield(root)
            if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
        val viewField = TrimeInputMethodService::class.java.getDeclaredField("inputView").apply { isAccessible = true }
        fun input() = viewField.get(service) as? InputView
        suspend fun until(label: String, condition: () -> Boolean) {
            try {
                withTimeout(15_000) { while (!main(condition)) delay(25) }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Timed out waiting for $label", failure)
            }
        }
        val before = main { editor.text.toString() }
        main { service.beginClipboardSearch() }
        until("state-message search session") { service.isClipboardSearchEditing }
        val admission = CompletableDeferred<Long>()
        main { service.postRimeJob { admission.complete(checkNotNull(RimeInputAdmissionContext.current.get())) } }
        val oldOrigin = withTimeout(10_000) { admission.await() }
        main {
            val root = checkNotNull(input())
            check(descendants(root).filterIsInstance<TextView>().first {
                it.isShown && it.text.toString() == service.getString(R.string.clipboard_search_cancel)
            }.performClick())
        }
        until("state-message search exit") { !service.isClipboardSearchEditing && !service.isSearchInputFrozen }
        val bus = Rime::class.java.getDeclaredField("messageBus").apply { isAccessible = true }.get(null) as RimeMessageBus
        val messages = listOf(
            RimeMessage.OptionMessage(RimeMessage.OptionMessage.Data("_liquid_keyboard", true)),
            RimeMessage.SchemaMessage(SchemaItem("stale-search-schema", "stale")),
        )
        for (message in messages) {
            message.inputAdmission = oldOrigin
            bus.publish(message)
            until("late state message rejection") { message.isRejectedForClipboardSearch() }
            // Service and view consume the same broadcast object; a delayed second
            // consumer must reject it even after the first consumer has returned.
            main { check(!service.prepareInputMessage(message)) }
        }
        instrumentation.waitForIdleSync()
        main {
            val root = checkNotNull(input())
            check(root.di.direct.instance<BoardWindowManager>().isAttached(root.di.direct.instance<KeyboardWindow>())) {
                "A stale option or schema notification replaced the active keyboard"
            }
            check(editor.text.toString() == before) { "A stale state notification changed the editor" }
            check(service.prepareInputMessage(RimeMessage.OptionMessage(RimeMessage.OptionMessage.Data("probe-global", false)))) {
                "An untagged global option was rejected after search"
            }
            check(service.prepareInputMessage(RimeMessage.SchemaMessage(SchemaItem("probe-global")))) {
                "An untagged global schema was rejected after search"
            }
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: late search option/schema broadcasts rejected by both consumers; global notifications retained\n") })
    }

    private suspend fun verifyCase(instrumentation: Instrumentation, service: TrimeInputMethodService, editor: EditText, failure: Failure) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        instrumentation.waitForIdleSync()
        val firstDrain = CompletableDeferred<Boolean>()
        val token = main { service.editorToken }
        main { service.lifecycleScope.launch { firstDrain.complete(service.drainForSearch(token)) } }
        check(withTimeout(10_000) { firstDrain.await() }) { "Could not prepare search bind probe" }
        main { service.releaseSearchDrain() }

        val field = TrimeInputMethodService::class.java.getDeclaredField("rime").apply { isAccessible = true }
        val original = main { field.get(service) as RimeSession }
        val attempts = AtomicInteger()
        val stalled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = object : RimeSession by original {
            override suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T {
                if (attempts.incrementAndGet() == 2) {
                    stalled.complete(Unit)
                    if (failure == Failure.EXCEPTION) throw IllegalStateException("probe failure")
                    release.await()
                }
                return original.runOnReady(block)
            }
        }
        val before = main { editor.text.toString() }
        val selection = main { editor.selectionStart to editor.selectionEnd }
        try {
            main {
                field.set(service, fake)
                service.beginClipboardSearch()
            }
            withTimeout(10_000) { stalled.await() }
            if (failure != Failure.EXCEPTION) {
                main { service.commitText("blocked-bind-input") }
                check(main { editor.text.toString() == before }) { "Frozen search bind accepted physical input" }
            }
            if (failure == Failure.UNTAGGED) {
                val stale = RimeMessage.StatusMessage(StatusProto())
                main { check(!service.prepareInputMessage(stale)) }
                // The second subscriber receives the same object after the first has restored
                // the ordinary editor; it must still drop this stale message.
                main { check(!service.prepareInputMessage(stale)) }
            }
            withTimeout(10_000) {
                while (main { service.isSearchInputFrozen || service.isClipboardSearchEditing }) delay(20)
            }
            val resumed = CompletableDeferred<Unit>()
            main { service.postRimeJob { resumed.complete(Unit) } }
            withTimeout(10_000) { resumed.await() }
            main { service.commitText("bind-recovery") }
            withTimeout(10_000) { while (main { editor.text.toString() != before + "bind-recovery" }) delay(20) }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: ${failure.name.lowercase()} search bind restores the editor and input queue\n") })
        } finally {
            release.complete(Unit)
            main {
                field.set(service, original)
                service.releaseSearchDrain()
                editor.text.replace(0, editor.text.length, before)
                editor.setSelection(selection.first.coerceIn(0, editor.text.length), selection.second.coerceIn(0, editor.text.length))
            }
        }
    }
}
