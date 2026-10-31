/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ClipboardImageDispatchGateTest : StringSpec({
    "a boundary and a sender cannot both win dispatch ownership" {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val gate = ClipboardImageDispatchGate()
                val start = CountDownLatch(1)
                val sender = pool.submit<Boolean> { start.await(); gate.tryClaim() }
                val boundary = pool.submit<Boolean> { start.await(); gate.cancelBeforeClaim() }
                start.countDown()
                val claimed = sender.get(5, TimeUnit.SECONDS)
                val cancelled = boundary.get(5, TimeUnit.SECONDS)
                (claimed xor cancelled) shouldBe true
                gate.mayHaveDispatched shouldBe claimed
                gate.tryClaim() shouldBe false
            }
        } finally { pool.shutdownNow() }
    }
})
