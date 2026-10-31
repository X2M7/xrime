/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.RimeApi
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.keyboard.KeyView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Verifies that a frozen search transition drains already accepted input without allowing new input. */
internal object SearchDrainProbe {
    suspend fun verify(instrumentation: Instrumentation, service: TrimeInputMethodService, editor: EditText) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        val initial = main { editor.text.toString() }
        val initialSelection = main { editor.selectionStart to editor.selectionEnd }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Boolean>()
        val token = main { service.editorToken }
        try {
            main {
                service.postRimeJob {
                    entered.complete(Unit)
                    release.await()
                    service.commitText("drain-probe")
                }
            }
            withTimeout(10_000) { entered.await() }
            main {
                service.lifecycleScope.launch {
                    runCatching { service.drainForSearch(token) }
                        .onSuccess(result::complete)
                        .onFailure(result::completeExceptionally)
                }
            }
            withTimeout(10_000) { while (!main { service.isSearchInputFrozen }) delay(10) }
            main {
                val field = TrimeInputMethodService::class.java.getDeclaredField("inputView").apply { isAccessible = true }
                val input = checkNotNull(field.get(service) as? InputView) { "Missing real IME input view" }
                fun descendants(view: View): Sequence<View> = sequence {
                    yield(view)
                    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
                }
                val key = descendants(input).filterIsInstance<KeyView>().firstOrNull {
                    it.isShown && it.width > 0 && it.height > 0 && it.getGlobalVisibleRect(Rect())
                }
                    ?: error("No visible real key for the frozen-touch check")
                val inputPosition = IntArray(2).also(input::getLocationOnScreen)
                val visible = Rect().also { check(key.getGlobalVisibleRect(it)) }
                val x = visible.exactCenterX() - inputPosition[0]
                val y = visible.exactCenterY() - inputPosition[1]
                check(x >= 0f && x < input.width && y >= 0f && y < input.height) { "Visible key is outside the input view" }
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0)
                try {
                    check(input.onInterceptTouchEvent(down)) { "Frozen input failed to intercept the key gesture" }
                    check(input.dispatchTouchEvent(down) && input.dispatchTouchEvent(up)) { "Frozen input did not consume touch" }
                    check(editor.text.toString() == initial) { "Frozen key touch changed the editor" }
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
            main { service.commitText("blocked-input") }
            check(!main { editor.text.toString().contains("blocked-input") })
            release.complete(Unit)
            check(withTimeout(10_000) { result.await() }) { "Accepted input did not drain to all consumers" }
            withTimeout(10_000) { while (!main { editor.text.toString().contains("drain-probe") }) delay(10) }
            check(!main { editor.text.toString().contains("blocked-input") })
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: search drain preserves accepted input and rejects new input\n") })
        } finally {
            release.complete(Unit)
            main {
                service.releaseSearchDrain()
                editor.text.replace(0, editor.text.length, initial)
                editor.setSelection(initialSelection.first.coerceIn(0, editor.text.length), initialSelection.second.coerceIn(0, editor.text.length))
            }
        }
        instrumentation.waitForIdleSync()
        val settled = CompletableDeferred<Unit>()
        main { service.postRimeJob { settled.complete(Unit) } }
        withTimeout(10_000) { settled.await() }
        verifyInterrupted(instrumentation, service, editor, cancel = true)
        verifyInterrupted(instrumentation, service, editor, cancel = false)
    }

    /** The barrier job belongs to the service scope and must not strand the serial queue. */
    private suspend fun verifyInterrupted(instrumentation: Instrumentation, service: TrimeInputMethodService, editor: EditText, cancel: Boolean) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        val field = TrimeInputMethodService::class.java.getDeclaredField("rime").apply { isAccessible = true }
        val original = main { field.get(service) as RimeSession }
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Boolean>()
        val markerCalls = java.util.concurrent.atomic.AtomicInteger()
        val fake = object : RimeSession by original {
            override suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T {
                if (markerCalls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                return original.runOnReady(block)
            }
        }
        instrumentation.waitForIdleSync()
        val token = main {
            check(service.currentInputEditorInfo?.fieldId == editor.id) { "Expected editor is not active before drain test" }
            service.editorToken
        }
        val job = main {
            field.set(service, fake)
            service.lifecycleScope.launch { result.complete(service.drainForSearch(token)) }
        }
        fun phase(stage: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "search-drain interruption ${if (cancel) "cancel" else "timeout"}: $stage\n")
        })
        job.invokeOnCompletion { failure -> if (failure != null) result.completeExceptionally(failure) }
        try {
            phase("waiting for marker")
            try {
                withTimeout(10_000) { while (!entered.isCompleted && !result.isCompleted) delay(10) }
            } catch (_: TimeoutCancellationException) {
                error("Search drain marker wait timed out: calls=${markerCalls.get()} jobActive=${job.isActive} frozen=${service.isSearchInputFrozen} editorMatches=${service.editorToken == token}")
            }
            if (!entered.isCompleted) {
                val completed = result.await()
                error("Search drain returned $completed before marker: calls=${markerCalls.get()} frozen=${service.isSearchInputFrozen} editorMatches=${service.editorToken == token}")
            }
            phase("marker entered")
            if (cancel) {
                main { job.cancel() }
                withTimeout(10_000) { job.join() }
            } else {
                check(!withTimeout(10_000) { result.await() }) { "Timed out barrier unexpectedly succeeded" }
            }
            phase("interruption observed")
        } finally {
            withContext(NonCancellable) {
                job.cancel()
                job.join()
                main {
                    field.set(service, original)
                    service.releaseSearchDrain()
                }
            }
        }
        phase("waiting for next input")
        check(main { service.isCurrentEditor(token) && service.currentInputEditorInfo?.fieldId == editor.id }) {
            "Editor changed during drain interruption: calls=${markerCalls.get()} frozen=${service.isSearchInputFrozen} editorMatches=${service.editorToken == token}"
        }
        val resumed = CompletableDeferred<Unit>()
        main { service.postRimeJob { resumed.complete(Unit) } }
        try {
            withTimeout(10_000) { resumed.await() }
        } catch (_: TimeoutCancellationException) {
            error("Search drain did not release the input queue: calls=${markerCalls.get()} frozen=${service.isSearchInputFrozen} editorMatches=${service.editorToken == token}")
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "PASS: search drain ${if (cancel) "cancellation" else "timeout"} releases the input queue\n")
        })
    }
}
