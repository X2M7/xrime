/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class ClipboardSearchBindTest : StringSpec({
    "successful bind is accepted and a cancelled bind is rejected" {
        val ready = Job().apply { complete() }
        awaitClipboardSearchBind(ready, 500) shouldBe true
        val failed = Job().apply { cancel() }
        awaitClipboardSearchBind(failed, 500) shouldBe false
    }
    "timeout and caller cancellation cancel the independent bind" {
        val timeout = Job()
        awaitClipboardSearchBind(timeout, 20) shouldBe false
        timeout.isCancelled shouldBe true
        coroutineScope {
            val pending = Job()
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { awaitClipboardSearchBind(pending, 5_000) }
            waiter.cancelAndJoin()
            pending.isCancelled shouldBe true
        }
    }
})
