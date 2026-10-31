/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import java.util.concurrent.atomic.AtomicReference

/** Every clipboard callback invalidates work belonging to earlier clipboard contents. */
internal class ClipboardImageEventGeneration {
    private data class State(val generation: Long, val enabled: Boolean)
    private val current = AtomicReference(State(0, true))
    private fun update(enabled: Boolean? = null): State {
        while (true) {
            val old = current.get()
            val next = State(old.generation + 1, enabled ?: old.enabled)
            if (current.compareAndSet(old, next)) return next
        }
    }
    fun advance(): Long = update().generation
    fun enable() { update(true) }
    fun disable() { update(false) }
    fun snapshot(): Long = current.get().generation
    fun isCurrent(ticket: Long): Boolean = current.get().let { it.enabled && it.generation == ticket }

    /** Acquire before reading the matching ClipData; both generations must stay current. */
    fun acquireAuthorization(ticket: Long, listenerIsCurrent: () -> Boolean): ImageCaptureAuthorization? =
        ImageCaptureAuthorization.acquire(
            listeningIsCurrent = { isCurrent(ticket) && listenerIsCurrent() },
            acceptEvent = {
                val state = current.get()
                state.enabled && state.generation == ticket && listenerIsCurrent() && current.compareAndSet(state, state)
            },
        )
}
