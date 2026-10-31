/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ClipboardSuggestionStateTest : StringSpec({
    "disable invalidates old admission and clears the current suggestion" {
        var value: String? = null
        val state = ClipboardSuggestionState<String> { value = it }
        state.setEnabled(true)
        val admission = requireNotNull(state.admit())
        val first = requireNotNull(state.publish(admission, "old", 1))
        value shouldBe "old"
        val clear = requireNotNull(state.setEnabled(false))
        value.shouldBeNull()
        state.isCurrent(first) shouldBe false
        state.isCurrent(clear) shouldBe true
        state.publish(admission, "late", 1).shouldBeNull()
        state.setEnabled(true)
        state.publish(admission, "late", 1).shouldBeNull()
        val fresh = requireNotNull(state.publish(requireNotNull(state.admit()), "new", 2))
        state.isCurrent(clear) shouldBe false
        state.isCurrent(fresh) shouldBe true
        value shouldBe "new"
    }
    "queued notifications have unique identity even for the same bean" {
        val state = ClipboardSuggestionState<String> {}
        state.setEnabled(true)
        val admission = requireNotNull(state.admit())
        val bean = "same"
        val first = requireNotNull(state.publish(admission, bean, 1))
        val clear = state.clear()
        val second = requireNotNull(state.publish(admission, bean, 1))
        state.isCurrent(first) shouldBe false
        state.isCurrent(clear) shouldBe false
        state.isCurrent(second) shouldBe true
    }
    "a blocked old publication cannot survive disable and reenable" {
        val state = ClipboardSuggestionState<String> {}
        state.setEnabled(true)
        val admission = requireNotNull(state.admit())
        val ready = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val result = AtomicReference<ClipboardSuggestionState.Update<String>?>()
        val worker = thread {
            ready.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            result.set(state.publish(admission, "old", 1))
        }
        check(ready.await(5, TimeUnit.SECONDS))
        state.setEnabled(false)
        state.setEnabled(true)
        resume.countDown()
        worker.join(5000)
        worker.isAlive shouldBe false
        result.get().shouldBeNull()
    }
})
