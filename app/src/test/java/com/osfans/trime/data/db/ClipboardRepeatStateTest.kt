/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ClipboardRepeatStateTest : StringSpec({
    "selection lasts until a new clipboard event and does not reorder history" {
        val state = ClipboardRepeatState()
        state.setEnabled(true)
        val event = state.invalidate()
        state.accepted(event, 2, clipboardRepeatDigest("new"))
        (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection.id shouldBe 2
        state.choose(event, 4, clipboardRepeatDigest("alpha")) shouldBe true
        val selected = (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection
        state.accepted(event, 2, clipboardRepeatDigest("new"))
        state.accepts(selected) shouldBe true
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Chosen(selected)
        val next = state.invalidate()
        state.accepts(selected) shouldBe false
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
        state.accepted(event, 2, clipboardRepeatDigest("new"))
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
        state.accepted(next, 3, clipboardRepeatDigest("latest"))
        (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection.id shouldBe 3
    }
    "disabled and reopened listener cannot revive an old selection or admission" {
        val state = ClipboardRepeatState()
        state.setEnabled(true)
        val event = state.event()
        state.choose(event, 7, clipboardRepeatDigest("beta")) shouldBe true
        val chosen = (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection
        state.setEnabled(false)
        state.setEnabled(true)
        state.choose(event, 7, chosen.digest) shouldBe false
        state.accepted(event, 7, chosen.digest)
        state.accepts(chosen) shouldBe false
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
        (clipboardRepeatDigest("beta") == clipboardRepeatDigest("changed")) shouldBe false
    }
    "row mutation revokes only its own selection and blocks replacement until finished" {
        val state = ClipboardRepeatState()
        state.setEnabled(true)
        val event = state.event()
        state.choose(event, 7, clipboardRepeatDigest("beta")) shouldBe true
        val selected = (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection
        state.beginRowMutation(8)
        state.accepts(selected) shouldBe true
        state.endRowMutation(8)
        state.beginRowMutation(7)
        state.accepts(selected) shouldBe false
        state.choose(state.event(), 7, selected.digest) shouldBe false
        state.endRowMutation(7)
        state.choose(state.event(), 7, selected.digest) shouldBe true
        state.beginAllMutation()
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
        state.choose(state.event(), 8, selected.digest) shouldBe false
        state.endAllMutation()
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
    }
    "digest distinguishes raw UTF-16 code units including lone surrogates" {
        val values = listOf("?", "\uD800", "\uDC00", "\uD83D\uDE00", "\uFFFD")
        values.map(::clipboardRepeatDigest).toSet().size shouldBe values.size
        clipboardRepeatDigest("\uD83D\uDE00") shouldBe clipboardRepeatDigest("\uD83D\uDE00")
    }
    "verified duplicate notifications preserve pending and explicit selections" {
        val state = ClipboardRepeatState()
        state.setEnabled(true)
        val first = state.invalidate()
        val duplicate = state.invalidate()
        state.coalesce(first, duplicate, clipboardRepeatDigest("first")) shouldBe true
        state.accepted(first, 5, clipboardRepeatDigest("first"))
        val captured = (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection
        captured.generation shouldBe duplicate
        state.choose(duplicate, 7, clipboardRepeatDigest("chosen")) shouldBe true
        val inFlight = (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection
        val laterDuplicate = state.invalidate()
        state.coalesce(duplicate, laterDuplicate, clipboardRepeatDigest("first")) shouldBe true
        state.accepts(inFlight) shouldBe true
        state.accepted(first, 5, clipboardRepeatDigest("first"))
        (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection.id shouldBe 7
        val intervening = state.invalidate()
        val later = state.invalidate()
        state.coalesce(laterDuplicate, later, clipboardRepeatDigest("first")) shouldBe false
        state.accepts(inFlight) shouldBe false
        state.accepted(intervening, 5, clipboardRepeatDigest("first"))
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
    }
    "a row mutation during duplicate reconciliation cannot restore a deleted row" {
        val state = ClipboardRepeatState()
        state.setEnabled(true)
        val event = state.event()
        state.choose(event, 7, clipboardRepeatDigest("chosen")) shouldBe true
        val duplicate = state.invalidate()
        state.beginRowMutation(7)
        state.endRowMutation(7)
        state.coalesce(event, duplicate, clipboardRepeatDigest("chosen")) shouldBe false
        state.accepted(event, 7, clipboardRepeatDigest("chosen"))
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
    }
    "capture completing between invalidation and verified coalescing stays hidden until proven" {
        val state = ClipboardRepeatState()
        state.setEnabled(true)
        val digest = clipboardRepeatDigest("first")
        val first = state.invalidate()
        val duplicate = state.invalidate()
        state.accepted(first, 5, digest)
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
        state.coalesce(first, duplicate, digest) shouldBe true
        (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection.id shouldBe 5
    }
    "staged capture cannot cross a different event or mismatching identity" {
        val state = ClipboardRepeatState()
        state.setEnabled(true)
        val first = state.invalidate()
        val second = state.invalidate()
        state.accepted(first, 5, clipboardRepeatDigest("first"))
        state.coalesce(first, second, clipboardRepeatDigest("different")) shouldBe true
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
        val third = state.invalidate()
        state.accepted(second, 5, clipboardRepeatDigest("first"))
        state.invalidate() // Sensitive, empty, privacy or a different copy.
        state.coalesce(second, third, clipboardRepeatDigest("first")) shouldBe false
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
    }
    "pending duplicate never replaces an explicit selection and row mutation revokes it" {
        val state = ClipboardRepeatState()
        state.setEnabled(true)
        val first = state.invalidate()
        state.choose(first, 7, clipboardRepeatDigest("chosen")) shouldBe true
        val second = state.invalidate()
        state.accepted(first, 5, clipboardRepeatDigest("clipboard"))
        state.coalesce(first, second, clipboardRepeatDigest("clipboard")) shouldBe true
        (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection.id shouldBe 7
        val third = state.invalidate()
        state.accepted(first, 5, clipboardRepeatDigest("clipboard"))
        state.beginRowMutation(7)
        state.endRowMutation(7)
        state.coalesce(second, third, clipboardRepeatDigest("clipboard")) shouldBe false
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable
    }
    "capacity cleanup revokes both kinds of pending restoration without discarding an empty capture" {
        val digest = clipboardRepeatDigest("candidate")
        val state = ClipboardRepeatState()
        state.setEnabled(true)
        val first = state.invalidate()
        state.choose(first, 7, digest) shouldBe true
        val duplicate = state.invalidate()
        state.beginAllMutation(invalidateEvent = false)
        state.endAllMutation()
        state.coalesce(first, duplicate, digest) shouldBe false
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable

        val next = state.invalidate()
        val nextDuplicate = state.invalidate()
        state.accepted(next, 8, digest)
        state.beginAllMutation(invalidateEvent = false)
        state.endAllMutation()
        state.coalesce(next, nextDuplicate, digest) shouldBe false
        state.snapshot() shouldBe ClipboardRepeatState.Snapshot.Unavailable

        val fresh = state.invalidate()
        state.beginAllMutation(invalidateEvent = false)
        state.endAllMutation()
        state.accepted(fresh, 9, digest)
        (state.snapshot() as ClipboardRepeatState.Snapshot.Chosen).selection.id shouldBe 9
    }
})
