/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import java.util.concurrent.atomic.AtomicInteger

/** Single linearization point: either a boundary cancels, or a worker claims dispatch. */
internal class ClipboardImageDispatchGate {
    private val state = AtomicInteger(0)
    fun tryClaim(): Boolean = state.compareAndSet(0, 1)
    fun cancelBeforeClaim(): Boolean = state.compareAndSet(0, 2)
    val mayHaveDispatched: Boolean get() = state.get() == 1
}
