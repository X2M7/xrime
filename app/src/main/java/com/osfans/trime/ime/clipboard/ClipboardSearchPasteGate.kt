/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

/** Main-thread gate: a successful dispatch consumes a still-current result at most once. */
internal class ClipboardSearchPasteGate {
    private var inFlight = false

    fun dispatch(
        isCurrent: () -> Boolean,
        send: () -> Boolean,
        consume: () -> Boolean,
        afterDispatch: () -> Unit,
    ): Boolean {
        if (inFlight || !isCurrent()) return false
        inFlight = true
        try {
            if (!send() || !isCurrent() || !consume()) return false
            afterDispatch()
            return true
        } finally {
            inFlight = false
        }
    }
}
