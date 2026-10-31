/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ClipboardSearchPasteGateTest : StringSpec({
    "a rejected dispatch leaves the result usable and success consumes it once" {
        val gate = ClipboardSearchPasteGate()
        var current = true
        var sent = 0
        var closed = 0
        fun attempt(send: () -> Boolean) = gate.dispatch(
            isCurrent = { current },
            send = send,
            consume = { current = false; true },
            afterDispatch = { closed++ },
        )
        attempt { false } shouldBe false
        current shouldBe true
        closed shouldBe 0
        attempt { sent++; true } shouldBe true
        attempt { sent++; true } shouldBe false
        sent shouldBe 1
        closed shouldBe 1
    }

    "reentrant attempts and owner changes cannot cause duplicate dispatch or stale navigation" {
        val gate = ClipboardSearchPasteGate()
        var current = true
        var nestedSent = 0
        var closed = 0
        gate.dispatch(
            isCurrent = { current },
            send = {
                gate.dispatch({ true }, { nestedSent++; true }, { true }, {}) shouldBe false
                current = false
                true
            },
            consume = { error("stale result consumed") },
            afterDispatch = { closed++ },
        ) shouldBe false
        nestedSent shouldBe 0
        closed shouldBe 0
    }
})
