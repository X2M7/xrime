/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RimeInputDrainTest : StringSpec({
    "drain waits for child work and all consumers and retries after a late child" {
        val drain = RimeInputDrain()
        val service = checkNotNull(drain.register())
        val view = checkNotNull(drain.register())
        val origin = checkNotNull(drain.root())
        drain.freeze() shouldBe true
        drain.root() shouldBe null
        drain.nextMarker() shouldBe null
        drain.continuation(origin) shouldBe true
        drain.finish(origin)
        drain.finish(origin)
        val first = checkNotNull(drain.nextMarker())
        drain.acknowledge(service, first) shouldBe RimeInputDrain.Ack.WAIT
        drain.continuation(origin) shouldBe true
        drain.acknowledge(view, first) shouldBe RimeInputDrain.Ack.RETRY
        drain.nextMarker() shouldBe null
        drain.finish(origin)
        val second = checkNotNull(drain.nextMarker())
        drain.acknowledge(view, first) shouldBe RimeInputDrain.Ack.WAIT
        drain.acknowledge(service, second) shouldBe RimeInputDrain.Ack.WAIT
        drain.acknowledge(service, second) shouldBe RimeInputDrain.Ack.WAIT
        drain.acknowledge(view, second) shouldBe RimeInputDrain.Ack.COMPLETE
        drain.continuation(origin) shouldBe false
    }

    "participant removal or registration during drain aborts it" {
        val lost = RimeInputDrain()
        val service = checkNotNull(lost.register())
        val view = checkNotNull(lost.register())
        lost.freeze() shouldBe true
        val marker = checkNotNull(lost.nextMarker())
        lost.unregister(view)
        lost.acknowledge(service, marker) shouldBe RimeInputDrain.Ack.ABORTED
        lost.nextMarker() shouldBe null

        val added = RimeInputDrain()
        val initial = checkNotNull(added.register())
        added.freeze() shouldBe true
        val anotherMarker = checkNotNull(added.nextMarker())
        val late = checkNotNull(added.register())
        added.acknowledge(initial, anotherMarker) shouldBe RimeInputDrain.Ack.ABORTED
        added.thaw() shouldBe true
        val work = checkNotNull(added.root())
        added.finish(work)
        added.freeze() shouldBe true
        val next = checkNotNull(added.nextMarker())
        added.acknowledge(initial, next) shouldBe RimeInputDrain.Ack.WAIT
        added.acknowledge(late, next) shouldBe RimeInputDrain.Ack.COMPLETE
    }

    "tokens from a recreated drain cannot authorize work" {
        val previous = RimeInputDrain()
        previous.register()
        val old = checkNotNull(previous.root())
        val replacement = RimeInputDrain()
        replacement.register()
        replacement.continuation(old) shouldBe false
    }
})
