/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ClipboardSearchSessionTest : StringSpec({
    "search transitions reject stale and early native responses" {
        val state = ClipboardSearchSession()
        state.begin(7, 20, privateMode = false, composing = true) shouldBe false
        state.begin(7, 20, privateMode = false, composing = false) shouldBe true
        state.route(7, 20, false) shouldBe ClipboardSearchSession.Route.REJECT
        state.ready(7, 19, false)
        state.phase shouldBe ClipboardSearchSession.Phase.ENTERING
        state.ready(7, 20, false)
        state.route(7, 20, false) shouldBe ClipboardSearchSession.Route.LOCAL
        state.route(7, 19, false) shouldBe ClipboardSearchSession.Route.REJECT
        val old = checkNotNull(state.changed())
        val current = checkNotNull(state.changed())
        state.restore(7, 21, privateMode = false, results = true) shouldBe true
        state.route(7, 20, false) shouldBe ClipboardSearchSession.Route.REJECT
        state.route(7, 21, false) shouldBe ClipboardSearchSession.Route.REJECT
        state.restored(7, 20, false)
        state.phase shouldBe ClipboardSearchSession.Phase.RESTORING
        state.restored(7, 21, false)
        state.acceptsResult(old, 7, false) shouldBe false
        state.consume(current, 7, false, rowExists = false) shouldBe false
        state.consume(current, 7, false, rowExists = true) shouldBe true
        state.consume(current, 7, false, rowExists = true) shouldBe false
    }

    "focus change and privacy prevent result delivery" {
        val state = ClipboardSearchSession()
        state.begin(7, 30, privateMode = true, composing = false) shouldBe false
        state.begin(7, 30, privateMode = false, composing = false) shouldBe true
        state.ready(7, 30, false)
        val current = checkNotNull(state.changed())
        state.route(8, 30, false) shouldBe ClipboardSearchSession.Route.REJECT
        state.route(7, 30, true) shouldBe ClipboardSearchSession.Route.REJECT
        state.restore(7, 31, privateMode = false, results = true) shouldBe true
        state.restored(7, 31, true)
        state.acceptsResult(current, 7, true) shouldBe false
        state.restored(7, 31, false)
        state.consume(current, 8, false, rowExists = true) shouldBe false
        state.close()
        state.consume(current, 7, false, rowExists = true) shouldBe false
    }
})
